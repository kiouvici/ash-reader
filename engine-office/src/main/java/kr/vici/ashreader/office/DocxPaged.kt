// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import kr.vici.ashreader.office.DocxConverter.Companion.R
import kr.vici.ashreader.office.DocxConverter.Companion.W
import kr.vici.ashreader.office.DocxConverter.Companion.children
import kr.vici.ashreader.office.DocxConverter.Companion.descendants
import kr.vici.ashreader.office.DocxConverter.Companion.first
import kr.vici.ashreader.office.DocxConverter.Companion.onOff
import kr.vici.ashreader.office.DocxConverter.Companion.parse
import kr.vici.ashreader.office.WordHtml.Item
import org.w3c.dom.Element

/**
 * DOCX → Word 원본(구조 보존) 쪽 묶음(F-30). 이북식 변환기([DocxConverter])와 달리 Word 의 쪽 설정·서식을 최대한 옮긴다.
 *  - 구역(sectPr): 쪽 크기·방향·여백·머리글/바닥글 거리, 머리글/바닥글(first/even/default, 앞 구역 상속), titlePg, 쪽 번호 시작·형식, 문서 격자
 *  - 문단: 스타일 상속(docDefaults → 문단 스타일 → 번호 수준 → 직접), 정렬·들여쓰기·내어쓰기·간격(앞/뒤/줄·자동·문맥)·탭 위치·음영·쪽 나눔
 *  - 글자: 글꼴(테마 글꼴 포함)·크기·굵게·기울임·밑줄·취소선·색·형광·위/아래 첨자·숨김·대문자
 *  - 표: 격자 너비·들여쓰기·정렬·테두리(표 스타일/표/칸, 바깥·안쪽)·음영·가로/세로 병합·행 높이·칸 여백·세로 정렬·중첩 표
 *  - 그림: 글자처럼 배치(inline), 떠 있는 그림(글 뒤/앞 = 쪽 위 절대 위치, 그 밖 배치 = 문단 위 블록으로 근사), EMF/WMF 는 회색 상자
 *  - 각주(쪽 아래), 미주(문서 끝), 쪽 번호 필드(PAGE·NUMPAGES), 글상자 글자(문단 뒤 블록)
 *  - 5묶음: 다단(w:cols)·단 나눔, 과부·고아 줄 방지(widowControl), 표 스타일 조건부 서식(첫/끝 행·열, 줄무늬, 모서리 칸),
 *    EMF·WMF 그림(그리기 목록 → 앱이 PNG 로), 목차(개요 수준·제목 스타일)
 */
class DocxPaged {
    fun convert(docx: ByteArray): PagedWord = Ctx(DocxConverter.readZip(docx)).run()

    private class RP(
        var ascii: String? = null, var ea: String? = null, var sz: Float? = null,
        var b: Boolean? = null, var i: Boolean? = null, var u: Boolean? = null, var strike: Boolean? = null,
        var color: String? = null, var bg: String? = null, var vert: String? = null, var vanish: Boolean? = null, var caps: Boolean? = null,
    ) {
        fun copy() = RP(ascii, ea, sz, b, i, u, strike, color, bg, vert, vanish, caps)
    }

    private class Tab(val pos: Float, val type: String, val leader: String?)

    private class PP(
        var jc: String? = null, var left: Float? = null, var right: Float? = null, var firstLine: Float? = null,
        var before: Float? = null, var after: Float? = null, var beforeAuto: Boolean? = null, var afterAuto: Boolean? = null,
        var line: Float? = null, var lineRule: String? = null, var contextual: Boolean? = null, var pageBreakBefore: Boolean? = null,
        var tabs: MutableMap<Int, Tab> = LinkedHashMap(), var numId: String? = null, var ilvl: Int? = null,
        var shd: String? = null, var wordWrap: Boolean? = null, var snap: Boolean? = null,
        var widow: Boolean? = null, var outline: Int? = null,
        var borders: MutableMap<String, Pair<WordHtml.Border, Float>> = HashMap(),
    )

    private class Style(val id: String, val type: String, val basedOn: String?, val el: Element)
    private class Level(val fmt: String, val text: String, val start: Int, val jc: String?, val pPr: Element?, val rPr: Element?, val suff: String)
    private class Geo(
        val w: Float, val h: Float, val top: Float, val right: Float, val bottom: Float, val left: Float, val hdr: Float, val ftr: Float,
        val titlePg: Boolean, val pgStart: Int, val pgFmt: String, val grid: Float, val type: String,
        val cols: Int = 1, val colSpace: Float = 36f,
    ) {
        val cw get() = (w - left - right).coerceAtLeast(72f)
        /** 다단이면 단 폭(본문은 단 폭으로 조판한다). */
        val colW get() = if (cols <= 1) cw else ((cw - (cols - 1) * colSpace) / cols).coerceAtLeast(36f)
    }

    private class Ctx(val zip: Map<String, ByteArray>) {
        val styles = HashMap<String, Style>()
        var defStyleP: String? = null
        var docDefR: Element? = null
        var docDefP: Element? = null
        val theme = HashMap<String, String>() // minorHAnsi, minorEastAsia, majorHAnsi, majorEastAsia
        val nums = HashMap<String, Pair<String, Map<Int, Int>>>() // numId → (abstractId, startOverrides)
        val abstracts = HashMap<String, Map<Int, Level>>()
        val counters = HashMap<String, IntArray>()
        val overrideApplied = HashSet<String>()
        var defaultTab = 36f
        var compat = 15
        var evenOdd = false
        val images = LinkedHashMap<String, ByteArray>()
        val footRefs = ArrayList<String>()
        val endRefs = ArrayList<String>()
        val overlays = ArrayList<PagedWord.Overlay>()
        var ovSeq = 0
        var paras = 0; var tables = 0; var imgs = 0; var tabParas = 0; var pageBreaks = 0
        var geo: Geo? = null
        /** 지금 칸의 표 스타일 (pPr, rPr). 기본 문단 스타일 문단에는 표 스타일이 문단 스타일보다 앞선다(Word 동작). */
        var cellStyle: Pair<List<Element>, List<Element>>? = null
        val metas = LinkedHashMap<String, PagedWord.MetaPic>()
        val headings = ArrayList<PagedWord.Heading>()

