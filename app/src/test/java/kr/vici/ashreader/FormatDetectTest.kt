// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kr.vici.ashreader.data.ByteSource
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.DocHash
import kr.vici.ashreader.data.FormatDetector
import kr.vici.ashreader.data.ZipNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** F-05 시그니처 판별: 4A 에서 더한 형식(DjVu·CHM·MOBI/AZW3·그림 ZIP)과 경로 없는(SAF) 판별. */
class FormatDetectTest {
    private val testFiles = File(System.getProperty("ash.testFiles") ?: "../test-files")

    private fun zip(vararg entries: Pair<String, ByteArray>, storedFirst: Boolean = false): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            entries.forEachIndexed { i, (n, d) ->
                val e = ZipEntry(n)
                if (storedFirst && i == 0) { e.method = ZipEntry.STORED; e.size = d.size.toLong(); e.compressedSize = d.size.toLong(); e.crc = CRC32().apply { update(d) }.value }
                z.putNextEntry(e); z.write(d); z.closeEntry()
            }
        }
        return bo.toByteArray()
    }

    @Test fun djvuAndChmSamples() {
        val dj = File(testFiles, "public/djvu-sample.djvu")
        val chm = File(testFiles, "public/chm-sample-cp949.chm")
        assertEquals(DocFormat.DJVU, FormatDetector.detect(dj, "x.bin"))
        assertEquals(DocFormat.CHM, FormatDetector.detect(chm, "x.bin"))
        // 경로 없이(SAF) 읽어도 같은 판별·같은 식별 해시
        assertEquals(DocFormat.DJVU, FormatDetector.detect(ByteSource.of(dj.readBytes()), "x.bin"))
        assertEquals(DocHash.of(dj), DocHash.of(ByteSource.of(dj.readBytes())))
    }

    private fun mobi(version: Int): ByteArray {
        val b = ByteArray(400)
        "BOOKMOBI".toByteArray().copyInto(b, 60)
        val rec0 = 100
        b[78] = 0; b[79] = 0; b[80] = 0; b[81] = rec0.toByte()
        "MOBI".toByteArray().copyInto(b, rec0 + 16)
        b[rec0 + 39] = version.toByte()
        return b
    }

    @Test fun mobiVersusAzw3() {
        assertEquals(DocFormat.MOBI, FormatDetector.detect(ByteSource.of(mobi(6)), "a.mobi"))
        assertEquals(DocFormat.AZW3, FormatDetector.detect(ByteSource.of(mobi(8)), "a.mobi"))
        assertEquals(DocFormat.AZW3, FormatDetector.detect(ByteSource.of(mobi(6)), "a.azw3"))
    }

    @Test fun imageZipIsComicEvenWithoutPath() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val z = zip("01/001.png" to png, "01/002.jpg" to png, "ComicInfo.xml" to "<x/>".toByteArray())
        assertEquals(DocFormat.CBZ, FormatDetector.detect(ByteSource.of(z), "만화.zip"))
        val names = ZipNames.list(ByteSource.of(z))
        assertNotNull(names)
        assertEquals(listOf("01/001.png", "01/002.jpg", "ComicInfo.xml"), names!!.map { it.name })
    }

    @Test fun epubMimetypeWithoutPath() {
        val z = zip("mimetype" to "application/epub+zip".toByteArray(), "META-INF/container.xml" to "<c/>".toByteArray(), storedFirst = true)
        assertEquals(DocFormat.EPUB, FormatDetector.detect(ByteSource.of(z), "book.zip"))
    }

    @Test fun extensions() {
        assertEquals(DocFormat.DJVU, FormatDetector.byExtension("djv"))
        assertEquals(DocFormat.CHM, FormatDetector.byExtension("chm"))
        assertEquals(DocFormat.AZW3, FormatDetector.byExtension("azw3"))
    }
}
