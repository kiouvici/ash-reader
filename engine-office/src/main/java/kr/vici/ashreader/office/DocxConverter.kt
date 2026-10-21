// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * DOCX → XHTML 변환기 (기술설계서 3.4, 실험 ②).
 * 문단·제목·번호/글머리·표(gridSpan/vMerge/중첩)·그림(DrawingML/VML)·머리글/바닥글(default/first/even)·각주·런 서식 일부.
 * 결과 XHTML은 합성 EPUB 장(章)으로 MuPDF에 넣는다. 그림은 images 맵(경로 → 바이트)으로 따로 낸다.
 *
 * XML 해석은 javax.xml DOM(안드로이드·JVM 공통 내장)으로 한다. 설계서의 XmlPullParser는 JVM 단위 테스트에
 * 별도 의존성이 필요해 이번 실험에서는 DOM을 썼다(문서 크기가 작아 메모리 영향 미미, 결과 문서 참조).
 */
class DocxConverter(private val options: Options = Options()) {

    data class Options(
        /** 이북식 보기용: 글꼴 크기·색 같은 런 서식을 빼고 구조만 남긴다. */
        val simplified: Boolean = false,
    )

    class Result(
        val xhtml: String,
        val images: Map<String, ByteArray>,
        val stats: Stats,
    )

    data class Stats(
        val paragraphs: Int,
        val headings: Int,
        val listItems: Int,
        val tables: Int,
        /** 표마다 각 행의 '논리 칸 수'(colspan 합, rowspan 으로 덮인 칸 포함) */
        val tableGrids: List<List<Int>>,
        val images: Int,
        val unsupportedImages: Int,
        val headers: Int,
        val footers: Int,
        val footnotes: Int,
        /** 본문 문단(표 밖·안 포함) 텍스트 순서 */
        val paragraphTexts: List<String>,
    )

    fun convert(docx: ByteArray): Result = Ctx(readZip(docx), options).run()

    private class Ctx(val zip: Map<String, ByteArray>, val opt: Options) {
        val sb = StringBuilder()
        val images = LinkedHashMap<String, ByteArray>()
        var paras = 0; var headings = 0; var listItems = 0; var tables = 0; var imgs = 0; var badImgs = 0
        val grids = ArrayList<List<Int>>()
        val texts = ArrayList<String>()
        val styles = HashMap<String, StyleInfo>()
        val nums = HashMap<String, Map<Int, LevelInfo>>() // numId → ilvl → level
        val counters = HashMap<String, IntArray>()
        val footnoteIds = ArrayList<String>()
        var pid = 0
        lateinit var docRels: Map<String, Rel>

        data class StyleInfo(val name: String, val basedOn: String?, val outline: Int?, val numId: String?, val ilvl: Int?, val bold: Boolean?, val italic: Boolean?, val center: String?)
        data class LevelInfo(val fmt: String, val text: String, val start: Int)
        data class Rel(val type: String, val target: String, val external: Boolean)

