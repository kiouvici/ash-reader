// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.bench

import android.content.Context
import android.os.SystemClock
import java.io.File
import kr.vici.ashreader.engine.hwp.HwpDoc
import kr.vici.ashreader.engine.hwp.HwpHealth
import kr.vici.ashreader.engine.mupdf.MupdfDoc

private fun copyBenchAsset(context: Context, name: String, dir: File): File {
    val f = File(dir, name)
    if (!f.exists()) context.assets.open("bench/$name").use { i -> f.outputStream().use { i.copyTo(it) } }
    return f
}

private fun fontDir(context: Context): File {
    val d = File(context.filesDir, "fonts").apply { mkdirs() }
    for (n in listOf("NanumMyeongjo-Regular.ttf", "NanumMyeongjo-Bold.ttf", "NanumGothic-Regular.ttf", "NanumGothic-Bold.ttf"))
        copyBenchAsset(context, n, d)
    return d
}

private fun ms(t0: Long) = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6

/** ① HWP/HWPX 원본 렌더: 파일별 열기·첫 쪽 표시 시간(N-01 5초), 쪽 수, F-28 판정. */
object HwpRenderExperiment : Experiment {
    override val id = "1"
    override val title = "① HWP/HWPX 원본 렌더"
    override val description = "rhwp 열기·첫 쪽 시간(N-01 5초), 쪽 수, 빈 쪽 판정. 공개 파일 + 고른 폴더의 HWP"
    override val ready = true

    override suspend fun run(context: Context, files: List<TestFile>): List<Measurement> {
        HwpDoc.installFonts(listOf(fontDir(context)))
        val work = File(context.cacheDir, "bench-hwp").apply { mkdirs() }
        val width = context.resources.displayMetrics.widthPixels
        val rows = mutableListOf<Measurement>()
        val firsts = mutableListOf<Double>()
        for (f in files.filter { it.name.endsWith(".hwp", true) || it.name.endsWith(".hwpx", true) }) {
            val local = File(work, f.name.substringAfterLast('/'))
            f.open(context).use { i -> local.outputStream().use { i.copyTo(it) } }
            val t0 = SystemClock.elapsedRealtimeNanos()
            val r = runCatching {
                HwpDoc.open(local.absolutePath).use { d ->
                    val tOpen = ms(t0)
                    val bmp = d.renderPage(0, width)
                    val tFirst = ms(t0)
                    val f28 = HwpHealth.firstPageFailed(HwpHealth.inkRatio(bmp), d.pageTextChars(0))
                    listOf(
                        Measurement(title, f.name, "열기", tOpen, "ms"),
                        Measurement(title, f.name, "첫 쪽 표시(N-01)", tFirst, "ms", if (tFirst <= 5000) "합격" else "불합격"),
                        Measurement(title, f.name, "쪽 수", d.pageCount().toDouble(), "쪽"),
                        Measurement(title, f.name, "F-28 자동 전환", if (f28) 1.0 else 0.0, "", if (f28) "전환" else "원본 유지"),
                    ).also { firsts += tFirst }
                }
            }
            rows += r.getOrElse { listOf(Measurement(title, f.name, "열기 실패", Double.NaN, "", it.message ?: "")) }
            local.delete()
        }
        return if (firsts.isEmpty()) rows else rows + Stats.summarize(title, "첫 쪽 표시(N-01)", "ms", firsts)
    }
}

/** ④ 한국어 줄바꿈·금칙: 폭 5단계 위반 수와 조판 시간. */
object LineBreakExperiment : Experiment {
    override val id = "4"
    override val title = "④ 한국어 줄바꿈·금칙"
    override val description = "keep-all 패치 MuPDF: 낱말 중간 줄바꿈·금칙 위반 0건, 조판 시간"
    override val ready = true

    private val START = ".,!?…」』”’)]}。、:;%℃".toSet()
    private val END = "「『“‘([{".toSet()

    override suspend fun run(context: Context, files: List<TestFile>): List<Measurement> {
        val fonts = fontDir(context)
        val text = context.assets.open("bench/linebreak-sentences.txt").bufferedReader().readText()
        val paras = text.split(Regex("\n\\s*\n")).map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
        val x = File(context.cacheDir, "lb.xhtml")
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        x.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"ko\"><body>" +
            paras.joinToString("") { "<p>${esc(it)}</p>" } + "</body></html>")
        val css = """
            @font-face { font-family: s; src: url("${File(fonts, "NanumMyeongjo-Regular.ttf").absolutePath}"); }
            body { margin: 0; font-family: s; line-height: 1.7; }
            p { margin: 0 0 0.5em 0; text-align: justify; text-indent: 1em; word-break: keep-all; overflow-wrap: break-word; }
        """.trimIndent()
        MupdfDoc.installFonts(fonts)
        // 원문: 글자 + 뒤 공백 여부
        val chars = ArrayList<Char>(); val sp = ArrayList<Boolean>()
        for (p in paras) { for (c in p) { if (c == ' ') { if (sp.isNotEmpty()) sp[sp.size - 1] = true } else { chars += c; sp += false } }; if (sp.isNotEmpty()) sp[sp.size - 1] = true }
        val tokLen = IntArray(chars.size); var i = 0
        while (i < chars.size) { var j = i; while (j < chars.size && !sp[j]) j++; for (k in i..minOf(j, chars.size - 1)) tokLen[k] = j - i + 1; i = j + 1 }
        val rows = mutableListOf<Measurement>()
        for (w in listOf(200, 260, 320, 400, 520)) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val lines = MupdfDoc.open(x.absolutePath, MupdfDoc.LayoutSpec(w.toFloat(), 20000f, 12f), css).use { d ->
                (0 until d.pageCount()).flatMap { d.pageLines(it) }
            }
            val tLayout = ms(t0)
            var pos = 0; var mid = 0; var kin = 0
            for ((li, ln) in lines.withIndex()) {
                val cs = ln.filter { !it.isWhitespace() }; if (cs.isEmpty()) continue
                if (cs.first() in START || cs.last() in END) kin++
                for (c in cs) { while (pos < chars.size && chars[pos] != c) pos++; if (pos >= chars.size) break; pos++ }
                val last = pos - 1
                if (last in chars.indices && !sp[last] && li + 1 < lines.size && tokLen[last] <= w / 12) mid++
            }
            rows += Measurement(title, "폭 ${w}pt", "낱말 중간 줄바꿈", mid.toDouble(), "건", if (mid == 0) "합격" else "불합격")
            rows += Measurement(title, "폭 ${w}pt", "금칙 위반", kin.toDouble(), "건", if (kin == 0) "합격" else "불합격")
            rows += Measurement(title, "폭 ${w}pt", "조판+줄 추출", tLayout, "ms", "${lines.size}줄")
        }
        return rows
    }
}
