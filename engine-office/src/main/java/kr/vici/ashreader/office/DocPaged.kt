// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import java.io.ByteArrayInputStream
import kr.vici.ashreader.office.WordHtml.Item
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.usermodel.CharacterRun
import org.apache.poi.hwpf.usermodel.Paragraph
import org.apache.poi.hwpf.usermodel.Range
import org.apache.poi.hwpf.usermodel.TableCell

/**
 * DOC(Word 97-2003) → Word 원본 쪽 묶음(F-30). POI HWPF 의 Range·Table·Section·CharacterRun·PicturesTable 만 쓴다(java.awt 경로 회피).
 *  - 구역별 쪽 크기·여백, 문단 정렬·들여쓰기·간격·쪽 나눔, 글자 글꼴·크기·굵게·기울임·밑줄·취소선·색·첨자·숨김
 *  - 표 칸 너비·병합(가로·세로)·테두리·세로 정렬, 그림(PNG/JPEG, EMF/WMF 는 그리기 목록 → 앱이 PNG 로), 머리글/바닥글(글자)
 *  - 필드는 결과만(\u0013 명령 \u0014 결과 \u0015), 쪽 번호 필드는 {{ASH_PAGE}}
 *  - 5묶음: 각주(쪽 아래, 글자만), 사용자 지정 탭 위치(왼쪽 맞춤으로), 다단, 과부·고아 줄 방지, 목차(제목 스타일)
 * 떠 있는 그림, 탭 맞춤 종류(가운데·오른쪽·소수점)와 채움 문자는 옮기지 않는다.
 */
object DocPaged {
    private val HEADING = Regex("^(heading|제목)\\s*(\\d)$")
    fun convert(bytes: ByteArray): PagedWord {
        val doc = HWPFDocument(ByteArrayInputStream(bytes))
        val ctx = Ctx(doc)
        return ctx.run()
    }

    private class Ctx(val doc: HWPFDocument) {
        val images = LinkedHashMap<String, ByteArray>()
        var imgSeq = 0; var paras = 0; var tables = 0; var tabParas = 0; var pageBreaks = 0
        val pics = runCatching { doc.picturesTable }.getOrNull()
        val metas = LinkedHashMap<String, PagedWord.MetaPic>()
        val headings = ArrayList<PagedWord.Heading>()
        /** 각주: 본문 CP(참조 글자 자리) → 각주 번호(0..). */
        val footnotes = runCatching { doc.footnotes }.getOrNull()
        val noteByCp: Map<Int, Int> = runCatching {
            val n = footnotes!!
            (0 until n.notesCount).associateBy { n.getNoteAnchorPosition(it) }
        }.getOrDefault(emptyMap())
        val noteOrder = ArrayList<Int>()