        fun run(): Result {
            val docXml = zip["word/document.xml"] ?: throw IllegalArgumentException("word/document.xml 없음")
            docRels = rels("word/_rels/document.xml.rels")
            loadStyles(); loadNumbering()
            val doc = parse(docXml)
            val body = first(doc.documentElement, "body") ?: throw IllegalArgumentException("본문 없음")

            // 머리글·바닥글: 첫 sectPr(본문 마지막 것이 문서 기본)
            val sectPr = children(body).lastOrNull { it.localName == "sectPr" }
            val titlePg = sectPr?.let { first(it, "titlePg") }?.let { onOff(it) } ?: false
            val hf = HashMap<String, String>() // "header-default" → xhtml
            var headerCount = 0; var footerCount = 0
            if (sectPr != null) for (ref in children(sectPr)) {
                val kind = when (ref.localName) { "headerReference" -> "header"; "footerReference" -> "footer"; else -> continue }
                val type = ref.getAttributeNS(W, "type").ifEmpty { "default" }
                val rel = docRels[ref.getAttributeNS(R, "id")] ?: continue
                val part = zip[resolve("word/", rel.target)] ?: continue
                val saved = sb.length
                val partRels = rels(relsPathFor(resolve("word/", rel.target)))
                blockChildren(parse(part).documentElement, partRels, collectText = false)
                val html = sb.substring(saved); sb.setLength(saved)
                if (html.isNotBlank()) {
                    hf["$kind-$type"] = html
                    if (kind == "header") headerCount++ else footerCount++
                }
            }

            blockChildren(body, docRels, collectText = true)
            val bodyHtml = sb.toString(); sb.setLength(0)

            // 각주
            val notes = StringBuilder()
            zip["word/footnotes.xml"]?.let { fx ->
                val fdoc = parse(fx)
                val fRels = rels("word/_rels/footnotes.xml.rels")
                for (fn in children(fdoc.documentElement).filter { it.localName == "footnote" }) {
                    val id = fn.getAttributeNS(W, "id")
                    if (id !in footnoteIds) continue
                    blockChildren(fn, fRels, collectText = false)
                    notes.append("<aside epub:type=\"footnote\" id=\"fn").append(id).append("\" class=\"footnote\">")
                        .append(sb).append("</aside>\n")
                    sb.setLength(0)
                }
            }

            val out = StringBuilder()
            out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n")
            out.append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" lang=\"ko\">\n<head>\n<meta charset=\"UTF-8\"/>\n<title>document</title>\n")
            out.append("<style>").append(CSS).append("</style>\n</head>\n<body")
            if (titlePg) out.append(" data-title-page=\"true\"")
            out.append(">\n")
            for (k in listOf("header-first", "header-default", "header-even")) hf[k]?.let {
                out.append("<header class=\"docx-header\" data-type=\"").append(k.substringAfter('-')).append("\">").append(it).append("</header>\n")
            }
            out.append("<section class=\"docx-body\">\n").append(bodyHtml).append("</section>\n")
            if (notes.isNotEmpty()) out.append("<section class=\"docx-footnotes\">\n").append(notes).append("</section>\n")
            for (k in listOf("footer-first", "footer-default", "footer-even")) hf[k]?.let {
                out.append("<footer class=\"docx-footer\" data-type=\"").append(k.substringAfter('-')).append("\">").append(it).append("</footer>\n")
            }
            out.append("</body>\n</html>\n")
            return Result(out.toString(), images, Stats(paras, headings, listItems, tables, grids, imgs, badImgs, headerCount, footerCount, footnoteIds.size, texts))
        }

        // ---------------- 블록 ----------------
        fun blockChildren(parent: Element, rels: Map<String, Rel>, collectText: Boolean) {
            var openList: String? = null
            fun closeList() { if (openList != null) { sb.append("</").append(openList).append(">\n"); openList = null } }
            for (el in children(parent)) {
                when (el.localName) {
                    "p" -> {
                        val li = listInfo(el)
                        if (li != null) {
                            val tag = if (li.first.fmt == "bullet") "ul" else "ol"
                            if (openList != tag) { closeList(); sb.append("<").append(tag).append(" class=\"docx-list\">\n"); openList = tag }
                            paragraph(el, rels, collectText, li)
                        } else { closeList(); paragraph(el, rels, collectText, null) }
                    }
                    "tbl" -> { closeList(); table(el, rels, collectText) }
                    "sdt" -> { closeList(); first(el, "sdtContent")?.let { blockChildren(it, rels, collectText) } }
                    "customXml", "ins", "smartTag" -> { closeList(); blockChildren(el, rels, collectText) }
                    "AlternateContent" -> { closeList(); altContent(el)?.let { blockChildren(it, rels, collectText) } }
                    else -> {}
                }
            }
            closeList()
        }