        fun run(): PagedWord {
            val docXml = zip["word/document.xml"] ?: throw IllegalArgumentException("word/document.xml 없음")
            loadTheme(); loadStyles(); loadNumbering(); loadSettings()
            val docRels = rels("word/_rels/document.xml.rels")
            val body = first(parse(docXml).documentElement, "body") ?: throw IllegalArgumentException("본문 없음")

            // 구역 나누기: 문단 pPr/sectPr 이 그 문단까지의 구역을 끝낸다. 마지막은 body/sectPr.
            val blocks = children(body)
            val groups = ArrayList<Pair<List<Element>, Element?>>()
            var cur = ArrayList<Element>()
            for (b in blocks) {
                if (b.localName == "sectPr") continue
                cur += b
                val sp = if (b.localName == "p") first(b, "pPr")?.let { first(it, "sectPr") } else null
                if (sp != null) { groups += cur to sp; cur = ArrayList() }
            }
            groups += cur to blocks.lastOrNull { it.localName == "sectPr" }

            val sections = ArrayList<PagedWord.Section>()
            val inherited = HashMap<String, String>() // "header-default" → rId
            var headersN = 0; var footersN = 0
            for ((gi, g) in groups.withIndex()) {
                val sp = g.second
                val geo = geometry(sp)
                this.geo = geo
                overlays.clear()
                val sb = StringBuilder()
                blockList(g.first, docRels, geo.colW, sb)
                // 미주는 마지막 구역 끝에
                if (gi == groups.size - 1 && endRefs.isNotEmpty()) endnotes(geo.cw, sb)
                // 머리글/바닥글 참조(없는 종류는 앞 구역 것을 이어 씀)
                if (sp != null) for (ref in children(sp)) {
                    val kind = when (ref.localName) { "headerReference" -> "header"; "footerReference" -> "footer"; else -> continue }
                    inherited["$kind-" + ref.getAttributeNS(W, "type").ifEmpty { "default" }] = ref.getAttributeNS(R, "id")
                }
                val hs = HashMap<String, String>(); val fs = HashMap<String, String>()
                for ((key, rid) in inherited) {
                    val rel = docRels[rid] ?: continue
                    val path = resolve("word/", rel)
                    val part = zip[path] ?: continue
                    val hb = StringBuilder()
                    val savedOv = ArrayList(overlays)
                    blockList(children(parse(part).documentElement), rels(relsPathFor(path)), geo.cw, hb, headerPart = true)
                    overlays.clear(); overlays.addAll(savedOv) // 머리글의 떠 있는 그림은 머리글 안 블록으로만(간단화)
                    val (kind, type) = key.split('-').let { it[0] to it[1] }
                    if (kind == "header") { hs[type] = hb.toString(); headersN++ } else { fs[type] = hb.toString(); footersN++ }
                }
                // 이어지는(continuous) 구역은 쪽 설정이 같으면 앞 구역에 붙인다
                val prev = sections.lastOrNull()
                // 단 수가 다르면 같은 쪽에 이어 붙이지 못한다(새 쪽에서 시작 — 한계)
                if (prev != null && geo.type == "continuous" && prev.pageW == geo.w && prev.pageH == geo.h && prev.left == geo.left && prev.right == geo.right && prev.cols == geo.cols) {
                    sections[sections.size - 1] = PagedWord.Section(prev.pageW, prev.pageH, prev.top, prev.right, prev.bottom, prev.left, prev.headerDist, prev.footerDist,
                        prev.titlePg, prev.evenOdd, prev.pgStart, prev.pgFmt, prev.body + sb, prev.headers, prev.footers, prev.overlays + overlays, prev.cols, prev.colSpace)
                } else {
                    sections += PagedWord.Section(geo.w, geo.h, geo.top, geo.right, geo.bottom, geo.left, geo.hdr, geo.ftr, geo.titlePg, evenOdd,
                        if (sections.isEmpty() && geo.pgStart < 0) 1 else geo.pgStart, geo.pgFmt, sb.toString(), hs, fs, ArrayList(overlays), geo.cols, geo.colSpace)
                }
            }

            // 각주
            val notes = ArrayList<Pair<String, String>>()
            zip["word/footnotes.xml"]?.let { fx ->
                val fRels = rels("word/_rels/footnotes.xml.rels")
                val byId = children(parse(fx).documentElement).filter { it.localName == "footnote" }.associateBy { it.getAttributeNS(W, "id") }
                val cw = sections.first().let { it.pageW - it.left - it.right }
                var k = 0
                while (k < footRefs.size) { // 각주 안 각주 참조가 목록을 늘릴 수 있다
                    val id = footRefs[k++]
                    val fn = byId[id] ?: continue
                    val sb = StringBuilder()
                    noteNumber = footRefs.indexOf(id) + 1
                    blockList(children(fn), fRels, cw, sb, headerPart = true)
                    notes += id to sb.toString()
                }
            }
            return PagedWord(
                sections, notes, images,
                PagedWord.Stats(sections.size, paras, tables, imgs, sections.sumOf { it.overlays.size }, headersN, footersN, notes.size, tabParas, pageBreaks, metas.size, headings.size),
                metas, headings,
            )
        }

        var noteNumber = 0

        fun endnotes(cw: Float, sb: StringBuilder) {
            val ex = zip["word/endnotes.xml"] ?: return
            val eRels = rels("word/_rels/endnotes.xml.rels")
            val byId = children(parse(ex).documentElement).filter { it.localName == "endnote" }.associateBy { it.getAttributeNS(W, "id") }
            sb.append("<div style=\"padding-top:12pt\"><div style=\"width:144pt;border-top:0.5pt solid #000;height:6pt\"></div></div>\n")
            for ((n, id) in endRefs.withIndex()) {
                val en = byId[id] ?: continue
                noteNumber = -(n + 1)
                blockList(children(en), eRels, cw, sb, headerPart = true)
            }
        }

        // ---------------- 구역 ----------------
        fun tw(e: Element?, attr: String): Float? = e?.getAttributeNS(W, attr)?.takeIf { it.isNotEmpty() }?.toFloatOrNull()?.div(20f)

        fun geometry(sp: Element?): Geo {
            val pgSz = sp?.let { first(it, "pgSz") }
            var w = tw(pgSz, "w") ?: 595.3f; var h = tw(pgSz, "h") ?: 841.9f
            if (pgSz?.getAttributeNS(W, "orient") == "landscape" && h > w) { val t = w; w = h; h = t }
            val m = sp?.let { first(it, "pgMar") }
            val pn = sp?.let { first(it, "pgNumType") }
            val grid = sp?.let { first(it, "docGrid") }
            val gridOn = grid?.getAttributeNS(W, "type") in setOf("lines", "linesAndChars", "snapToChars")
            // 다단: 같은 폭 단(num·space). 폭이 다른 단(w:col 여러 개)은 단 수만 쓰고 같은 폭으로 근사한다.
            val colsEl = sp?.let { first(it, "cols") }
            val colChildren = colsEl?.let { children(it).filter { c -> c.localName == "col" } }.orEmpty()
            val nCols = (colsEl?.getAttributeNS(W, "num")?.toIntOrNull() ?: colChildren.size.takeIf { it > 1 } ?: 1).coerceIn(1, 6)
            val colSpace = tw(colsEl, "space") ?: colChildren.firstOrNull()?.let { tw(it, "space") } ?: 36f
            return Geo(
                w, h,
                kotlin.math.abs(tw(m, "top") ?: 72f), tw(m, "right") ?: 72f, kotlin.math.abs(tw(m, "bottom") ?: 72f), (tw(m, "left") ?: 72f) + (tw(m, "gutter") ?: 0f),
                tw(m, "header") ?: 36f, tw(m, "footer") ?: 36f,
                sp?.let { first(it, "titlePg") }?.let { onOff(it) } ?: false,
                pn?.getAttributeNS(W, "start")?.toIntOrNull() ?: -1,
                pn?.getAttributeNS(W, "fmt")?.ifEmpty { null } ?: "decimal",
                if (gridOn) tw(grid, "linePitch") ?: 0f else 0f,
                sp?.let { first(it, "type") }?.getAttributeNS(W, "val")?.ifEmpty { null } ?: "nextPage",
                nCols, colSpace,
            )
        }

        // ---------------- 블록 ----------------
        private sealed class Blk {
            class P(val para: WordHtml.Para, val styleId: String?, val contextual: Boolean, val pre: String, val post: String) : Blk()
            class Html(val html: String) : Blk()
        }