        fun run(): PagedWord {
            val range = doc.range
            val sections = ArrayList<PagedWord.Section>()
            val (hs, fs) = headersFooters()
            val nSec = runCatching { range.numSections() }.getOrDefault(1).coerceAtLeast(1)
            for (si in 0 until nSec) {
                val sec = runCatching { range.getSection(si) }.getOrNull()
                val w = sec?.let { runCatching { it.pageWidth / 20f }.getOrNull() }?.takeIf { it > 72f } ?: 595.3f
                val h = sec?.let { runCatching { it.pageHeight / 20f }.getOrNull() }?.takeIf { it > 72f } ?: 841.9f
                val ml = sec?.let { runCatching { it.marginLeft / 20f }.getOrNull() } ?: 90f
                val mr = sec?.let { runCatching { it.marginRight / 20f }.getOrNull() } ?: 90f
                val mt = sec?.let { runCatching { kotlin.math.abs(it.marginTop) / 20f }.getOrNull() } ?: 72f
                val mb = sec?.let { runCatching { kotlin.math.abs(it.marginBottom) / 20f }.getOrNull() } ?: 72f
                val cw = (w - ml - mr).coerceAtLeast(72f)
                // 다단(같은 폭으로)
                val nCols = sec?.let { runCatching { it.numColumns }.getOrNull() }?.coerceIn(1, 6) ?: 1
                val colSpace = sec?.let { runCatching { it.distanceBetweenColumns / 20f }.getOrNull() }?.takeIf { it >= 0f } ?: 36f
                val colW = if (nCols <= 1) cw else ((cw - (nCols - 1) * colSpace) / nCols).coerceAtLeast(36f)
                val sb = StringBuilder()
                if (sec != null) body(sec, colW, sb) else body(range, colW, sb)
                sections += PagedWord.Section(w, h, mt, mr, mb, ml, 36f, 36f, hs.containsKey("first"), hs.containsKey("even") || fs.containsKey("even"),
                    if (si == 0) 1 else -1, "decimal", sb.toString(), hs, fs, emptyList(), nCols, colSpace)
            }
            val notes = noteBodies(sections.firstOrNull()?.let { it.pageW - it.left - it.right } ?: 400f)
            return PagedWord(
                sections, notes, images,
                PagedWord.Stats(sections.size, paras, tables, imgSeq, 0, hs.size, fs.size, notes.size, tabParas, pageBreaks, metas.size, headings.size),
                metas, headings,
            )
        }

        /** 각주 본문(글자만): 참조 순서대로, 첫 글자(자동 번호 표지 0x02)는 번호로 바꾼다. */
        fun noteBodies(cw: Float): List<Pair<String, String>> {
            val n = footnotes ?: return emptyList()
            if (noteOrder.isEmpty()) return emptyList()
            val all = runCatching { doc.footnoteRange.text() }.getOrNull() ?: return emptyList()
            val out = ArrayList<Pair<String, String>>()
            for ((k, ni) in noteOrder.withIndex()) {
                val a = runCatching { n.getNoteTextStartOffset(ni) }.getOrDefault(-1)
                val b = runCatching { n.getNoteTextEndOffset(ni) }.getOrDefault(-1)
                if (a < 0 || b <= a || b > all.length) continue
                val raw = all.substring(a, b).replaceFirst("\u0002", "")
                val sb = StringBuilder()
                val lines = clean(raw).split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                for ((li, line) in lines.withIndex()) {
                    val items = ArrayList<Item>()
                    if (li == 0) items += Item.Raw("<span style=\"font-size:9pt\"><a class=\"nr\">${k + 1}</a></span>", 4f)
                    items += Item.Text(WordHtml.Run((if (li == 0) " " else "") + line, null, null, 9f))
                    WordHtml.para(WordHtml.Para(items, markSize = 9f), cw, sb)
                }
                out += "d$ni" to sb.toString()
            }
            return out
        }

        fun headersFooters(): Pair<Map<String, String>, Map<String, String>> {
            val hs = HashMap<String, String>(); val fs = HashMap<String, String>()
            runCatching {
                val st = org.apache.poi.hwpf.usermodel.HeaderStories(doc)
                fun put(m: HashMap<String, String>, k: String, t: String?) {
                    val s = clean(t ?: return).trim()
                    if (s.isEmpty()) return
                    val out = StringBuilder()
                    for (line in s.split('\r', '\n').filter { it.isNotBlank() }) {
                        WordHtml.para(WordHtml.Para(listOf(Item.Text(WordHtml.Run(line, null, null, 10f))), markSize = 10f), 400f, out)
                    }
                    m[k] = out.toString()
                }
                put(hs, "first", st.firstHeader); put(hs, "even", st.evenHeader); put(hs, "default", st.oddHeader)
                put(fs, "first", st.firstFooter); put(fs, "even", st.evenFooter); put(fs, "default", st.oddFooter)
            }
            return hs to fs
        }