        fun paragraph(p: Element, rels: Map<String, Rel>, collectText: Boolean, li: Pair<LevelInfo, String>?) {
            val pPr = first(p, "pPr")
            val styleId = pPr?.let { first(it, "pStyle") }?.getAttributeNS(W, "val")
            val outline = pPr?.let { first(it, "outlineLvl") }?.getAttributeNS(W, "val")?.toIntOrNull() ?: styleOutline(styleId)
            val pageBreakBefore = pPr?.let { first(it, "pageBreakBefore") }?.let { onOff(it) } ?: false
            val jc = pPr?.let { first(it, "jc") }?.getAttributeNS(W, "val") ?: styleChain(styleId).firstNotNullOfOrNull { it.center }
            val content = StringBuilder()
            val text = StringBuilder()
            inline(p, rels, content, text, collectText)
            val id = "p${pid++}"
            val tag = when {
                li != null -> "li"
                outline != null && outline in 0..5 -> "h${outline + 1}"
                else -> "p"
            }
            if (tag.startsWith("h")) headings++
            if (tag == "li") listItems++ else paras++
            val style = StringBuilder()
            if (pageBreakBefore) style.append("page-break-before:always;")
            when (jc) { "center" -> style.append("text-align:center;"); "right", "end" -> style.append("text-align:right;"); "both", "distribute" -> style.append("text-align:justify;") }
            if (!opt.simplified) pPr?.let { first(it, "ind") }?.let { ind ->
                ind.getAttributeNS(W, "left").ifEmpty { ind.getAttributeNS(W, "start") }.toIntOrNull()?.takeIf { it > 0 && li == null }?.let { style.append("margin-left:").append(it / 20).append("pt;") }
                ind.getAttributeNS(W, "firstLine").toIntOrNull()?.takeIf { it > 0 }?.let { style.append("text-indent:").append(it / 20).append("pt;") }
            }
            sb.append("<").append(tag).append(" id=\"").append(id).append("\"")
            if (style.isNotEmpty()) sb.append(" style=\"").append(style).append("\"")
            if (li != null && li.first.fmt != "bullet") sb.append(" data-num=\"").append(esc(li.second)).append("\"")
            sb.append(">")
            if (li != null && li.first.fmt != "bullet") sb.append("<span class=\"num\">").append(esc(li.second)).append("</span> ")
            sb.append(if (content.isEmpty()) "<br/>" else content)
            sb.append("</").append(tag).append(">\n")
            if (collectText && text.isNotBlank()) texts += text.toString().trim()
        }

        fun inline(parent: Element, rels: Map<String, Rel>, out: StringBuilder, text: StringBuilder, collectText: Boolean) {
            for (el in children(parent)) when (el.localName) {
                "r" -> run(el, rels, out, text)
                "hyperlink" -> {
                    val rel = rels[el.getAttributeNS(R, "id")]
                    val anchor = el.getAttributeNS(W, "anchor")
                    val href = when { rel != null && rel.external -> rel.target; anchor.isNotEmpty() -> "#$anchor"; else -> null }
                    if (href != null) out.append("<a href=\"").append(esc(href)).append("\">")
                    inline(el, rels, out, text, collectText)
                    if (href != null) out.append("</a>")
                }
                "ins", "smartTag", "customXml", "fldSimple", "sdt" -> inline(if (el.localName == "sdt") first(el, "sdtContent") ?: el else el, rels, out, text, collectText)
                "sdtContent" -> inline(el, rels, out, text, collectText)
                "bookmarkStart" -> el.getAttributeNS(W, "name").takeIf { it.isNotEmpty() && !it.startsWith("_") }?.let { out.append("<a id=\"").append(esc(it)).append("\"></a>") }
                "AlternateContent" -> altContent(el)?.let { inline(it, rels, out, text, collectText) }
                else -> {}
            }
        }

