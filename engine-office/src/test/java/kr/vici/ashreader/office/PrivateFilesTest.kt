// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Test

/**
 * 실무 파일(test-files/private, 저장소에 없음) 대조. 기준 PDF의 텍스트는 같은 폴더의 *.ref.txt
 * (pdftotext 출력, 로컬에서 미리 만들어 둠)로 읽는다. 파일이 없으면 건너뛴다(CI).
 */
class PrivateFilesTest {
    @Test fun real04Docx() {
        val f = TestFiles.private("real-04.docx")
        val refTxt = File(f.parentFile, "real-04.ref.txt")
        Assume.assumeTrue(refTxt.exists())
        val r = DocxConverter().convert(f.readBytes())
        DocxConverterTest.assertOrder(r.stats.paragraphTexts, TestFiles.norm(refTxt.readText()))
        println("[real-04] 표 ${r.stats.tables}, 그림 ${r.stats.images}, 머리글 ${r.stats.headers}, 바닥글 ${r.stats.footers}")
    }

    @Test fun real03IsRtfDespiteDocExtension() {
        val f = TestFiles.private("real-03.doc")
        assertEquals(SniffedFormat.RTF, FormatSniffer.sniff(f.readBytes()))
    }
}