        fun body(r: Range, cw: Float, out: StringBuilder) {
            var i = 0
            val n = r.numParagraphs()
            while (i < n) {
                val p = r.getParagraph(i)
                if (p.isInTable) {
                    val t = runCatching { r.getTable(p) }.getOrNull()
                    if (t != null) {
                        table(t, cw, out)
                        i += t.numParagraphs().coerceAtLeast(1)
                        continue
                    }
                }
                paragraph(p, cw, out)
                i++
            }
        }

        fun paragraph(p: Paragraph, cw: Float, out: StringBuilder) {
            paras++
            val items = ArrayList<Item>()
            var inInstr = 0
            var markSize = 10f; var markFont: String? = null
            for (k in 0 until p.numCharacterRuns()) {
                val cr = p.getCharacterRun(k)
                markSize = cr.fontSize / 2f; markFont = cr.fontName
                val pt = pics
                if (pt != null && runCatching { pt.hasPicture(cr) }.getOrDefault(false)) {
                    picture(cr)?.let { items += it }
                    continue
                }
                val raw = cr.text() ?: continue
                val sb = StringBuilder()
                fun flush() { if (sb.isNotEmpty()) { if (!cr.isVanished) items += Item.Text(run(cr, sb.toString())); sb.setLength(0) } }
                for ((ci, ch) in raw.withIndex()) {
                    val code = ch.code
                    if (code == 0x13) { flush(); inInstr++; continue }
                    if (code == 0x14) { if (inInstr > 0) inInstr--; flush(); continue }
                    if (code == 0x15) { flush(); continue }
                    if (inInstr > 0) continue
                    when (code) {
                        0x02 -> {
                            // 각주 참조(자동 번호): 이 CP 에 걸린 각주
                            flush()
                            val cp = runCatching { cr.startOffset }.getOrDefault(-1) + ci
                            val ni = noteByCp[cp] ?: noteByCp.entries.firstOrNull { kotlin.math.abs(it.key - cp) <= 1 }?.value
                            if (ni != null) {
                                if (ni !in noteOrder) noteOrder += ni
                                val num = noteOrder.indexOf(ni) + 1
                                val sz = (cr.fontSize / 2f).takeIf { it > 0f } ?: 10f
                                items += Item.Raw("<span style=\"font-size:${WordHtml.f(sz)}pt\"><a class=\"nr\" href=\"#ash-fn-d$ni\">$num</a></span>", sz * 0.4f)
                            }
                        }
                        0x0C -> { flush(); items += Item.PageBreak; pageBreaks++ }
                        0x0B -> { flush(); items += Item.LineBreak }
                        0x09 -> { flush(); items += Item.Tab }
                        0x1E -> sb.append('‑')
                        else -> if (code >= 0x20) sb.append(ch)
                    }
                }
                flush()
            }
            // 끝의 쪽 나눔(구역 끝 \f)은 쪽 나눔으로 두지 않는다
            while (items.lastOrNull() == Item.PageBreak) { items.removeAt(items.size - 1); pageBreaks-- }
            val jc = when (runCatching { p.justification.toInt() }.getOrDefault(0)) { 1 -> "center"; 2 -> "right"; 3, 4 -> "both"; else -> "left" }
            // 사용자 지정 탭 위치(맞춤 종류는 POI 가 내주지 않아 왼쪽 맞춤으로)
            val tabs = runCatching {
                val k = p.tabStopsNumber
                val pos = p.tabStopsPositions
                (0 until minOf(k, pos?.size ?: 0)).map { WordHtml.TabStop(pos!![it] / 20f) }
            }.getOrDefault(emptyList())
            // 목차: 제목 스타일(heading N / 제목 N)
            val level = runCatching { doc.styleSheet.getStyleDescription(p.styleIndex.toInt())?.name }.getOrNull()?.lowercase()
                ?.let { HEADING.find(it)?.groupValues?.get(2)?.toInt()?.minus(1) }
            val inTable = p.isInTable
            val headId = if (level != null && !inTable) {
                val title = items.filterIsInstance<Item.Text>().joinToString("") { it.run.text }.trim()
                if (title.isNotEmpty()) "h${headings.size}".also { headings += PagedWord.Heading(it, level, title.take(120)) } else null
            } else null
            val para = WordHtml.Para(
                items, jc,
                left = runCatching { p.indentFromLeft / 20f }.getOrDefault(0f),
                right = runCatching { p.indentFromRight / 20f }.getOrDefault(0f),
                firstLine = runCatching { p.firstLineIndent / 20f }.getOrDefault(0f),
                before = runCatching { p.spacingBefore / 20f }.getOrDefault(0f),
                after = runCatching { p.spacingAfter / 20f }.getOrDefault(0f),
                tabs = tabs,
                markSize = markSize, markFont = markFont,
                pageBreakBefore = runCatching { p.pageBreakBefore() }.getOrDefault(false) && !p.isInTable,
                widow = !inTable && runCatching { p.isWidowControlled }.getOrDefault(false),
                headId = headId,
            )
            tabParas += WordHtml.para(para, cw, out)
        }