        fun run(r: Element, rels: Map<String, Rel>, out: StringBuilder, text: StringBuilder) {
            val rPr = first(r, "rPr")
            val open = StringBuilder(); val close = StringBuilder()
            fun wrap(o: String, c: String) { open.append(o); close.insert(0, c) }
            if (rPr != null) {
                if (first(rPr, "b")?.let { onOff(it) } == true) wrap("<b>", "</b>")
                if (first(rPr, "i")?.let { onOff(it) } == true) wrap("<i>", "</i>")
                if (first(rPr, "u")?.getAttributeNS(W, "val")?.let { it != "none" } == true) wrap("<u>", "</u>")
                if (first(rPr, "strike")?.let { onOff(it) } == true) wrap("<s>", "</s>")
                when (first(rPr, "vertAlign")?.getAttributeNS(W, "val")) { "superscript" -> wrap("<sup>", "</sup>"); "subscript" -> wrap("<sub>", "</sub>") }
                if (!opt.simplified) {
                    val css = StringBuilder()
                    first(rPr, "color")?.getAttributeNS(W, "val")?.takeIf { it.matches(Regex("[0-9A-Fa-f]{6}")) && it != "000000" }?.let { css.append("color:#").append(it).append(";") }
                    first(rPr, "sz")?.getAttributeNS(W, "val")?.toIntOrNull()?.let { css.append("font-size:").append(it / 2.0).append("pt;") }
                    first(rPr, "highlight")?.getAttributeNS(W, "val")?.let { HIGHLIGHT[it] }?.let { css.append("background-color:").append(it).append(";") }
                    if (css.isNotEmpty()) wrap("<span style=\"$css\">", "</span>")
                }
            }
            val body = StringBuilder()
            for (c in children(r)) when (c.localName) {
                "t" -> { val t = c.textContent; body.append(esc(t)); text.append(t) }
                "tab" -> { body.append(" "); text.append(' ') }
                "br" -> if (c.getAttributeNS(W, "type") == "page") body.append("<span class=\"page-break\"></span>") else body.append("<br/>")
                "cr" -> body.append("<br/>")
                "noBreakHyphen" -> body.append("‑")
                "sym" -> c.getAttributeNS(W, "char").toIntOrNull(16)?.let { body.append(esc(String(Character.toChars(if (it >= 0xF000) it - 0xF000 else it)))) }
                "footnoteReference" -> {
                    val id = c.getAttributeNS(W, "id")
                    if (id !in footnoteIds) footnoteIds += id
                    val n = footnoteIds.indexOf(id) + 1
                    body.append("<a epub:type=\"noteref\" class=\"noteref\" href=\"#fn").append(id).append("\">").append(n).append("</a>")
                }
                "footnoteRef" -> body.append("<span class=\"fnref\">").append(footnoteIds.indexOf(c.parentNode.let { findAncestorFootnoteId(it) }) + 1).append("</span>")
                "drawing" -> drawing(c, rels, body)
                "pict", "object" -> vml(c, rels, body)
                "AlternateContent" -> altContent(c)?.let { ac -> for (x in children(ac)) when (x.localName) { "drawing" -> drawing(x, rels, body); "pict" -> vml(x, rels, body) } }
                else -> {}
            }
            if (body.isNotEmpty()) out.append(open).append(body).append(close)
        }

        fun findAncestorFootnoteId(n: Node?): String {
            var x = n
            while (x != null) { if (x is Element && x.localName == "footnote") return x.getAttributeNS(W, "id"); x = x.parentNode }
            return ""
        }

        fun drawing(d: Element, rels: Map<String, Rel>, out: StringBuilder) {
            val blip = descendants(d, "blip").firstOrNull() ?: return
            val ext = descendants(d, "extent").firstOrNull()
            val cx = ext?.getAttribute("cx")?.toLongOrNull(); val cy = ext?.getAttribute("cy")?.toLongOrNull()
            val floating = descendants(d, "anchor").isNotEmpty() || d.getElementsByTagNameNS(WP, "anchor").length > 0
            image(blip.getAttributeNS(R, "embed"), rels, out, cx?.let { it / 12700.0 }, cy?.let { it / 12700.0 }, floating)
        }

        fun vml(p: Element, rels: Map<String, Rel>, out: StringBuilder) {
            val img = descendants(p, "imagedata").firstOrNull() ?: return
            image(img.getAttributeNS(R, "id"), rels, out, null, null, false)
        }

        fun image(rid: String, rels: Map<String, Rel>, out: StringBuilder, wPt: Double?, hPt: Double?, floating: Boolean) {
            val rel = rels[rid] ?: return
            val path = resolve("word/", rel.target)
            val bytes = zip[path] ?: return
            val lower = path.lowercase()
            if (lower.endsWith(".emf") || lower.endsWith(".wmf")) {
                badImgs++
                out.append("<span class=\"img-unsupported\">이 그림은 보여 드릴 수 없어요.</span>")
                return
            }
            imgs++
            val name = "images/" + path.substringAfterLast('/')
            images[name] = bytes
            out.append("<img src=\"").append(esc(name)).append("\" alt=\"\"")
            if (floating) out.append(" class=\"float\"")
            if (wPt != null && hPt != null && !opt.simplified) out.append(" style=\"width:%.1fpt;height:%.1fpt\"".format(java.util.Locale.ROOT, wPt, hPt))
            else if (wPt != null) out.append(" style=\"max-width:100%%;width:%.1fpt\"".format(java.util.Locale.ROOT, wPt))
            out.append("/>")
        }

