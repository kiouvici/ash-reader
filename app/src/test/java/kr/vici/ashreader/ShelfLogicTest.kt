// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import kr.vici.ashreader.data.Book
import kr.vici.ashreader.ui.GroupSort
import kr.vici.ashreader.ui.LibGroup
import kr.vici.ashreader.ui.ShelfColls
import kr.vici.ashreader.ui.ShelfItem
import kr.vici.ashreader.ui.ShelfItems
import kr.vici.ashreader.ui.ShelfView
import kr.vici.ashreader.ui.ShelfViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 작업 07 서재 규칙: ≡ 모음 순서·숨김, 모음마다 보기·정렬 기억, 시리즈 쌓기, 직접 정렬, 기본 모음 책. */
class ShelfLogicTest {
    private fun book(id: String, title: String = id, added: Long = 0, opened: Long = 0, status: Int = Book.TO_READ, author: String = "") =
        Book(id = id, title = title, format = "EPUB", path = "/x/$id", addedAt = added, lastOpenedAt = opened, status = status, author = author)

    @Test
    fun collectionsArrangeKeepsSavedOrderAndAppendsNew() {
        // 처음: 기본 순서, 모두 보임
        assertEquals(ShelfColls.BUILT_IN, ShelfColls.arrange("", emptyList()).map { it.first })
        // 저장한 순서·숨김 + 새 사용자 모음은 뒤에, 없어진 모음은 빠짐
        val saved = "series,-hidden,c-gone,all"
        val a = ShelfColls.arrange(saved, listOf("c-new"))
        assertEquals(listOf("series", "hidden", "all"), a.take(3).map { it.first })
        assertEquals(false, a.first { it.first == "hidden" }.second)
        assertEquals("c-new", a.last().first)
        assertTrue(a.none { it.first == "c-gone" })
        // 다시 문자열로
        assertTrue(ShelfColls.format(a).startsWith("series,-hidden,all"))
    }

    @Test
    fun viewsRememberedPerCollection() {
        var s = ""
        s = ShelfViews.put(s, "all", ShelfView(GroupSort.TITLE, true))
        s = ShelfViews.put(s, "c-1", ShelfView(GroupSort.MANUAL, false))
        assertEquals(ShelfView(GroupSort.TITLE, true), ShelfViews.of(s, "all", false))
        assertEquals(ShelfView(GroupSort.MANUAL, false), ShelfViews.of(s, "c-1", true))
        // 기억 없는 모음은 기본(최근 읽은 순 + 설정의 처음 보기)
        assertEquals(ShelfView(GroupSort.RECENT, true), ShelfViews.of(s, "folder", true))
    }

    @Test
    fun manualSortThenRestByRecent() {
        val l = listOf(book("a", opened = 10), book("b", opened = 30), book("c", opened = 20), book("d", opened = 5))
        val r = GroupSort.MANUAL.apply(l, listOf("d", "a"))
        assertEquals(listOf("d", "a", "b", "c"), r.map { it.id })
    }

    @Test
    fun authorSortPutsBlankLast() {
        val l = listOf(book("x", author = ""), book("y", author = "한강"), book("z", author = "김영하"))
        assertEquals(listOf("z", "y", "x"), GroupSort.AUTHOR.apply(l).map { it.id })
    }

    @Test
    fun seriesStackedAtFirstPlace() {
        val a1 = book("a1"); val a2 = book("a2"); val b = book("b"); val c = book("c")
        val g = LibGroup("s-a", "코트 위의 여름", listOf(a1, a2))
        val items = ShelfItems.stacked(listOf(b, a2, c, a1), listOf(g))
        assertEquals(listOf("b", "stack-s-a", "c"), items.map { it.key })
        assertTrue(items[1] is ShelfItem.Stack)
    }

    @Test
    fun builtInCollections() {
        val now = 100L * 86_400_000L
        val l = listOf(
            book("r", status = Book.READING, opened = now - 1),
            book("d", status = Book.DONE),
            book("new", added = now - 2 * 86_400_000L),
            book("old", added = now - 40 * 86_400_000L),
        )
        assertEquals(listOf("r"), ShelfItems.builtIn(ShelfColls.READING, l, now).map { it.id })
        assertEquals(listOf("d"), ShelfItems.builtIn(ShelfColls.DONE, l, now).map { it.id })
        assertTrue(ShelfItems.builtIn(ShelfColls.RECENT_FILES, l, now).map { it.id }.contains("new"))
        assertTrue(ShelfItems.builtIn(ShelfColls.RECENT_FILES, l, now).none { it.id == "old" })
        assertEquals("어제", ShelfItems.ago(now - 30 * 3_600_000L, now))
    }
}
