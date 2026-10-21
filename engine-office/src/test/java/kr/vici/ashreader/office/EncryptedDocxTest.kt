// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import java.io.ByteArrayInputStream
import org.apache.poi.poifs.crypt.EncryptionInfo
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedDocxTest {
    private val enc by lazy { TestFiles.public("word-encrypted-agile.docx").readBytes() }

    @Test fun detectedAsEncryptedOoxml() {
        assertEquals(SniffedFormat.ENCRYPTED_OOXML, FormatSniffer.sniff(enc))
        assertTrue(AgileDecryptor.isEncryptedOoxml(enc))
    }

    @Test fun wrongPassword() {
        assertTrue(AgileDecryptor.decrypt(enc, "틀린암호") is AgileDecryptor.Result.WrongPassword)
    }

    @Test fun selfImplementationDecryptsToOriginal() {
        val t = System.nanoTime()
        val r = AgileDecryptor.decrypt(enc, "ash1234")
        println("[측정] Agile 해제(spinCount 100000) %.0f ms (JVM)".format((System.nanoTime() - t) / 1e6))
        assertTrue(r is AgileDecryptor.Result.Ok)
        val zip = (r as AgileDecryptor.Result.Ok).zip
        assertArrayEquals(TestFiles.public("word-structure.docx").readBytes(), zip)
        val conv = DocxConverter().convert(zip)
        assertEquals(listOf(4, 4, 4, 4), conv.stats.tableGrids[0])
    }

    /** 1순위였던 POI 경로: poi(+scratchpad)만으로 Agile 해제가 되는지 기록한다(Agile 구현은 poi-ooxml 쪽). */
    @Test fun poiWithoutOoxmlCannotDecryptAgile() {
        val hasAgile = runCatching { Class.forName("org.apache.poi.poifs.crypt.agile.AgileDecryptor") }.isSuccess
        val r = runCatching {
            POIFSFileSystem(ByteArrayInputStream(enc)).use { fs -> EncryptionInfo(fs).decryptor.verifyPassword("ash1234") }
        }
        println("[POI] agile 클래스 있음=$hasAgile, EncryptionInfo 결과=${r.exceptionOrNull()?.javaClass?.simpleName ?: r.getOrNull()}")
        // 결과는 기록만 한다(판정은 결과 문서). 자체 구현이 1순위를 대신한다.
    }
}