        // ---------------- 표 ----------------
        fun table(tbl: Element, rels: Map<String, Rel>, collectText: Boolean) {
            tables++
            val rows = children(tbl).filter { it.localName == "tr" }
            // 1단계: 각 행의 칸(gridSpan, vMerge) 수집
            data class Cell(val el: Element, val col: Int, val span: Int, val vm: String?) // vm: null | "restart" | "continue"
            val grid = rows.map { tr ->
                var col = children(tr).firstOrNull { it.localName == "trPr" }?.let { first(it, "gridBefore") }?.getAttributeNS(W, "val")?.toIntOrNull() ?: 0
                val cells = ArrayList<Cell>()
                for (tc in rowCells(tr)) {
                    val tcPr = first(tc, "tcPr")
                    val span = tcPr?.let { first(it, "gridSpan") }?.getAttributeNS(W, "val")?.toIntOrNull() ?: 1
                    val vmEl = tcPr?.let { first(it, "vMerge") }
                    val vm = vmEl?.let { if (it.getAttributeNS(W, "val") == "restart") "restart" else "continue" }
                    cells += Cell(tc, col, span, vm); col += span
                }
                cells
            }
            val tblBorders = children(tbl).firstOrNull { it.localName == "tblPr" }?.let { first(it, "tblBorders") }
            val bordered = tblBorders == null || children(tblBorders).any { it.getAttributeNS(W, "val").let { v -> v != "nil" && v != "none" } } ||
                (children(tbl).firstOrNull { it.localName == "tblPr" }?.let { first(it, "tblStyle") }?.getAttributeNS(W, "val")?.contains("Grid", true) == true)
            sb.append("<table class=\"docx-table").append(if (bordered) " bordered" else "").append("\">\n")
            val logical = ArrayList<Int>()
            for ((ri, cells) in grid.withIndex()) {
                sb.append("<tr>")
                var logicalCols = 0
                for (c in cells) {
                    logicalCols += c.span
                    if (c.vm == "continue") continue
                    var rowspan = 1
                    if (c.vm == "restart") {
                        var k = ri + 1
                        while (k < grid.size && grid[k].any { it.col == c.col && it.vm == "continue" }) { rowspan++; k++ }
                    }
                    sb.append("<td")
                    if (c.span > 1) sb.append(" colspan=\"").append(c.span).append("\"")
                    if (rowspan > 1) sb.append(" rowspan=\"").append(rowspan).append("\"")
                    val shade = first(c.el, "tcPr")?.let { first(it, "shd") }?.getAttributeNS(W, "fill")
                    if (!opt.simplified && shade != null && shade.matches(Regex("[0-9A-Fa-f]{6}")) && shade.uppercase() != "FFFFFF") sb.append(" style=\"background-color:#").append(shade).append("\"")
                    sb.append(">")
                    blockChildren(c.el, rels, collectText)
                    sb.append("</td>")
                }
                sb.append("</tr>\n")
                logical += logicalCols
            }
            sb.append("</table>\n")
            grids += logical
        }

        fun rowCells(tr: Element): List<Element> {
            val out = ArrayList<Element>()
            for (c in children(tr)) when (c.localName) {
                "tc" -> out += c
                "sdt" -> first(c, "sdtContent")?.let { out += children(it).filter { x -> x.localName == "tc" } }
                "customXml" -> out += children(c).filter { x -> x.localName == "tc" }
            }
            return out
        }

        // ---------------- 스타일·번호 ----------------
        fun loadStyles() {
            val x = zip["word/styles.xml"] ?: return
            for (s in children(parse(x).documentElement).filter { it.localName == "style" }) {
                val id = s.getAttributeNS(W, "styleId")
                val name = first(s, "name")?.getAttributeNS(W, "val") ?: ""
                val pPr = first(s, "pPr"); val rPr = first(s, "rPr")
                var outline = pPr?.let { first(it, "outlineLvl") }?.getAttributeNS(W, "val")?.toIntOrNull()
                if (outline == null) Regex("^heading (\\d)$", RegexOption.IGNORE_CASE).find(name)?.let { outline = it.groupValues[1].toInt() - 1 }
                if (outline == null && name.equals("Title", true)) outline = 0
                val numPr = pPr?.let { first(it, "numPr") }
                styles[id] = StyleInfo(
                    name, first(s, "basedOn")?.getAttributeNS(W, "val"), outline,
                    numPr?.let { first(it, "numId") }?.getAttributeNS(W, "val"),
                    numPr?.let { first(it, "ilvl") }?.getAttributeNS(W, "val")?.toIntOrNull(),
                    rPr?.let { first(it, "b") }?.let { onOff(it) }, rPr?.let { first(it, "i") }?.let { onOff(it) },
                    pPr?.let { first(it, "jc") }?.getAttributeNS(W, "val"),
                )
            }
        }

