// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.bench

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import java.io.File
import kr.vici.ashreader.engine.comic.ComicArchive
import kr.vici.ashreader.engine.comic.EncryptedArchiveException
import kr.vici.ashreader.engine.comic.GrayImage
import kr.vici.ashreader.engine.comic.PageDecoder
import kr.vici.ashreader.engine.comic.PanelDetector
import kr.vici.ashreader.engine.comic.PanelScoring
import kr.vici.ashreader.engine.comic.ReadingDirection
import kr.vici.ashreader.engine.comic.Upscaler

/**
 * ⑤ 만화 칸 인식·AI 화질 향상.
 *  - 시험 파일(.cbz/.cbr): 열기·쪽 수·방향·solid, 쪽마다 칸 인식 시간(디코딩 제외, N-10).
 *    같은 폴더에 `<책 이름>.panels.json`(정답 상자, PanelScoring 형식)이 있으면 IoU≥0.7 인식률(F-72 80%).
 *  - 화질 향상: 800×1200 쪽(시험 파일 첫 쪽 또는 합성)을 Real-CUGAN 2x 로 GPU·CPU 각각 처리, 쪽당 시간(F-73 5초).
 */
object ComicExperiment : Experiment {
    override val id = "5"
    override val title = "⑤ 만화 칸 인식·AI 화질"
    override val description = "CBZ·CBR 열기, 칸 인식 시간·인식률(정답 JSON 있으면), Real-CUGAN 2x 쪽당 시간(GPU/CPU), Vulkan 버전"
    override val ready = true

    private const val RUNS = 10       // 9.0 규칙: 10회 중앙값
    private const val UPSCALE_RUNS = 3 // 화질 향상은 1회가 수 초라 3회
    private fun ms(t0: Long) = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6

