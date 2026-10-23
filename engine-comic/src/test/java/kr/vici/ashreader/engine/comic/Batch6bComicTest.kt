// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.comic

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 6B: RAR 이름 인코딩(N-09), 실제 바이트 상한, 짝 한 쪽 밀기(F-50). */
class Batch6bComicTest {
    @Test fun rarNameUtf8() {
        assertEquals("원피스/001.jpg", ComicFormat.decodeName("원피스/001.jpg".toByteArray(Charsets.UTF_8)))
    }

    @Test fun rarNameCp949Fallback() {
        // "만화" CP949 = B8 B8 C8 AD (UTF-8 로는 잘못된 바이트)
        val b = byteArrayOf(0xB8.toByte(), 0xB8.toByte(), 0xC8.toByte(), 0xAD.toByte(), '/'.code.toByte(), '1'.code.toByte())
        assertEquals("만화/1", ComicFormat.decodeName(b))
    }

    @Test fun rarNameEmpty() = assertEquals("", ComicFormat.decodeName(ByteArray(0)))

    @Test fun copyBoundedStopsAtCap() {
        val src = ByteArray(1000) { it.toByte() }
        assertEquals(1000L, ComicFormat.copyBounded(ByteArrayInputStream(src), ByteArrayOutputStream(), 1000))
        assertEquals(-1L, ComicFormat.copyBounded(ByteArrayInputStream(src), ByteArrayOutputStream(), 999))
        assertEquals(1000, ComicFormat.readBounded(ByteArrayInputStream(src), 4096).size)
        assertTrue(runCatching { ComicFormat.readBounded(ByteArrayInputStream(src), 10) }.isFailure)
    }

    private val tall = List(6) { 100 to 150 }

    @Test fun shiftAfterCover() {
        val plain = SpreadLayout.build(tall, spread = true, landscape = true, wide = WideMode.SPLIT, rtl = false, coverAlone = true)
        assertEquals((0 until 6).toList(), plain.map { it.page })
        val shifted = SpreadLayout.build(tall, spread = true, landscape = true, wide = WideMode.SPLIT, rtl = false, coverAlone = true, shift = true)
        // 표지(0) 단독, 그다음 빈 자리 + 1쪽, (2,3), (4,5)
        assertEquals(listOf(0, -1, 1, 2, 3, 4, 5), shifted.map { it.page })
        assertEquals(SlotPart.BLANK, shifted[1].part)
    }

    @Test fun shiftWithoutCoverAlone() {
        val shifted = SpreadLayout.build(tall, spread = true, landscape = true, wide = WideMode.SPLIT, rtl = false, coverAlone = false, shift = true)
        assertEquals(listOf(-1, 0, 1, 2, 3, 4, 5), shifted.map { it.page })
    }

    @Test fun shiftIgnoredInSinglePage() {
        val one = SpreadLayout.build(tall, spread = false, landscape = false, wide = WideMode.SPLIT, rtl = false, coverAlone = true, shift = true)
        assertEquals((0 until 6).toList(), one.map { it.page })
    }
}
