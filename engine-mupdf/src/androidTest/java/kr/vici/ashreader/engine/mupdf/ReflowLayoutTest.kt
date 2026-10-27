// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.mupdf

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 배치 7A MuPDF 패치 0002 검사(에뮬레이터).
 * - I-715 넓은 표: 표가 본문 폭을 넘지 않는다(모든 글자 상자가 쪽 안).
 * - I-713·7G 양쪽 정렬: 띄어쓰기 단위 줄 바꿈은 그대로, 낱말 사이 0.7em 까지 → 글자 사이 0.05em 까지 →
 *   남으면 띄어쓰기 3개 이상 줄은 띄어쓰기에, 2개 이하 줄은 왼쪽 정렬(글자 사이를 벌리지 않음).
 * - I-711 @page 여백: 장 가운데 쪽도 위·아래 여백 안에 글자가 놓인다.
 */
@RunWith(AndroidJUnit4::class)
class ReflowLayoutTest {
    companion object {
        private const val TAG = "ASH-LB"
        private lateinit var work: File
        private lateinit var font: String

        @BeforeClass @JvmStatic
        fun setUp() {
            val ins = InstrumentationRegistry.getInstrumentation()
            work = File(ins.targetContext.cacheDir, "reflow7a").apply { mkdirs() }
            for (name in listOf("NanumMyeongjo-Regular.ttf", "NanumMyeongjo-Bold.ttf")) {
                ins.context.assets.open(name).use { i -> File(work, name).outputStream().use { i.copyTo(it) } }
            }
            MupdfDoc.installFonts(work)
            font = File(work, "NanumMyeongjo-Regular.ttf").absolutePath
        }

        private fun css(extra: String = "") = """
            @font-face { font-family: ashbody; src: url("$font"); }
            body { font-family: ashbody; line-height: 1.7; text-align: justify; word-break: keep-all; overflow-wrap: break-word; margin: 0; }
            p { margin: 0; text-indent: 1em; }
            table { border-collapse: collapse; } td { border: 0.5pt solid #000; padding: 2pt 4pt; vertical-align: top; }
            @page { margin: 0 !important; }
        """.trimIndent() + "\n" + extra

        private fun write(name: String, body: String): File = File(work, name).apply {
            writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"ko\"><head><title>t</title></head><body>$body</body></html>")
        }
    }

    @Test
    fun wideTableFitsTextColumn() {
        // 한글 공문 아래 결재·연락처 표 모양: 칸 6개, 긴 전화번호·URL·메일
        val row = { a: String -> "<td>$a</td>" }
        val cells = listOf(
            // 값은 모두 지어낸 것(공개 저장소 — 실제 기관·사람·연락처를 쓰지 않는다). 글자 길이만 실제 공문과 비슷하게.
            listOf("담당", "홍길동", "사무국장", "김철수", "원장", "이영희"),
            listOf("시행", "가나 2026-123 (2026. 1. 2.)", "접수", "", "우편", "00000"),
            listOf("주소", "○○도 ○○시 가나다로 123(라마동 456-7)", "홈페이지", "http://blog.example.com/sample_c00000", "", ""),
            listOf("전화", "000)000-0000", "전송", "(000)000-0000", "E-mail", "sample_c00000@example.com"),
        )
        val html = "<p>위 표</p><table>" + cells.joinToString("") { r -> "<tr>" + r.joinToString("") { row(it) } + "</tr>" } + "</table><p>아래</p>"
        val f = write("wide.xhtml", html)
        for (w in listOf(300f, 360f, 420f)) {
            MupdfDoc.open(f.absolutePath, MupdfDoc.LayoutSpec(w, 700f, 16f), css()).use { doc ->
                var maxR = 0f
                var chars = 0
                for (p in 0 until doc.pageCount()) {
                    val t = doc.pageText(p)
                    for (i in t.text.indices) {
                        if (t.text[i] == '\n' || t.text[i].isWhitespace()) continue
                        maxR = maxOf(maxR, t.boxes[4 * i + 2]); chars++
                    }
                }
                Log.i(TAG, "wide table w=$w chars=$chars maxRight=$maxR")
                assertTrue("글자가 있어야 함", chars > 50)
                assertTrue("표가 오른쪽으로 넘침(w=$w, 오른쪽 끝 ${maxR})", maxR <= 1.002f)
            }
        }
    }

    @Test
    fun justifiedLinesHaveNoHugeWordGaps() {
        val para = "학생들의 물에 대한 적응력 향상과 위기 상황에서 자기생명 보호능력 강화를 위해 생존수영 실기교육을 실시하고자 합니다. " +
            "세부 일정을 아래와 같이 추진할 예정이오니 참고하시기 바랍니다. 가족들 다 흡족하게 먹었잖아요. 그렇죠 네 우리가 뭐 저기 뭐 엄청 풍족하게 먹은 건 아니지만. " +
            "오래된 책장에서 꺼낸 책은 손때가 묻어 모서리가 둥글었고, 넘길 때마다 종이 냄새가 났다. 그날 밤 우리는 늦게까지 이야기를 나누었고, 아무도 먼저 일어나자는 말을 하지 않았다."
        val f = write("gap.xhtml", "<p>$para</p><p>$para</p>")
        val em = 18f
        for (pw in listOf(300f, 360f, 420f, 520f)) {
            MupdfDoc.open(f.absolutePath, MupdfDoc.LayoutSpec(pw, 4000f, em), css()).use { doc ->
                val t = doc.pageText(0)
                val lines = JustifyStats.lines(t.text, t.boxes, pw, em)
                val body = lines.filter { !it.paraEnd }
                val justified = body.filter { it.justified }
                val ragged = body.filter { !it.justified }
                Log.i(TAG, "justify w=$pw lines=${body.size} justified=${justified.size} ragged=${ragged.size} " +
                    "maxLetter=${lines.maxOf { it.maxLetter }}em maxGap=${lines.maxOf { it.maxGap }}em")
                assertTrue(body.size > 5)
                for (l in lines) {
                    // 7G: 글자 사이는 거의 보이지 않게(0.05em 까지)만 — '냄 새 가 났 다' 처럼 벌어지지 않는다
                    assertTrue("글자 사이가 벌어짐(w=$pw ${l.maxLetter}em): ${l.text}", l.maxLetter <= 0.06f)
                }
                for (l in ragged) {
                    // 남는 폭을 띄어쓰기·글자 사이로 다 못 채우는 줄(띄어쓰기 2개 이하)은 왼쪽 정렬, 띄어쓰기는 자연 폭 그대로
                    assertTrue("고르지 않은 줄인데 띄어쓰기가 3개 이상(w=$pw): ${l.text}", l.spaces <= 2)
                    assertTrue("고르지 않은 줄의 띄어쓰기가 늘어남(w=$pw ${l.maxGap}em): ${l.text}", l.maxGap <= 0.45f)
                }
                for (l in justified) {
                    if (l.spaces <= 2) assertTrue("띄어쓰기 2개 이하 줄 낱말 사이 0.7em 넘음(w=$pw ${l.maxGap}em): ${l.text}", l.maxGap <= 0.75f)
                }
                // 대부분 줄은 양쪽 정렬(오른쪽 끝이 맞음)
                assertTrue("양쪽 정렬 줄이 너무 적음(w=$pw): ${justified.size}/${body.size}", justified.size * 2 >= body.size || pw < 360f)
            }
        }
    }

    @Test
    fun pageMarginAppliesToEveryPage() {
        val para = (1..60).joinToString(" ") { "문단 $it 의 글자입니다." }
        val f = write("margin.xhtml", "<p>$para</p><p>$para</p>")
        val h = 500f
        MupdfDoc.open(f.absolutePath, MupdfDoc.LayoutSpec(320f, h, 16f), css("@page { margin: 60px 0 80px 0 !important; }")).use { doc ->
            assertTrue(doc.pageCount() >= 3)
            for (p in 0 until doc.pageCount()) {
                val t = doc.pageText(p)
                var top = 1f; var bottom = 0f
                for (i in t.text.indices) {
                    if (t.text[i] == '\n' || t.text[i].isWhitespace()) continue
                    top = minOf(top, t.boxes[4 * i + 1]); bottom = maxOf(bottom, t.boxes[4 * i + 3])
                }
                assertTrue("쪽 ${p + 1}: 글자가 위 여백 안으로 들어감 ($top)", top * h >= 60f - 1f)
                assertTrue("쪽 ${p + 1}: 글자가 아래 여백(정보줄 자리)과 겹침 ($bottom)", bottom * h <= h - 80f + 1f)
            }
            assertEquals(h / 320f, doc.pageAspect(0), 0.01f)
        }
    }
}

