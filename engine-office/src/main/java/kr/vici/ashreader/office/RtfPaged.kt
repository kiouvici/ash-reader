// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import kr.vici.ashreader.office.WordHtml.Item

/**
 * RTF(‘.doc’ 이름의 RTF 포함) → Word 원본 쪽 묶음(F-30). 이북식 [RtfConverter] 와 달리 서식·쪽 설정을 옮긴다.
 *  - 쪽: \paperw/\paperh/\marg*(\*sxn 구역 값 포함), \headery/\footery, \landscape, \titlepg, \pgnstarts
 *  - 문단: \q*, \li/\ri/\fi, \sb/\sa, \sl/\slmult, \tx(\tqr/\tqc/\tldot), \pagebb, \page
 *  - 글자: \f(글꼴표·문자 집합), \fs, \b, \i, \ul, \strike, \cf, \highlight/\cb, \super/\sub, \caps, \v
 *  - 표: \trowd/\cellx/\cell/\row, \trqc/\trleft, \trrh, 칸·행 테두리(\clbrdr*, \trbrdr*), \clcbpat, \clvmgf/\clvmrg, \clmgf/\clmrg, \clvertal*
 *  - 그림: \pict(\pngblip/\jpegblip, 16진·\bin), 크기 \picwgoal·\picscale. EMF/WMF 는 그리기 목록 → 앱이 PNG 로, \dibitmap 은 BMP 로
 *  - 머리글/바닥글(\header·\headerf·\headerl·\headerr, footer 동일), 각주(\footnote), 쪽 번호 필드(\field PAGE/NUMPAGES), \pntext·\listtext 번호 글자
 */
object RtfPaged {
    /** \* 가 붙어도 처리하는 목적지(그 밖의 \* 목적지는 통째로 건너뜀). */
    private val STAR_KNOWN = setOf("fldinst", "shppict", "footnote", "pict", "pn", "listtext", "pntext")

    private class CP(
        var font: Int = -1, var size: Float = 12f, var b: Boolean = false, var i: Boolean = false, var u: Boolean = false, var strike: Boolean = false,
        var color: Int = 0, var bg: Int = 0, var vert: String? = null, var caps: Boolean = false, var hidden: Boolean = false,
    ) { fun copy() = CP(font, size, b, i, u, strike, color, bg, vert, caps, hidden) }

    private class PPr(
        var jc: String = "left", var li: Float = 0f, var ri: Float = 0f, var fi: Float = 0f, var sb: Float = 0f, var sa: Float = 0f,
        var sl: Int = 0, var slmult: Boolean = false, var tabs: MutableList<WordHtml.TabStop> = ArrayList(), var intbl: Boolean = false, var pagebb: Boolean = false,
        var pendTabAlign: String = "left", var pendLeader: String? = null,
    ) { fun copy() = PPr(jc, li, ri, fi, sb, sa, sl, slmult, ArrayList(tabs), intbl, pagebb, pendTabAlign, pendLeader) }

    private class CellDef(var right: Float = 0f, var vmf: Boolean = false, var vmr: Boolean = false, var hmf: Boolean = false, var hmr: Boolean = false,
                          val borders: HashMap<String, WordHtml.Border> = HashMap(), var bg: Int = -1, var vAlign: String? = null)

    /** 출력 그릇: 본문·머리글·각주 각각. */
    private class Out {
        val body = StringBuilder()
        val items = ArrayList<Item>()
        val cells = ArrayList<String>()
        val cellBuf = StringBuilder()
        val rows = ArrayList<Pair<List<CellDef>, List<String>>>()
        var rowHeight = 0f
        var rowLeft = 0f; var rowAlign: String? = null
        var tableDefs = ArrayList<CellDef>()
        var tableRowHeights = ArrayList<Float>()
    }

    private enum class Dest { NONE, SKIP, FONTTBL, COLORTBL, PICT, HEADER, FOOTNOTE, FLDINST, FLDRSLT }

    private class St(var dest: Dest, var cp: CP, var pp: PPr, var uc: Int, var hfKind: String? = null, var startedHere: Boolean = false)

