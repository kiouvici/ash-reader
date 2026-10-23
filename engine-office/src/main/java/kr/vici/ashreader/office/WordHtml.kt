// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import java.util.Locale

/**
 * Word 원본 보기(F-30)의 문단·표 → XHTML 조각 공통 출력기. DOCX·RTF·DOC 변환기가 같은 규칙으로 쓴다.
 *  - 글꼴: 한글·한자 묶음은 동아시아 글꼴(명조/고딕 번들), 라틴 묶음은 라틴 글꼴(번들 또는 MuPDF 내장).
 *  - 줄 높이: Word '배수'는 글꼴 메트릭 배수 × 글자 크기, '고정/최소'는 그 값.
 *  - 탭: MuPDF 는 탭·inline-block 을 지원하지 않으므로, 탭이 든 문단은 '테두리 없는 한 줄 표'로 탭 위치마다 칸을 나눈다.
 */
object WordHtml {
    class Run(
        val text: String,
        val font: String?, val eaFont: String?, val size: Float,
        val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false, val strike: Boolean = false,
        val color: String? = null, val bg: String? = null, val vert: String? = null,
    )

    sealed class Item {
        class Text(val run: Run) : Item()
        /** 그림·표지 같은 그대로 넣을 HTML. [w] = 폭 추정(pt), [h] = 높이(pt, 줄 높이 계산용). */
        class Raw(val html: String, val w: Float = 0f, val h: Float = 0f) : Item()
        object Tab : Item()
        object LineBreak : Item()
        object PageBreak : Item()
        /** 단 나눔(다단 구역에서 다음 단으로, 한 단이면 쪽 나눔). */
        object ColumnBreak : Item()
    }

    class TabStop(val pos: Float, val align: String = "left", val leader: String? = null)

    class Para(
        val items: List<Item>,
        val jc: String? = null,
        val left: Float = 0f, val right: Float = 0f, val firstLine: Float = 0f,
        val before: Float = 0f, val after: Float = 0f,
        /** auto(배수, [lineVal] = 배수) | exact | atLeast (pt) */
        val lineRule: String = "auto", val lineVal: Float = 1f,
        val tabs: List<TabStop> = emptyList(), val defaultTab: Float = 36f,
        /** 빈 문단·문단 기호의 글자 크기/글꼴 */
        val markSize: Float = 10f, val markFont: String? = null,
        val bg: String? = null,
        val keepAll: Boolean = true,
        val pageBreakBefore: Boolean = false,
        /** 문서 격자(줄 단위) 간격 pt. 0 = 없음. */
        val gridPitch: Float = 0f,
        val id: String? = null,
        /** 문단 테두리 top/bottom/left/right → (테두리, 글자와 간격 pt) */
        val borders: Map<String, Pair<Border, Float>> = emptyMap(),
        /** 과부·고아 줄 방지(Word widowControl): 합성기가 문단 첫·마지막 줄을 혼자 두지 않는다. */
        val widow: Boolean = false,
        /** 목차 제목 표지 id(제목 문단만). */
        val headId: String? = null,
    )

    class Border(val width: Float, val style: String, val color: String)

    class Cell(
        val col: Int, val span: Int, var rowspan: Int = 1, val html: String,
        val top: Border? = null, val right: Border? = null, val bottom: Border? = null, val left: Border? = null,
        val bg: String? = null, val vAlign: String? = null,
        val padL: Float = 5.4f, val padR: Float = 5.4f, val padT: Float = 0f, val padB: Float = 0f,
        /** 세로 병합으로 덮인 칸(출력 안 함) */
        val covered: Boolean = false,
    )

    class Row(val cells: List<Cell>, val height: Float = 0f, val exact: Boolean = false)

    class Table(val cols: List<Float>, val rows: List<Row>, val indent: Float = 0f, val align: String? = null)

    /** 0.5em 공백을 나눔 공백(0.273em)으로 내려면 키울 배수. */
    private const val WIDE_SPACE = 1.83f

    fun f(v: Float): String = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')

    fun esc(s: String): String = DocxConverter.esc(s)

