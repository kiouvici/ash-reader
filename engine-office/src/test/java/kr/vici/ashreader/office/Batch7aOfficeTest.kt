// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 배치 7A: Word eBook 보기 머리글(I-712). */
class Batch7aOfficeTest {
    @Test fun trimEmptyParasStripsEdgesAndCollapsesRuns() {
        val e = "<p id=\"p1\"><br/></p>\n"
        val e2 = "<p id=\"p2\" style=\"text-align:center;\"><br/></p>\n"
        val a = "<p id=\"p3\">가</p>\n"
        val b = "<p id=\"p4\"><img src=\"images/x.png\" alt=\"\"/></p>\n"
        assertEquals(a + e + b, DocxConverter.trimEmptyParas(e + e2 + a + e + e2 + e + b + e + e))
        assertEquals("", DocxConverter.trimEmptyParas(e + e2))
        assertEquals(a, DocxConverter.trimEmptyParas(a))
    }

    @Test fun simplifiedModeShowsOneHeader() {
        val bytes = TestFiles.public("word-structure.docx").readBytes()
        val s = DocxConverter(DocxConverter.Options(simplified = true)).convert(bytes).xhtml
        // 첫 쪽 머리글이 따로 있는 문서(titlePg) → 첫 쪽 머리글만
        assertTrue(s.contains("HEADER-FIRST"))
        assertFalse(s.contains("HEADER-DEFAULT"))
        assertEquals(1, Regex("<header ").findAll(s).count())
        assertEquals(1, Regex("<footer ").findAll(s).count())
        // 구조 보존(기본) 변환은 그대로 모두
        val full = DocxConverter().convert(bytes).xhtml
        assertTrue(full.contains("HEADER-FIRST") && full.contains("HEADER-DEFAULT"))
    }
}