        fun blockList(els: List<Element>, rels: Map<String, String>, cw: Float, out: StringBuilder, headerPart: Boolean = false, inCell: Boolean = false) {
            val units = ArrayList<Blk>()
            fun collect(list: List<Element>) {
                for (el in list) when (el.localName) {
                    "p" -> units += paragraph(el, rels, cw, inCell, plain = inCell || headerPart)
                    "tbl" -> { val sb = StringBuilder(); table(el, rels, cw, sb); units += Blk.Html(sb.toString()) }
                    "sdt" -> first(el, "sdtContent")?.let { collect(children(it)) }
                    "customXml", "ins", "smartTag", "moveTo" -> collect(children(el))
                    "AlternateContent" -> altContent(el)?.let { collect(children(it)) }
                    else -> {}
                }
            }
            collect(els)
            // 문맥 간격: 같은 스타일 문단 사이 간격 없앰
            for (i in units.indices) {
                when (val u = units[i]) {
                    is Blk.Html -> out.append(u.html)
                    is Blk.P -> {
                        var p = u.para
                        val nx = units.getOrNull(i + 1) as? Blk.P
                        if (nx != null && u.contextual && nx.styleId == u.styleId) p = copyPara(p, after = 0f)
                        val pv = units.getOrNull(i - 1) as? Blk.P
                        if (pv != null && u.contextual && pv.styleId == u.styleId) p = copyPara(p, before = 0f)
                        out.append(u.pre)
                        tabParas += WordHtml.para(p, cw, out)
                        out.append(u.post)
                    }
                }
            }
        }

        fun copyPara(p: WordHtml.Para, before: Float = p.before, after: Float = p.after) = WordHtml.Para(
            p.items, p.jc, p.left, p.right, p.firstLine, before, after, p.lineRule, p.lineVal, p.tabs, p.defaultTab, p.markSize, p.markFont,
            p.bg, p.keepAll, p.pageBreakBefore, p.gridPitch, p.id, p.borders, p.widow, p.headId,
        )

        // ---------------- 스타일 해석 ----------------
        fun chain(id: String?): List<Style> {
            val out = ArrayList<Style>(); var c = id; var g = 0
            while (c != null && g++ < 30) { val s = styles[c] ?: break; out += s; c = s.basedOn }
            return out.asReversed()
        }

        fun font(rf: Element, attr: String, themeAttr: String): String? {
            val t = rf.getAttributeNS(W, themeAttr)
            if (t.isNotEmpty()) theme[t]?.let { return it }
            return rf.getAttributeNS(W, attr).ifEmpty { null }
        }

        fun applyR(rp: RP, rPr: Element?) {
            if (rPr == null) return
            first(rPr, "rFonts")?.let { rf ->
                (font(rf, "ascii", "asciiTheme") ?: font(rf, "hAnsi", "hAnsiTheme"))?.let { rp.ascii = it }
                font(rf, "eastAsia", "eastAsiaTheme")?.let { rp.ea = it }
            }
            first(rPr, "sz")?.getAttributeNS(W, "val")?.toFloatOrNull()?.let { rp.sz = it / 2f }
            first(rPr, "b")?.let { rp.b = onOff(it) }
            first(rPr, "i")?.let { rp.i = onOff(it) }
            first(rPr, "u")?.let { rp.u = it.getAttributeNS(W, "val").let { v -> v.isNotEmpty() && v != "none" } }
            first(rPr, "strike")?.let { rp.strike = onOff(it) }
            first(rPr, "dstrike")?.let { if (onOff(it)) rp.strike = true }
            first(rPr, "color")?.getAttributeNS(W, "val")?.let { rp.color = it.takeIf { c -> HEX.matches(c) }?.uppercase() }
            first(rPr, "highlight")?.getAttributeNS(W, "val")?.let { rp.bg = HIGHLIGHT[it] }
            first(rPr, "shd")?.getAttributeNS(W, "fill")?.takeIf { HEX.matches(it) && !it.equals("auto", true) }?.let { if (rp.bg == null) rp.bg = it.uppercase() }
            first(rPr, "vertAlign")?.getAttributeNS(W, "val")?.let { rp.vert = it.takeIf { v -> v == "superscript" || v == "subscript" } }
            first(rPr, "vanish")?.let { rp.vanish = onOff(it) }
            first(rPr, "caps")?.let { rp.caps = onOff(it) }
        }

        fun applyP(pp: PP, pPr: Element?) {
            if (pPr == null) return
            first(pPr, "jc")?.getAttributeNS(W, "val")?.let { pp.jc = it }
            first(pPr, "ind")?.let { ind ->
                (tw(ind, "left") ?: tw(ind, "start"))?.let { pp.left = it }
                (tw(ind, "right") ?: tw(ind, "end"))?.let { pp.right = it }
                tw(ind, "firstLine")?.let { pp.firstLine = it }
                tw(ind, "hanging")?.let { pp.firstLine = -it }
                ind.getAttributeNS(W, "leftChars").toFloatOrNull()?.takeIf { it != 0f }?.let { pp.left = it / 100f * 10f }
                ind.getAttributeNS(W, "firstLineChars").toFloatOrNull()?.takeIf { it != 0f }?.let { pp.firstLine = it / 100f * 10f }
                ind.getAttributeNS(W, "hangingChars").toFloatOrNull()?.takeIf { it != 0f }?.let { pp.firstLine = -it / 100f * 10f }
            }
            first(pPr, "spacing")?.let { s ->
                tw(s, "before")?.let { pp.before = it }
                tw(s, "after")?.let { pp.after = it }
                s.getAttributeNS(W, "beforeLines").toFloatOrNull()?.let { pp.before = it / 100f * 12f }
                s.getAttributeNS(W, "afterLines").toFloatOrNull()?.let { pp.after = it / 100f * 12f }
                s.getAttributeNS(W, "beforeAutospacing").takeIf { it.isNotEmpty() }?.let { pp.beforeAuto = it == "1" || it == "true" || it == "on" }
                s.getAttributeNS(W, "afterAutospacing").takeIf { it.isNotEmpty() }?.let { pp.afterAuto = it == "1" || it == "true" || it == "on" }
                s.getAttributeNS(W, "line").toFloatOrNull()?.let { pp.line = it }
                s.getAttributeNS(W, "lineRule").takeIf { it.isNotEmpty() }?.let { pp.lineRule = it }
            }
            first(pPr, "contextualSpacing")?.let { pp.contextual = onOff(it) }
            first(pPr, "pageBreakBefore")?.let { pp.pageBreakBefore = onOff(it) }
            first(pPr, "tabs")?.let { ts ->
                for (t in children(ts).filter { it.localName == "tab" }) {
                    val pos = tw(t, "pos") ?: continue
                    val v = t.getAttributeNS(W, "val")
                    val key = (pos * 20).toInt()
                    if (v == "clear") pp.tabs.remove(key) else pp.tabs[key] = Tab(pos, v.ifEmpty { "left" }, t.getAttributeNS(W, "leader").ifEmpty { null })
                }
            }
            first(pPr, "numPr")?.let { np ->
                first(np, "numId")?.getAttributeNS(W, "val")?.let { pp.numId = it }
                first(np, "ilvl")?.getAttributeNS(W, "val")?.toIntOrNull()?.let { pp.ilvl = it }
            }
            first(pPr, "shd")?.getAttributeNS(W, "fill")?.takeIf { HEX.matches(it) }?.let { pp.shd = it.uppercase().takeIf { c -> c != "FFFFFF" } }
            first(pPr, "wordWrap")?.let { pp.wordWrap = onOff(it) }
            first(pPr, "pBdr")?.let { bd ->
                for (c in children(bd)) {
                    val side = when (c.localName) { "top" -> "top"; "bottom" -> "bottom"; "left", "start" -> "left"; "right", "end" -> "right"; else -> continue }
                    val b = border(c) ?: continue
                    if (b.width <= 0f) pp.borders.remove(side) else pp.borders[side] = b to (c.getAttributeNS(W, "space").toFloatOrNull() ?: 1f)
                }
            }
            first(pPr, "snapToGrid")?.let { pp.snap = onOff(it) }
            first(pPr, "widowControl")?.let { pp.widow = onOff(it) }
            first(pPr, "outlineLvl")?.getAttributeNS(W, "val")?.toIntOrNull()?.let { pp.outline = it }
        }

