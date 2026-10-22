// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import kr.vici.ashreader.doc.TextGeo
import kr.vici.ashreader.doc.TextPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextGeoTest {
    /** 한 줄에 글자마다 폭 0.1 상자. */
    private fun line(s: String): TextPage {
        val b = FloatArray(s.length * 4)
        s.forEachIndexed { i, c ->
            if (c != '\n') { b[4 * i] = i * 0.05f; b[4 * i + 1] = 0.1f; b[4 * i + 2] = i * 0.05f + 0.05f; b[4 * i + 3] = 0.15f }
        }
        return TextPage(s, b, 1.4f)
    }

    @Test fun wordAtKorean() {
        val t = "하늘과 바람과 별, 시"
        assertEquals("바람과", t.substring(TextGeo.wordAt(t, 5)))
        assertEquals("별", t.substring(TextGeo.wordAt(t, 8)))
    }

    @Test fun charAtNearest() {
        val p = line("가나다라")
        assertEquals(2, TextGeo.charAt(p, 0.12f, 0.12f))
        assertEquals(-1, TextGeo.charAt(p, 0.9f, 0.9f))
    }

    @Test fun rectsMergeOneLine() {
        val p = line("가나다라")
        assertEquals(1, TextGeo.rects(p, 0, 4).size)
    }

    @Test fun findIgnoresLineBreaks() {
        assertEquals(listOf(2), TextGeo.find("가나다\n라", "다 라"))
        assertEquals(listOf(0, 4), TextGeo.find("Abc abc", "ABC"))
    }

    @Test fun resolveMovedText() {
        assertEquals(3 until 5, TextGeo.resolve("가나다라마", 0, 2, "라마"))
        assertNull(TextGeo.resolve("가나다", 0, 1, "없음"))
    }

    @Test fun contentRectFits() {
        val r = TextGeo.contentRect(2f, 100f, 100f)
        assertEquals(50f, r.r - r.l, 0.01f)
        assertEquals(100f, r.b - r.t, 0.01f)
    }
}
