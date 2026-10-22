// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import java.io.ByteArrayInputStream
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.usermodel.Paragraph
import org.apache.poi.hwpf.usermodel.Range

/**
 * DOC(Word 97-2003) 본문·표 텍스트 → XHTML (F-31). Apache POI HWPF(poi-scratchpad)의 Range/Table만 쓴다.
 * 그림·서식은 다루지 않는다(java.awt 경로 회피).
 */
object DocExtractor {
    class Result(val xhtml: String, val paragraphTexts: List<String>, val tableGrids: List<List<Int>>)

    /** Word 97-2003 암호 문서(FIB fEncrypted, MS-DOC 2.5.2). 이 형식의 암호 해제는 지원하지 않는다. */
    fun isEncrypted(bytes: ByteArray): Boolean = runCatching {
        val wd = Cfb.open(bytes).read("WordDocument")
        wd != null && wd.size > 0x0B && (wd[0x0B].toInt() and 0x01) != 0
    }.getOrDefault(false)

    fun convert(bytes: ByteArray): Result {
        val doc = HWPFDocument(ByteArrayInputStream(bytes))
        val range: Range = doc.range
        val sb = StringBuilder()
        val texts = ArrayList<String>()
        val grids = ArrayList<List<Int>>()
        var i = 0
        var pid = 0
        while (i < range.numParagraphs()) {
            val p = range.getParagraph(i)
            if (p.isInTable) {
                val table = range.getTable(p)
                sb.append("<table class=\"doc-table bordered\">\n")
                val g = ArrayList<Int>()
                for (r in 0 until table.numRows()) {
                    val row = table.getRow(r)
                    sb.append("<tr>")
                    for (c in 0 until row.numCells()) {
                        val cell = row.getCell(c)
                        sb.append("<td>")
                        for (k in 0 until cell.numParagraphs()) {
                            val t = clean(cell.getParagraph(k).text())
                            if (t.isNotEmpty()) { sb.append("<p>").append(DocxConverter.esc(t)).append("</p>"); texts += t }
                        }
                        sb.append("</td>")
                    }
                    sb.append("</tr>\n")
                    g += row.numCells()
                }
                sb.append("</table>\n")
                grids += g
                i += table.numParagraphs()
                continue
            }
            val t = clean(p.text())
            val tag = headingTag(doc, p)
            sb.append("<").append(tag).append(" id=\"p").append(pid++).append("\">")
                .append(if (t.isEmpty()) "<br/>" else DocxConverter.esc(t)).append("</").append(tag).append(">\n")
            if (t.isNotEmpty()) texts += t
            i++
        }
        val html = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"ko\">\n<head>\n<meta charset=\"UTF-8\"/>\n<title>document</title>\n" +
            "<style>table.doc-table{border-collapse:collapse}table.doc-table td{border:0.5pt solid #000;padding:2pt 4pt}</style>\n</head>\n<body>\n" +
            sb + "</body>\n</html>\n"
        return Result(html, texts, grids)
    }

    private fun headingTag(doc: HWPFDocument, p: Paragraph): String {
        val name = runCatching { doc.styleSheet.getStyleDescription(p.styleIndex.toInt())?.name }.getOrNull() ?: return "p"
        val m = Regex("^heading (\\d)$", RegexOption.IGNORE_CASE).find(name) ?: return "p"
        return "h" + m.groupValues[1].toInt().coerceIn(1, 6)
    }

    private fun clean(s: String): String =
        s.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u0007]"), "").replace('\r', ' ').trim()
}