    fun convert(bytes: ByteArray): PagedWord = Parser(bytes).run()

    private class Parser(val b: ByteArray) {
        val fonts = HashMap<Int, Pair<String, Int>>() // id → (name, charset)
        var curFontId = 0; var curFontCs = 0; val curFontName = StringBuilder()
        val colors = ArrayList<String?>()
        var cr = -1; var cg = -1; var cb = -1
        var ansi: Charset = cs1252()
        var deff = 0
        // 쪽
        var paperW = 612f; var paperH = 792f; var mL = 90f; var mR = 90f; var mT = 72f; var mB = 72f; var hdrY = 36f; var ftrY = 36f
        var landscape = false; var titlePg = false; var pgStart = 1
        val headers = HashMap<String, String>(); val footers = HashMap<String, String>()
        val notes = ArrayList<Pair<String, String>>()
        val images = LinkedHashMap<String, ByteArray>()
        val metas = LinkedHashMap<String, PagedWord.MetaPic>()
        var imgSeq = 0; var paras = 0; var tables = 0; var tabParas = 0; var pageBreaks = 0
        val sections = ArrayList<PagedWord.Section>()

        val outs = ArrayList<Out>().apply { add(Out()) }
        val out get() = outs.last()
        val stack = ArrayList<St>()
        var st = St(Dest.NONE, CP(), PPr(), 1)
        var skipChars = 0
        var starred = false
        val pending = ByteArrayOutputStream()
        val text = StringBuilder()
        // 그림
        val pictHex = ByteArrayOutputStream(); var pictBin: ByteArray? = null
        var pictType = ""; var picw = 0; var pich = 0; var picwg = 0; var pichg = 0; var scx = 100; var scy = 100
        // 필드
        val fldInst = StringBuilder(); var fldIsPage: String? = null
        // 표 정의(현재 \trowd)
        var defs = ArrayList<CellDef>(); var curDef = CellDef(); var borderTarget: String? = null; var lastBorder: WordHtml.Border? = null
        var rowBorders = HashMap<String, WordHtml.Border>()

        fun cw(): Float = (if (landscape && paperH > paperW) paperH else paperW) - mL - mR

        fun run(): PagedWord {
            var i = 0
            val n = b.size
            var groupStart = false
            while (i < n) {
                val c = b[i].toInt() and 0xFF
                when (c) {
                    '{'.code -> { flushText(); stack += st; st = St(st.dest, st.cp.copy(), st.pp.copy(), st.uc, st.hfKind); groupStart = true; i++; continue }
                    '}'.code -> { flushText(); endGroup(); groupStart = false; i++; continue }
                    '\\'.code -> {
                        i++
                        if (i >= n) break
                        val c2 = b[i].toInt() and 0xFF
                        if (c2 < 0x80 && c2.toChar().isLetter()) {
                            val ws = i
                            while (i < n && (b[i].toInt() and 0xFF).let { it < 0x80 && it.toChar().isLetter() }) i++
                            val word = String(b, ws, i - ws, Charsets.US_ASCII)
                            var param: Int? = null
                            if (i < n && (b[i] == '-'.code.toByte() || (b[i].toInt() and 0xFF).toChar().isDigit())) {
                                val ps = i; i++
                                while (i < n && (b[i].toInt() and 0xFF).toChar().isDigit()) i++
                                param = String(b, ps, i - ps, Charsets.US_ASCII).toIntOrNull()
                            }
                            if (i < n && b[i] == ' '.code.toByte()) i++
                            if (word == "bin") {
                                val len = (param ?: 0).coerceIn(0, n - i)
                                if (st.dest == Dest.PICT) pictBin = b.copyOfRange(i, i + len)
                                i += len
                                groupStart = false
                                continue
                            }
                            groupStart = false
                            val star = starred
                            starred = false
                            if (star && word !in STAR_KNOWN) { flushText(); st.dest = Dest.SKIP; continue }
                            control(word, param)
                            continue
                        }
                        groupStart = false
                        when (c2.toChar()) {
                            '\'' -> {
                                val hex = if (i + 2 < n) String(b, i + 1, 2, Charsets.US_ASCII) else ""
                                i += 3
                                val v = hex.toIntOrNull(16) ?: continue
                                if (skipChars > 0) { skipChars--; continue }
                                if (textDest()) pending.write(v)
                            }
                            '*' -> { starred = true; i++ }
                            '~' -> { emitChar(' '); i++ }
                            '_' -> { emitChar('‑'); i++ }
                            '-' -> i++
                            '\r', '\n' -> { i++; paragraphEnd() }
                            else -> { emitChar(c2.toChar()); i++ }
                        }
                        continue
                    }
                    '\r'.code, '\n'.code -> { i++; continue }
                    else -> {
                        groupStart = false
                        if (st.dest == Dest.PICT) {
                            val h = Character.digit(c, 16)
                            if (h >= 0) pictHex.write(h)
                            i++; continue
                        }
                        if (skipChars > 0) { skipChars--; i++; continue }
                        when (st.dest) {
                            Dest.FONTTBL -> if (c == ';'.code) endFont() else curFontName.append(c.toChar())
                            Dest.COLORTBL -> if (c == ';'.code) { colors += if (cr < 0) null else "%02X%02X%02X".format(cr, cg, cb); cr = -1; cg = -1; cb = -1 }
                            else -> if (textDest()) { if (c < 0x80) { flushBytes(); text.append(c.toChar()) } else pending.write(c) }
                        }
                        i++
                    }
                }
            }
            flushText()
            paragraphEnd(force = false)
            flushTable()
            endSection()
            return PagedWord(sections, notes, images, PagedWord.Stats(sections.size, paras, tables, imgSeq, 0, headers.size, footers.size, notes.size, tabParas, pageBreaks, metas.size), metas)
        }

