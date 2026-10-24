// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import kr.vici.ashreader.data.Book
import kr.vici.ashreader.ui.GroupSort
import kr.vici.ashreader.ui.LibGroups
import kr.vici.ashreader.ui.bookState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 작업 05 서재 묶음(형식·폴더·시리즈 카드). */
class LibGroupsTest {
    private fun b(id: String, folder: String = "", format: String = "PDF", title: String = id) =
        Book(id = id, title = title, format = format, path = "/x/$id", folder = folder, addedAt = 0)

    @Test
    fun foldersTopLevelIsLibraryFolder_kidsAreSubfolders() {
        val books = listOf(b("a", "Download"), b("b", "Download/2025"), b("c", "Download/2025/1월"), b("d", "Download/규정"), b("e", "Books"))
        val g = LibGroups.folders(books, listOf("Download", "Books"))
        assertEquals(listOf("Books", "Download"), g.map { it.name })
        val dl = g.first { it.name == "Download" }
        assertEquals(4, dl.books.size) // 하위 폴더까지 모든 책
        assertEquals(listOf("2025", "규정"), dl.kids.map { it.name })
        val y = dl.kids.first { it.name == "2025" }
        assertEquals(2, y.books.size)
        assertEquals(listOf("1월"), y.kids.map { it.name })
        assertNotNull(LibGroups.find(g, y.kids[0].id))
        assertEquals("폴더 2개  4권", LibGroups.cardSub(dl))
        assertEquals("폴더 2개, 책 4권", LibGroups.detailSub(dl))
    }

    @Test
    fun libraryFolderNameWithSlash_matchesLongest() {
        val books = listOf(b("a", "Download/소설"), b("b", "Download/소설/단편"), b("c", "Download/만화"))
        val g = LibGroups.folders(books, listOf("Download", "Download/소설"))
        val novel = g.first { it.name == "Download/소설" }
        assertEquals(2, novel.books.size)
        assertEquals(listOf("단편"), novel.kids.map { it.name })
        val dl = g.first { it.name == "Download" }
        assertEquals(listOf("만화"), dl.kids.map { it.name })
    }

    @Test
    fun copiesWithoutFolder_goToImported() {
        val g = LibGroups.folders(listOf(b("a", ""), b("b", "Download")), listOf("Download"))
        assertTrue(g.any { it.name == LibGroups.IMPORTED && it.books.size == 1 })
    }

    @Test
    fun formatsInGroupOrder() {
        val g = LibGroups.formats(listOf(b("a", format = "HWP"), b("b", format = "EPUB"), b("c", format = "PDF")))
        assertEquals(listOf("이북", "PDF·DjVu", "한글"), g.map { it.name })
    }

    @Test
    fun nameSortIsNatural() {
        val l = listOf(b("x10", title = "권 10"), b("x2", title = "권 2"), b("x1", title = "권 1"))
        assertEquals(listOf("권 1", "권 2", "권 10"), GroupSort.TITLE.apply(l).map { it.title })
    }

    @Test
    fun stateLabels() {
        val day = 86_400_000L
        val now = 100 * day + day / 2
        assertEquals("새 책", bookState(b("a").copy(addedAt = now - 5 * day), false, now).text)
        assertEquals("어제 받음", bookState(b("a").copy(addedAt = now - day), false, now).text)
        val reading = bookState(b("a").copy(lastOpenedAt = now, lastPage = 41, pageCount = 100, status = Book.READING), false, now)
        assertEquals("42%", reading.text)
        assertTrue(reading.reading)
        assertEquals("다 읽음", bookState(b("a").copy(status = Book.DONE, lastOpenedAt = now), false, now).text)
        assertEquals("열 수 없음", bookState(b("a"), true, now).text)
    }
}