        fun run(cr: CharacterRun, text: String): WordHtml.Run {
            val color = runCatching { cr.ico24 }.getOrDefault(-1).takeIf { it != -1 && it != 0 }?.let {
                // ico24 = 0x00BBGGRR
                "%02X%02X%02X".format(it and 0xFF, (it shr 8) and 0xFF, (it shr 16) and 0xFF)
            }
            val vert = when (runCatching { cr.subSuperScriptIndex.toInt() }.getOrDefault(0)) { 1 -> "superscript"; 2 -> "subscript"; else -> null }
            return WordHtml.Run(
                text, cr.fontName, cr.fontName, (cr.fontSize / 2f).takeIf { it > 0f } ?: 10f,
                cr.isBold, cr.isItalic, runCatching { cr.underlineCode != 0 }.getOrDefault(false), runCatching { cr.isStrikeThrough }.getOrDefault(false),
                color?.takeIf { it != "000000" }, null, vert,
            )
        }

        fun picture(cr: CharacterRun): Item? = runCatching {
            val pic = pics!!.extractPicture(cr, false) ?: return null
            val ext = pic.suggestFileExtension()?.lowercase().orEmpty()
            val sx = pic.horizontalScalingFactor.takeIf { it > 0 } ?: 1000
            val sy = pic.verticalScalingFactor.takeIf { it > 0 } ?: 1000
            val w = pic.dxaGoal / 20f * sx / 1000f
            val h = pic.dyaGoal / 20f * sy / 1000f
            if (ext == "emf" || ext == "wmf") {
                val data = pic.content
                if (data != null && (Metafile.isEmf(data) || Metafile.isWmf(data))) {
                    val name = "images/doc${imgSeq++}.$ext.png"
                    metas[name] = PagedWord.MetaPic(data, w, h)
                    return Item.Raw("<img src=\"$name\" alt=\"\" style=\"width:${WordHtml.f(w)}pt;height:${WordHtml.f(h)}pt\"/>", w, h)
                }
            }
            if (ext != "png" && ext != "jpg" && ext != "jpeg" && ext != "gif") {
                return Item.Raw("<span class=\"img-unsupported\" style=\"font-size:9pt\">[그림]</span>", 30f, 12f)
            }
            val name = "images/doc${imgSeq++}.$ext"
            images[name] = pic.content
            Item.Raw("<img src=\"$name\" alt=\"\" style=\"width:${WordHtml.f(w)}pt;height:${WordHtml.f(h)}pt\"/>", w, h)
        }.getOrNull()

        fun border(bc: org.apache.poi.hwpf.usermodel.BorderCode?): WordHtml.Border? {
            if (bc == null || bc.isEmpty) return null
            val w = (bc.lineWidth / 8f).coerceAtLeast(0.25f)
            val st = when (bc.borderType) { 3 -> "double"; 6 -> "dotted"; 7, 8, 22 -> "dashed"; 0, 255 -> return null; else -> "solid" }
            return WordHtml.Border(w, st, "000000")
        }