        fun textDest() = st.dest == Dest.NONE || st.dest == Dest.HEADER || st.dest == Dest.FOOTNOTE || st.dest == Dest.FLDRSLT || st.dest == Dest.FLDINST

        fun charsetOf(font: Int): Charset {
            val cs = fonts[font]?.second ?: return ansi
            return when (cs) {
                129 -> cp949() ?: ansi
                128 -> runCatching { Charset.forName("Shift_JIS") }.getOrDefault(ansi)
                134 -> runCatching { Charset.forName("GBK") }.getOrDefault(ansi)
                136 -> runCatching { Charset.forName("Big5") }.getOrDefault(ansi)
                else -> ansi
            }
        }

        fun flushBytes() {
            if (pending.size() > 0) { text.append(String(pending.toByteArray(), charsetOf(st.cp.font))); pending.reset() }
        }

        fun flushText() {
            flushBytes()
            if (text.isEmpty()) return
            val s = text.toString(); text.setLength(0)
            if (st.dest == Dest.FLDINST) { fldInst.append(s); return }
            if (st.dest == Dest.FLDRSLT && fldIsPage != null) return
            if (st.cp.hidden) return
            out.items += Item.Text(run(s))
        }

        fun run(s: String): WordHtml.Run {
            val cp = st.cp
            val fname = fonts[if (cp.font >= 0) cp.font else deff]?.first
            return WordHtml.Run(if (cp.caps) s.uppercase() else s, fname, fname, cp.size, cp.b, cp.i, cp.u, cp.strike,
                colors.getOrNull(cp.color)?.takeIf { it != "000000" }, colors.getOrNull(cp.bg)?.takeIf { cp.bg > 0 }, cp.vert)
        }

        fun emitChar(ch: Char) {
            if (skipChars > 0) { skipChars--; return }
            if (!textDest()) return
            flushBytes(); text.append(ch)
        }

        fun endFont() {
            val name = curFontName.toString().trim().trimEnd(';').trim()
            fonts[curFontId] = name to curFontCs
            curFontName.setLength(0); curFontCs = 0
        }

