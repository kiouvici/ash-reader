// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocxConverterTest {
    private val result by lazy { DocxConverter().convert(TestFiles.public("word-structure.docx").readBytes()) }

    @Test fun xhtmlIsWellFormed() {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false) }
        f.newDocumentBuilder().parse(result.xhtml.byteInputStream())
    }

    @Test fun headingsAndLists() {
        assertEquals(5, result.stats.headings)
        assertEquals(5, result.stats.listItems)
        assertTrue(result.xhtml.contains("<h1 id=\"p4\">ASH 시험 문서 제목</h1>") || result.xhtml.contains(">ASH 시험 문서 제목</h1>"))
        assertTrue(result.xhtml.contains("<span class=\"num\">3.</span> 번호 셋"))
        assertTrue(result.xhtml.contains("<b>굵게</b>"))
        assertTrue(result.xhtml.contains("<i>기울임</i>"))
    }

    @Test fun mergedCellTable() {
        // 4×4 격자: 가로 병합, 세로 병합(3행), 2×2 사각 병합 → 모든 행의 논리 칸 수 4
        assertEquals(listOf(4, 4, 4, 4), result.stats.tableGrids[0])
        assertTrue(result.xhtml.contains("<td colspan=\"2\"><p id=\"p13\">가로병합") || result.xhtml.contains("<td colspan=\"2\"><p"))
        assertTrue(result.xhtml.contains("rowspan=\"3\""))
        assertTrue(result.xhtml.contains("colspan=\"2\" rowspan=\"2\""))
        // 덮인 칸은 출력하지 않는다
        assertEquals(1, Regex("세로병합").findAll(result.xhtml).count())
    }

    @Test fun nestedTable() {
        assertEquals(3, result.stats.tables)
        assertTrue(result.xhtml.indexOf("바깥 칸") < result.xhtml.indexOf("안쪽 00"))
    }

    @Test fun imagesHeadersFootersFootnotes() {
        assertEquals(2, result.stats.images)
        assertEquals(2, result.images.size)
        assertTrue(result.images.values.all { it.size > 8 && it[1] == 'P'.code.toByte() })
        assertEquals(2, result.stats.headers) // first + default
        assertEquals(2, result.stats.footers)
        assertTrue(result.xhtml.contains("data-title-page=\"true\""))
        assertEquals(1, result.stats.footnotes)
        assertTrue(result.xhtml.contains("epub:type=\"noteref\""))
        assertTrue(result.xhtml.contains("FOOTNOTE-1"))
    }

    /** F-30 합격 기준 자동 대조: 기준 PDF(LibreOffice 출력, pdftotext)의 글자 순서와 변환 문단 순서 비교 */
    @Test fun paragraphOrderMatchesReference() {
        val ref = TestFiles.norm(TestFiles.public("word-structure.ref.txt").readText())
        assertOrder(result.stats.paragraphTexts, ref)
        for (hf in listOf("HEADER-FIRST", "HEADER-DEFAULT", "FOOTER-FIRST", "FOOTER-DEFAULT")) {
            assertTrue(ref.contains(hf)); assertTrue(result.xhtml.contains(hf))
        }
    }

    @Test fun simplifiedModeDropsRunStyles() {
        val s = DocxConverter(DocxConverter.Options(simplified = true)).convert(TestFiles.public("word-structure.docx").readBytes())
        assertTrue(!s.xhtml.contains("<span style="))
        assertEquals(result.stats.paragraphTexts, s.stats.paragraphTexts)
    }

    @Test fun corruptDocxThrowsInsteadOfCrashing() {
        val r = runCatching { DocxConverter().convert(TestFiles.public("word-corrupt.docx").readBytes()) }
        assertTrue(r.isFailure)
    }

    @Test fun conversionTimeJvm() {
        val b = TestFiles.public("word-structure.docx").readBytes()
        repeat(3) { DocxConverter().convert(b) }
        val t = System.nanoTime(); repeat(10) { DocxConverter().convert(b) }
        println("::notice title=실험②::[측정] word-structure.docx 변환 평균 %.1f ms (JVM, 참고값)".format((System.nanoTime() - t) / 10 / 1e6))
    }

    companion object {
        fun assertOrder(paras: List<String>, refNorm: String) {
            var pos = 0
            val missing = ArrayList<String>()
            var outOfOrder = 0
            for (p in paras) {
                val k = TestFiles.norm(p)
                if (k.isEmpty()) continue
                val i = refNorm.indexOf(k, pos).takeIf { it >= 0 } ?: refNorm.indexOf(k)
                when {
                    i < 0 -> missing += p
                    i < pos -> outOfOrder++
                    else -> pos = i + k.length
                }
            }
            println("::notice title=실험②::[대조] 문단 ${paras.size}개, 누락 ${missing.size}, 순서 어긋남 $outOfOrder")
            assertTrue("누락 문단: $missing", missing.isEmpty())
            assertEquals(0, outOfOrder)
        }
    }
}