        fun paraStyleId(p: Element): String? = first(p, "pPr")?.let { first(it, "pStyle") }?.getAttributeNS(W, "val") ?: defStyleP

        fun baseRun(styleId: String?): RP {
            val rp = RP()
            applyR(rp, docDefR)
            val ts = cellStyle
            val tableWins = ts != null && (styleId == null || styleId == defStyleP)
            if (ts != null && !tableWins) ts.second.forEach { applyR(rp, it) }
            for (s in chain(styleId)) applyR(rp, first(s.el, "rPr"))
            if (ts != null && tableWins) ts.second.forEach { applyR(rp, it) }
            return rp
        }

        // ---------------- 문단 ----------------
        /** [plain] = 표 칸·머리글·각주 문단(과부·고아 줄 표지와 목차 표지를 달지 않는다). */
        fun paragraph(p: Element, rels: Map<String, String>, cw: Float, inCell: Boolean, plain: Boolean = inCell): Blk {
            paras++
            val pPr = first(p, "pPr")
            val styleId = paraStyleId(p)
            val pp = PP()
            applyP(pp, docDefP)
            val ts = cellStyle
            val tableWins = ts != null && (styleId == null || styleId == defStyleP)
            if (ts != null && !tableWins) ts.first.forEach { applyP(pp, it) }
            for (s in chain(styleId)) applyP(pp, first(s.el, "pPr"))
            if (ts != null && tableWins) ts.first.forEach { applyP(pp, it) }
            // 번호: 스타일·직접 numPr
            val direct = PP(); applyP(direct, pPr)
            val numId = direct.numId ?: pp.numId
            val ilvl = direct.ilvl ?: pp.ilvl ?: 0
            val lvl = numId?.takeIf { it != "0" }?.let { level(it, ilvl) }
            if (lvl != null) applyP(pp, lvl.pPr)
            applyP(pp, pPr)
            pp.numId = numId

            val base = baseRun(styleId)
            val mark = base.copy(); applyR(mark, pPr?.let { first(it, "rPr") })
            val items = ArrayList<Item>()
            val pre = StringBuilder(); val post = StringBuilder()
            if (lvl != null && lvl.fmt != "none") {
                val label = numLabel(numId!!, ilvl, lvl)
                if (label.isNotEmpty()) {
                    val lr = mark.copy(); lr.u = false; applyR(lr, lvl.rPr)
                    items += Item.Text(mkRun(lr, label))
                    when (lvl.suff) { "space" -> items += Item.Text(mkRun(lr, " ")); "nothing" -> {}; else -> items += Item.Tab }
                }
            }
            inline(p, rels, base, items, pre, post, FieldState(), cw)
            if (items.any { it is Item.PageBreak || it is Item.ColumnBreak }) pageBreaks++
            val sz = mark.sz ?: 10f
            val g = geo?.grid ?: 0f
            // 목차(F-10 원본 보기): 개요 수준(0~8) 또는 '제목 N' 스타일 이름
            val level = (pp.outline ?: headingLevel(styleId))?.takeIf { it in 0..8 }
            val headId = if (!plain && level != null) {
                val title = items.filterIsInstance<Item.Text>().joinToString("") { it.run.text }.trim().replace(Regex("\\s+"), " ")
                if (title.isNotEmpty() && !title.contains(PagedWord.PAGE)) "h${headings.size}".also { headings += PagedWord.Heading(it, level, title.take(120)) } else null
            } else null
            val lineRule = pp.lineRule ?: "auto"
            val lineVal = when (lineRule) { "exact", "atLeast" -> (pp.line ?: 240f) / 20f; else -> (pp.line ?: 240f) / 240f }
            val para = WordHtml.Para(
                items = items,
                jc = pp.jc,
                left = pp.left ?: 0f, right = pp.right ?: 0f, firstLine = pp.firstLine ?: 0f,
                before = if (pp.beforeAuto == true) (if (inCell) 0f else 14f) else pp.before ?: 0f,
                after = if (pp.afterAuto == true) (if (inCell) 0f else 14f) else pp.after ?: 0f,
                lineRule = lineRule, lineVal = lineVal,
                tabs = pp.tabs.values.filter { it.type != "bar" }.map { WordHtml.TabStop(it.pos, it.type, it.leader) },
                defaultTab = defaultTab,
                markSize = sz, markFont = mark.ea ?: mark.ascii,
                bg = pp.shd, keepAll = pp.wordWrap != false,
                pageBreakBefore = pp.pageBreakBefore == true && !inCell,
                gridPitch = if (pp.snap != false && !inCell) g else 0f,
                borders = pp.borders,
                widow = pp.widow == true && !plain,
                headId = headId,
            )
            return Blk.P(para, styleId, pp.contextual == true, pre.toString(), post.toString())
        }

        /** 스타일 이름이 '제목 N'(heading N)이면 개요 수준 N-1. */
        fun headingLevel(styleId: String?): Int? {
            for (st in chain(styleId).asReversed()) {
                val name = first(st.el, "name")?.getAttributeNS(W, "val")?.lowercase() ?: continue
                HEADING.find(name)?.let { return it.groupValues[2].toInt() - 1 }
            }
            return null
        }

        fun mkRun(rp: RP, text: String) = WordHtml.Run(
            if (rp.caps == true) text.uppercase() else text, rp.ascii, rp.ea, rp.sz ?: 10f,
            rp.b == true, rp.i == true, rp.u == true, rp.strike == true, rp.color?.takeIf { it != "000000" }, rp.bg, rp.vert,
        )

        class FieldState { val stack = ArrayList<Array<String>>() /* [instr, phase(i|r), emitted] */ }

        fun inline(parent: Element, rels: Map<String, String>, base: RP, items: MutableList<Item>, pre: StringBuilder, post: StringBuilder, fs: FieldState, cw: Float) {
            for (el in children(parent)) when (el.localName) {
                "r" -> run(el, rels, base, items, pre, post, fs, cw)
                "hyperlink", "ins", "smartTag", "customXml", "moveTo", "dir", "bdo" -> inline(el, rels, base, items, pre, post, fs, cw)
                "sdt" -> first(el, "sdtContent")?.let { inline(it, rels, base, items, pre, post, fs, cw) }
                "fldSimple" -> {
                    val instr = el.getAttributeNS(W, "instr").trim().uppercase()
                    when {
                        instr.startsWith("PAGE") && !instr.startsWith("PAGEREF") -> items += Item.Text(mkRun(firstRunProps(el, base), PagedWord.PAGE))
                        instr.startsWith("NUMPAGES") || instr.startsWith("SECTIONPAGES") -> items += Item.Text(mkRun(firstRunProps(el, base), PagedWord.PAGES))
                        else -> inline(el, rels, base, items, pre, post, fs, cw)
                    }
                }
                "AlternateContent" -> altContent(el)?.let { inline(it, rels, base, items, pre, post, fs, cw) }
                else -> {}
            }
        }

        fun firstRunProps(el: Element, base: RP): RP {
            val r = descendants(el, "r").firstOrNull() ?: return base
            return runProps(r, base)
        }

        fun runProps(r: Element, base: RP): RP {
            val rp = base.copy()
            val rPr = first(r, "rPr")
            rPr?.let { first(it, "rStyle") }?.getAttributeNS(W, "val")?.let { cs -> for (s in chain(cs)) applyR(rp, first(s.el, "rPr")) }
            applyR(rp, rPr)
            return rp
        }

