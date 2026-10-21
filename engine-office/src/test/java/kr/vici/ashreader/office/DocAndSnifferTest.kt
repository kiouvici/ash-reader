// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocAndSnifferTest {
    @Test fun sniffPublicFiles() {
        assertEquals(SniffedFormat.DOCX, FormatSniffer.sniff(TestFiles.public("word-structure.docx").readBytes()))
        assertEquals(SniffedFormat.DOC, FormatSniffer.sniff(TestFiles.public("word97-structure.doc").readBytes()))
        assertEquals(SniffedFormat.RTF, FormatSniffer.sniff(TestFiles.public("rtf-named-as.doc").readBytes()))
        assertEquals(SniffedFormat.PDF, FormatSniffer.sniff(TestFiles.public("word-structure.ref.pdf").readBytes()))
        assertEquals(SniffedFormat.UNKNOWN, FormatSniffer.sniff(ByteArray(10)))
    }

    /** F-31: DOC 본문·표 텍스트 누락 0 — 같은 원본에서 만든 DOCX의 문단 텍스트가 모두 DOC 추출 결과에 있어야 한다. */
    @Test fun docTextAndTables() {
        val t = System.nanoTime()
        val doc = DocExtractor.convert(TestFiles.public("word97-structure.doc").readBytes())
        println("::notice title=실험②::[측정] DOC 추출 %.0f ms (JVM, 첫 호출·클래스 로딩 포함)".format((System.nanoTime() - t) / 1e6))
        // 각주 번호(DOCX 쪽에만 글자로 들어감)는 비교에서 뺀다
        fun key(x: String) = TestFiles.norm(x).replace(Regex("\\d"), "")
        val all = key(doc.paragraphTexts.joinToString(""))
        val expected = DocxConverter().convert(TestFiles.public("word-structure.docx").readBytes()).stats.paragraphTexts
        val missing = expected.filter { !all.contains(key(it)) }
        println("::notice title=실험②::[대조] DOC 문단 ${doc.paragraphTexts.size}개, DOCX 기준 ${expected.size}개 중 누락 ${missing.size}: $missing; 표 ${doc.tableGrids}")
        assertTrue("누락: $missing", missing.isEmpty())
        assertTrue(doc.tableGrids.isNotEmpty())
        assertTrue(doc.xhtml.contains("<table"))
    }
}