/** 쪽 글자 상자에서 줄마다 낱말 사이·글자 사이 너비(em)를 잰다. 상자는 쪽 폭 비율(0..1). */
internal object JustifyStats {
    class Line(val text: String, val spaces: Int, val maxGap: Float, val maxLetter: Float, val justified: Boolean, val paraEnd: Boolean)

    private fun hangul(c: Char) = c in '\uAC00'..'\uD7A3'

    fun lines(text: String, boxes: FloatArray, pw: Float, em: Float): List<Line> {
        val out = ArrayList<Line>()
        // 오른쪽 끝(본문 폭) = 가장 긴 줄의 오른쪽
        var colRight = 0f
        for (i in text.indices) if (!text[i].isWhitespace()) colRight = maxOf(colRight, boxes[4 * i + 2])
        var start = 0
        while (start < text.length) {
            var end = text.indexOf('\n', start); if (end < 0) end = text.length
            var a = start; while (a < end && text[a] == ' ') a++
            var z = end - 1; while (z >= a && text[z] == ' ') z--
            if (z >= a) {
                var spaces = 0; var maxGap = 0f; var maxLetter = 0f
                var k = a + 1
                while (k <= z) {
                    if (text[k] == ' ') {
                        var b = k; while (b <= z && text[b] == ' ') b++
                        spaces++
                        maxGap = maxOf(maxGap, (boxes[4 * b] - boxes[4 * (k - 1) + 2]) * pw / em)
                        k = b + 1
                        continue
                    }
                    if (hangul(text[k - 1]) && hangul(text[k])) maxLetter = maxOf(maxLetter, (boxes[4 * k] - boxes[4 * (k - 1) + 2]) * pw / em)
                    k++
                }
                val right = boxes[4 * z + 2]
                val line = text.substring(a, z + 1)
                // 시험 문단은 모두 '않았다.' 로 끝난다(문단 마지막 줄은 왼쪽 정렬이 맞음)
                val paraEnd = line.endsWith("않았다.")
                out += Line(line, spaces, maxGap, maxLetter, (colRight - right) * pw <= 0.1f * em, paraEnd)
            }
            start = end + 1
        }
        return out
    }
}
