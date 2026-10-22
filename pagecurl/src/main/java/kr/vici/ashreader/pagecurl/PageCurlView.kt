// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.pagecurl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.TextureView
import android.view.ViewConfiguration
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 책 넘김 표면 (기술 설계서 4.2). TextureView + 전용 GL 스레드(EGL14, GLES 2.0).
 * 정보줄 등 고정 층은 이 뷰 위에 따로 겹친다(M-03: 정보줄 고정).
 *
 * 스레드: 공개 메서드는 메인 스레드에서 부른다. 그리기 상태는 모두 GL 스레드가 가진다.
 */
class PageCurlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    /** 탭 (0..1 정규화 좌표, 화면 기준). 탭 영역 판정은 바깥(앱)이 한다. */
    var onTap: ((xFrac: Float, yFrac: Float) -> Unit)? = null

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

    init {
        surfaceTextureListener = this
        isOpaque = true
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
    // TextureView
    // ------------------------------------------------------------------
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        gl.post {
            val core = EglCore()
            if (!core.init(surface)) {
                Log.e(EglCore.TAG, "EGL 초기화 실패")
                return@post
            }
            egl = core
            renderer.init()
            choreographer = Choreographer.getInstance()
            onResize(width, height)
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        gl.post { onResize(width, height) }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // GL 스레드에서 EGL 을 정리한 뒤 SurfaceTexture 를 놓아야 하므로 끝날 때까지 기다린다.
        val done = java.util.concurrent.CountDownLatch(1)
        gl.post {
            clearTextures()
            renderer.release()
            egl?.release()
            egl = null
            done.countDown()
        }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

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
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = lx; downY = event.y; downTime = SystemClock.uptimeMillis(); dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = lx - downX
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
                if (dragging) {
                    gl.post { endDrag() }
                } else if (event.actionMasked == MotionEvent.ACTION_UP &&
                    SystemClock.uptimeMillis() - downTime < ViewConfiguration.getLongPressTimeout()
                ) {
                    performClick()
                    onTap?.invoke(event.x / w, event.y / height)
                }
                dragging = false
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
        py = y
        requestFrame()
    }

    private fun endDrag() {
        if (phase != Phase.DRAG) return
        val commit = if (forward) CurlMath.shouldCommit(t) else !CurlMath.shouldCommit(t)
        // 되감기(forward=false)에서 commit = 이전 쪽으로 넘어감 = t→0
        val target = if (forward) (if (commit) 1f else 0f) else (if (commit) 0f else 1f)
        animateTo(target)
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

    private fun animateTo(target: Float) {
        phase = Phase.ANIM
        animFrom = t
        animTo = target
        animStartNs = System.nanoTime()
        animDurNs = max(1L, (config.turnDurationMs * 1_000_000L * abs(target - t)).toLong())
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
        textures.keys.filter { it !in range }.forEach { renderer.deleteTexture(textures.remove(it)!!) }
        val maxTex = renderer.maxTextureSize
        val scale = min(1f, maxTex / max(pageW(), viewH))
        val pw = max(1, (pageW() * scale).toInt())
        val ph = max(1, (viewH * scale).toInt())
        val gen = sourceGen
        // 가까운 쪽부터
        range.sortedBy { abs(it - position) }.forEach { idx ->
            if (idx in textures || idx in loading) return@forEach
            loading += idx
            loader.execute {
                val bmp: Bitmap? = try {
                    src.render(idx, pw, ph)
                } catch (e: Throwable) {
                    Log.e(EglCore.TAG, "쪽 $idx 렌더 실패", e)
                    null
                }
                gl.post {
                    loading -= idx
                    if (bmp == null) return@post
                    if (gen == sourceGen && egl != null && idx in wanted() && idx !in textures) {
                        textures[idx] = renderer.upload(bmp)
                        requestFrame()
                    }
                    bmp.recycle()
                }
            }
        }
    }

    private fun clearTextures() {
        textures.values.forEach { renderer.deleteTexture(it) }
        textures.clear()
        loading.clear()
    }

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
            val k = ((System.nanoTime() - animStartNs).toFloat() / animDurNs).coerceIn(0f, 1f)
            val e = 1f - (1f - k) * (1f - k) // 감속
            t = animFrom + (animTo - animFrom) * e
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
        val mirror = config.direction == TurnDirection.RTL
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
        // 종이책 말림
        val gx = viewW
        val radius = CurlMath.radiusFor(t, pw * 0.18f)
        val fx = CurlMath.fingerX(gx, pw, t)
        val lift = (py - gy) * (1f - t)
        val fy = gy + lift - (h * 0.06f) * 4f * t * (1f - t)
        val curl = CurlMath.curlFor(gx, gy, fx, fy, radius)
        if (spread) {
            if (p >= 0) renderer.drawFlat(0f, 0f, pw, h, tex(p))
            if (p + 3 < count()) renderer.drawFlat(pw, 0f, pw, h, tex(p + 3), shadowFrom = curl)
            renderer.drawCurl(pw, 0f, pw, h, tex(p + 1), tex(p + 2), backIsTex = true, curl = curl)
        } else {
            renderer.drawFlat(0f, 0f, pw, h, tex(p + 1), shadowFrom = curl)
            renderer.drawCurl(0f, 0f, pw, h, tex(p), 0, backIsTex = false, curl = curl)
        }
    }
}
