// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ComicFormatTest {
    private fun res(n: String) = javaClass.getResourceAsStream("/$n")!!.readBytes()

    @Test
    fun sniffAndSolidRar5() {
        val solid = res("rar5-solid.cbr"); val plain = res("rar5-plain.cbr")
        assertEquals(ComicFormat.Kind.RAR5, ComicFormat.sniff(solid))
        assertTrue(ComicFormat.isSolid(solid.copyOf(64)))
        assertFalse(ComicFormat.isSolid(plain.copyOf(64)))
    }

    @Test
    fun rar4HeaderFlags() {
        // RAR4: 시그니처 7바이트 + MAIN_HEAD(CRC16, type 0x73, flags LE)
        val sig = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
        val solid = sig + byteArrayOf(0, 0, 0x73, 0x08, 0x00, 13, 0)
        val plain = sig + byteArrayOf(0, 0, 0x73, 0x00, 0x00, 13, 0)
        assertEquals(ComicFormat.Kind.RAR4, ComicFormat.sniff(solid))
        assertTrue(ComicFormat.isSolid(solid)); assertFalse(ComicFormat.isSolid(plain))
    }

    @Test
    fun naturalOrderAndImageFilter() {
        val pages = ComicFormat.pages(listOf("p10.jpg", "p2.jpg", "p1.JPG", "ComicInfo.xml", "__MACOSX/._p1.jpg", ".hidden.png", "a/p3.webp").map { it to 1L })
        assertEquals(listOf("a/p3.webp", "p1.JPG", "p2.jpg", "p10.jpg"), pages.map { it.name })
    }

    @Test
    fun comicInfoDirection() {
        assertEquals(ReadingDirection.RTL, ComicFormat.directionFromComicInfo("<ComicInfo><Manga>YesAndRightToLeft</Manga></ComicInfo>"))
        assertEquals(ReadingDirection.LTR, ComicFormat.directionFromComicInfo("<ComicInfo><Manga>No</Manga></ComicInfo>"))
        assertNull(ComicFormat.directionFromComicInfo("<ComicInfo><Title>x</Title></ComicInfo>"))
    }

    @Test
    fun zipCp949Names() {
        val f = File.createTempFile("ash", ".cbz")
        ZipOutputStream(f.outputStream(), charset("x-windows-949")).use { z ->
            z.putNextEntry(ZipEntry("만화/01.jpg")); z.write(byteArrayOf(1)); z.closeEntry()
        }
        // UTF-8 플래그 없는 CP949 이름 → 엄격 UTF-8 실패 → CP949 로 다시 연다
        ComicFormat.openZip(f).use { z -> assertEquals("만화/01.jpg", z.entries().nextElement().name) }
        assertEquals(ComicFormat.Kind.ZIP, ComicFormat.sniff(f.readBytes()))
        f.delete()
    }
}
