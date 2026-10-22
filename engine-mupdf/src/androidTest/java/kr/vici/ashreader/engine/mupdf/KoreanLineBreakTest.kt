// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.mupdf

import android.os.Build
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
 * 실험 ④ (기술 설계서 9장): 패치한 MuPDF의 한국어 줄바꿈(F-37)·금칙(F-38)을 기기/에뮬레이터에서 자동 검사.
 * 합격 기준: 폭 5단계 모두 낱말 중간 줄바꿈 0건(한 줄보다 긴 낱말 제외), 줄머리·줄끝 금칙 위반 0건.
 * 같은 규칙의 데스크톱 검사기는 docs/실험결과/④-한국어줄바꿈.md 참조.
 */
@RunWith(AndroidJUnit4::class)
class KoreanLineBreakTest {

    companion object {
        private const val TAG = "ASH-LB"
        private val LINE_START_FORBIDDEN = ".,!?…」』”’)]}。、:;%℃".toSet()
        private val LINE_END_FORBIDDEN = "「『“‘([{".toSet()
        private val WIDTHS = listOf(200, 260, 320, 400, 520)
        private const val EM = 12f

        private lateinit var work: File
        private lateinit var paras: List<String>

        @BeforeClass @JvmStatic
        fun setUp() {
            val ins = InstrumentationRegistry.getInstrumentation()
            val testCtx = ins.context
            work = File(ins.targetContext.cacheDir, "lb").apply { mkdirs() }
            for (name in listOf("NanumMyeongjo-Regular.ttf", "NanumMyeongjo-Bold.ttf")) {
                testCtx.assets.open(name).use { i -> File(work, name).outputStream().use { i.copyTo(it) } }
            }
            MupdfDoc.installFonts(work)
            val text = testCtx.assets.open("linebreak-sentences.txt").bufferedReader().readText()
            paras = text.split(Regex("\n\\s*\n")).map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
        }

        private fun css(keepAll: Boolean): String {
            val r = File(work, "NanumMyeongjo-Regular.ttf").absolutePath
            val b = File(work, "NanumMyeongjo-Bold.ttf").absolutePath
            return """
                @font-face { font-family: ash-serif; src: url("$r"); }
                @font-face { font-family: ash-serif; font-weight: bold; src: url("$b"); }
                body { margin: 0; font-family: ash-serif; line-height: 1.7; }
                p { margin: 0 0 0.5em 0; text-align: justify; text-indent: 1em; }
            """.trimIndent() + if (keepAll) "\np { word-break: keep-all; overflow-wrap: break-word; }" else ""
        }

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    data class Result(val lines: Int, val midWord: Int, val midWordAllowed: Int, val startForbidden: Int, val endForbidden: Int, val examples: List<String>)

    private fun check(lines: List<String>, hint: Int): Result {
        // 원문을 '공백 아닌 글자 + 뒤에 공백이 있었는지'의 흐름으로 만든다
        val chars = ArrayList<Char>(); val spaceAfter = ArrayList<Boolean>()
        for (p in paras) {
            for (ch in p) {
                if (ch == ' ') { if (spaceAfter.isNotEmpty()) spaceAfter[spaceAfter.size - 1] = true; continue }
                chars += ch; spaceAfter += false
            }
            if (spaceAfter.isNotEmpty()) spaceAfter[spaceAfter.size - 1] = true
        }
        val tokenLen = IntArray(chars.size)
        var i = 0
        while (i < chars.size) {
            var j = i
            while (j < chars.size && !spaceAfter[j]) j++
            val len = j - i + 1
            for (k in i..minOf(j, chars.size - 1)) tokenLen[k] = len
            i = j + 1
        }
        var pos = 0; var mid = 0; var midOk = 0; var sf = 0; var ef = 0
        val ex = ArrayList<String>()
        for ((li, ln) in lines.withIndex()) {
            val cs = ln.filter { !it.isWhitespace() }
            if (cs.isEmpty()) continue
            if (cs.first() in LINE_START_FORBIDDEN) { sf++; ex += "줄머리 금칙: ${ln.take(12)}" }
            if (cs.last() in LINE_END_FORBIDDEN) { ef++; ex += "줄끝 금칙: ${ln.takeLast(12)}" }
            for (c in cs) {
                while (pos < chars.size && chars[pos] != c) pos++
                if (pos >= chars.size) break
                pos++
            }
            val last = pos - 1
            if (last in chars.indices && !spaceAfter[last] && li + 1 < lines.size) {
                if (tokenLen[last] > hint) midOk++ else { mid++; if (ex.size < 20) ex += "낱말 중간: …${ln.takeLast(10)} / ${lines[li + 1].take(10)}…" }
            }
        }
        return Result(lines.size, mid, midOk, sf, ef, ex)
    }

    private fun runMode(keepAll: Boolean): Map<Int, Result> {
        val xhtml = File(work, if (keepAll) "keepall.xhtml" else "baseline.xhtml")
        xhtml.writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"ko\"><head><title>t</title></head><body>" +
                paras.joinToString("") { "<p>${esc(it)}</p>" } + "</body></html>"
        )
        val out = LinkedHashMap<Int, Result>()
        for (w in WIDTHS) {
            MupdfDoc.open(xhtml.absolutePath, MupdfDoc.LayoutSpec(w.toFloat(), 20000f, EM), css(keepAll)).use { doc ->
                val lines = (0 until doc.pageCount()).flatMap { doc.pageLines(it) }
                out[w] = check(lines, (w / EM).toInt())
            }
        }
        return out
    }

