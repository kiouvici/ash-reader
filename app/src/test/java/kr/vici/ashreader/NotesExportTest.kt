// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import kr.vici.ashreader.data.Highlight
import kr.vici.ashreader.data.Note
import kr.vici.ashreader.data.NotesExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-69 형광펜·메모 내보내기(.txt·.md). */
class NotesExportTest {
    private val book = NotesExport.BookNotes(
        "바다 # 마을", "EPUB",
        listOf(
            Highlight(bookId = "a", page = 4, startIdx = 10, endIdx = 20, text = "# 바닷가 마을에는\n배가 들어왔다", color = 1, note = "좋은 문장"),
            Highlight(bookId = "a", page = 0, startIdx = 0, endIdx = 5, text = "첫 문장", color = 0),
        ),
        listOf(Note(bookId = "a", page = 2, text = "- 생각 메모")),
    )

    @Test fun markdownOrdersByPageAndEscapes() {
        val md = NotesExport.render(listOf(book), NotesExport.Fmt.MD, now = 864_000_000L)
        assertTrue(md.startsWith("# 바다 # 마을\n"))
        val i1 = md.indexOf("첫 문장"); val i2 = md.indexOf("생각 메모"); val i3 = md.indexOf("바닷가 마을에는")
        assertTrue(i1 in 0 until i2 && i2 < i3)
        assertTrue(md.contains("> \\# 바닷가 마을에는\n> 배가 들어왔다"))
        assertTrue(md.contains("5쪽 · 형광펜 초록"))
        assertTrue(md.contains("메모: 좋은 문장"))
        assertTrue(md.contains("**3쪽 · 메모**: \\- 생각 메모"))
    }

    @Test fun textFormat() {
        val t = NotesExport.render(listOf(book), NotesExport.Fmt.TXT, now = 864_000_000L)
        assertTrue(t.contains("[1쪽 · 형광펜 노랑]\n“첫 문장”"))
        assertTrue(t.contains("[3쪽 · 메모]\n- 생각 메모"))
        assertEquals(3, NotesExport.count(listOf(book)))
    }

    @Test fun fileName() {
        assertEquals("ASH형광펜_a_b_c_1970-01-11.md", NotesExport.fileName("a/b:c", NotesExport.Fmt.MD, now = 864_000_000L))
        assertEquals("ASH형광펜_모든책_1970-01-11.txt", NotesExport.fileName(null, NotesExport.Fmt.TXT, now = 864_000_000L))
    }
}
