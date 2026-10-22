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

/**
 * ② Word(DOCX/DOC): 공개 시험 파일 + 고른 폴더의 .docx/.doc 를 열어 첫 쪽까지의 시간(N-10: 파일 읽기 시작 → 첫 쪽 비트맵)과
 * 문단·표·그림 수를 잰다. 경로: 시그니처 판별 → (암호면 해제) → DOCX/DOC→XHTML → MuPDF 조판 → 첫 쪽 렌더.
 * 각 파일 10회 반복, 중앙값으로 판정(9.0). 암호 DOCX는 공개 시험 파일 암호(ash1234)로만 시도한다.
 */
object OfficeExperiment : Experiment {
    override val id = "2"
    override val title = "② Word(DOCX/DOC)"
    override val description = "DOCX·DOC·암호 DOCX 열기→첫 쪽(N-01 5초, 10회 중앙값), 문단·표·그림 수. 공개 파일 + 고른 폴더"
    override val ready = true

    private const val REPEAT = 10
    private const val TEST_PASSWORD = "ash1234"

    private class Opened(val xhtml: String, val images: Map<String, ByteArray>, val kind: String, val paras: Int, val tables: Int, val imgs: Int, val hf: Int, val notes: Int)

    private fun convert(bytes: ByteArray): Opened {
        var b = bytes
        var kind = ""
        var fmt = kr.vici.ashreader.office.FormatSniffer.sniff(b)
        if (fmt == kr.vici.ashreader.office.SniffedFormat.ENCRYPTED_OOXML) {
            when (val r = kr.vici.ashreader.office.AgileDecryptor.decrypt(b, TEST_PASSWORD)) {
                is kr.vici.ashreader.office.AgileDecryptor.Result.Ok -> { b = r.zip; kind = "암호 해제+" }
                is kr.vici.ashreader.office.AgileDecryptor.Result.WrongPassword -> error("암호가 시험 암호와 다름")
                is kr.vici.ashreader.office.AgileDecryptor.Result.Unsupported -> error("지원 안 하는 암호 방식: ${r.reason}")
            }
            fmt = kr.vici.ashreader.office.FormatSniffer.sniff(b)
        }
        return when (fmt) {
            kr.vici.ashreader.office.SniffedFormat.DOCX -> {
                val r = kr.vici.ashreader.office.DocxConverter().convert(b)
                val s = r.stats
                Opened(r.xhtml, r.images, kind + "DOCX", s.paragraphs + s.listItems, s.tables, s.images, s.headers + s.footers, s.footnotes)
            }
            kr.vici.ashreader.office.SniffedFormat.DOC -> {
                val r = kr.vici.ashreader.office.DocExtractor.convert(b)
                Opened(r.xhtml, emptyMap(), kind + "DOC", r.paragraphTexts.size, r.tableGrids.size, 0, 0, 0)
            }
            else -> error("Word 형식 아님(시그니처: $fmt)")
        }
    }

    override suspend fun run(context: Context, files: List<TestFile>): List<Measurement> {
        val fonts = fontDir(context)
        MupdfDoc.installFonts(fonts)
        val css = """
            @font-face { font-family: s; src: url("${File(fonts, "NanumMyeongjo-Regular.ttf").absolutePath}"); }
            body { font-family: s; }
        """.trimIndent()
        val work = File(context.cacheDir, "bench-office").apply { deleteRecursively(); mkdirs() }
        val dm = context.resources.displayMetrics
        val widthPx = dm.widthPixels
        val pageW = 360f
        val pageH = pageW * dm.heightPixels / dm.widthPixels
        val rows = mutableListOf<Measurement>()
        val allMedians = mutableListOf<Double>()
        val targets = files.filter { f ->
            val n = f.name.lowercase()
            (n.endsWith(".docx") || n.endsWith(".doc")) && !n.contains("corrupt")
        }
        for (f in targets) {
            val times = mutableListOf<Double>()
            var last: Opened? = null
            var pages = 0
            val err = runCatching {
                repeat(REPEAT) { k ->
                    val dir = File(work, "r$k").apply { deleteRecursively(); mkdirs() }
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val bytes = f.open(context).use { it.readBytes() }
                    val o = convert(bytes)
                    for ((p, data) in o.images) File(dir, p).apply { parentFile?.mkdirs() }.writeBytes(data)
                    val x = File(dir, "doc.xhtml").apply { writeText(o.xhtml) }
                    MupdfDoc.open(x.absolutePath, MupdfDoc.LayoutSpec(pageW, pageH, 11f), css).use { d ->
                        d.renderPage(0, widthPx).recycle()
                        pages = d.pageCount()
                    }
                    times += ms(t0)
                    last = o
                    dir.deleteRecursively()
                }
            }.exceptionOrNull()
            if (err != null) {
                rows += Measurement(title, f.name, "열기 실패", Double.NaN, "", "${f.origin}, ${err.javaClass.simpleName}: ${err.message}")
                continue
            }
            val o = last!!
            val med = Stats.median(times)
            allMedians += med
            rows += Measurement(title, f.name, "첫 쪽 표시(N-01) 중앙값", med, "ms", if (med <= 5000) "합격, ${o.kind}, n=$REPEAT" else "불합격, ${o.kind}, n=$REPEAT")
            rows += Measurement(title, f.name, "첫 쪽 표시 최댓값", times.max(), "ms", "첫 회 ${"%.0f".format(times.first())}ms(클래스 로딩 포함)")
            rows += Measurement(title, f.name, "문단 수", o.paras.toDouble(), "개", "목록 항목 포함")
            rows += Measurement(title, f.name, "표 수", o.tables.toDouble(), "개", "중첩 표 포함")
            rows += Measurement(title, f.name, "그림 수", o.imgs.toDouble(), "개")
            rows += Measurement(title, f.name, "머리글·바닥글 수", o.hf.toDouble(), "개", "본문 흐름 안 표시(쪽별 합성은 미구현)")
            rows += Measurement(title, f.name, "각주 수", o.notes.toDouble(), "개")
            rows += Measurement(title, f.name, "조판 쪽 수", pages.toDouble(), "쪽", "폭 ${pageW.toInt()}pt")
        }
        work.deleteRecursively()
        return rows + Stats.summarize(title, "첫 쪽 표시(N-01)", "ms", allMedians)
    }
}
