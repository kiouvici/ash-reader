// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.SeriesGroups
import kr.vici.ashreader.doc.HwpDiag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 6B: 시리즈 묶기(F-62)·다음 권(F-61)·HWP 3.0 안내. */
class Batch6bTest {
    private fun book(id: String, title: String, dir: String, key: String = "", vol: Int = 0, manual: Int = Book.SERIES_AUTO) =
        Book(id = id, title = title, format = "CBZ", path = "$dir/$title.cbz", origin = Book.ORIGIN_FILE, seriesKey = key, volume = vol, seriesManual = manual)

    @Test fun autoGroupNeedsTwoInSameFolder() {
        val books = listOf(
            book("a1", "원피스 1", "/s/A", "원피스", 1),
            book("a2", "원피스 2", "/s/A", "원피스", 2),
            book("a10", "원피스 10", "/s/A", "원피스", 10),
            book("b1", "원피스 3", "/s/B", "원피스", 3), // 다른 폴더 한 권 → 묶지 않음
        )
        val g = SeriesGroups.groups(books)
        assertEquals(1, g.size)
        assertEquals(listOf("a1", "a2", "a10"), g.values.first().map { it.id })
        assertEquals("원피스", SeriesGroups.nameOf(g.keys.first()))
    }

    @Test fun nextVolumeNaturalOrder() {
        val books = listOf(book("a2", "원피스 2", "/s/A", "원피스", 2), book("a10", "원피스 10", "/s/A", "원피스", 10), book("a9", "원피스 9", "/s/A", "원피스", 9))
        assertEquals("a9", SeriesGroups.next(books[0], books)?.id)
        assertEquals("a10", SeriesGroups.next(books[2], books)?.id)
        assertNull(SeriesGroups.next(books[1], books))
    }

    @Test fun nextVolumeFallbackSeriesKey() {
        val books = listOf(book("a1", "책 1", "/s/A", "책", 1), book("b2", "책 2", "/s/B", "책", 2))
        assertEquals("b2", SeriesGroups.next(books[0], books)?.id)
    }

    @Test fun manualGroupAndExclude() {
        val books = listOf(
            book("x", "여행기", "/s/A", "내 묶음", 0, Book.SERIES_MANUAL),
            book("y", "일기", "/s/B", "내 묶음", 0, Book.SERIES_MANUAL),
            book("z1", "만화 1", "/s/C", "만화", 1, Book.SERIES_EXCLUDED),
            book("z2", "만화 2", "/s/C", "만화", 2),
        )
        val g = SeriesGroups.groups(books)
        assertEquals(setOf("내 묶음"), g.keys.map { SeriesGroups.nameOf(it) }.toSet())
        assertTrue(SeriesGroups.isManual(g.keys.first()))
        assertEquals(listOf("x", "y"), g.values.first().map { it.id }) // 여행기 < 일기 (가나다)
    }

    @Test fun legacyHwpHead() {
        assertTrue(HwpDiag.isLegacyHwpHead("HWP Document File V3.00 \u001A\u0001\u0002\u0003\u0004\u0005".toByteArray(Charsets.ISO_8859_1)))
        assertTrue(HwpDiag.isLegacyHwpHead("HWP Document File V2.10".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(HwpDiag.isLegacyHwpHead("HWP Document File".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(HwpDiag.isLegacyHwpHead(ByteArray(32)))
    }
}
