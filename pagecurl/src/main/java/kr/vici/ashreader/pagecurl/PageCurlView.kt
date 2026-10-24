// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.pagecurl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.VelocityTracker
import android.view.ViewConfiguration
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 책 넘김 표면 (기술 설계서 4.2). SurfaceView + 전용 GL 스레드(EGL14, GLES 2.0).
 * 정보줄 등 고정 층은 이 뷰 위에 따로 겹친다(M-03: 정보줄 고정).
 *
 * TextureView → SurfaceView (배치 3A): TB375FC(144Hz, Mali-G615) 실측에서 GL 작업은 p50 3.4ms 로 주기(6.9ms) 안인데
 * 늦음+건너뜀이 약 11%였다. TextureView 는 매 프레임 앱 RenderThread 가 한 번 더 합성해야 하고(UI 스레드·GPU 를 함께 씀),
 * 그 합성이 밀리면 eglSwapBuffers 가 버퍼를 못 받아 다음 vsync 를 놓친다. SurfaceView 는 SurfaceFlinger 에 바로 내므로
 * 이 의존이 없고, Surface.setFrameRate 로 넘김 중 주사율 투표도 할 수 있다(HighRefresh).
 *
 * 스레드: 공개 메서드는 메인 스레드에서 부른다. 그리기 상태는 모두 GL 스레드가 가진다.
 */
class PageCurlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    /** 탭 (0..1 정규화 좌표, 화면 기준). 탭 영역 판정은 바깥(앱)이 한다. */
    var onTap: ((xFrac: Float, yFrac: Float) -> Unit)? = null

    /** 길게 누름 (0..1 정규화 좌표, 화면 기준). 글자 선택(S-28)에 쓴다. 이 콜백이 있으면 그 손가락의 탭·끌기는 무시. */
    var onLongPress: ((xFrac: Float, yFrac: Float) -> Unit)? = null

    /** 보이는 첫 쪽 번호가 바뀔 때 (메인 스레드) */
    var onPageChanged: ((first: Int, spread: Boolean) -> Unit)? = null

    private val _isAnimating = MutableStateFlow(false)

    /** 넘김 중 true. AI 화질·칸 인식·쪽 수 계산 큐는 이 값이 true 인 동안 새 작업을 꺼내지 않는다(6.2). */
    val isAnimating: StateFlow<Boolean> = _isAnimating.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val glThread = HandlerThread("PageCurlGL").apply { start() }
    private val gl = Handler(glThread.looper)
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "PageCurlLoader").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // ---- GL 스레드 상태 ----
    private var egl: EglCore? = null
    private val renderer = CurlRenderer()
    private var choreographer: Choreographer? = null
    private var frameScheduled = false
    private var viewW = 0f
    private var viewH = 0f
    private var source: PageSource? = null
    private var sourceGen = 0
    private var config = CurlConfig()
    private var position = 0
    private val textures = HashMap<Int, Int>()
    private val loading = HashSet<Int>()
    /** 쪽 칸 안 종이 자리(u0, v0, u1, v1 — 0..1). 없으면 칸 전체(이북류). 대표님 23:05: 넘길 때 종이만 움직이고 여백은 가만히. */
    private val papers = HashMap<Int, FloatArray>()

    private enum class Phase { IDLE, DRAG, ANIM }
    private var phase = Phase.IDLE
    private var forward = true
    private var t = 0f
    private var gy = 0f
    private var py = 0f
    private var animFrom = 0f
    private var animTo = 0f
    private var animStartNs = 0L
    private var animDurNs = 0L
    /** 손을 뗀 뒤 애니메이션의 시작 기울기(진행도 단위, I-716). */
    private var animSlope = 0f
    /** 넘기는 방향으로 끈 거리 / 쪽 너비(I-716). */
    private var dragFrac = 0f
    private var autoRemaining = 0
    private var autoIntervalMs = 1000L
    private var autoDone: ((FrameReport) -> Unit)? = null
    private val recorder = FrameStatsRecorder()
    private var refreshHz = 60f

    // ---- 메인 스레드 터치 상태 ----
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var dragging = false
    private var longFired = false
    private val longPressRunnable = Runnable {
        val cb = onLongPress ?: return@Runnable
        if (dragging || width <= 0 || height <= 0) return@Runnable
        longFired = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        cb(rawDownX / width, downY / height)
    }
    private var rawDownX = 0f
    private var velocity: VelocityTracker? = null

    init {
        holder.addCallback(this)
        // 불투명 층(합성기가 아래를 섞지 않음). 실제 버퍼 형식은 EGL 설정(RGBA8888)이 정한다.
        holder.setFormat(PixelFormat.OPAQUE)
    }

    fun setPageSource(src: PageSource, startPage: Int = 0) {
        gl.post {
            sourceGen++
            source = src
            position = startPage.coerceIn(0, max(0, src.pageCount - 1))
            clearTextures()
            alignPosition()
            ensureTextures()
            notifyPage()
            requestFrame()
        }
    }

    fun setConfig(cfg: CurlConfig) {
        gl.post {
            val layoutChanged = cfg.spread != config.spread || cfg.coverAlone != config.coverAlone
            config = cfg
            if (layoutChanged) {
                clearTextures()
                alignPosition()
                ensureTextures()
                notifyPage()
            }
            requestFrame()
        }
    }

    /** 다음/이전 넘김 (탭·볼륨 키). 효과 설정을 따른다. */
    fun turn(next: Boolean) {
        gl.post { startTurn(next, fromUser = false) }
    }

    /**
     * 자동 넘김 측정 (N-06): [count]회 다음 쪽 넘김, 회차 사이 [intervalMs].
     * 넘김 속도는 [CurlConfig.turnDurationMs] 로 일정. 끝나면 메인 스레드에서 [onDone].
     */
    fun runAutoTurns(count: Int, intervalMs: Long = 1000, onDone: (FrameReport) -> Unit) {
        gl.post {
            // 판정 주기는 기기가 지원하는 최고 주사율(넘김 중 그 주사율을 요청하므로)
            refreshHz = HighRefresh.maxRate(this) ?: display?.refreshRate?.takeIf { it > 1f } ?: 60f
            recorder.start(refreshHz)
            autoRemaining = count
            autoIntervalMs = intervalMs
            autoDone = onDone
            nextAuto()
        }
    }

    fun cancelAutoTurns() {
        gl.post {
            autoRemaining = 0
            autoDone = null
            recorder.stop()
        }
    }

    /** 뷰를 다 쓴 뒤 호출 (Activity onDestroy). */
    fun release() {
        mainHandler.removeCallbacks(longPressRunnable)
        gl.post {
            clearTextures()
            renderer.release()
            egl?.release()
            egl = null
        }
        glThread.quitSafely()
        loader.shutdownNow()
    }

    // ------------------------------------------------------------------
    // SurfaceView
    // ------------------------------------------------------------------
    override fun surfaceCreated(holder: SurfaceHolder) {
        val surface = holder.surface
        gl.post {
            val core = EglCore()
            if (!core.init(surface)) {
                Log.e(EglCore.TAG, "EGL 초기화 실패")
                return@post
            }
            egl = core
            renderer.init()
            choreographer = Choreographer.getInstance()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        gl.post { onResize(width, height) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // GL 스레드에서 EGL 을 정리한 뒤 Surface 를 놓아야 하므로 끝날 때까지 기다린다.
        // 7D E2E: 예전에는 정리 작업을 큐 맨 뒤에 넣고 2초만 기다렸다. 느린 GPU(소프트웨어 GL)에서 쪽 그림 올리기가 줄 서 있으면
        // 2초 안에 못 끝나, EGL 이 아직 쥔 Surface 를 시스템이 먼저 풀어 Surface::~Surface 에서 SIGSEGV(힙 손상)가 났다.
        // → 정리를 큐 맨 앞에 넣고(앞 작업 하나만 기다림) 넉넉히 기다린다.
        val done = java.util.concurrent.CountDownLatch(1)
        gl.postAtFrontOfQueue {
            clearTextures()
            renderer.release()
            egl?.release()
            egl = null
            done.countDown()
        }
        if (!done.await(10, java.util.concurrent.TimeUnit.SECONDS)) Log.w(EglCore.TAG, "surfaceDestroyed: GL 정리가 10초 안에 끝나지 않음")
    }

    private fun onResize(w: Int, h: Int) {
        val spreadBefore = isSpread()
        viewW = w.toFloat()
        viewH = h.toFloat()
        if (spreadBefore != isSpread() || textures.isNotEmpty()) {
            // 쪽 크기가 바뀌면 다시 그린다
            clearTextures()
        }
        alignPosition()
        ensureTextures()
        notifyPage()
        requestFrame()
    }

    // ------------------------------------------------------------------
    // 터치 (메인 스레드) → GL 스레드
    // ------------------------------------------------------------------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat().takeIf { it > 0 } ?: return false
        val mirror = config.direction == TurnDirection.RTL
        val lx = if (mirror) w - event.x else event.x
        // 넘김 속도(I-716): 거울(오→왼) 좌표로 잰다
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
        }
        velocity?.let { vt ->
            val ev = MotionEvent.obtain(event)
            ev.setLocation(lx, event.y)
            vt.addMovement(ev)
            ev.recycle()
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = lx; rawDownX = event.x; downY = event.y; downTime = SystemClock.uptimeMillis(); dragging = false; longFired = false
                if (onLongPress != null) mainHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (longFired) return true
                val dx = lx - downX
                if (abs(dx) > touchSlop || abs(event.y - downY) > touchSlop) mainHandler.removeCallbacks(longPressRunnable)
                if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(event.y - downY)) {
                    dragging = true
                    val next = dx < 0
                    val y0 = downY
                    gl.post { beginDrag(next, y0) }
                }
                if (dragging) {
                    val x = lx; val y = event.y; val x0 = downX
                    gl.post { updateDrag(x0, x, y) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                if (longFired) {
                    longFired = false
                } else if (dragging) {
                    val vt = velocity
                    var vx = 0f
                    if (vt != null && event.actionMasked == MotionEvent.ACTION_UP) {
                        vt.computeCurrentVelocity(1000)
                        vx = vt.xVelocity
                    }
                    val dens = resources.displayMetrics.density
                    val cancel = event.actionMasked == MotionEvent.ACTION_CANCEL
                    gl.post { endDrag(vx, dens, cancel) }
                } else if (event.actionMasked == MotionEvent.ACTION_UP &&
                    SystemClock.uptimeMillis() - downTime < ViewConfiguration.getLongPressTimeout()
                ) {
                    performClick()
                    onTap?.invoke(event.x / w, event.y / height)
                }
                dragging = false
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    // ------------------------------------------------------------------
    // 넘김 상태 (GL 스레드)
    // ------------------------------------------------------------------
    private fun isSpread(): Boolean = when (config.spread) {
        SpreadMode.SINGLE -> false
        SpreadMode.SPREAD -> true
        SpreadMode.AUTO -> {
            val dm = resources.displayMetrics
            val shortDp = min(viewW, viewH) / dm.density
            viewW > viewH && shortDp >= 600f
        }
    }

    private fun step() = if (isSpread()) 2 else 1
    private fun pageW() = if (isSpread()) viewW / 2f else viewW
    private fun count() = source?.pageCount ?: 0

    /**
     * position = 펼침의 왼쪽 쪽 번호 (한 쪽 보기면 그 쪽).
     * F-50 표지 단독: 왼쪽 쪽 번호가 -1, 1, 3… (첫 펼침 = 빈 왼쪽 + 0쪽 표지). 아니면 0, 2, 4….
     */
    private fun minPosition(): Int = if (isSpread() && config.coverAlone) -1 else 0

    private fun alignPosition() {
        if (isSpread()) {
            val odd = config.coverAlone
            if (Math.floorMod(position, 2) != (if (odd) 1 else 0)) position -= 1
        }
        position = position.coerceIn(minPosition(), max(minPosition(), count() - 1))
    }

    private fun canTurn(next: Boolean): Boolean =
        if (next) position + step() < count() else position - step() >= minPosition()

    private fun beginDrag(next: Boolean, y: Float) {
        if (phase == Phase.ANIM && autoRemaining > 0) return
        if (phase == Phase.ANIM) finishAnim()
        if (!canTurn(next)) return
        startCurl(next)
        phase = Phase.DRAG
        gy = y.coerceIn(viewH * 0.05f, viewH * 0.95f)
        py = gy
        dragFrac = 0f
    }

    private fun startCurl(next: Boolean) {
        forward = true
        if (!next) {
            // 이전 넘김 = 앞 펼침에서 시작한 넘김을 거꾸로 되감기
            position -= step()
            forward = false
            notifyPage()
        }
        t = if (next) 0f else 1f
        ensureTextures()
        setAnimating(true)
    }

    private fun updateDrag(x0: Float, x: Float, y: Float) {
        if (phase != Phase.DRAG) return
        val span = pageW() * if (isSpread()) 1.6f else 1.2f
        val d = (x0 - x) / span
        t = (if (forward) d else 1f - (-d)).coerceIn(0f, 1f)
        dragFrac = (if (forward) x0 - x else x - x0) / pageW().coerceAtLeast(1f)
        py = y
        requestFrame()
    }

    /**
     * 손을 뗌(I-716). 실제 책처럼 쪽 너비의 약 1/4 을 넘기거나 넘기는 쪽으로 튕기면 넘기고, 아니면 제자리로.
     * [vx] = 손가락 가로 속도(px/초, 거울 좌표), 애니메이션은 그 속도에서 이어서 시작한다.
     */
    private fun endDrag(vx: Float, density: Float, cancel: Boolean) {
        if (phase != Phase.DRAG) return
        val velTurnDp = (if (forward) -vx else vx) / density.coerceAtLeast(0.1f)
        // 취소(두 손가락 확대 등)는 제자리로
        val commit = !cancel && CurlMath.commitOnRelease(dragFrac, velTurnDp)
        // 되감기(forward=false)에서 commit = 이전 쪽으로 넘어감 = t→0
        val target = if (forward) (if (commit) 1f else 0f) else (if (commit) 0f else 1f)
        val span = pageW() * if (isSpread()) 1.6f else 1.2f
        animateTo(target, vPerS = -vx / span.coerceAtLeast(1f))
    }

    private fun startTurn(next: Boolean, fromUser: Boolean) {
        if (phase != Phase.IDLE) {
            if (fromUser || phase == Phase.DRAG) return
            finishAnim()
        }
        if (!canTurn(next)) return
        if (config.effect == TurnEffect.NONE) {
            position += if (next) step() else -step()
            ensureTextures()
            notifyPage()
            requestFrame()
            return
        }
        startCurl(next)
        gy = viewH * 0.85f
        py = gy
        t = if (next) 0f else 1f
        animateTo(if (next) 1f else 0f)
    }

    /** [vPerS] = 손을 뗄 때 진행도 속도(진행도/초). null = 손가락 없는 넘김(탭·볼륨·자동): 예전과 같은 감속 곡선. */
    private fun animateTo(target: Float, vPerS: Float? = null) {
        phase = Phase.ANIM
        animFrom = t
        animTo = target
        animStartNs = 0L // 다음 프레임의 vsync 시각에서 시작
        if (vPerS == null) {
            animDurNs = max(1L, (config.turnDurationMs * 1_000_000L * abs(target - t)).toLong())
            animSlope = 2f * (target - t) // 1-(1-k)² 와 같은 시작 기울기
        } else {
            val (ms, slope) = CurlMath.releasePlan(t, target, vPerS, config.turnDurationMs)
            animDurNs = ms * 1_000_000L
            animSlope = slope
        }
        requestFrame()
    }

    private fun finishAnim() {
        // 진행 중 애니메이션을 끝 상태로 정리
        if (animTo >= 1f) position += step() // 다 넘어간 상태(되감기 취소 포함)
        phase = Phase.IDLE
        t = 0f
        forward = true
        setAnimating(false)
        ensureTextures()
        notifyPage()
        recorder.onTurnEnd()
    }

    private fun nextAuto() {
        if (autoRemaining <= 0) {
            val cb = autoDone ?: return
            autoDone = null
            val report = recorder.stop()
            mainHandler.post { cb(report) }
            return
        }
        if (!canTurn(true)) {
            // 끝에 닿으면 처음으로 (측정 중 넘김 횟수 유지)
            position = minPosition()
            ensureTextures()
            notifyPage()
        }
        autoRemaining--
        startTurn(next = true, fromUser = false)
    }

    private fun setAnimating(v: Boolean) {
        if (_isAnimating.value != v) {
            _isAnimating.value = v
            mainHandler.post { HighRefresh.request(this, v) }
        }
    }

    // ------------------------------------------------------------------
    // 쪽 텍스처 (미리 해석 범위: 앞 2쪽 ~ 뒤 4쪽)
    // ------------------------------------------------------------------
    private fun wanted(): IntRange {
        val s = step()
        return (position - 2 * s).coerceAtLeast(0)..(position + 3 * s - 1).coerceAtMost(count() - 1)
    }

    private fun ensureTextures() {
        val src = source ?: return
        if (viewW <= 0 || egl == null) return
        val range = wanted()
        textures.keys.filter { it !in range }.forEach { renderer.deleteTexture(textures.remove(it)!!); papers.remove(it) }
        val maxTex = renderer.maxTextureSize
        val scale = min(1f, maxTex / max(pageW(), viewH))
        val pw = max(1, (pageW() * scale).toInt())
        val ph = max(1, (viewH * scale).toInt())
        val gen = sourceGen
        // 지금 보이는 쪽인데 아직 그림이 없으면(처음 열기·건너뛰기) 작게 먼저 그려 흰 화면을 줄인다(작업 03: 무거운 한글 쪽 2.5초+)
        val visible = if (isSpread()) setOf(position, position + 1) else setOf(position)
        // 가까운 쪽부터
        range.sortedBy { abs(it - position) }.forEach { idx ->
            if (idx in textures || idx in loading) return@forEach
            // release() 뒤(shutdownNow)에는 새 작업을 넣지 않는다(RejectedExecutionException 방지)
            if (loader.isShutdown) return@forEach
            loading += idx
            val preview = idx in visible
            try { loader.execute {
                if (preview) render(src, idx, max(1, pw / PREVIEW_DIV), max(1, ph / PREVIEW_DIV))?.let { small ->
                    gl.post {
                        if (gen == sourceGen && egl != null && idx in wanted() && idx !in textures) {
                            textures[idx] = renderer.upload(small)
                            requestFrame()
                        }
                        small.recycle()
                    }
                }
                val bmp = render(src, idx, pw, ph)
                val paper = if (bmp != null && src.paperOnly) runCatching { scanPaper(bmp) }.getOrNull() else null
                gl.post {
                    loading -= idx
                    if (bmp == null) return@post
                    if (gen == sourceGen && egl != null && idx in wanted()) {
                        if (paper != null) papers[idx] = paper else papers.remove(idx)
                        // 미리보기(작은 그림)가 있으면 선명한 그림으로 바꾼다
                        textures.remove(idx)?.let { renderer.deleteTexture(it) }
                        textures[idx] = renderer.upload(bmp)
                        requestFrame()
                    }
                    bmp.recycle()
                }
            } } catch (e: RejectedExecutionException) {
                loading -= idx
            }
        }
    }

    private fun render(src: PageSource, idx: Int, w: Int, h: Int): Bitmap? = try {
        src.render(idx, w, h)
    } catch (e: Throwable) {
        Log.e(EglCore.TAG, "쪽 $idx 렌더 실패", e)
        null
    }

    private fun clearTextures() {
        textures.values.forEach { renderer.deleteTexture(it) }
        textures.clear()
        papers.clear()
        loading.clear()
    }

    /**
     * 그린 쪽 그림에서 종이 자리(u0, v0, u1, v1)를 잰다: 모서리 색(바탕)과 다른 점이 처음 나오는 줄·칸까지가 여백.
     * 만화(책등 쪽 붙임·여백 자르기)·PDF·한글 원본 모두 실제 그려진 자리를 따른다. 칸을 거의 다 채우면 null(칸 전체).
     * 흰 종이를 흰 바탕에 그리면 글·그림이 있는 곳까지만 잡히지만, 흰 여백은 바탕과 같아 보이므로 넘김 모양은 같다.
     */
    private fun scanPaper(b: Bitmap): FloatArray? {
        val w = b.width; val h = b.height
        if (w < 8 || h < 8) return null
        val bg = b.getPixel(0, 0)
        fun far(c: Int): Boolean {
            val d = abs(((c shr 16) and 0xFF) - ((bg shr 16) and 0xFF)) + abs(((c shr 8) and 0xFF) - ((bg shr 8) and 0xFF)) + abs((c and 0xFF) - (bg and 0xFF))
            return d > 24
        }
        val row = IntArray(w)
        fun rowHas(y: Int): Boolean { b.getPixels(row, 0, w, 0, y, w, 1); var x = 0; while (x < w) { if (far(row[x])) return true; x += 2 }; return false }
        val col = IntArray(h)
        fun colHas(x: Int): Boolean { b.getPixels(col, 0, 1, x, 0, 1, h); var y = 0; while (y < h) { if (far(col[y])) return true; y += 2 }; return false }
        var top = 0; while (top < h && !rowHas(top)) top++
        if (top >= h) return null // 빈 쪽
        var bottom = h - 1; while (bottom > top && !rowHas(bottom)) bottom--
        var left = 0; while (left < w && !colHas(left)) left++
        var right = w - 1; while (right > left && !colHas(right)) right--
        val u0 = left.toFloat() / w; val u1 = (right + 1f) / w; val v0 = top.toFloat() / h; val v1 = (bottom + 1f) / h
        if (u1 - u0 > 0.985f && v1 - v0 > 0.985f) return null
        return floatArrayOf(u0, v0, u1, v1)
    }

    private fun paper(i: Int): FloatArray? = if (i in 0 until count()) papers[i] else null

    private fun tex(i: Int): Int = if (i in 0 until count()) textures[i] ?: 0 else 0

    private fun notifyPage() {
        val p = max(0, position)
        val s = isSpread()
        mainHandler.post { onPageChanged?.invoke(p, s) }
    }

    // ------------------------------------------------------------------
    // 그리기
    // ------------------------------------------------------------------
    private fun requestFrame() {
        if (frameScheduled) return
        val ch = choreographer ?: return
        frameScheduled = true
        ch.postFrameCallback(frameCallback)
    }

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        frameScheduled = false
        onFrame(frameTimeNanos)
    }

    private fun onFrame(vsyncNs: Long) {
        val core = egl ?: return
        if (viewW <= 0f) return
        var ended = false
        if (phase == Phase.ANIM) {
            // 넘김 진행은 vsync 시각 기준(그리기 시작이 조금 밀려도 프레임 간격이 고르다)
            if (animStartNs == 0L) animStartNs = vsyncNs
            val k = ((vsyncNs - animStartNs).toFloat() / animDurNs).coerceIn(0f, 1f)
            // 손을 뗄 때 속도에서 이어지는 감속 곡선(I-716)
            t = CurlMath.releaseCurve(animFrom, animTo, animSlope, k).coerceIn(0f, 1f)
            if (k >= 1f) ended = true
        }
        val animating = phase != Phase.IDLE
        draw()
        val ok = core.swap()
        val done = System.nanoTime()
        if (animating) recorder.onFrame(vsyncNs, done, continuing = true)
        if (!ok) Log.w(EglCore.TAG, "eglSwapBuffers 실패")
        if (ended) {
            val wasAuto = autoDone != null
            if (animTo >= 1f) position += step() // 다 넘어간 상태(되감기 취소 포함)
            phase = Phase.IDLE
            t = 0f
            forward = true
            setAnimating(false)
            recorder.onTurnEnd()
            ensureTextures()
            notifyPage()
            requestFrame()
            if (wasAuto) gl.postDelayed({ nextAuto() }, autoIntervalMs)
        } else if (phase != Phase.IDLE) {
            requestFrame()
        }
    }

    private fun draw() {
        val baseMirror = config.direction == TurnDirection.RTL
        // 작업 03: 이전 쪽 넘김(되감기)은 앞 넘김을 좌우로 뒤집은 모양 — 지금 쪽이 왼쪽 모서리에서 말려 오른쪽으로 넘어가며
        // 이전 쪽이 드러난다(예전에는 앞 넘김을 거꾸로 되돌려 오른쪽 모서리가 돌아오는 모양이라 넘김 방향과 같아 보였다).
        val rewind = !forward && phase != Phase.IDLE && config.effect == TurnEffect.CURL
        val mirror = if (rewind) !baseMirror else baseMirror
        val shadow = config.shadowStrength
        renderer.begin(viewW, viewH, mirror, config.backColor, config.backMix, shadow)
        val spread = isSpread()
        val pw = pageW()
        val h = viewH
        val p = position
        if (phase == Phase.IDLE || config.effect == TurnEffect.NONE) {
            if (spread) {
                if (p >= 0) renderer.drawFlat(0f, 0f, pw, h, tex(p))
                if (p + 1 < count()) renderer.drawFlat(pw, 0f, pw, h, tex(p + 1))
            } else {
                renderer.drawFlat(0f, 0f, pw, h, tex(p))
            }
            return
        }
        if (config.effect == TurnEffect.SLIDE) {
            // 대표님 23:05: 쪽 모양이 정해진 책은 화면 전체가 아니라 종이만 넘어간다(둘레 여백은 가만히).
            // 한 쪽 = 다음 쪽은 제자리, 지금 종이만 밀려 나감. 두 쪽 펼침 = 오른쪽 한 장이 책등을 축으로 넘어가 왼쪽에 덮임.
            val leafIdx = if (spread) p + 1 else p
            val pr = paper(leafIdx)
            if (pr != null) {
                if (spread) {
                    if (p >= 0) renderer.drawFlat(0f, 0f, pw, h, tex(p))
                    renderer.drawFlat(pw, 0f, pw, h, tex(p + 3))
                    if (t < 0.5f) {
                        val lw = pr[2] * pw * (1f - 2f * t)
                        renderer.drawFlat(pw, pr[1] * h, lw, (pr[3] - pr[1]) * h, tex(p + 1), uv = floatArrayOf(0f, pr[1], pr[2], pr[3]))
                    } else {
                        val back = paper(p + 2) ?: pr
                        val lw = back[2] * pw * (2f * t - 1f)
                        renderer.drawFlat(pw - lw, back[1] * h, lw, (back[3] - back[1]) * h, tex(p + 2), uv = floatArrayOf(1f - back[2], back[1], 1f, back[3]))
                    }
                } else {
                    renderer.drawFlat(0f, 0f, pw, h, tex(p + 1))
                    val lx = pr[0] * pw; val lw = (pr[2] - pr[0]) * pw
                    renderer.drawFlat(lx - t * (lx + lw), pr[1] * h, lw, (pr[3] - pr[1]) * h, tex(p), uv = pr)
                }
                return
            }
            val off = -t * viewW
            if (spread) {
                if (p >= 0) renderer.drawFlat(off, 0f, pw, h, tex(p))
                renderer.drawFlat(off + pw, 0f, pw, h, tex(p + 1))
                renderer.drawFlat(off + viewW, 0f, pw, h, tex(p + 2))
                renderer.drawFlat(off + viewW + pw, 0f, pw, h, tex(p + 3))
            } else {
                renderer.drawFlat(off, 0f, pw, h, tex(p))
                renderer.drawFlat(off + viewW, 0f, pw, h, tex(p + 1))
            }
            return
        }
        // 종이책 말림. 되감기는 t(1→0)를 이전 쪽 넘김 진행도 u = 1 - t 로 바꿔 뒤집힌 화면에서 앞 넘김처럼 그린다.
        val u = if (rewind) 1f - t else t
        // 대표님 23:05: 쪽 모양이 정해진 책은 종이만 말린다(둘레 여백은 가만히). 말리는 장 = 한 쪽이면 지금 쪽, 펼침이면 넘기는 장의 앞면.
        val leafIdx = if (spread) (if (rewind) p + 2 else p + 1) else (if (rewind) p + 1 else p)
        val pr = paper(leafIdx)
        if (pr != null) {
            val ty = pr[1] * h; val lh = (pr[3] - pr[1]) * h
            // 펼침: 책등(pw)부터 종이 바깥 끝까지가 한 장 — 다 넘어가면 맞은편 쪽 자리와 꼭 맞는다.
            // 한 쪽: 종이만, 다 넘어가면 화면 왼쪽 밖으로.
            val lx = if (spread) pw else pr[0] * pw
            val lw = if (spread) pr[2] * pw else (pr[2] - pr[0]) * pw
            val gxL = lx + lw
            val travel = if (spread) lw else gxL
            val radius = CurlMath.radiusFor(u, lw * 0.18f)
            val fx = CurlMath.fingerX(gxL, travel, u)
            val gyL = gy.coerceIn(ty + lh * 0.05f, ty + lh * 0.95f)
            val fy = CurlMath.fingerY(gyL - ty, py - ty, lh, u) + ty
            val curl = CurlMath.curlFor(gxL, gyL, fx, fy, radius)
            val uv = if (spread) floatArrayOf(0f, pr[1], pr[2], pr[3]) else pr
            if (rewind) {
                if (spread) {
                    if (p + 3 < count()) renderer.drawFlat(0f, 0f, pw, h, tex(p + 3))
                    if (p >= 0) renderer.drawFlat(pw, 0f, pw, h, tex(p), shadowFrom = curl)
                    renderer.drawCurl(lx, ty, lw, lh, tex(p + 2), tex(p + 1), backIsTex = true, curl = curl, uv = uv)
                } else {
                    renderer.drawFlat(0f, 0f, pw, h, tex(p), shadowFrom = curl)
                    renderer.drawCurl(lx, ty, lw, lh, tex(p + 1), 0, backIsTex = false, curl = curl, uv = uv)
                }
            } else if (spread) {
                if (p >= 0) renderer.drawFlat(0f, 0f, pw, h, tex(p))
                if (p + 3 < count()) renderer.drawFlat(pw, 0f, pw, h, tex(p + 3), shadowFrom = curl)
                renderer.drawCurl(lx, ty, lw, lh, tex(p + 1), tex(p + 2), backIsTex = true, curl = curl, uv = uv)
            } else {
                renderer.drawFlat(0f, 0f, pw, h, tex(p + 1), shadowFrom = curl)
                renderer.drawCurl(lx, ty, lw, lh, tex(p), 0, backIsTex = false, curl = curl, uv = uv)
            }
            return
        }
        val gx = viewW
        val radius = CurlMath.radiusFor(u, pw * 0.18f)
        val fx = CurlMath.fingerX(gx, pw, u)
        // I-716·작업 03: 손가락 위아래는 조금만 따라가고, 잡은 높이에 따라 위·아래 모서리가 먼저 넘어간다
        val fy = CurlMath.fingerY(gy, py, h, u)
        val curl = CurlMath.curlFor(gx, gy, fx, fy, radius)
        if (rewind) {
            // 뒤집힌 화면: 0 자리 = 실제 오른쪽, pw 자리 = 실제 왼쪽. position(p) 은 이미 이전 펼침(돌아갈 곳)이다.
            if (spread) {
                if (p + 3 < count()) renderer.drawFlat(0f, 0f, pw, h, tex(p + 3))
                if (p >= 0) renderer.drawFlat(pw, 0f, pw, h, tex(p), shadowFrom = curl)
                renderer.drawCurl(pw, 0f, pw, h, tex(p + 2), tex(p + 1), backIsTex = true, curl = curl)
            } else {
                renderer.drawFlat(0f, 0f, pw, h, tex(p), shadowFrom = curl)
                renderer.drawCurl(0f, 0f, pw, h, tex(p + 1), 0, backIsTex = false, curl = curl)
            }
            return
        }
        if (spread) {
            if (p >= 0) renderer.drawFlat(0f, 0f, pw, h, tex(p))
            if (p + 3 < count()) renderer.drawFlat(pw, 0f, pw, h, tex(p + 3), shadowFrom = curl)
            renderer.drawCurl(pw, 0f, pw, h, tex(p + 1), tex(p + 2), backIsTex = true, curl = curl)
        } else {
            renderer.drawFlat(0f, 0f, pw, h, tex(p + 1), shadowFrom = curl)
            renderer.drawCurl(0f, 0f, pw, h, tex(p), 0, backIsTex = false, curl = curl)
        }
    }

    private companion object {
        /** 보이는 쪽 미리보기 크기 = 쪽 크기 / 이 값(화소 수 1/9 — 무거운 쪽도 금방 그린다). */
        const val PREVIEW_DIV = 3
    }
}