        fun control(w: String, p: Int?) {
            // 목적지
            when (w) {
                "fonttbl" -> { st.dest = Dest.FONTTBL; return }
                "colortbl" -> { st.dest = Dest.COLORTBL; return }
                "stylesheet", "info", "listtable", "listoverridetable", "rsidtbl", "generator", "xmlnstbl", "themedata", "colorschememapping",
                "datastore", "latentstyles", "pgdsctbl", "filetbl", "revtbl", "bkmkstart", "bkmkend", "annotation", "nonshppict", "xe", "tc",
                "pn", "atnid", "atnauthor", "fldtype", "mmathPr", "shpinst", "userprops", "docvar", "panose", "falt", "fname", "leveltext", "levelnumbers" -> { flushText(); st.dest = Dest.SKIP; return }
                "header", "headerl", "headerr", "headerf", "footer", "footerl", "footerr", "footerf" -> {
                    flushText(); st.dest = Dest.HEADER; st.hfKind = w; st.startedHere = true; outs += Out(); st.pp = PPr(); st.cp = CP(); return
                }
                "footnote" -> { flushText(); st.dest = Dest.FOOTNOTE; st.hfKind = "fn"; st.startedHere = true; outs += Out(); st.pp = PPr(); return }
                "pict" -> { flushText(); st.dest = Dest.PICT; st.startedHere = true; pictHex.reset(); pictBin = null; pictType = ""; picw = 0; pich = 0; picwg = 0; pichg = 0; scx = 100; scy = 100; return }
                "fldinst" -> { flushText(); st.dest = Dest.FLDINST; fldInst.setLength(0); fldIsPage = null; return }
                "fldrslt" -> {
                    flushText()
                    val ins = fldInst.toString().trim().uppercase()
                    fldIsPage = when {
                        ins.startsWith("PAGE") && !ins.startsWith("PAGEREF") -> PagedWord.PAGE
                        ins.startsWith("NUMPAGES") || ins.startsWith("SECTIONPAGES") -> PagedWord.PAGES
                        else -> null
                    }
                    if (st.dest != Dest.SKIP) st.dest = Dest.FLDRSLT
                    fldIsPage?.let { out.items += Item.Text(run(it)) }
                    return
                }
            }
            if (st.dest == Dest.SKIP) return
            if (st.dest == Dest.FONTTBL) {
                when (w) { "f" -> { if (curFontName.isNotBlank()) endFont(); curFontId = p ?: 0 }; "fcharset" -> curFontCs = p ?: 0 }
                return
            }
            if (st.dest == Dest.COLORTBL) {
                when (w) { "red" -> cr = p ?: 0; "green" -> cg = p ?: 0; "blue" -> cb = p ?: 0 }
                if (cg < 0 && cr >= 0) cg = 0; if (cb < 0 && cr >= 0) cb = 0
                return
            }
            if (st.dest == Dest.PICT) {
                when (w) {
                    "pngblip", "jpegblip", "emfblip", "wmetafile", "dibitmap", "wbitmap", "macpict", "pmmetafile" -> pictType = w
                    "picw" -> picw = p ?: 0; "pich" -> pich = p ?: 0; "picwgoal" -> picwg = p ?: 0; "pichgoal" -> pichg = p ?: 0
                    "picscalex" -> scx = p ?: 100; "picscaley" -> scy = p ?: 100
                }
                return
            }
            val cp = st.cp; val pp = st.pp
            when (w) {
                "ansicpg" -> p?.let { ansi = codepage(it) ?: ansi }
                "deff" -> deff = p ?: 0
                "uc" -> st.uc = (p ?: 1).coerceAtLeast(0)
                "u" -> { val v = p ?: 0; emitChar((if (v < 0) v + 65536 else v).toChar()); skipChars = st.uc }
                // 쪽
                "paperw", "pgwsxn" -> paperW = tw(p); "paperh", "pghsxn" -> paperH = tw(p)
                "margl", "marglsxn" -> mL = tw(p); "margr", "margrsxn" -> mR = tw(p); "margt", "margtsxn" -> mT = kotlin.math.abs(tw(p)); "margb", "margbsxn" -> mB = kotlin.math.abs(tw(p))
                "headery" -> hdrY = tw(p); "footery" -> ftrY = tw(p)
                "landscape", "lndscpsxn" -> landscape = true
                "titlepg" -> titlePg = true
                "pgnstarts", "pgnstart" -> pgStart = p ?: 1
                "sect" -> { flushText(); paragraphEnd(force = false); flushTable(); endSection() }
                "sectd" -> {}
                // 문단
                "pard" -> { flushText(); st.pp = PPr(); }
                "par" -> { flushText(); paragraphEnd() }
                "ql" -> pp.jc = "left"; "qc" -> pp.jc = "center"; "qr" -> pp.jc = "right"; "qj", "qd" -> pp.jc = "both"
                "li", "lin" -> pp.li = tw(p); "ri", "rin" -> pp.ri = tw(p); "fi" -> pp.fi = tw(p)
                "sb" -> pp.sb = tw(p); "sa" -> pp.sa = tw(p)
                "sl" -> pp.sl = p ?: 0; "slmult" -> pp.slmult = (p ?: 0) == 1
                "tqr" -> pp.pendTabAlign = "right"; "tqc" -> pp.pendTabAlign = "center"; "tqdec" -> pp.pendTabAlign = "decimal"
                "tldot" -> pp.pendLeader = "dot"; "tlhyph" -> pp.pendLeader = "hyphen"; "tlul", "tlth" -> pp.pendLeader = "underscore"; "tlmdot" -> pp.pendLeader = "middleDot"
                "tx", "tb" -> { pp.tabs += WordHtml.TabStop(tw(p), pp.pendTabAlign, pp.pendLeader); pp.pendTabAlign = "left"; pp.pendLeader = null }
                "intbl" -> pp.intbl = true
                "pagebb" -> pp.pagebb = true
                "page" -> { flushText(); out.items += Item.PageBreak; pageBreaks++ }
                "line" -> { flushText(); out.items += Item.LineBreak }
                "tab" -> { flushText(); out.items += Item.Tab }
                "emdash" -> emitChar('—'); "endash" -> emitChar('–'); "bullet" -> emitChar('•')
                "lquote" -> emitChar('‘'); "rquote" -> emitChar('’'); "ldblquote" -> emitChar('“'); "rdblquote" -> emitChar('”')
                "emspace" -> emitChar(' '); "enspace" -> emitChar(' '); "qmspace" -> emitChar(' ')
                "chpgn" -> { flushText(); out.items += Item.Text(run(PagedWord.PAGE)) }
                "chftn" -> if (st.dest == Dest.FOOTNOTE) { flushText(); out.items += Item.Raw("<span style=\"font-size:${WordHtml.f(cp.size)}pt\"><a class=\"nr\">${notes.size + 1}</a></span>", cp.size * 0.4f) } // 본문 쪽 번호는 각주 묶음이 단다
                // 글자
                "plain" -> { flushText(); st.cp = CP() }
                "f" -> { flushText(); cp.font = p ?: 0 }
                "fs" -> { flushText(); cp.size = (p ?: 24) / 2f }
                "b" -> { flushText(); cp.b = p != 0 }
                "i" -> { flushText(); cp.i = p != 0 }
                "ul", "uld", "uldb", "ulw", "ulth", "uldash" -> { flushText(); cp.u = p != 0 }
                "ulnone" -> { flushText(); cp.u = false }
                "strike", "striked" -> { flushText(); cp.strike = p != 0 }
                "cf" -> { flushText(); cp.color = p ?: 0 }
                "highlight", "cb", "chcbpat" -> { flushText(); cp.bg = p ?: 0 }
                "super" -> { flushText(); cp.vert = "superscript" }; "sub" -> { flushText(); cp.vert = "subscript" }; "nosupersub" -> { flushText(); cp.vert = null }
                "caps" -> { flushText(); cp.caps = p != 0 }
                "v" -> { flushText(); cp.hidden = p != 0 }
                // 표
                "trowd" -> { flushText(); defs = ArrayList(); curDef = CellDef(); rowBorders = HashMap(); out.rowHeight = 0f; out.rowLeft = 0f; out.rowAlign = null; borderTarget = null }
                "trleft" -> out.rowLeft = tw(p)
                "trqc" -> out.rowAlign = "center"; "trqr" -> out.rowAlign = "right"
                "trrh" -> out.rowHeight = kotlin.math.abs(tw(p))
                "clvmgf" -> curDef.vmf = true; "clvmrg" -> curDef.vmr = true; "clmgf" -> curDef.hmf = true; "clmrg" -> curDef.hmr = true
                "clvertalc" -> curDef.vAlign = "center"; "clvertalb" -> curDef.vAlign = "bottom"
                "clcbpat" -> curDef.bg = p ?: -1
                "clbrdrt" -> borderTarget = "c:top"; "clbrdrb" -> borderTarget = "c:bottom"; "clbrdrl" -> borderTarget = "c:left"; "clbrdrr" -> borderTarget = "c:right"
                "trbrdrt" -> borderTarget = "r:top"; "trbrdrb" -> borderTarget = "r:bottom"; "trbrdrl" -> borderTarget = "r:left"; "trbrdrr" -> borderTarget = "r:right"
                "trbrdrh" -> borderTarget = "r:insideH"; "trbrdrv" -> borderTarget = "r:insideV"
                "brdrs", "brdrth", "brdrsh", "brdrhair", "brdrdb", "brdrdot", "brdrdash", "brdrdashsm", "brdrnone", "brdrnil" -> {
                    val style = when (w) { "brdrdb" -> "double"; "brdrdot" -> "dotted"; "brdrdash", "brdrdashsm" -> "dashed"; else -> "solid" }
                    setBorder(if (w == "brdrnone" || w == "brdrnil") WordHtml.Border(0f, "none", "000000") else WordHtml.Border(0.5f, style, "000000"))
                }
                "brdrw" -> lastBorder?.let { lb -> setBorder(WordHtml.Border(maxOf((p ?: 10) / 20f, 0.25f), lb.style, lb.color)) }
                "brdrcf" -> lastBorder?.let { lb -> setBorder(WordHtml.Border(lb.width, lb.style, colors.getOrNull(p ?: 0) ?: "000000")) }
                "cellx" -> { curDef.right = tw(p); defs += curDef; curDef = CellDef(); borderTarget = null }
                "cell", "nestcell" -> { flushText(); paragraphEnd(force = false, inCell = true); out.cells += out.cellBuf.toString(); out.cellBuf.setLength(0) }
                "row", "nestrow" -> { flushText(); endRow() }
            }
        }