        fun run(r: Element, rels: Map<String, String>, base: RP, items: MutableList<Item>, pre: StringBuilder, post: StringBuilder, fs: FieldState, cw: Float) {
            val rp = runProps(r, base)
            val hidden = rp.vanish == true
            fun inResult(): Array<String>? = fs.stack.lastOrNull()
            for (c in children(r)) {
                val fld = inResult()
                val suppress = fld != null && (fld[1] == "i" || (fld[1] == "r" && fld[2] == "1"))
                when (c.localName) {
                    "fldChar" -> when (c.getAttributeNS(W, "fldCharType")) {
                        "begin" -> fs.stack += arrayOf("", "i", "0")
                        "separate" -> fld?.let { f ->
                            f[1] = "r"
                            val ins = f[0].trim().uppercase()
                            val ph = when {
                                ins.startsWith("PAGE") && !ins.startsWith("PAGEREF") -> PagedWord.PAGE
                                ins.startsWith("NUMPAGES") || ins.startsWith("SECTIONPAGES") -> PagedWord.PAGES
                                else -> null
                            }
                            if (ph != null) { items += Item.Text(mkRun(rp, ph)); f[2] = "1" }
                        }
                        "end" -> fs.stack.removeLastOrNull()?.let { f ->
                            if (f[1] == "i") { // 결과 없는 필드
                                val ins = f[0].trim().uppercase()
                                if (ins.startsWith("PAGE") && !ins.startsWith("PAGEREF")) items += Item.Text(mkRun(rp, PagedWord.PAGE))
                                else if (ins.startsWith("NUMPAGES")) items += Item.Text(mkRun(rp, PagedWord.PAGES))
                            }
                        }
                    }
                    "instrText" -> fld?.let { if (it[1] == "i") it[0] = it[0] + c.textContent }
                    "t" -> if (!hidden && !suppress) items += Item.Text(mkRun(rp, c.textContent))
                    "tab", "ptab" -> if (!hidden && !suppress) items += Item.Tab
                    "br" -> if (!suppress) when (c.getAttributeNS(W, "type")) {
                        "page" -> items += Item.PageBreak
                        "column" -> items += Item.ColumnBreak
                        else -> items += Item.LineBreak
                    }
                    "cr" -> if (!suppress) items += Item.LineBreak
                    "noBreakHyphen" -> if (!hidden && !suppress) items += Item.Text(mkRun(rp, "‑"))
                    "sym" -> if (!hidden && !suppress) c.getAttributeNS(W, "char").toIntOrNull(16)?.let { items += Item.Text(mkRun(rp, symbol(it, c.getAttributeNS(W, "font")))) }
                    "footnoteReference" -> {
                        val id = c.getAttributeNS(W, "id")
                        if (id !in footRefs) footRefs += id
                        val n = footRefs.indexOf(id) + 1
                        items += Item.Raw("<span style=\"font-size:${WordHtml.f(rp.sz ?: 10f)}pt\"><a class=\"nr\" href=\"#ash-fn-$id\">$n</a></span>", (rp.sz ?: 10f) * 0.4f)
                    }
                    "endnoteReference" -> {
                        val id = c.getAttributeNS(W, "id")
                        if (id !in endRefs) endRefs += id
                        val n = endRefs.indexOf(id) + 1
                        items += Item.Raw("<span style=\"font-size:${WordHtml.f(rp.sz ?: 10f)}pt\"><a class=\"nr\">${PagedComposerNum.roman(n).lowercase()}</a></span>", (rp.sz ?: 10f) * 0.4f)
                    }
                    "footnoteRef", "endnoteRef" -> {
                        val n = noteNumber
                        val s = if (n < 0) PagedComposerNum.roman(-n).lowercase() else n.toString()
                        items += Item.Raw("<span style=\"font-size:${WordHtml.f(rp.sz ?: 10f)}pt\"><a class=\"nr\">$s</a></span>", (rp.sz ?: 10f) * 0.4f)
                    }
                    "drawing" -> if (!suppress) drawing(c, rels, items, pre, post, cw)
                    "pict", "object" -> if (!suppress) vml(c, rels, items, post, cw)
                    "AlternateContent" -> altContent(c)?.let { ac ->
                        for (x in children(ac)) when (x.localName) { "drawing" -> drawing(x, rels, items, pre, post, cw); "pict" -> vml(x, rels, items, post, cw) }
                    }
                    else -> {}
                }
            }
        }

        fun symbol(code: Int, font: String): String {
            val c = if (code >= 0xF000) code - 0xF000 else code
            val f = font.lowercase()
            if (f.contains("wingdings") || f.contains("symbol")) return when (c) {
                0xB7, 0x9F, 0xA1, 0x6C -> "•"; 0xA7, 0x6E -> "▪"; 0xFC -> "✓"; 0xD8 -> "➢"; 0x71 -> "□"; 0xA8 -> "□"; 0x76 -> "❖"; 0xE0 -> "→"
                else -> "•"
            }
            return String(Character.toChars(c))
        }

        // ---------------- 그림 ----------------
        fun imageHtml(rid: String, rels: Map<String, String>, wPt: Float, hPt: Float): Pair<String, String?> {
            val target = rels[rid] ?: return "" to null
            val path = resolve("word/", target)
            val bytes = zip[path] ?: return "" to null
            val lower = path.lowercase()
            if (lower.endsWith(".emf") || lower.endsWith(".wmf")) {
                // EMF·WMF: 그리기 목록으로 읽을 수 있으면 앱이 PNG 로 그린다(못 읽으면 회색 상자)
                if (!Metafile.isEmf(bytes) && !Metafile.isWmf(bytes)) return "<span class=\"img-unsupported\" style=\"font-size:9pt\">[그림]</span>" to null
                imgs++
                val name = "images/" + path.substringAfterLast('/') + ".png"
                metas[name] = PagedWord.MetaPic(bytes, wPt, hPt)
                return "<img src=\"${esc(name)}\" alt=\"\" style=\"width:${WordHtml.f(wPt)}pt;height:${WordHtml.f(hPt)}pt\"/>" to name
            }
            if (lower.endsWith(".svg")) {
                return "<span class=\"img-unsupported\" style=\"font-size:9pt\">[그림]</span>" to null
            }
            imgs++
            val name = "images/" + path.substringAfterLast('/')
            images[name] = bytes
            return "<img src=\"${esc(name)}\" alt=\"\" style=\"width:${WordHtml.f(wPt)}pt;height:${WordHtml.f(hPt)}pt\"/>" to name
        }

        fun emu(e: Element?, a: String): Float? = e?.getAttribute(a)?.toLongOrNull()?.let { it / 12700f }