        fun styleChain(id: String?): List<StyleInfo> {
            val out = ArrayList<StyleInfo>(); var cur = id; var g = 0
            while (cur != null && g++ < 20) { val s = styles[cur] ?: break; out += s; cur = s.basedOn }
            return out
        }

        fun styleOutline(id: String?): Int? = styleChain(id).firstNotNullOfOrNull { it.outline }?.takeIf { it < 9 }

        fun loadNumbering() {
            val x = zip["word/numbering.xml"] ?: return
            val root = parse(x).documentElement
            val abs = HashMap<String, Map<Int, LevelInfo>>()
            for (a in children(root).filter { it.localName == "abstractNum" }) {
                val lv = HashMap<Int, LevelInfo>()
                for (l in children(a).filter { it.localName == "lvl" }) {
                    lv[l.getAttributeNS(W, "ilvl").toIntOrNull() ?: 0] = LevelInfo(
                        first(l, "numFmt")?.getAttributeNS(W, "val") ?: "decimal",
                        first(l, "lvlText")?.getAttributeNS(W, "val") ?: "%1.",
                        first(l, "start")?.getAttributeNS(W, "val")?.toIntOrNull() ?: 1,
                    )
                }
                abs[a.getAttributeNS(W, "abstractNumId")] = lv
            }
            for (n in children(root).filter { it.localName == "num" }) {
                val aid = first(n, "abstractNumId")?.getAttributeNS(W, "val") ?: continue
                abs[aid]?.let { nums[n.getAttributeNS(W, "numId")] = it }
            }
        }

        /** 번호 문단이면 (수준 정보, 표시 문자열) */
        fun listInfo(p: Element): Pair<LevelInfo, String>? {
            val pPr = first(p, "pPr")
            val numPr = pPr?.let { first(it, "numPr") }
            val styleId = pPr?.let { first(it, "pStyle") }?.getAttributeNS(W, "val")
            val chain = styleChain(styleId)
            val numId = numPr?.let { first(it, "numId") }?.getAttributeNS(W, "val") ?: chain.firstNotNullOfOrNull { it.numId } ?: return null
            if (numId == "0") return null
            val ilvl = numPr?.let { first(it, "ilvl") }?.getAttributeNS(W, "val")?.toIntOrNull() ?: chain.firstNotNullOfOrNull { it.ilvl } ?: 0
            val levels = nums[numId] ?: return null
            val lvl = levels[ilvl] ?: return null
            if (lvl.fmt == "none") return null
            val ctr = counters.getOrPut(numId) { IntArray(9) { (levels[it]?.start ?: 1) - 1 } }
            ctr[ilvl]++
            for (k in ilvl + 1 until 9) ctr[k] = (levels[k]?.start ?: 1) - 1
            if (lvl.fmt == "bullet") return lvl to "•"
            var s = lvl.text
            for (k in 0..ilvl) s = s.replace("%${k + 1}", fmtNum(ctr[k].coerceAtLeast(1), levels[k]?.fmt ?: "decimal"))
            return lvl to s
        }

        fun fmtNum(n: Int, fmt: String): String = when (fmt) {
            "lowerLetter" -> ('a' + (n - 1) % 26).toString()
            "upperLetter" -> ('A' + (n - 1) % 26).toString()
            "lowerRoman" -> roman(n).lowercase()
            "upperRoman" -> roman(n)
            "ganada", "koreanDigital2" -> GANADA.getOrElse(n - 1) { n.toString() }
            "chosung" -> CHOSUNG.getOrElse(n - 1) { n.toString() }
            "decimalEnclosedCircle", "decimalEnclosedCircleChinese" -> if (n in 1..20) String(Character.toChars(0x2460 + n - 1)) else n.toString()
            else -> n.toString()
        }

        fun roman(n: Int): String {
            val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var x = n; val b = StringBuilder()
            for (i in v.indices) while (x >= v[i]) { b.append(s[i]); x -= v[i] }
            return b.toString()
        }

        // ---------------- 공통 ----------------
        fun rels(path: String): Map<String, Rel> {
            val x = zip[path] ?: return emptyMap()
            val m = HashMap<String, Rel>()
            for (r in children(parse(x).documentElement)) {
                m[r.getAttribute("Id")] = Rel(r.getAttribute("Type"), r.getAttribute("Target"), r.getAttribute("TargetMode") == "External")
            }
            return m
        }