        fun setBorder(bd: WordHtml.Border) {
            lastBorder = bd
            val t = borderTarget ?: return
            if (t.startsWith("c:")) curDef.borders[t.substring(2)] = bd else rowBorders[t.substring(2)] = bd
        }

        fun tw(p: Int?) = (p ?: 0) / 20f

        fun paragraphEnd(force: Boolean = true, inCell: Boolean = false) {
            flushText()
            val o = out
            if (!force && o.items.isEmpty()) return
            val pp = st.pp
            val cell = inCell || pp.intbl
            if (!cell) flushTable()
            paras++
            val lineRule: String; val lineVal: Float
            when {
                pp.sl == 0 -> { lineRule = "auto"; lineVal = 1f }
                pp.sl < 0 -> { lineRule = "exact"; lineVal = -pp.sl / 20f }
                pp.slmult -> { lineRule = "auto"; lineVal = pp.sl / 240f }
                else -> { lineRule = "atLeast"; lineVal = pp.sl / 20f }
            }
            val fname = fonts[if (st.cp.font >= 0) st.cp.font else deff]?.first
            val para = WordHtml.Para(
                ArrayList(o.items), pp.jc, pp.li, pp.ri, pp.fi, pp.sb, pp.sa, lineRule, lineVal, pp.tabs.toList(), 36f,
                st.cp.size, fname, pageBreakBefore = pp.pagebb && !cell,
            )
            o.items.clear()
            val target = if (cell) o.cellBuf else o.body
            tabParas += WordHtml.para(para, if (cell) 200f else cw(), target)
        }

