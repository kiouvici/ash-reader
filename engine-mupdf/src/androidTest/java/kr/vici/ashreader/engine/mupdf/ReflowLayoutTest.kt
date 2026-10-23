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
 * - I-713 양쪽 정렬: 띄어쓰기 단위 줄 바꿈은 그대로, 낱말 사이가 0.5em 을 크게 넘지 않는다.
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
            listOf("담당", "안성훈", "사무국장", "강숙진", "원장", "박민옥"),
            listOf("시행", "제영 2026-133 (2025. 4. 30.)", "접수", "", "우편", "27141"),
            listOf("주소", "충북 제천시 탑안로 424(고암동 253-3)", "홈페이지", "http://blog.naver.com/hlog_c00430", "", ""),
            listOf("전화", "043)645-1614", "전송", "(043)646-7615", "E-mail", "hlog_c00430@naver.com"),
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
            "세부 일정을 아래와 같이 추진할 예정이오니 참고하시기 바랍니다. 가족들 다 흡족하게 먹었잖아요. 그렇죠 네 우리가 뭐 저기 뭐 엄청 풍족하게 먹은 건 아니지만."
        val f = write("gap.xhtml", "<p>$para</p><p>$para</p>")
        val em = 18f
        MupdfDoc.open(f.absolutePath, MupdfDoc.LayoutSpec(300f, 2000f, em), css()).use { doc ->
            val t = doc.pageText(0)
            val pw = 300f
            var worst = 0f
            var lines = 0
            var tight = 0
            var start = 0
            while (start < t.text.length) {
                var end = t.text.indexOf('\n', start); if (end < 0) end = t.text.length
                val ln = t.text.substring(start, end)
                // 줄 안 띄어쓰기 자리 너비(앞 글자 오른쪽 ~ 뒤 글자 왼쪽)
                if (end < t.text.length - 1 && ln.trim().contains(' ')) {
                    lines++
                    var lineWorst = 0f
                    for (k in start + 1 until end - 1) {
                        if (t.text[k] != ' ') continue
                        var a = k - 1; while (a > start && t.text[a] == ' ') a--
                        var b = k + 1; while (b < end && t.text[b] == ' ') b++
                        if (b >= end) continue
                        val gap = (t.boxes[4 * b] - t.boxes[4 * a + 2]) * pw / em
                        lineWorst = maxOf(lineWorst, gap)
                    }
                    worst = maxOf(worst, lineWorst)
                    if (lineWorst <= 0.55f) tight++
                }
                start = end + 1
            }
            Log.i(TAG, "justify lines=$lines tight=$tight worstGap=${worst}em")
            assertTrue(lines > 3)
            // 대부분 줄은 띄어쓰기 0.5em 상한 안. 낱말이 적은 줄도 글자 사이(0.2em까지)가 나눠 가져 패치 전(1.7em)보다 좁다
            assertTrue("낱말 사이가 너무 벌어짐: ${worst}em", worst < 1.2f)
            assertTrue("0.55em 이하 줄이 너무 적음: $tight/$lines", tight * 10 >= lines * 7)
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