    // ---------------- 글자 ----------------
    fun runHtml(r: Run, out: StringBuilder) {
        if (r.text.isEmpty()) return
        val st = StringBuilder()
        st.append("font-size:").append(f(r.size)).append("pt;")
        if (r.bold) st.append("font-weight:bold;")
        if (r.italic) st.append("font-style:italic;")
        if (r.underline || r.strike) st.append("text-decoration:").append(if (r.underline) "underline" else "line-through").append(';')
        if (r.color != null) st.append("color:#").append(r.color).append(';')
        if (r.bg != null) st.append("background-color:#").append(r.bg).append(';')
        val (o, c) = when (r.vert) { "superscript" -> "<sup>" to "</sup>"; "subscript" -> "<sub>" to "</sub>"; else -> "" to "" }
        out.append(o).append("<span style=\"").append(st).append("\">")
        val wide = PagedWord.wideSpaceFont(r.eaFont ?: r.font)
        for ((k, seg) in PagedWord.splitScripts(r.text)) {
            when (k) {
                1 -> out.append("<span style=\"font-family:").append(PagedWord.cjkFamily(r.eaFont ?: r.font))
                    .append(";font-size:").append(f(r.size * PagedWord.CJK_SCALE)).append("pt\">")
                2 -> out.append("<span style=\"font-family:ashsym\">")
                else -> out.append("<span style=\"font-family:").append(PagedWord.latinFamily(r.font ?: r.eaFont)).append("\">")
            }
            if (k == 1 && wide) {
                // 한양(HY)·고정폭(체) 글꼴은 낱말 사이 공백이 0.5em(실측) — 공백만 큰 글자로 넓힌다(줄 높이는 문단 값 고정)
                val parts = seg.split(' ')
                for ((pi, part) in parts.withIndex()) {
                    if (pi > 0) out.append("<span style=\"font-size:").append(f(r.size * WIDE_SPACE)).append("pt\"> </span>")
                    out.append(esc(part))
                }
            } else out.append(esc(seg))
            out.append("</span>")
        }
        out.append("</span>").append(c)
    }

    private fun maxSize(items: List<Item>, p: Para): Pair<Float, String?> {
        var best = 0f; var font: String? = null; var anyCjk = false
        for (it in items) if (it is Item.Text && it.run.text.isNotBlank()) {
            val cjk = it.run.text.any { ch -> PagedWord.isCjk(ch.code) }
            if (it.run.size > best || (it.run.size == best && cjk && !anyCjk)) {
                best = it.run.size; font = if (cjk) it.run.eaFont ?: it.run.font else it.run.font ?: it.run.eaFont
            }
            if (cjk) anyCjk = true
        }
        if (best == 0f) { best = p.markSize; font = p.markFont }
        return best to font
    }

    fun lineHeight(p: Para, items: List<Item>): Float {
        val (sz, font) = maxSize(items, p)
        val natural = sz * PagedWord.lineFactor(font)
        var lh = when (p.lineRule) {
            "exact" -> p.lineVal
            "atLeast" -> maxOf(p.lineVal, natural)
            else -> natural * p.lineVal
        }
        if (p.gridPitch > 0f && p.lineRule == "auto") lh = maxOf(p.gridPitch, kotlin.math.ceil((natural / p.gridPitch).toDouble()).toFloat() * p.gridPitch) * p.lineVal
        val img = items.filterIsInstance<Item.Raw>().maxOfOrNull { it.h } ?: 0f
        return if (p.lineRule == "exact") lh else maxOf(lh, img)
    }