        fun endRow() {
            val o = out
            if (o.cellBuf.isNotEmpty() || o.items.isNotEmpty()) { paragraphEnd(force = false, inCell = true); o.cells += o.cellBuf.toString(); o.cellBuf.setLength(0) }
            val d = ArrayList(defs)
            // \trbrdr* (행 테두리)는 Word 도 그리지 않는다(칸 테두리 \clbrdr* 만 그림 — real-03 기준 PDF 확인)
            o.rows += d to ArrayList(o.cells)
            o.tableRowHeights += o.rowHeight
            o.cells.clear()
        }

        fun flushTable() {
            val o = out
            if (o.rows.isEmpty()) return
            tables++
            // 열 경계: 모든 행의 \cellx 를 모아 격자로
            val bounds = sortedSetOf<Float>()
            for ((d, _) in o.rows) { var x = o.rowLeft; bounds += x; for (cd in d) { bounds += cd.right; x = cd.right } }
            val edges = bounds.toList()
            val cols = edges.zipWithNext { a, b -> (b - a).coerceAtLeast(1f) }
            val rows = ArrayList<WordHtml.Row>()
            val grid = o.rows.map { (d, cells) ->
                var left = o.rowLeft
                d.mapIndexed { k, cd ->
                    val c0 = edges.indexOfFirst { kotlin.math.abs(it - left) < 1f }.coerceAtLeast(0)
                    val c1 = edges.indexOfFirst { kotlin.math.abs(it - cd.right) < 1f }.let { if (it <= c0) c0 + 1 else it }
                    left = cd.right
                    Triple(cd, c0, c1 - c0) to (cells.getOrNull(k) ?: "")
                }
            }
            for ((ri, r) in grid.withIndex()) {
                val cells = ArrayList<WordHtml.Cell>()
                var skipMerged = false
                for ((t, html) in r) {
                    val (cd, col, span) = t
                    if (cd.hmr) { skipMerged = true; continue }
                    var rowspan = 1
                    if (cd.vmf) {
                        var k = ri + 1
                        while (k < grid.size && grid[k].any { it.first.second == col && it.first.first.vmr }) { rowspan++; k++ }
                    }
                    // 가로 병합(\clmgf + \clmrg): 뒤 칸 수만큼 넓힌다
                    var sp = span
                    if (cd.hmf) { val idx = r.indexOfFirst { it.first === t }; var k = idx + 1; while (k < r.size && r[k].first.first.hmr) { sp += r[k].first.third; k++ } }
                    val b = cd.borders
                    cells += WordHtml.Cell(col, sp, rowspan, html, b["top"], b["right"], b["bottom"], b["left"],
                        colors.getOrNull(cd.bg)?.takeIf { cd.bg >= 0 && it != "FFFFFF" }, cd.vAlign, 5.4f, 5.4f, 0f, 0f, covered = cd.vmr)
                }
                if (skipMerged) {}
                rows += WordHtml.Row(cells, o.tableRowHeights.getOrNull(ri) ?: 0f)
            }
            val indent = edges.first()
            WordHtml.table(WordHtml.Table(cols, rows, if (o.rowAlign == null) indent else 0f, o.rowAlign), o.body)
            o.rows.clear(); o.tableRowHeights.clear()
        }

