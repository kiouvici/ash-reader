// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 형광펜·메모 내보내기(F-69, S-54): .txt 또는 .md 글. 파일로 저장(SAF)하거나 공유(글 그대로)한다.
 * 쪽 번호는 저장할 때의 쪽(글꼴·크기를 바꾸면 달라질 수 있어 '쪽 무렵'으로 적지 않고 그대로 쓴다).
 */
object NotesExport {
    enum class Fmt(val ext: String, val mime: String, val label: String) {
        TXT("txt", "text/plain", "텍스트 .txt"), MD("md", "text/markdown", "마크다운 .md")
    }

    data class BookNotes(val title: String, val format: String, val highlights: List<Highlight>, val notes: List<Note>)

    val COLORS = listOf("노랑", "초록", "파랑", "분홍")

    fun count(books: List<BookNotes>) = books.sumOf { it.highlights.size + it.notes.size }

    fun render(books: List<BookNotes>, fmt: Fmt, now: Long = System.currentTimeMillis()): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date(now))
        val sb = StringBuilder()
        val md = fmt == Fmt.MD
        val list = books.filter { it.highlights.isNotEmpty() || it.notes.isNotEmpty() }
        if (list.size > 1) {
            if (md) sb.append("# ASH Reader 형광펜·메모\n\n") else sb.append("ASH Reader 형광펜·메모\n")
            sb.append(if (md) "- 내보낸 날: $date\n- 책 ${list.size}권\n\n" else "내보낸 날: $date · 책 ${list.size}권\n\n")
        }
        for (b in list) {
            val h1 = if (list.size > 1) "##" else "#"
            if (md) sb.append("$h1 ").append(mdLine(b.title)).append("\n\n")
            else sb.append(b.title).append("\n").append("=".repeat(minOf(40, b.title.length * 2 + 2))).append("\n")
            if (list.size == 1) sb.append(if (md) "- 형식: ${b.format}\n- 내보낸 날: $date\n\n" else "형식: ${b.format} · 내보낸 날: $date\n\n")
            // 쪽 순서로 형광펜과 쪽 메모를 섞어 적는다
            val items = b.highlights.map { it.page to it.startIdx to Either(h = it) } + b.notes.map { it.page to Int.MAX_VALUE to Either(n = it) }
            for ((_, e) in items.sortedWith(compareBy({ it.first.first }, { it.first.second }))) {
                val h = e.h; val n = e.n
                if (h != null) {
                    val tag = "${h.page + 1}쪽 · 형광펜 ${COLORS.getOrElse(h.color) { "노랑" }}"
                    if (md) {
                        sb.append(h.text.trim().lines().joinToString("\n") { "> " + mdLine(it) }).append("\n>\n> — ").append(tag).append("\n")
                        if (h.note.isNotBlank()) sb.append("\n메모: ").append(mdLine(h.note.trim())).append("\n")
                        sb.append("\n")
                    } else {
                        sb.append("[").append(tag).append("]\n").append("“").append(h.text.trim()).append("”\n")
                        if (h.note.isNotBlank()) sb.append("메모: ").append(h.note.trim()).append("\n")
                        sb.append("\n")
                    }
                } else if (n != null) {
                    val tag = "${n.page + 1}쪽 · 메모"
                    if (md) sb.append("- **").append(tag).append("**: ").append(mdLine(n.text.trim()).replace("\n", "  \n  ")).append("\n\n")
                    else sb.append("[").append(tag).append("]\n").append(n.text.trim()).append("\n\n")
                }
            }
        }
        if (list.isEmpty()) sb.append(if (md) "# 형광펜·메모\n\n아직 형광펜·메모가 없어요.\n" else "아직 형광펜·메모가 없어요.\n")
        return sb.toString()
    }

    private class Either(val h: Highlight? = null, val n: Note? = null)

    /** 마크다운에서 줄 첫머리 기호(#, >, -, 숫자.)가 서식으로 읽히지 않게 막는다. */
    private fun mdLine(s: String): String {
        val t = s.replace("\r", "")
        return if (Regex("^\\s*([#>*+-]|\\d+\\.)").containsMatchIn(t)) "\\" + t.trimStart() else t
    }

    /** 파일 이름: ASH형광펜_<제목 또는 '모든책'>_YYYY-MM-DD.<확장자> (파일 이름에 못 쓰는 글자는 _). */
    fun fileName(title: String?, fmt: Fmt, now: Long = System.currentTimeMillis()): String {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date(now))
        val t = (title ?: "모든책").replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40).trim('_').ifEmpty { "책" }
        return "ASH형광펜_${t}_$d.${fmt.ext}"
    }
}
