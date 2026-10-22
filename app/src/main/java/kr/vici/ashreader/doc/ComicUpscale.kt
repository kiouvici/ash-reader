// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.doc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.PowerManager
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.vici.ashreader.engine.comic.Upscaler

/** WebP 저장 형식(API 30+ 는 WEBP_LOSSY). */
@Suppress("DEPRECATION")
internal fun webpFormat(): Bitmap.CompressFormat =
    if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

/**
 * AI 화질 개선(F-73) 실제 적용: 만화 쪽을 백그라운드에서 Real-CUGAN 2배로 키워 디스크 캐시(app cache/upscale, LRU)에 둔다.
 * - 지금 쪽 + 다음 1~2쪽을 차례로 처리하고, 쪽을 건너뛰면 대기 중인 작업은 취소한다(처리 중인 한 쪽은 끝까지).
 * - 이미 화면 해상도 이상인 쪽은 건너뛴다. GPU(Vulkan) 먼저, 실패하면 CPU.
 * - 절전 모드에서는 새 작업을 시작하지 않는다.
 * 원본은 바로 보여 주고, 처리된 쪽은 [ready] 에 들어온다(화면이 바꿔 낀다).
 */
class ComicUpscale(context: Context, private val doc: ComicReaderDoc) : Closeable {
    private val app = context.applicationContext
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "ash-upscale").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + exec.asCoroutineDispatcher())
    private val dir = File(root(app), doc.cacheKey).apply { mkdirs() }
    /** 화면(메인) 스레드에서만 만진다. */
    private val jobs = HashMap<Int, Job>()
    private val skipped: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    /** 처리 스레드에서만 만진다. */
    private var engine: Upscaler? = null
    private var cpuOnly = false
    @Volatile private var closed = false

    private val readyState = MutableStateFlow(scan())
    /** 2배 결과가 캐시에 있는 쪽. */
    val ready: StateFlow<Set<Int>> = readyState
    private val busyState = MutableStateFlow<Int?>(null)
    /** 지금 처리 중인 쪽. */
    val busy: StateFlow<Int?> = busyState

    private fun file(i: Int) = File(dir, "$i.webp")

    private fun scan(): Set<Int> = dir.listFiles()?.mapNotNull { f ->
        if (f.name.endsWith(".webp") && f.length() > 0) f.name.removeSuffix(".webp").toIntOrNull() else null
    }?.toSet().orEmpty()

    /** [current] 쪽을 먼저, 이어서 다음 1~2쪽. 범위 밖 대기 작업은 취소. 화면(메인) 스레드에서 부른다. */
    fun request(current: Int, screenW: Int, screenH: Int) {
        if (closed) return
        val want = (current..current + 2).filter { it in 0 until doc.pageCount }
        val iter = jobs.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.key !in want || !e.value.isActive) { e.value.cancel(); iter.remove() }
        }
        if (powerSave() || screenW <= 0 || screenH <= 0) return
        for (i in want) {
            if (i in readyState.value || i in skipped || jobs.containsKey(i)) continue
            jobs[i] = scope.launch { process(i, screenW, screenH) }
        }
    }

    /** 캐시된 2배 쪽(폭이 [minW] 이상 남도록 줄여 읽음). 없으면 null. */
    fun load(i: Int, minW: Int): Bitmap? {
        if (i !in readyState.value) return null
        val f = file(i)
        return runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            if (o.outWidth <= 0) return null
            var s = 1
            val target = minW.coerceAtLeast(1)
            while (s < 16 && o.outWidth / (s * 2) >= target) s *= 2
            f.setLastModified(System.currentTimeMillis())
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        }.getOrNull()
    }

    private suspend fun process(i: Int, sw: Int, sh: Int) {
        try {
            currentCoroutineContext().ensureActive()
            if (closed || powerSave()) return
            val (w, h) = doc.pageSize(i)
            if (w <= 0 || h <= 0 || !Upscaler.shouldUpscale(w, h, sw, sh) || w.toLong() * h > MAX_INPUT_PIXELS) { skipped += i; return }
            val src = doc.fullBitmap(i)
            if (src == null) { skipped += i; return }
            currentCoroutineContext().ensureActive()
            busyState.value = i
            val out = try { runEngine(src) } finally { src.recycle() }
            try {
                dir.mkdirs() // 설정에서 캐시를 지웠을 수 있다
                val tmp = File(dir, "$i.tmp")
                tmp.outputStream().use { o -> out.compress(webpFormat(), 90, o) }
                if (!tmp.renameTo(file(i))) { tmp.delete(); return }
            } finally { out.recycle() }
            readyState.update { it + i }
            trim(app)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            skipped += i
        } finally {
            if (busyState.value == i) busyState.value = null
        }
    }

    private fun runEngine(src: Bitmap): Bitmap {
        val e = engine ?: create().also { engine = it }
        return try {
            e.upscale(src).bitmap
        } catch (t: Exception) {
            if (!e.isGpu) throw t
            // GPU(Vulkan) 실패 → 이후 CPU 로
            e.close(); engine = null; cpuOnly = true
            create().also { engine = it }.upscale(src).bitmap
        }
    }

    private fun create(): Upscaler {
        if (!cpuOnly) runCatching { Upscaler(app.assets, preferGpu = true) }.getOrNull()?.let { return it }
        cpuOnly = true
        return Upscaler(app.assets, preferGpu = false)
    }

    private fun powerSave(): Boolean =
        (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

    override fun close() {
        if (closed) return
        closed = true
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        scope.cancel()
        runCatching { exec.execute { engine?.close(); engine = null } }
        exec.shutdown()
    }

    companion object {
        /** 입력 쪽 최대 화소(2배 결과 메모리 제한). */
        const val MAX_INPUT_PIXELS = 4_000_000L
        /** 디스크 캐시 상한(바이트). 설정(300MB/1GB/2GB, 기본 1GB — F-73)을 앱이 넣는다. */
        @Volatile var limitBytes: Long = 1L shl 30

        fun root(c: Context) = File(c.cacheDir, "upscale")

        /** 오래 안 쓴 파일부터 지워 [limit] 이하로. */
        @Synchronized
        fun trim(c: Context, limit: Long = limitBytes) {
            val files = root(c).walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toList()
            var total = files.sumOf { it.length() }
            for (f in files) {
                if (total <= limit) break
                total -= f.length()
                f.delete()
            }
        }

        fun usedBytes(c: Context): Long = root(c).walkTopDown().filter { it.isFile }.sumOf { it.length() }

        /** 설정의 '캐시 지우기'. */
        @Synchronized
        fun clear(c: Context) { root(c).deleteRecursively() }

        /** 문서 하나의 캐시(한 번만 보기 문서를 닫을 때). */
        fun deleteFor(c: Context, key: String) { if (key.isNotEmpty()) File(root(c), key).deleteRecursively() }
    }
}