        fun endGroup() {
            val ended = st
            st = if (stack.isEmpty()) st else stack.removeAt(stack.size - 1)
            if (!ended.startedHere) return
            when (ended.dest) {
                Dest.HEADER -> {
                    // 머리글 안의 마지막 문단·표 마무리
                    val saved = st; st = ended
                    paragraphEnd(force = false); flushTable()
                    st = saved
                    val o = outs.removeAt(outs.size - 1)
                    val k = ended.hfKind ?: "header"
                    val kind = when { k.endsWith("f") -> "first"; k.endsWith("l") -> "even"; else -> "default" }
                    if (k.startsWith("header")) headers[kind] = o.body.toString() else footers[kind] = o.body.toString()
                }
                Dest.FOOTNOTE -> {
                    val saved = st; st = ended
                    paragraphEnd(force = false); flushTable()
                    st = saved
                    val o = outs.removeAt(outs.size - 1)
                    val id = "r" + (notes.size + 1)
                    notes += id to o.body.toString()
                    out.items += Item.Raw("<span style=\"font-size:${WordHtml.f(st.cp.size)}pt\"><a class=\"nr\" href=\"#ash-fn-$id\">${notes.size}</a></span>", st.cp.size * 0.4f)
                }
                Dest.PICT -> picture()
                else -> {}
            }
        }

