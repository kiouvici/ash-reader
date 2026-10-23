// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.doc

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.BatteryManager
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.vici.ashreader.engine.comic.Upscaler
import kr.vici.ashreader.engine.mupdf.MupdfDoc
import kr.vici.ashreader.pagecurl.PageCurlView

/**
 * EPUB 그림 AI 화질 향상(F-73 확장, D-021). 이북(EPUB·변환한 MOBI·AZW3·CHM) 쪽 안의 작은 래스터 그림만
 * 만화와 같은 Real-CUGAN 2배 엔진으로 키워 쪽 그림 위에 바꿔 그린다. 글자에는 적용하지 않는다.
 * - 쪽을 처음 그릴 때는 원래 그림 그대로, 뒤(백그라운드)에서 처리해 끝나면 [version] 이 바뀌고 화면이 다시 그린다.
 * - 대상은 [EpubUpscaleRules.eligible]: 화면에서 원래보다 크게 보이는 래스터 그림(SVG·GIF·아이콘·이미 큰 그림·단순 그림 제외).
 * - 결과는 만화와 같은 캐시 폴더(cache/upscale/epub)에 그림 내용 지문으로 둔다 — 상한(기본 1GB)·지우기를 함께 쓴다.
 * - 넘김 애니메이션 중([UpscaleGate])·절전 모드·배터리 15% 이하(충전 아님)에는 새 작업을 하지 않는다.
 */
class EpubImageUpscale(context: Context, private val doc: MupdfReaderDoc) : Closeable {
    private val app = context.applicationContext
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "ash-epub-upscale").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + exec.asCoroutineDispatcher())
    private val dir = File(ComicUpscale.root(app), "epub")
    @Volatile private var closed = false
    private var engine: Upscaler? = null
    private var cpuOnly = false

    /** 쪽마다 바꿔 그릴 그림(분석이 끝난 쪽만). */
    private class Item(val block: MupdfDoc.ImageBlock, val file: File)
    private val pages = ConcurrentHashMap<Int, List<Item>>()
    private val queued: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val kinds by lazy { buildIndex(doc.sourceFile) }

    private val versionState = MutableStateFlow(0)
    /** 처리된 그림이 늘 때마다 1씩 커진다(화면이 다시 그린다). */
    val version: StateFlow<Int> = versionState

    /**
     * 쪽 그림 [raw](폭 = 화면 쪽 폭)에 처리된 그림을 덮어 그린다. 아직 보지 않은 쪽이면 처리를 요청한다(이 쪽과 다음 쪽).
     * 렌더 스레드에서, 엔진 잠금 밖에서 부른다.
     */
    fun decorate(index: Int, raw: Bitmap) {
        if (closed) return
        val items = pages[index]
        if (items == null) { request(index, raw.width, raw.height); request(index + 1, raw.width, raw.height); return }
        if (items.isEmpty()) return
        val c = Canvas(raw)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        for (it in items) {
            val b = it.block
            val dst = RectF(b.l * raw.width, b.t * raw.height, b.r * raw.width, b.b * raw.height)
            if (dst.width() < 2f || dst.height() < 2f) continue
            val up = load(it.file, dst.width().toInt()) ?: continue
            c.drawBitmap(up, null, dst, p)
            up.recycle()
        }
    }

    private fun request(index: Int, w: Int, h: Int) {
        if (closed || index !in 0 until doc.pageCount || pages.containsKey(index) || !queued.add(index)) return
        scope.launch {
            try { analyze(index, w, h) } catch (e: CancellationException) { throw e } catch (_: Throwable) { pages[index] = emptyList() }
            finally { queued.remove(index) }
        }
    }

    private suspend fun analyze(index: Int, pageW: Int, pageH: Int) {
        UpscaleGate.awaitIdle()
        if (closed || powerSave() || batteryLow()) return // 다음에 그 쪽을 그릴 때 다시
        val blocks = doc.imageBlocks(index)
        val out = ArrayList<Item>()
        var made = false
        for ((k, b) in blocks.withIndex()) {
            if (closed) return
            val dispW = (b.r - b.l) * pageW; val dispH = (b.b - b.t) * pageH
            if (!EpubUpscaleRules.eligible(b.w, b.h, dispW, dispH, kinds.kindOf(b.w, b.h), b.orientation)) continue
            val src = doc.imageBitmap(index, k) ?: continue
            try {
                val px = IntArray(src.width * src.height)
                src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
                val step = (px.size / 4096).coerceAtLeast(1)
                if (EpubUpscaleRules.isFlat(IntArray(px.size / step) { px[it * step] })) continue // 이미 선명한 단순 그림
                val crc = CRC32().apply { for (v in px) { update(v); update(v ushr 8); update(v ushr 16) } }.value
                val f = File(dir, "${src.width}x${src.height}-${java.lang.Long.toHexString(crc)}.webp")
                if (!(f.isFile && f.length() > 0)) {
                    UpscaleGate.awaitIdle()
                    if (closed || powerSave()) return
                    val up = runEngine(src)
                    try {
                        dir.mkdirs()
                        val tmp = File(dir, f.name + ".tmp")
                        tmp.outputStream().use { o -> up.compress(webpFormat(), 90, o) }
                        if (!tmp.renameTo(f)) { tmp.delete(); continue }
                    } finally { up.recycle() }
                    made = true
                    ComicUpscale.trim(app)
                }
                out += Item(b, f)
            } finally { src.recycle() }
        }
        pages[index] = out
        if (out.isNotEmpty() || made) versionState.update { it + 1 }
    }

    /** 처리된 그림(폭이 [minW] 이상 남게 줄여 읽음). 캐시에서 지워졌으면 null. */
    private fun load(f: File, minW: Int): Bitmap? = runCatching {
        if (!f.isFile) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (s < 16 && o.outWidth / (s * 2) >= minW.coerceAtLeast(1)) s *= 2
        f.setLastModified(System.currentTimeMillis())
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.ARGB_8888 })
    }.getOrNull()

    private fun runEngine(src: Bitmap): Bitmap {
        val e = engine ?: create().also { engine = it }
        return try {
            e.upscale(src).bitmap
        } catch (t: Exception) {
            if (!e.isGpu) throw t
            e.close(); engine = null; cpuOnly = true
            create().also { engine = it }.upscale(src).bitmap
        }
    }

    private fun create(): Upscaler {
        if (!cpuOnly) runCatching { Upscaler(app.assets, preferGpu = true) }.getOrNull()?.let { return it }
        cpuOnly = true
        return Upscaler(app.assets, preferGpu = false)
    }

    private fun powerSave(): Boolean = (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

    private fun batteryLow(): Boolean = runCatching {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        level >= 0 && scale > 0 && level * 100 / scale <= 15 && i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0
    }.getOrDefault(false)

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        runCatching { exec.execute { engine?.close(); engine = null } }
        exec.shutdown()
    }

    companion object {
        /** EPUB(zip) 안 그림 파일의 크기·종류 표. zip 이 아니면(FB2·HTML 등) 빈 표 — 아무 그림도 처리하지 않는다. */
        fun buildIndex(file: File): EpubUpscaleRules.Index = runCatching {
            val list = ArrayList<Triple<String, Int, Int>>()
            ZipFile(file).use { z ->
                var n = 0
                for (e in z.entries()) {
                    if (e.isDirectory || n >= 3000) continue
                    val kind = EpubUpscaleRules.kindOfName(e.name)
                    if (kind != EpubUpscaleRules.Kind.RASTER && kind != EpubUpscaleRules.Kind.GIF) continue
                    n++
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    z.getInputStream(e).use { BitmapFactory.decodeStream(it, null, o) }
                    if (o.outWidth > 0 && o.outHeight > 0) list += Triple(e.name, o.outWidth, o.outHeight)
                }
            }
            EpubUpscaleRules.Index(list)
        }.getOrElse { EpubUpscaleRules.Index(emptyList()) }
    }
}

