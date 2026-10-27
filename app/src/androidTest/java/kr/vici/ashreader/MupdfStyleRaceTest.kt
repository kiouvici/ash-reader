// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kr.vici.ashreader.engine.mupdf.MupdfDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 작업 12: 같은 화면인데 전체 쪽 수가 달라지던 문제.
 * MuPDF 사용자 CSS 는 전역이고 스타일은 처음 쪽 수를 셀 때 적용된다. 문서 A 를 연 뒤 쪽 수를 세기 전에
 * 다른 CSS 로 문서 B 를 열어도(회전 중 동시 배치·다른 책) A 는 A 의 CSS 로 나뉘어야 한다.
 */
@RunWith(AndroidJUnit4::class)
class MupdfStyleRaceTest {
    private val work = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "style-race").apply { mkdirs() }

    private fun book(): File = File(work, "book.xhtml").apply {
        val p = (1..120).joinToString("") { "<p>${it}번째 문단입니다. 같은 화면이면 몇 번을 다시 열어도 전체 쪽 수가 같아야 합니다.</p>" }
        writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"ko\"><head><title>t</title></head><body>$p</body></html>")
    }

    private fun css(lineHeight: String) = "body { line-height: $lineHeight; margin: 0; } p { margin: 0; } @page { margin: 0 !important; }"

    private val spec = MupdfDoc.LayoutSpec(360f, 640f, 16f)

    private fun count(f: File, c: String): Int = MupdfDoc.open(f.absolutePath, spec, c).use { it.pageCount() }

    @Test
    fun otherDocCssDoesNotLeakIntoOpenedDoc() {
        val f = book()
        val tight = count(f, css("1.0"))
        val loose = count(f, css("3.0"))
        assertNotEquals("시험 전제: 줄 간격이 다르면 쪽 수가 달라야 함", tight, loose)
        repeat(3) {
            val a = MupdfDoc.open(f.absolutePath, spec, css("1.0"))
            val b = MupdfDoc.open(f.absolutePath, spec, css("3.0")) // A 의 쪽 수를 세기 전에 전역 CSS 가 바뀜
            try {
                assertEquals("A 는 A 의 CSS 로 나뉘어야 함", tight, a.pageCount())
                assertEquals("B 는 B 의 CSS 로 나뉘어야 함", loose, b.pageCount())
            } finally { a.close(); b.close() }
        }
    }
}