        fun picture() {
            val data = pictBin ?: pictHex.toByteArray().let { h ->
                val o = ByteArray(h.size / 2)
                for (k in o.indices) o[k] = ((h[2 * k].toInt() shl 4) or h[2 * k + 1].toInt()).toByte()
                o
            }
            val wPt = (if (picwg > 0) picwg / 20f else picw * 0.75f) * scx / 100f
            val hPt = (if (pichg > 0) pichg / 20f else pich * 0.75f) * scy / 100f
            if ((pictType == "emfblip" || pictType == "wmetafile") && data.isNotEmpty() && (Metafile.isEmf(data) || Metafile.isWmf(data))) {
                val name = "images/rtf${imgSeq++}.${if (pictType == "emfblip") "emf" else "wmf"}.png"
                metas[name] = PagedWord.MetaPic(data, wPt, hPt)
                out.items += Item.Raw("<img src=\"$name\" alt=\"\" style=\"width:${WordHtml.f(wPt)}pt;height:${WordHtml.f(hPt)}pt\"/>", wPt, hPt)
                return
            }
            if (pictType == "dibitmap" && data.isNotEmpty()) {
                Metafile.dibToBmp(data)?.let { bmp ->
                    val name = "images/rtf${imgSeq++}.bmp"
                    images[name] = bmp
                    out.items += Item.Raw("<img src=\"$name\" alt=\"\" style=\"width:${WordHtml.f(wPt)}pt;height:${WordHtml.f(hPt)}pt\"/>", wPt, hPt)
                    return
                }
            }
            val ext = when (pictType) { "pngblip" -> "png"; "jpegblip" -> "jpg"; else -> null }
            if (ext == null || data.isEmpty()) {
                out.items += Item.Raw("<span class=\"img-unsupported\" style=\"font-size:9pt\">[그림]</span>", 30f, 12f)
                return
            }
            val name = "images/rtf${imgSeq++}.$ext"
            images[name] = data
            out.items += Item.Raw("<img src=\"$name\" alt=\"\" style=\"width:${WordHtml.f(wPt)}pt;height:${WordHtml.f(hPt)}pt\"/>", wPt, hPt)
        }

        fun endSection() {
            flushText()
            val o = outs.first()
            if (o.items.isNotEmpty()) paragraphEnd(force = false)
            // 끝의 빈 구역(\sect 뒤 내용 없음)은 쪽을 만들지 않는다
            if (sections.isNotEmpty() && o.body.isBlank()) return
            var w = paperW; var h = paperH
            if (landscape && h > w) { val t = w; w = h; h = t }
            sections += PagedWord.Section(w, h, mT, mR, mB, mL, hdrY, ftrY, titlePg, false, if (sections.isEmpty()) pgStart else -1, "decimal",
                o.body.toString(), HashMap(headers), HashMap(footers), emptyList())
            o.body.setLength(0)
        }

        fun cs1252(): Charset = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)
        fun cp949(): Charset? = runCatching { Charset.forName("x-windows-949") }.getOrNull() ?: runCatching { Charset.forName("MS949") }.getOrNull()
        fun codepage(cp: Int): Charset? = when (cp) {
            949 -> cp949()
            65001 -> Charsets.UTF_8
            else -> runCatching { Charset.forName("windows-$cp") }.getOrNull() ?: runCatching { Charset.forName("cp$cp") }.getOrNull()
        }
    }
}