        fun relsPathFor(part: String) = part.substringBeforeLast('/') + "/_rels/" + part.substringAfterLast('/') + ".rels"

        fun resolve(base: String, target: String): String {
            if (target.startsWith("/")) return target.removePrefix("/")
            val parts = (base + target).split('/').fold(ArrayList<String>()) { acc, s ->
                when (s) { "", "." -> {}; ".." -> if (acc.isNotEmpty()) acc.removeAt(acc.size - 1); else -> acc += s }; acc
            }
            return parts.joinToString("/")
        }

        /** mc:AlternateContent: Choice는 확장 기능(wps 도형 등)일 수 있어 Fallback을 우선한다. 그림은 Choice에 있으므로 Fallback에 그림이 없으면 Choice. */
        fun altContent(ac: Element): Element? {
            val choice = children(ac).firstOrNull { it.localName == "Choice" }
            val fallback = children(ac).firstOrNull { it.localName == "Fallback" }
            return when {
                choice != null && descendants(choice, "blip").isNotEmpty() -> choice
                fallback != null -> fallback
                else -> choice
            }
        }
    }

    companion object {
        const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
        private val GANADA = listOf("가", "나", "다", "라", "마", "바", "사", "아", "자", "차", "카", "타", "파", "하")
        private val CHOSUNG = listOf("ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ")
        private val HIGHLIGHT = mapOf("yellow" to "#FFFF00", "green" to "#00FF00", "cyan" to "#00FFFF", "magenta" to "#FF00FF", "lightGray" to "#D3D3D3", "red" to "#FF0000", "blue" to "#0000FF")
        private const val CSS = "table.docx-table{border-collapse:collapse;margin:0.5em 0}" +
            "table.docx-table.bordered td{border:0.5pt solid #000}td{padding:2pt 4pt;vertical-align:top}" +
            "ol.docx-list,ul.docx-list{margin:0;padding-left:1.5em}ol.docx-list{list-style:none}" +
            "ol.docx-list li span.num{display:inline-block;min-width:1.5em;margin-left:-1.5em}" +
            "img.float{display:block;margin:0.3em auto}.img-unsupported{display:inline-block;background:#ddd;padding:1em;color:#555}" +
            "aside.footnote{font-size:0.85em}a.noteref{vertical-align:super;font-size:0.75em}" +
            "span.page-break{display:block;page-break-after:always}"

        fun esc(s: String): String {
            val b = StringBuilder(s.length)
            for (ch in s) when (ch) {
                '<' -> b.append("&lt;"); '>' -> b.append("&gt;"); '&' -> b.append("&amp;"); '"' -> b.append("&quot;")
                else -> if (ch.code < 0x20 && ch != '\n' && ch != '\t') {} else b.append(ch)
            }
            return b.toString()
        }

        fun readZip(b: ByteArray): Map<String, ByteArray> {
            val m = HashMap<String, ByteArray>()
            var total = 0L
            ZipInputStream(ByteArrayInputStream(b)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val bytes = z.readBytes()
                    total += bytes.size
                    require(total < 512L * 1024 * 1024) { "압축 해제 크기 초과(N-09)" }
                    m[e.name.removePrefix("/")] = bytes
                }
            }
            return m
        }

        internal fun parse(b: ByteArray): Document {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = true
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { f.isExpandEntityReferences = false }
            return f.newDocumentBuilder().parse(ByteArrayInputStream(b))
        }

        internal fun children(e: Element): List<Element> {
            val out = ArrayList<Element>()
            var n = e.firstChild
            while (n != null) { if (n is Element) out += n; n = n.nextSibling }
            return out
        }

        internal fun first(e: Element, local: String): Element? {
            var n = e.firstChild
            while (n != null) { if (n is Element && n.localName == local) return n; n = n.nextSibling }
            return null
        }

        internal fun descendants(e: Element, local: String): List<Element> {
            val nl = e.getElementsByTagNameNS("*", local)
            return (0 until nl.length).map { nl.item(it) as Element }
        }

        internal fun onOff(e: Element): Boolean = e.getAttributeNS(W, "val").let { it.isEmpty() || it == "1" || it == "true" || it == "on" }
    }
}