        fun drawing(d: Element, rels: Map<String, String>, items: MutableList<Item>, pre: StringBuilder, post: StringBuilder, cw: Float) {
            val anchor = children(d).firstOrNull { it.localName == "anchor" }
            val ext = (anchor ?: children(d).firstOrNull { it.localName == "inline" })?.let { first(it, "extent") }
            val w = emu(ext, "cx") ?: 0f; val h = emu(ext, "cy") ?: 0f
            // 글상자
            descendants(d, "txbxContent").firstOrNull()?.let { tx ->
                val sb = StringBuilder()
                blockList(children(tx), rels, maxOf(w, 36f), sb, headerPart = true)
                post.append("<div style=\"width:").append(WordHtml.f(minOf(maxOf(w, 36f), cw))).append("pt\">").append(sb).append("</div>\n")
                return
            }
            val blip = descendants(d, "blip").firstOrNull() ?: return
            val (html, name) = imageHtml(blip.getAttributeNS(R, "embed"), rels, w, h)
            if (html.isEmpty()) return
            if (anchor == null) { items += Item.Raw(html, w, h); return }
            val g = geo!!
            val behind = anchor.getAttribute("behindDoc").let { it == "1" || it == "true" }
            val wrapNone = children(anchor).any { it.localName == "wrapNone" }
            val ph = first(anchor, "positionH"); val pv = first(anchor, "positionV")
            val relH = ph?.getAttribute("relativeFrom") ?: "column"
            val relV = pv?.getAttribute("relativeFrom") ?: "paragraph"
            val hFrameW = if (relH == "page") g.w else cw
            val x = ph?.let { first(it, "posOffset") }?.textContent?.trim()?.toLongOrNull()?.let { it / 12700f }
                ?: when (ph?.let { first(it, "align") }?.textContent?.trim()) { "center" -> (hFrameW - w) / 2f; "right", "outside" -> hFrameW - w; else -> 0f }
            if ((wrapNone || behind) && name != null) {
                val vFrame = when (relV) { "page" -> g.h; "margin", "topMargin" -> g.h - g.top - g.bottom; else -> 0f }
                val y = pv?.let { first(it, "posOffset") }?.textContent?.trim()?.toLongOrNull()?.let { it / 12700f }
                    ?: when (pv?.let { first(it, "align") }?.textContent?.trim()) { "center" -> (vFrame - h) / 2f; "bottom", "outside" -> vFrame - h; else -> 0f }
                val id = "o" + (ovSeq++)
                overlays += PagedWord.Overlay(id, name, x, y, w, h, if (relH == "page") "page" else "margin",
                    when (relV) { "page" -> "page"; "margin", "topMargin" -> "margin"; else -> "paragraph" }, behind)
                items += Item.Raw("<a href=\"#ash-anc-$id\">&#x2060;</a>")
                return
            }
            // 둘러싸기 배치: 문단 앞 블록 그림으로 근사(가로 위치는 지킴)
            val left = (if (relH == "page") x - g.left else x).coerceIn(0f, maxOf(0f, cw - w))
            pre.append("<p style=\"line-height:").append(WordHtml.f(h + 2f)).append("pt;margin-left:").append(WordHtml.f(left)).append("pt\">").append(html).append("</p>\n")
        }

        fun vml(p: Element, rels: Map<String, String>, items: MutableList<Item>, post: StringBuilder, cw: Float) {
            descendants(p, "txbxContent").firstOrNull()?.let { tx ->
                val sb = StringBuilder()
                blockList(children(tx), rels, cw, sb, headerPart = true)
                post.append("<div>").append(sb).append("</div>\n")
                return
            }
            val img = descendants(p, "imagedata").firstOrNull() ?: return
            val shape = descendants(p, "shape").firstOrNull()
            val style = shape?.getAttribute("style").orEmpty()
            fun dim(k: String): Float? = Regex("(?:^|;)\\s*$k\\s*:\\s*([0-9.]+)(pt|in|px|cm|mm)?").find(style)?.let { m ->
                val v = m.groupValues[1].toFloatOrNull() ?: return null
                when (m.groupValues[2]) { "in" -> v * 72f; "px" -> v * 0.75f; "cm" -> v * 28.35f; "mm" -> v * 2.835f; else -> v }
            }
            val w = dim("width") ?: 72f; val h = dim("height") ?: 72f
            val (html, _) = imageHtml(img.getAttributeNS(R, "id"), rels, w, h)
            if (html.isNotEmpty()) items += Item.Raw(html, w, h)
        }

        // ---------------- 표 ----------------
        fun border(e: Element?): WordHtml.Border? {
            if (e == null) return null
            val v = e.getAttributeNS(W, "val")
            if (v == "nil" || v == "none" || v.isEmpty()) return WordHtml.Border(0f, "none", "000000")
            val w = (e.getAttributeNS(W, "sz").toFloatOrNull() ?: 4f) / 8f
            val color = e.getAttributeNS(W, "color").takeIf { HEX.matches(it) }?.uppercase() ?: "000000"
            val st = when { v.contains("double") -> "double"; v == "dotted" -> "dotted"; v.startsWith("dash") -> "dashed"; else -> "solid" }
            return WordHtml.Border(if (st == "double") maxOf(w * 3f, 2.25f) else maxOf(w, 0.25f), st, color)
        }

        fun sides(bs: Element?): Map<String, WordHtml.Border?> {
            val m = HashMap<String, WordHtml.Border?>()
            if (bs == null) return m
            for (c in children(bs)) {
                val k = when (c.localName) { "start" -> "left"; "end" -> "right"; else -> c.localName }
                m[k] = border(c)
            }
            return m
        }

