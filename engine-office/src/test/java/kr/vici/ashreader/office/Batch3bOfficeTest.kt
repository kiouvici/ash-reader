// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import java.io.ByteArrayOutputStream
import org.apache.poi.poifs.crypt.EncryptionInfo
import org.apache.poi.poifs.crypt.EncryptionMode
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3B: Standard 암호 DOCX, 암호 DOC 판별, RTF 경로. */
class Batch3bOfficeTest {
    /** POI(Standard 암호화기)로 만든 파일을 자체 구현이 푼다. */
    @Test fun standardEncryptionRoundTrip() {
        val zip = TestFiles.public("word-structure.docx").readBytes()
        val info = EncryptionInfo(EncryptionMode.standard)
        val enc = info.encryptor
        enc.confirmPassword("ash1234")
        val bos = ByteArrayOutputStream()
        POIFSFileSystem().use { fs ->
            enc.getDataStream(fs).use { it.write(zip) }
            fs.writeFilesystem(bos)
        }
        val file = bos.toByteArray()
        assertEquals(SniffedFormat.ENCRYPTED_OOXML, FormatSniffer.sniff(file))
        assertTrue(AgileDecryptor.decrypt(file, "틀림") is AgileDecryptor.Result.WrongPassword)
        val r = AgileDecryptor.decrypt(file, "ash1234")
        assertTrue("결과: $r", r is AgileDecryptor.Result.Ok)
        assertArrayEquals(zip, (r as AgileDecryptor.Result.Ok).zip)
    }

    @Test fun plainDocIsNotEncrypted() {
        assertFalse(DocExtractor.isEncrypted(TestFiles.public("word97-structure.doc").readBytes()))
        assertFalse(DocExtractor.isEncrypted(TestFiles.public("rtf-named-as.doc").readBytes()))
    }

    @Test fun rtfPublicFile() {
        val r = RtfConverter.convert(TestFiles.public("rtf-named-as.doc").readBytes())
        assertEquals(listOf("RTF disguised as DOC. 한글"), r.paragraphTexts)
        assertTrue(r.xhtml.contains("<p id=\"p0\">"))
    }

    @Test fun rtfCodepageAndSkips() {
        val src = "{\\rtf1\\ansi\\ansicpg949{\\fonttbl{\\f0 \\'b9\\'d9\\'c5\\'c1;}}{\\*\\generator x;}\\f0 \\'c7\\'d1\\'b1\\'db \\{a\\}\\par" +
            "\\pard second\\tab x\\line y\\par{\\pict 0123abcd}\\uc2\\u54620\\'aa\\'bb!\\par}"
        val r = RtfConverter.convert(src.toByteArray(Charsets.ISO_8859_1))
        assertEquals(listOf("한글 {a}", "second\tx\ny", "한!"), r.paragraphTexts)
    }
}