        fun table(t: org.apache.poi.hwpf.usermodel.Table, cw: Float, out: StringBuilder) {
            tables++
            // 열 경계: 모든 행 칸의 왼쪽 끝 + 너비
            val edges = sortedSetOf<Float>()
            for (ri in 0 until t.numRows()) {
                val row = t.getRow(ri)
                for (ci in 0 until row.numCells()) {
                    val c = row.getCell(ci)
                    val l = c.leftEdge / 20f
                    edges += l; edges += l + c.width / 20f
                }
            }
            val e = edges.toList().fold(ArrayList<Float>()) { acc, v -> if (acc.isEmpty() || v - acc.last() > 1f) acc += v; acc }
            if (e.size < 2) return
            val cols = e.zipWithNext { a, b -> b - a }
            fun colOf(x: Float) = e.indices.minByOrNull { kotlin.math.abs(e[it] - x) } ?: 0
            data class G(val cell: TableCell, val col: Int, val span: Int)
            val grid = (0 until t.numRows()).map { ri ->
                val row = t.getRow(ri)
                (0 until row.numCells()).map { ci ->
                    val c = row.getCell(ci)
                    val c0 = colOf(c.leftEdge / 20f)
                    val c1 = colOf((c.leftEdge + c.width) / 20f).coerceAtLeast(c0 + 1)
                    G(c, c0, c1 - c0)
                }
            }
            val rows = ArrayList<WordHtml.Row>()
            for ((ri, r) in grid.withIndex()) {
                val cells = ArrayList<WordHtml.Cell>()
                for (g in r) {
                    val c = g.cell
                    val covered = runCatching { c.isVerticallyMerged && !c.isFirstVerticallyMerged }.getOrDefault(false)
                    var rowspan = 1
                    if (runCatching { c.isFirstVerticallyMerged }.getOrDefault(false)) {
                        var k = ri + 1
                        while (k < grid.size && grid[k].any { it.col == g.col && runCatching { it.cell.isVerticallyMerged && !it.cell.isFirstVerticallyMerged }.getOrDefault(false) }) { rowspan++; k++ }
                    }
                    val inner = StringBuilder()
                    if (!covered) for (k in 0 until c.numParagraphs()) paragraph(c.getParagraph(k), maxOf(cols.subList(g.col, minOf(g.col + g.span, cols.size)).sum() - 10.8f, 4f), inner)
                    cells += WordHtml.Cell(
                        g.col, g.span, rowspan, inner.toString(),
                        border(runCatching { c.brcTop }.getOrNull()), border(runCatching { c.brcRight }.getOrNull()),
                        border(runCatching { c.brcBottom }.getOrNull()), border(runCatching { c.brcLeft }.getOrNull()),
                        vAlign = when (runCatching { c.vertAlign.toInt() }.getOrDefault(0)) { 1 -> "center"; 2 -> "bottom"; else -> null },
                        covered = covered,
                    )
                }
                val rh = runCatching { t.getRow(ri).rowHeight / 20f }.getOrDefault(0f)
                rows += WordHtml.Row(cells, kotlin.math.abs(rh), rh < 0)
            }
            WordHtml.table(WordHtml.Table(cols, rows, e.first() - 5.4f), out)
        }

        fun clean(s: String): String {
            val b = StringBuilder(); var instr = 0
            for (ch in s) when (ch.code) {
                0x13 -> instr++
                0x14 -> if (instr > 0) instr--
                0x15 -> {}
                0x0D, 0x0B -> if (instr == 0) b.append('\n')
                else -> if (instr == 0 && ch.code >= 0x20) b.append(ch)
            }
            return b.toString()
        }
    }
}