    // ---------------- 문단 ----------------
    /** [contentW] = 본문(또는 칸) 폭 pt. 탭 문단 수를 돌려준다. */
    fun para(p: Para, contentW: Float, out: StringBuilder): Int {
        // 쪽 나눔에서 문단을 나눈다
        val parts = ArrayList<List<Item>>()
        val breaks = ArrayList<Item>()
        var cur = ArrayList<Item>()
        for (it in p.items) if (it is Item.PageBreak || it is Item.ColumnBreak) { parts += cur; breaks += it; cur = ArrayList() } else cur += it
        parts += cur
        var tabParas = 0
        if (p.pageBreakBefore) out.append(PagedWord.PB)
        for ((pi, items) in parts.withIndex()) {
            if (pi > 0) out.append(if (breaks[pi - 1] is Item.ColumnBreak) PagedWord.CB else PagedWord.PB)
            val first = pi == 0; val last = pi == parts.size - 1
            if (!first && items.isEmpty() && last) {
                // 쪽 나눔이 문단 끝: Word 는 문단 기호를 다음 쪽 첫 줄로 둔다(빈 줄)
            }
            val lh = lineHeight(p, items)
            val st = StringBuilder()
            // Word 는 앞 문단 '뒤 간격'과 이 문단 '앞 간격' 중 큰 쪽만 띄운다(실측) = CSS 바깥 여백 겹침. MuPDF 도 겹친다.
            if (first && p.before > 0f) st.append("margin-top:").append(f(p.before)).append("pt;")
            if (last && p.after > 0f) st.append("margin-bottom:").append(f(p.after)).append("pt;")
            st.append("line-height:").append(f(lh)).append("pt;")
            if (p.bg != null) st.append("background-color:#").append(p.bg).append(';')
            for ((side, b) in p.borders) {
                if ((side == "top" && !first) || (side == "bottom" && !last)) continue
                st.append("border-").append(side).append(':').append(border(b.first)).append(";padding-").append(side).append(':').append(f(b.second)).append("pt;")
            }
            if (!p.keepAll) st.append("word-break:normal;")
            val hasTab = items.any { it is Item.Tab }
            if (hasTab) {
                tabParas++
                out.append("<div").append(p.id?.let { " id=\"$it\"" } ?: "").append(" style=\"").append(st).append("\">")
                if ((first && p.before > 0f) || (first && p.headId != null)) {
                    out.append("<div style=\"font-size:0.1pt;line-height:0.1pt\">")
                    if (p.before > 0f) out.append(PagedWord.sbMark(p.before))
                    if (p.headId != null) out.append(PagedWord.hMark(p.headId))
                    out.append("</div>")
                }
                tabTable(p, items, contentW, lh, out)
                out.append("</div>\n")
                continue
            }
            if (p.left != 0f) st.append("margin-left:").append(f(p.left)).append("pt;")
            if (p.right != 0f) st.append("margin-right:").append(f(p.right)).append("pt;")
            if (p.firstLine != 0f) st.append("text-indent:").append(f(p.firstLine)).append("pt;")
            when (p.jc) {
                "center" -> st.append("text-align:center;")
                "right", "end" -> st.append("text-align:right;")
                "both", "distribute", "justify" -> st.append("text-align:justify;")
                else -> st.append("text-align:left;")
            }
            out.append("<p").append(if (first) p.id?.let { " id=\"$it\"" } ?: "" else "").append(" style=\"").append(st).append("\">")
            if (first && p.before > 0f) out.append(PagedWord.sbMark(p.before))
            if (first && p.headId != null) out.append(PagedWord.hMark(p.headId))
            if (first && p.widow) out.append(PagedWord.pMark(true))
            inline(items, p, out)
            if (last && p.widow) out.append(PagedWord.P_END)
            out.append("</p>\n")
        }
        return tabParas
    }

    private fun inline(items: List<Item>, p: Para, out: StringBuilder) {
        var any = false
        for (it in items) when (it) {
            is Item.Text -> { if (it.run.text.isNotEmpty()) { runHtml(it.run, out); any = true } }
            is Item.Raw -> { out.append(it.html); if (it.w > 0f || it.h > 0f) any = true }
            Item.LineBreak -> { out.append("<br/>"); any = true }
            Item.Tab -> out.append(' ')
            Item.PageBreak, Item.ColumnBreak -> {}
        }
        if (!any) {
            // 빈 문단: 문단 기호 크기의 빈 줄
            out.append("<span style=\"font-size:").append(f(p.markSize)).append("pt;font-family:").append(PagedWord.cjkFamily(p.markFont)).append("\">&#x200B;</span>")
        }
    }

    private fun widthOf(items: List<Item>): Float {
        var w = 0f
        for (it in items) when (it) {
            is Item.Text -> w += PagedWord.estWidthEm(it.run.text) * it.run.size
            is Item.Raw -> w += it.w
            else -> {}
        }
        return w
    }