/**
 * 읽기 화면 연결(한 줄로 붙인다): 설정 'EPUB 그림 AI 화질 향상'이 켜져 있고 이북 문서이며 이 기기에서 AI 를 쓸 수 있으면
 * [EpubImageUpscale] 을 문서에 붙이고, 처리된 그림이 생기면 [onRefresh](지금 쪽 다시 그리기)를 부른다.
 * 종이 넘김 애니메이션 신호([PageCurlView.isAnimating])를 [UpscaleGate] 에 넣는다(만화 AI 도 같이 쉰다).
 */
@Composable
fun EpubUpscaleHook(doc: ReaderDoc, on: Boolean, curl: PageCurlView?, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val md = doc as? MupdfReaderDoc
    val want = on && md != null && md.ebookImages
    val cap by produceState(AiCapability.cached(context), want) {
        if (want && value == null) value = AiCapability.measureWithin(context)
    }
    val ok = want && cap?.verdict == AiCapability.Verdict.OK
    val up = remember(doc, ok) { if (ok && md != null) EpubImageUpscale(context, md) else null }
    DisposableEffect(up) {
        md?.imageUpscale = up
        onDispose {
            if (md != null && md.imageUpscale === up) md.imageUpscale = null
            up?.close()
        }
    }
    LaunchedEffect(curl) { curl?.isAnimating?.collect { UpscaleGate.curlAnimating = it } }
    DisposableEffect(curl) { onDispose { UpscaleGate.curlAnimating = false } }
    // 붙인 직후 지금 쪽을 한 번 다시 그려 처리를 시작하게 하고, 이후 처리된 그림이 생길 때마다 다시 그린다
    val v = up?.version?.collectAsState()?.value
    LaunchedEffect(up, v) { if (up != null) { delay(300); onRefresh() } }
}