        fun table(tbl: Element, rels: Map<String, String>, cw: Float, out: StringBuilder) {
            tables++
            val tblPr = first(tbl, "tblPr")
            val styleId = tblPr?.let { first(it, "tblStyle") }?.getAttributeNS(W, "val")
            val bordersM = HashMap<String, WordHtml.Border?>()
            var padL = 5.4f; var padR = 5.4f; var padT = 0f; var padB = 0f
            fun mar(m: Element?) {
                if (m == null) return
                for (c in children(m)) {
                    val v = tw(c, "w") ?: continue
                    when (c.localName) { "left", "start" -> padL = v; "right", "end" -> padR = v; "top" -> padT = v; "bottom" -> padB = v }
                }
            }
            var styleP: Element? = null; var styleR: Element? = null
            // 조건부 서식(표 스타일 tblStylePr): 종류 → 요소. 뒤 스타일(상속 아래)이 앞을 덮는다.
            val cond = HashMap<String, Element>()
            var rowBand = 1; var colBand = 1
            for (s in chain(styleId)) {
                first(s.el, "tblPr")?.let { tp ->
                    bordersM.putAll(sides(first(tp, "tblBorders"))); mar(first(tp, "tblCellMar"))
                    first(tp, "tblStyleRowBandSize")?.getAttributeNS(W, "val")?.toIntOrNull()?.let { rowBand = it.coerceAtLeast(1) }
                    first(tp, "tblStyleColBandSize")?.getAttributeNS(W, "val")?.toIntOrNull()?.let { colBand = it.coerceAtLeast(1) }
                }
                first(s.el, "pPr")?.let { styleP = it }; first(s.el, "rPr")?.let { styleR = it }
                for (c in children(s.el)) if (c.localName == "tblStylePr") c.getAttributeNS(W, "type").takeIf { it.isNotEmpty() }?.let { cond[it] = c }
            }
            // 표 모양(tblLook): 첫 행·마지막 행·첫 열·마지막 열·줄무늬 켜고 끄기(속성 또는 옛 16진 val)
            val look = tblPr?.let { first(it, "tblLook") }
            fun lookFlag(attr: String, bit: Int, def: Boolean): Boolean {
                val a = look?.getAttributeNS(W, attr).orEmpty()
                if (a.isNotEmpty()) return a == "1" || a == "true" || a == "on"
                val v = look?.getAttributeNS(W, "val")?.toIntOrNull(16) ?: return def
                return v and bit != 0
            }
            val lkFirstRow = lookFlag("firstRow", 0x0020, true)
            val lkLastRow = lookFlag("lastRow", 0x0040, false)
            val lkFirstCol = lookFlag("firstColumn", 0x0080, true)
            val lkLastCol = lookFlag("lastColumn", 0x0100, false)
            val lkNoH = lookFlag("noHBand", 0x0200, false)
            val lkNoV = lookFlag("noVBand", 0x0400, true)
            tblPr?.let { bordersM.putAll(sides(first(it, "tblBorders"))); mar(first(it, "tblCellMar")) }
            val ind = tblPr?.let { first(it, "tblInd") }?.let { tw(it, "w") } ?: 0f
            val jc = tblPr?.let { first(it, "jc") }?.getAttributeNS(W, "val")
            var cols = first(tbl, "tblGrid")?.let { g -> children(g).filter { it.localName == "gridCol" }.map { tw(it, "w") ?: 0f } } ?: emptyList()
            val rows = children(tbl).filter { it.localName == "tr" }
            // 격자가 없으면 첫 행 칸 너비로
            if (cols.isEmpty() || cols.sum() <= 0f) {
                cols = rowCells(rows.firstOrNull() ?: return).map { tc -> first(tc, "tcPr")?.let { first(it, "tcW") }?.let { tw(it, "w") } ?: (cw / 4f) }
            }
            // 1단계: 칸 수집
            class C(val tc: Element?, val col: Int, val span: Int, val vm: String?)
            val grid = rows.map { tr ->
                val trPr = first(tr, "trPr")
                var col = trPr?.let { first(it, "gridBefore") }?.getAttributeNS(W, "val")?.toIntOrNull() ?: 0
                val cells = ArrayList<C>()
                if (col > 0) cells += C(null, 0, col, null)
                for (tc in rowCells(tr)) {
                    val tcPr = first(tc, "tcPr")
                    val span = tcPr?.let { first(it, "gridSpan") }?.getAttributeNS(W, "val")?.toIntOrNull() ?: 1
                    val vm = tcPr?.let { first(it, "vMerge") }?.let { if (it.getAttributeNS(W, "val") == "restart") "restart" else "continue" }
                    cells += C(tc, col, span, vm); col += span
                }
                if (col < cols.size) cells += C(null, col, cols.size - col, null)
                cells
            }
            // 열 수가 격자보다 많으면 늘린다
            val maxCol = grid.maxOfOrNull { r -> r.sumOf { it.span } } ?: 0
            if (maxCol > cols.size) cols = cols + List(maxCol - cols.size) { 36f }
            val outRows = ArrayList<WordHtml.Row>()
            for ((ri, cells) in grid.withIndex()) {
                val trPr = first(rows[ri], "trPr")
                val th = trPr?.let { first(it, "trHeight") }
                val hVal = tw(th, "val") ?: 0f
                val exact = th?.getAttributeNS(W, "hRule") == "exact"
                val outCells = ArrayList<WordHtml.Cell>()
                for (c in cells) {
                    if (c.tc == null) { outCells += WordHtml.Cell(c.col, c.span, html = "", padL = 0f, padR = 0f); continue }
                    val tcPr = first(c.tc, "tcPr")
                    val cb = sides(tcPr?.let { first(it, "tcBorders") })
                    val lastCol = c.col + c.span >= cols.size
                    var rowspan = 1
                    if (c.vm == "restart") {
                        var k = ri + 1
                        while (k < grid.size && grid[k].any { it.col == c.col && it.vm == "continue" }) { rowspan++; k++ }
                    }
                    val lastRow = ri + rowspan >= grid.size
                    var cpl = padL; var cpr = padR; var cpt = padT; var cpb = padB
                    tcPr?.let { first(it, "tcMar") }?.let { m ->
                        for (x in children(m)) { val v = tw(x, "w") ?: continue; when (x.localName) { "left", "start" -> cpl = v; "right", "end" -> cpr = v; "top" -> cpt = v; "bottom" -> cpb = v } }
                    }
                    val w = (c.col until minOf(c.col + c.span, cols.size)).sumOf { cols[it].toDouble() }.toFloat()
                    // 이 칸에 걸리는 조건부 서식(Word 적용 순서: 줄무늬 열 → 줄무늬 행 → 첫/끝 열 → 첫/끝 행 → 모서리)
                    val isFirstRow = lkFirstRow && ri == 0
                    val isLastRow = lkLastRow && lastRow
                    val isFirstCol = lkFirstCol && c.col == 0
                    val isLastCol = lkLastCol && lastCol
                    val kinds = ArrayList<String>()
                    if (!lkNoV && !isFirstCol && !isLastCol) kinds += if (((c.col - (if (lkFirstCol) 1 else 0)) / colBand) % 2 == 0) "band1Vert" else "band2Vert"
                    if (!lkNoH && !isFirstRow && !isLastRow) kinds += if (((ri - (if (lkFirstRow) 1 else 0)) / rowBand) % 2 == 0) "band1Horz" else "band2Horz"
                    if (isFirstCol) kinds += "firstCol"
                    if (isLastCol) kinds += "lastCol"
                    if (isFirstRow) kinds += "firstRow"
                    if (isLastRow) kinds += "lastRow"
                    if (isFirstRow && isFirstCol) kinds += "nwCell"
                    if (isFirstRow && isLastCol) kinds += "neCell"
                    if (isLastRow && isFirstCol) kinds += "swCell"
                    if (isLastRow && isLastCol) kinds += "seCell"
                    val condEls = kinds.mapNotNull { cond[it] }
                    var condBg: String? = null
                    val condB = HashMap<String, WordHtml.Border?>()
                    for (ce in condEls) {
                        first(ce, "tcPr")?.let { tp ->
                            first(tp, "shd")?.getAttributeNS(W, "fill")?.let { f -> condBg = f.takeIf { HEX.matches(it) }?.uppercase() }
                            condB.putAll(sides(first(tp, "tcBorders")))
                        }
                    }
                    fun side(k: String, edge: Boolean, inside: String) = if (cb.containsKey(k)) cb[k] else if (condB.containsKey(k)) condB[k] else if (edge) bordersM[k] else bordersM[inside]
                    val inner = StringBuilder()
                    if (c.vm != "continue") {
                        // 표 스타일 문단·글자 속성 + 조건부 서식(첫 행 굵게 등)을 칸 안 문단에
                        val saved = cellStyle
                        cellStyle = (listOfNotNull(styleP) + condEls.mapNotNull { first(it, "pPr") }) to (listOfNotNull(styleR) + condEls.mapNotNull { first(it, "rPr") })
                        try { blockList(children(c.tc), rels, maxOf(w - cpl - cpr, 4f), inner, inCell = true) } finally { cellStyle = saved }
                    }
                    outCells += WordHtml.Cell(
                        c.col, c.span, rowspan, inner.toString(),
                        top = side("top", ri == 0, "insideH"), right = side("right", lastCol, "insideV"),
                        bottom = side("bottom", lastRow, "insideH"), left = side("left", c.col == 0, "insideV"),
                        bg = (tcPr?.let { first(it, "shd") }?.getAttributeNS(W, "fill")?.takeIf { HEX.matches(it) }?.uppercase() ?: condBg)?.takeIf { it != "FFFFFF" },
                        vAlign = tcPr?.let { first(it, "vAlign") }?.getAttributeNS(W, "val"),
                        padL = cpl, padR = cpr, padT = cpt, padB = cpb,
                        covered = c.vm == "continue",
                    )
                }
                outRows += WordHtml.Row(outCells, hVal, exact)
            }
            val indent = if (compat < 15 && jc == null) ind - padL else ind
            WordHtml.table(WordHtml.Table(cols, outRows, indent, jc), out)
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

        // ---------------- 번호 ----------------
        fun level(numId: String, ilvl: Int): Level? {
            val (aid, _) = nums[numId] ?: return null
            return abstracts[aid]?.get(ilvl)
        }

        fun numLabel(numId: String, ilvl: Int, lvl: Level): String {
            val (aid, ov) = nums[numId] ?: return ""
            val levels = abstracts[aid] ?: return ""
            val ctr = counters.getOrPut(aid) { IntArray(9) { (levels[it]?.start ?: 1) - 1 } }
            if (numId !in overrideApplied) {
                overrideApplied += numId
                for ((l, s) in ov) if (l in 0..8) ctr[l] = s - 1
            }
            if (ilvl in 0..8) {
                ctr[ilvl]++
                for (k in ilvl + 1 until 9) ctr[k] = (levels[k]?.start ?: 1) - 1
            }
            if (lvl.fmt == "bullet") return bullet(lvl.text, lvl.rPr)
            var s = lvl.text
            for (k in 0..minOf(ilvl, 8)) s = s.replace("%${k + 1}", fmtNum(ctr[k].coerceAtLeast(1), levels[k]?.fmt ?: "decimal"))
            return s
        }

        fun bullet(t: String, rPr: Element?): String {
            if (t.isEmpty()) return ""
            val c = t.codePointAt(0)
            val f = rPr?.let { first(it, "rFonts") }?.getAttributeNS(W, "ascii").orEmpty()
            return when {
                c in 0xF000..0xF0FF -> symbol(c, f.ifEmpty { "symbol" })
                c == 'o'.code && f.contains("Courier", true) -> "◦"
                c == '§'.code && f.contains("Wingdings", true) -> "▪"
                c == '·'.code && f.contains("Symbol", true) -> "•"
                else -> t
            }
        }

        fun fmtNum(n: Int, fmt: String): String = when (fmt) {
            "lowerLetter" -> ('a' + (n - 1) % 26).toString()
            "upperLetter" -> ('A' + (n - 1) % 26).toString()
            "lowerRoman" -> PagedComposerNum.roman(n).lowercase()
            "upperRoman" -> PagedComposerNum.roman(n)
            "ganada", "koreanDigital2" -> GANADA.getOrElse(n - 1) { n.toString() }
            "chosung" -> CHOSUNG.getOrElse(n - 1) { n.toString() }
            "decimalEnclosedCircle", "decimalEnclosedCircleChinese" -> if (n in 1..20) String(Character.toChars(0x2460 + n - 1)) else n.toString()
            "decimalZero" -> if (n < 10) "0$n" else n.toString()
            else -> n.toString()
        }

        // ---------------- 읽기 ----------------
        fun loadTheme() {
            val x = zip["word/theme/theme1.xml"] ?: return
            val root = parse(x).documentElement
            fun fontOf(group: Element?): Pair<String?, String?> {
                if (group == null) return null to null
                val latin = DocxConverter.first(group, "latin")?.getAttribute("typeface")?.ifEmpty { null }
                val ea = DocxConverter.first(group, "ea")?.getAttribute("typeface")?.ifEmpty { null }
                    ?: children(group).firstOrNull { it.localName == "font" && it.getAttribute("script") == "Hang" }?.getAttribute("typeface")
                return latin to ea
            }
            val minor = descendants(root, "minorFont").firstOrNull(); val major = descendants(root, "majorFont").firstOrNull()
            fontOf(minor).let { (l, e) -> l?.let { theme["minorHAnsi"] = it; theme["minorAscii"] = it }; e?.let { theme["minorEastAsia"] = it } }
            fontOf(major).let { (l, e) -> l?.let { theme["majorHAnsi"] = it; theme["majorAscii"] = it }; e?.let { theme["majorEastAsia"] = it } }
        }

        fun loadStyles() {
            val x = zip["word/styles.xml"] ?: return
            val root = parse(x).documentElement
            first(root, "docDefaults")?.let { dd ->
                docDefR = first(dd, "rPrDefault")?.let { first(it, "rPr") }
                docDefP = first(dd, "pPrDefault")?.let { first(it, "pPr") }
            }
            for (s in children(root).filter { it.localName == "style" }) {
                val id = s.getAttributeNS(W, "styleId")
                val type = s.getAttributeNS(W, "type")
                styles[id] = Style(id, type, first(s, "basedOn")?.getAttributeNS(W, "val"), s)
                if (type == "paragraph" && s.getAttributeNS(W, "default").let { it == "1" || it == "true" }) defStyleP = id
            }
        }

        fun loadNumbering() {
            val x = zip["word/numbering.xml"] ?: return
            val root = parse(x).documentElement
            for (a in children(root).filter { it.localName == "abstractNum" }) {
                val lv = HashMap<Int, Level>()
                for (l in children(a).filter { it.localName == "lvl" }) {
                    lv[l.getAttributeNS(W, "ilvl").toIntOrNull() ?: 0] = Level(
                        first(l, "numFmt")?.getAttributeNS(W, "val") ?: "decimal",
                        first(l, "lvlText")?.getAttributeNS(W, "val") ?: "",
                        first(l, "start")?.getAttributeNS(W, "val")?.toIntOrNull() ?: 1,
                        first(l, "lvlJc")?.getAttributeNS(W, "val"),
                        first(l, "pPr"), first(l, "rPr"),
                        first(l, "suff")?.getAttributeNS(W, "val") ?: "tab",
                    )
                }
                abstracts[a.getAttributeNS(W, "abstractNumId")] = lv
            }
            for (n in children(root).filter { it.localName == "num" }) {
                val aid = first(n, "abstractNumId")?.getAttributeNS(W, "val") ?: continue
                val ov = HashMap<Int, Int>()
                for (o in children(n).filter { it.localName == "lvlOverride" }) {
                    val l = o.getAttributeNS(W, "ilvl").toIntOrNull() ?: continue
                    first(o, "startOverride")?.getAttributeNS(W, "val")?.toIntOrNull()?.let { ov[l] = it }
                }
                nums[n.getAttributeNS(W, "numId")] = aid to ov
            }
        }

        fun loadSettings() {
            val x = zip["word/settings.xml"] ?: return
            val root = parse(x).documentElement
            first(root, "defaultTabStop")?.let { tw(it, "val") }?.takeIf { it > 1f }?.let { defaultTab = it }
            first(root, "evenAndOddHeaders")?.let { evenOdd = onOff(it) }
            first(root, "compat")?.let { c ->
                children(c).firstOrNull { it.localName == "compatSetting" && it.getAttributeNS(W, "name") == "compatibilityMode" }
                    ?.getAttributeNS(W, "val")?.toIntOrNull()?.let { compat = it }
                    ?: run { compat = 12 }
            } ?: run { compat = 12 }
        }

        fun rels(path: String): Map<String, String> {
            val x = zip[path] ?: return emptyMap()
            val m = HashMap<String, String>()
            for (r in children(parse(x).documentElement)) if (r.getAttribute("TargetMode") != "External") m[r.getAttribute("Id")] = r.getAttribute("Target")
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

        fun altContent(ac: Element): Element? {
            val choice = children(ac).firstOrNull { it.localName == "Choice" }
            val fallback = children(ac).firstOrNull { it.localName == "Fallback" }
            return when {
                choice != null && (descendants(choice, "blip").isNotEmpty() || descendants(choice, "txbxContent").isNotEmpty()) -> choice
                fallback != null -> fallback
                else -> choice
            }
        }

        fun esc(s: String) = DocxConverter.esc(s)
    }

    companion object {
        private val HEX = Regex("[0-9A-Fa-f]{6}")
        private val HEADING = Regex("^(heading|제목)\\s*(\\d)$")
        private val GANADA = listOf("가", "나", "다", "라", "마", "바", "사", "아", "자", "차", "카", "타", "파", "하")
        private val CHOSUNG = listOf("ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ")
        private val HIGHLIGHT = mapOf(
            "yellow" to "FFFF00", "green" to "00FF00", "cyan" to "00FFFF", "magenta" to "FF00FF", "lightGray" to "D3D3D3", "red" to "FF0000",
            "blue" to "0000FF", "darkYellow" to "808000", "darkGray" to "808080", "darkGreen" to "008000", "darkBlue" to "000080",
        )
    }
}

/** 로마 숫자(각주·쪽 번호 형식). */
internal object PagedComposerNum {
    fun roman(n: Int): String {
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n; val b = StringBuilder()
        for (i in v.indices) while (x >= v[i]) { b.append(s[i]); x -= v[i] }
        return b.toString()
    }
}