    override suspend fun run(context: Context, files: List<TestFile>): List<Measurement> {
        val rows = mutableListOf<Measurement>()
        val vk = runCatching { Upscaler.vulkanInfo() }.getOrElse { "오류: ${it.message}" }
        rows += Measurement(title, "기기", "Vulkan", if (vk == null) 0.0 else 1.0, "", vk ?: "없음 (CPU 경로만)")

        val work = File(context.cacheDir, "bench-comic").apply { deleteRecursively(); mkdirs() }
        val det = PanelDetector()
        val panelTimes = mutableListOf<Double>()
        var total = PanelScoring.Score.ZERO
        var firstPage: Bitmap? = null
        val truthFiles = files.filter { it.name.endsWith(".panels.json", true) }

        for (f in files.filter { it.name.endsWith(".cbz", true) || it.name.endsWith(".cbr", true) }) {
            val local = File(work, f.name.substringAfterLast('/'))
            f.open(context).use { i -> local.outputStream().use { i.copyTo(it) } }
            try {
                val t0 = SystemClock.elapsedRealtimeNanos()
                ComicArchive.open(local).use { a ->
                    rows += Measurement(title, f.name, "열기", ms(t0), "ms", "${a.kind}${if (a.solid) " solid" else ""}, ${a.pages.size}쪽, 방향=${a.direction ?: "미지정"}")
                    if (a.solid) {
                        val t1 = SystemClock.elapsedRealtimeNanos()
                        val n = a.extractAll(File(work, "solid-" + local.nameWithoutExtension))
                        rows += Measurement(title, f.name, "solid 전체 풀기", ms(t1), "ms", "${n}항목")
                    }
                    val dir = a.direction ?: ReadingDirection.LTR
                    val truth = truthFiles.firstOrNull { it.name.substringAfterLast('/').startsWith(local.nameWithoutExtension + ".") }
                        ?.let { t -> PanelScoring.parse(t.open(context).bufferedReader().readText()).associateBy { it.page } }.orEmpty()
                    var book = PanelScoring.Score.ZERO
                    for (p in a.pages.take(20)) {
                        val bytes = a.readPage(p.index)
                        val (w, h) = PageDecoder.bounds(bytes)
                        val bmp = PageDecoder.decodeFit(bytes, minOf(w, 1600))
                        if (firstPage == null) firstPage = bmp.copy(Bitmap.Config.ARGB_8888, false)
                        val gray = PageDecoder.toGray(bmp)
                        val t2 = SystemClock.elapsedRealtimeNanos()
                        val got = det.detect(gray, truth[p.name]?.direction ?: dir)
                        val tp = ms(t2); panelTimes += tp
                        val gt = truth[p.name]
                        if (gt != null) {
                            val sx = gt.width.toDouble() / gray.width
                            val s = PanelScoring.score(gt.panels, got.panels.map { it.scaled(sx) })
                            book += s
                            rows += Measurement(title, "${f.name}#${p.name}", "칸 인식", tp, "ms", "정답 ${s.truth}, 맞음 ${s.correct}, 순서틀림 ${s.matchedWrongOrder}, 인식 ${s.detected}")
                        } else rows += Measurement(title, "${f.name}#${p.name}", "칸 인식", tp, "ms", "${got.panels.size}칸 (정답 없음)${if (PageDecoder.needsTiles(w, h)) ", ${w}×${h} 타일 대상" else ""}")
                        bmp.recycle()
                    }
                    if (book.truth > 0) {
                        rows += Measurement(title, f.name, "인식률(F-72)", book.rate * 100, "%", if (book.rate >= 0.8) "합격" else "불합격")
                        total += book
                    }
                }
            } catch (e: EncryptedArchiveException) {
                rows += Measurement(title, f.name, "열기", Double.NaN, "", "암호: ${e.message}")
            } catch (e: Exception) {
                rows += Measurement(title, f.name, "열기 실패", Double.NaN, "", "${e.javaClass.simpleName}: ${e.message}")
            }
            local.delete()
        }

        // 시험 파일이 없으면 합성 쪽으로 칸 인식 시간만 잰다
        if (panelTimes.isEmpty()) {
            val bmp = syntheticPage(1000, 1500)
            val gray = PageDecoder.toGray(bmp)
            repeat(RUNS) {
                val t0 = SystemClock.elapsedRealtimeNanos()
                det.detect(gray, ReadingDirection.RTL)
                panelTimes += ms(t0)
            }
            rows += Measurement(title, "합성 1000×1500", "칸 인식", panelTimes.last(), "ms", "시험 파일 없음")
            if (firstPage == null) firstPage = bmp
        }
        rows += Stats.summarize(title, "칸 인식(쪽당, 1초 기준)", "ms", panelTimes)
        if (total.truth > 0) rows += Measurement(title, "요약", "인식률 전체", total.rate * 100, "%", "정답 ${total.truth}, 맞음 ${total.correct}")

        // 화질 향상: 800×1200 입력 (기술 설계서 9장 ⑤ ③)
        val src = Bitmap.createScaledBitmap(firstPage!!, 800, 1200, true)
        for (gpu in listOf(true, false)) {
            if (gpu && vk == null) continue
            val r = runCatching {
                Upscaler(context.assets, preferGpu = gpu).use { up ->
                    up.upscale(Bitmap.createScaledBitmap(src, 64, 96, true)).bitmap.recycle() // 예열(파이프라인 생성)
                    val times = (1..UPSCALE_RUNS).map { up.upscale(src).also { it.bitmap.recycle() }.millis }
                    val label = if (up.isGpu) "GPU" else "CPU"
                    times.mapIndexed { i, t -> Measurement(title, "800×1200 → 1600×2400", "화질 향상 $label #${i + 1}", t, "ms") } +
                        Stats.summarize(title, "화질 향상 $label(쪽당, 5초 기준)", "ms", times)
                }
            }
            rows += r.getOrElse { listOf(Measurement(title, "화질 향상", if (gpu) "GPU" else "CPU", Double.NaN, "", "실패: ${it.message}")) }
        }
        work.deleteRecursively()
        return rows
    }

    /** 칸 테두리가 있는 합성 쪽 */
    private fun syntheticPage(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.WHITE)
        val frame = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.BLACK }
        val fill = Paint().apply { color = Color.DKGRAY }
        val rows = listOf(listOf(1f), listOf(0.5f, 0.5f), listOf(0.3f, 0.7f))
        val rh = (h - 100 - 48) / 3f
        rows.forEachIndexed { ri, cols ->
            var x = 50f; val y = 50f + ri * (rh + 24)
            val avail = w - 100f - 24 * (cols.size - 1)
            for (fr in cols) {
                val pw = avail * fr
                c.drawRect(x, y, x + pw, y + rh, frame)
                c.drawCircle(x + pw / 2, y + rh / 2, rh / 5, fill)
                x += pw + 24
            }
        }
        return b
    }
}