    @Test
    fun keepAllHasNoMidWordBreaksOrKinsokuViolations() {
        Log.i(TAG, "device: API ${Build.VERSION.SDK_INT}, ABI ${Build.SUPPORTED_ABIS.joinToString()}")
        val base = runMode(keepAll = false)
        val keep = runMode(keepAll = true)
        for (w in WIDTHS) {
            val b = base.getValue(w); val k = keep.getValue(w)
            // 결과 줄(CI 로그에서 grep으로 표 생성): ASH-LB|폭|모드|줄|낱말중간|허용|줄머리|줄끝
            Log.i(TAG, "ASH-LB|$w|baseline|${b.lines}|${b.midWord}|${b.midWordAllowed}|${b.startForbidden}|${b.endForbidden}")
            Log.i(TAG, "ASH-LB|$w|keepall|${k.lines}|${k.midWord}|${k.midWordAllowed}|${k.startForbidden}|${k.endForbidden}")
            k.examples.forEach { Log.w(TAG, "  [$w] $it") }
        }
        // 패치가 실제로 적용됐는지: 기본 동작은 한국어를 음절 단위로 끊어야 한다
        assertTrue("기본 모드에서 낱말 중간 줄바꿈이 나와야 함(패치 미적용 감지용)", base.values.sumOf { it.midWord } > 0)
        assertEquals("keep-all 낱말 중간 줄바꿈", 0, keep.values.sumOf { it.midWord })
        assertEquals("keep-all 줄머리 금칙 위반", 0, keep.values.sumOf { it.startForbidden })
        assertEquals("keep-all 줄끝 금칙 위반", 0, keep.values.sumOf { it.endForbidden })
    }

    @Test
    fun rendersFirstPageBitmap() {
        val xhtml = File(work, "render.xhtml")
        xhtml.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"ko\"><body><p>${esc(paras.first())}</p></body></html>")
        MupdfDoc.open(xhtml.absolutePath, MupdfDoc.LayoutSpec(320f, 560f, EM), css(true)).use { doc ->
            val bmp = doc.renderPage(0, 640)
            var ink = 0
            for (y in 0 until bmp.height step 4) for (x in 0 until bmp.width step 4) if (bmp.getPixel(x, y) != -1) ink++
            Log.i(TAG, "render ${bmp.width}x${bmp.height} ink=$ink")
            assertTrue("렌더 결과에 글자가 있어야 함", ink > 100)
            File(work, "render-320.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