    /** 탭 문단 → 테두리 없는 한 줄 표. */
    private fun tabTable(p: Para, items: List<Item>, contentW: Float, lh: Float, out: StringBuilder) {
        val segs = ArrayList<List<Item>>()
        var cur = ArrayList<Item>()
        for (it in items) if (it == Item.Tab) { segs += cur; cur = ArrayList() } else cur += it
        segs += cur
        val startX = (p.left + p.firstLine).coerceAtLeast(0f)
        val rightEdge = (contentW - p.right).coerceAtLeast(startX + 20f)
        val stops = ArrayList(p.tabs.sortedBy { it.pos })
        if (p.firstLine < 0f && stops.none { kotlin.math.abs(it.pos - p.left) < 0.5f }) stops += TabStop(p.left)
        stops.sortBy { it.pos }
        class C(val start: Float, val items: List<Item>?, val align: String, val leader: String? = null)
        val cells = ArrayList<C>()
        var x = startX
        cells += C(startX, segs[0], "left")
        // 폭 추정에 여유(글꼴 차이로 칸 안에서 줄이 바뀌지 않게)
        fun slack(w: Float) = if (w <= 0f) 0f else w * 1.08f + 2f
        x += slack(widthOf(segs[0]))
        for (k in 1 until segs.size) {
            val w = slack(widthOf(segs[k]))
            val stop = stops.firstOrNull { it.pos > x + 0.5f } ?: run {
                val d = if (p.defaultTab > 1f) p.defaultTab else 36f
                TabStop((kotlin.math.floor(x / d) + 1) * d)
            }
            var start = when (stop.align) {
                "center" -> stop.pos - w / 2f
                "right", "end", "decimal" -> stop.pos - w
                else -> stop.pos
            }
            if (start < x) start = x
            if (start > rightEdge - 4f) start = x // 줄 밖 탭: 바로 뒤에 이어 씀
            if (stop.leader != null && stop.leader != "none" && start - x > 6f) cells += C(x, null, "right", stop.leader)
            cells += C(start, segs[k], if (k == segs.size - 1 && stop.align == "left") (p.jc ?: "left") else when (stop.align) { "center" -> "center"; "right", "end", "decimal" -> "right"; else -> "left" })
            x = start + w
        }
        // 칸 폭
        val widths = ArrayList<Float>()
        for (i in cells.indices) {
            val end = if (i + 1 < cells.size) cells[i + 1].start else maxOf(rightEdge, cells[i].start + 4f)
            widths += (end - cells[i].start).coerceAtLeast(0.5f)
        }
        val total = widths.sum()
        out.append("<table class=\"tabs\" style=\"width:").append(f(total)).append("pt;margin-left:").append(f(startX)).append("pt\"><tr>")
        for ((i, c) in cells.withIndex()) {
            out.append("<td style=\"width:").append(f(widths[i])).append("pt;text-align:").append(
                when (c.align) { "center" -> "center"; "right", "end" -> "right"; "both", "distribute" -> "justify"; else -> "left" },
            ).append(";line-height:").append(f(lh)).append("pt\">")
            if (c.items == null) {
                val ch = when (c.leader) { "hyphen" -> "-"; "underscore", "heavy" -> "_"; "middleDot" -> "·"; else -> "." }
                val size = p.markSize
                val n = (widths[i] / (size * 0.3f)).toInt() - 1
                if (n > 0) out.append("<span style=\"font-size:").append(f(size)).append("pt;font-family:serif\">").append(ch.repeat(n)).append("</span>")
            } else inline(c.items, p, out)
            out.append("</td>")
        }
        out.append("</tr></table>")
    }

    // ---------------- 표 ----------------
    fun border(b: Border?): String = if (b == null || b.width <= 0f) "none" else "${f(b.width)}pt ${b.style} #${b.color}"

    fun table(t: Table, out: StringBuilder) {
        val total = t.cols.sum()
        out.append("<table style=\"width:").append(f(total)).append("pt;")
        when (t.align) {
            "center" -> out.append("margin-left:auto;margin-right:auto;")
            "right", "end" -> out.append("margin-left:auto;margin-right:0;")
            else -> if (t.indent != 0f) out.append("margin-left:").append(f(t.indent)).append("pt;")
        }
        out.append("\">")
        for (r in t.rows) {
            out.append("<tr>")
            for (c in r.cells) {
                if (c.covered) continue
                val w = (c.col until minOf(c.col + c.span, t.cols.size)).sumOf { t.cols[it].toDouble() }.toFloat()
                out.append("<td")
                if (c.span > 1) out.append(" colspan=\"").append(c.span).append("\"")
                if (c.rowspan > 1) out.append(" rowspan=\"").append(c.rowspan).append("\"")
                out.append(" style=\"width:").append(f(maxOf(w - c.padL - c.padR, 1f))).append("pt;")
                out.append("border-top:").append(border(c.top)).append(";border-right:").append(border(c.right))
                    .append(";border-bottom:").append(border(c.bottom)).append(";border-left:").append(border(c.left)).append(';')
                out.append("padding:").append(f(c.padT)).append("pt ").append(f(c.padR)).append("pt ").append(f(c.padB)).append("pt ").append(f(c.padL)).append("pt;")
                if (c.bg != null) out.append("background-color:#").append(c.bg).append(';')
                when (c.vAlign) { "center" -> out.append("vertical-align:middle;"); "bottom" -> out.append("vertical-align:bottom;") }
                if (r.height > 0f && c.rowspan == 1) out.append("height:").append(f(maxOf(r.height - c.padT - c.padB, 1f))).append("pt;")
                out.append("\">").append(c.html).append("</td>")
            }
            out.append("</tr>\n")
        }
        out.append("</table>\n")
    }
}
