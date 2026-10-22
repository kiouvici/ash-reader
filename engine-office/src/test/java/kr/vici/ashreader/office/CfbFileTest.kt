// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import java.io.ByteArrayInputStream
import java.io.File
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 필수 4: 파일 전체를 읽지 않는 CFB 판별이 메모리 판별(Cfb)과 같은 답을 내는지. */
class CfbFileTest {
    private fun make(vararg streams: Pair<String, ByteArray>): File {
        val f = File.createTempFile("cfb", ".bin").apply { deleteOnExit() }
        POIFSFileSystem().use { fs ->
            for ((n, b) in streams) fs.createDocument(ByteArrayInputStream(b), n)
            f.outputStream().use { fs.writeFilesystem(it) }
        }
        return f
    }

    @Test fun hwpHeaderAndFlags() {
        val header = ByteArray(256).also { b ->
            "HWP Document File".toByteArray().copyInto(b)
            b[36] = 0x03 // 압축 + 암호
        }
        val big = ByteArray(20_000) { (it % 251).toByte() }
        val f = make("FileHeader" to header, "BodyText" to big)
        assertEquals(SniffedFormat.HWP5, CfbFile.sniff(f))
        assertEquals(3, CfbFile.hwpProperties(f))
        CfbFile.open(f)!!.use { c ->
            assertArrayEquals(header, c.read("FileHeader", 256))
            assertArrayEquals(big.copyOf(1000), c.read("BodyText", 1000))
            assertTrue("BodyText" in c.streamNames)
        }
        // 메모리 판별과 같은 답
        assertEquals(FormatSniffer.sniff(f.readBytes()), CfbFile.sniff(f))
    }

    @Test fun docAndEncrypted() {
        assertEquals(SniffedFormat.DOC, CfbFile.sniff(make("WordDocument" to ByteArray(5000))))
        assertEquals(SniffedFormat.ENCRYPTED_OOXML, CfbFile.sniff(make("EncryptionInfo" to ByteArray(100), "EncryptedPackage" to ByteArray(9000))))
        assertEquals(SniffedFormat.OLE_OTHER, CfbFile.sniff(make("Other" to ByteArray(10))))
    }

    @Test fun notCfb() {
        val f = File.createTempFile("notcfb", ".bin").apply { deleteOnExit(); writeBytes(ByteArray(1024)) }
        assertNull(CfbFile.open(f))
        assertNull(CfbFile.hwpProperties(f))
    }

    @Test fun publicDoc() {
        val f = TestFiles.public("word97-structure.doc")
        assertEquals(SniffedFormat.DOC, CfbFile.sniff(f))
    }
}
