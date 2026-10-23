// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.mupdf

import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Context
import com.artifex.mupdf.fitz.DefaultColorSpaces
import com.artifex.mupdf.fitz.Device
import com.artifex.mupdf.fitz.DisplayList
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DocumentWriter
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Path
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.Shade
import com.artifex.mupdf.fitz.StrokeState
import com.artifex.mupdf.fitz.StructuredTextWalker
import com.artifex.mupdf.fitz.Text
import java.io.File

/**
 * Word 원본 보기(F-30) 쪽 합성기. `:engine-office` 가 쓴 쪽 묶음 폴더(paged.txt + XHTML 조각 + 그림)를 받아
 * Word 쪽 크기·여백대로 PDF 를 만든다. 안드로이드 클래스를 쓰지 않는다(호스트 JVM 에서 같은 코드로 대조 측정).
 *
 * 방법(기술설계서 3.4 보강):
 *  1. 구역 본문을 '본문 폭 × 아주 긴 높이' 한 장(갤리)으로 MuPDF 가 조판한다. 명시적 쪽 나눔은 갤리 쪽을 나눈다.
 *  2. 갤리의 줄·그림·선 위치(StructuredText)를 보고 쪽 높이 안에서 '어떤 줄·표 칸도 가르지 않는' 자리를 골라 자른다.
 *     각주가 있으면 그 쪽의 각주 높이만큼 본문 높이를 줄여 다시 자른다.
 *  3. 쪽마다 머리글(첫 쪽/짝수/기본)·본문 조각·각주·바닥글·떠 있는 그림을 DocumentWriter 로 그린다.
 *     쪽 번호 필드({{ASH_PAGE}}, {{ASH_PAGES}})는 쪽마다 바꿔 넣는다.
 * 결과 PDF 는 글자가 살아 있어 기존 PDF 경로(글자 선택·검색·형광펜)로 연다.
 */
object PagedComposer {
    /** 갤리 높이(pt). 쪽 나눔 없이 이보다 길면 MuPDF 가 다음 갤리 쪽으로 넘기고, 그 자리는 쪽 나눔처럼 다룬다. */
    private const val GALLEY_H = 200_000f
    private const val TOL = 1.0f

    class Result(val pages: Int, val sections: Int)

    /** 시험·측정용 기록(쪽 나눔 결과). */
    @Volatile var debug: ((String) -> Unit)? = null

    // ---------------- 쪽 묶음 목록(paged.txt) ----------------
    class Overlay(val id: String, val src: String, val x: Float, val y: Float, val w: Float, val h: Float, val relX: String, val relY: String, val behind: Boolean)
    class Section(
        val pageW: Float, val pageH: Float, val top: Float, val right: Float, val bottom: Float, val left: Float,
        val headerDist: Float, val footerDist: Float, val titlePg: Boolean, val evenOdd: Boolean, val pgStart: Int, val pgFmt: String,
        val body: String, val headers: Map<String, String>, val footers: Map<String, String>, val overlays: List<Overlay>,
    )
    class Manifest(val sections: List<Section>, val notesFile: String?, val noteIds: List<String>)

    fun readManifest(f: File): Manifest {
        val secs = ArrayList<Section>()
        var notes: String? = null; var noteIds = emptyList<String>()
        var cur: HashMap<String, Any>? = null
        val hs = HashMap<String, String>(); val fs = HashMap<String, String>(); val ovs = ArrayList<Overlay>()
        for (raw in f.readLines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val t = line.split(' ')
            when (t[0]) {
                "notes" -> { notes = t.getOrNull(1)?.takeIf { it != "-" }; noteIds = t.getOrNull(2)?.takeIf { it.isNotEmpty() && it != "-" }?.split(',') ?: emptyList() }
                "section" -> { cur = HashMap(); hs.clear(); fs.clear(); ovs.clear() }
                "geom" -> cur!!["geom"] = t.drop(1).map { it.toFloat() }
                "flags" -> cur!!["flags"] = t.drop(1)
                "body" -> cur!!["body"] = t[1]
                "header" -> hs[t[1]] = t[2]
                "footer" -> fs[t[1]] = t[2]
                "overlay" -> ovs += Overlay(t[1], t[2], t[3].toFloat(), t[4].toFloat(), t[5].toFloat(), t[6].toFloat(), t[7], t[8], t[9] == "1")
                "end" -> {
                    val c = cur ?: continue
                    @Suppress("UNCHECKED_CAST") val g = c["geom"] as List<Float>
                    @Suppress("UNCHECKED_CAST") val fl = c["flags"] as List<String>
                    secs += Section(g[0], g[1], g[2], g[3], g[4], g[5], g[6], g[7], fl[0] == "1", fl[1] == "1", fl[2].toInt(), fl[3],
                        c["body"] as String, HashMap(hs), HashMap(fs), ArrayList(ovs))
                    cur = null
                }
            }
        }
        return Manifest(secs, notes, noteIds)
    }

    // ---------------- 조판 결과 ----------------
    private class Item(val y0: Float, val y1: Float, val vector: Boolean)
    private class Mark(val uri: String, val y: Float, val x: Float)
    private class GPage(val list: DisplayList, val items: List<Item>, val marks: List<Mark>) {
        val bottom: Float = items.maxOfOrNull { it.y1 } ?: 0f
        val top: Float = items.minOfOrNull { it.y0 } ?: 0f
    }
    private class Laid(val doc: Document, val pages: List<GPage>) {
        fun destroy() { pages.forEach { it.list.destroy() }; doc.destroy() }
    }

    private fun layout(file: File, width: Float, fontCss: String): Laid {
        val doc = synchronized(MupdfLock) {
            Context.setUserCSS(fontCss)
            val d = Document.openDocument(file.absolutePath)
            try { d.layout(width, GALLEY_H, 11f) } catch (e: Throwable) { d.destroy(); throw e }
            d
        }
        val out = ArrayList<GPage>()
        try {
            for (i in 0 until doc.countPages()) {
                val page = doc.loadPage(i)
                try {
                    val dl = page.toDisplayList()
                    val items = ArrayList<Item>()
                    val st = dl.toStructuredText("preserve-images,vectors")
                    try {
                        st.walk(object : StructuredTextWalker {
                            override fun onImageBlock(bbox: Rect?, transform: Matrix?, image: Image?) { if (bbox != null) items += Item(bbox.y0, bbox.y1, false) }
                            override fun beginTextBlock(bbox: Rect?, flags: Int) {}
                            override fun endTextBlock() {}
                            override fun beginLine(bbox: Rect?, wmode: Int, dir: Point?) { if (bbox != null && bbox.y1 > bbox.y0) items += Item(bbox.y0, bbox.y1, false) }
                            override fun endLine() {}
                            override fun onChar(c: Int, origin: Point?, font: Font?, size: Float, q: Quad?, argb: Int, flags: Int, bidi: Int) {}
                            override fun beginStruct(standard: String?, raw: String?, index: Int) {}
                            override fun endStruct() {}
                            override fun onVector(bbox: Rect?, info: StructuredTextWalker.VectorInfo?, argb: Int) {
                                if (bbox != null && bbox.y1 >= bbox.y0 && bbox.y0 < GALLEY_H - 1f) items += Item(bbox.y0, bbox.y1, true)
                            }
                        })
                    } finally { st.destroy() }
                    val marks = (page.links ?: emptyArray()).mapNotNull { l ->
                        val u = l.uri ?: return@mapNotNull null
                        if (!u.startsWith("#ash-")) null else Mark(u.substring(1), l.bounds.y0, l.bounds.x0)
                    }
                    out += GPage(dl, items, marks)
                } finally { page.destroy() }
            }
        } catch (e: Throwable) { out.forEach { it.list.destroy() }; doc.destroy(); throw e }
        return Laid(doc, out)
    }

    /**
     * 갤리 쪽 [g]를 [y0]부터 잘라 한 쪽에 담을 끝자리(갤리 좌표). 끝까지 들어가면 g.bottom.
     * 끝자리 c 는 어떤 항목의 윗변이며, (1) c 를 가로지르는 항목이 없고 (2) c 위에서 시작한 항목은 모두 [limit] 안에서 끝나야 한다.
     * 표 칸 테두리(세로선)가 줄을 가로지르므로 표 행은 통째로 넘어간다. 그런 자리가 없으면 선을 무시하고, 그래도 없으면 [limit]에서 자른다.
     */
    private fun cutAt(g: GPage, y0: Float, limit: Float): Float {
        if (g.bottom <= limit + TOL) return maxOf(g.bottom, y0 + 0.01f)
        val items = g.items.filter { it.y1 > y0 + TOL }
        for (useVec in listOf(true, false)) {
            val use = if (useVec) items else items.filter { !it.vector }
            val cands = use.asSequence().map { it.y0 }.filter { it > y0 + 0.5f && it <= limit + TOL + 400f }.distinct().sortedDescending()
            for (c in cands) {
                if (use.any { it.y0 < c - TOL && it.y1 > c + TOL }) continue // 가로지름
                if (use.any { it.y0 < c - TOL && it.y1 > limit + TOL }) continue // 위 항목이 쪽 밖으로
                // 바로 위에 붙은 선(표 윗테두리·칸 배경 시작)은 다음 쪽으로 같이 넘긴다
                val lift = g.items.filter { it.vector && it.y0 < c && it.y0 > c - 6f && it.y1 >= c - 0.5f }.minOfOrNull { it.y0 } ?: c
                return if (lift > y0 + 0.5f && lift < c) lift - 0.01f else c
            }
        }
        return limit // 한 줄·그림이 쪽보다 큼: 그대로 자른다
    }

    // ---------------- 합성 ----------------
    private class Plan(
        val sec: Int, val galley: Int, val y0: Float, val y1: Float, val bodyTop: Float, val kind: String?, val number: Int,
        val notes: List<Int>, val notesTop: Float, val firstOfSection: Boolean,
    )

    fun compose(dir: File, fontCss: String, out: File, cancelled: () -> Boolean = { false }): Result {
        val m = readManifest(File(dir, "paged.txt"))
        require(m.sections.isNotEmpty()) { "구역 없음" }
        val laidAll = ArrayList<Laid>()
        val hfCache = HashMap<String, Laid>()
        fun hf(name: String, width: Float, page: String, pages: String): Laid {
            val key = "$name|$width|$page|$pages"
            return hfCache.getOrPut(key) {
                val src = File(dir, name).readText()
                val f = if (!src.contains("{{ASH_PAGE")) File(dir, name) else File(dir, ".hf-" + Integer.toHexString(key.hashCode()) + ".xhtml").apply {
                    writeText(src.replace("{{ASH_PAGES}}", pages).replace("{{ASH_PAGE}}", page))
                }
                layout(f, width, fontCss)
            }
        }
        try {
            // 각주: 각주마다 갤리 한 쪽
            val notesLaid = m.notesFile?.let { layout(File(dir, it), m.sections[0].let { s -> s.pageW - s.left - s.right }, fontCss).also { l -> laidAll += l } }
            val noteIndex = HashMap<String, Int>().apply { m.noteIds.forEachIndexed { i, id -> put(id, i) } }
            fun noteH(i: Int): Float = notesLaid?.pages?.getOrNull(i)?.let { g ->
                (g.marks.firstOrNull { it.uri == "ash-end" }?.y ?: g.bottom) - minOf(g.top, 0f) + 1f
            } ?: 0f
            val sepH = 10f

            val bodies = m.sections.map { s -> layout(File(dir, s.body), s.pageW - s.left - s.right, fontCss).also { laidAll += it } }

            // 1차: 쪽 나누기(머리글 높이는 번호 자리표시로 잰다)
            val plans = ArrayList<Plan>()
            var number = 0
            for ((si, s) in m.sections.withIndex()) {
                if (cancelled()) throw InterruptedException()
                if (s.pgStart >= 0) number = s.pgStart - 1
                val cw = s.pageW - s.left - s.right
                var firstOfSec = true
                for ((gi, g) in bodies[si].pages.withIndex()) {
                    // 쪽 머리의 문단 앞 간격(MuPDF 는 갤리 맨 위 여백을 버린다 — Word 는 둔다)
                    var y0 = minOf(0f, g.top).let { t -> g.items.filter { !it.vector }.minOfOrNull { it.y0 }?.let { ft -> minOf(t, ft - spaceBefore(g, ft)) } ?: t }
                    do {
                        number++
                        val kind = kindFor(s, firstOfSec, number)
                        val hH = kind?.let { k -> s.headers[k] }?.let { contentH(hf(it, cw, "9", "99")) } ?: 0f
                        val fH = kind?.let { k -> s.footers[k] }?.let { contentH(hf(it, cw, "9", "99")) } ?: 0f
                        val bodyTop = maxOf(s.top, s.headerDist + hH)
                        val bodyBottom = minOf(s.pageH - s.bottom, s.pageH - s.footerDist - fH)
                        val avail = (bodyBottom - bodyTop).coerceAtLeast(36f)
                        var cut = cutAt(g, y0, y0 + avail)
                        var notes = emptyList<Int>()
                        repeat(5) {
                            val refs = g.marks.filter { it.uri.startsWith("ash-fn-") && it.y >= y0 - TOL && it.y < cut - TOL }
                                .mapNotNull { noteIndex[it.uri.removePrefix("ash-fn-")] }.distinct()
                            if (refs == notes) return@repeat
                            notes = refs
                            val nH = if (refs.isEmpty()) 0f else sepH + refs.sumOf { noteH(it).toDouble() }.toFloat()
                            val a2 = (avail - nH).coerceAtLeast(avail * 0.25f)
                            cut = cutAt(g, y0, y0 + a2)
                        }
                        val nH = notes.sumOf { noteH(it).toDouble() }.toFloat()
                        plans += Plan(si, gi, y0, cut, bodyTop, kind, number, notes, bodyBottom - nH, firstOfSec)
                        debug?.invoke("page ${plans.size}: sec=$si galley=$gi y=${y0}..${cut} avail=$avail bodyTop=$bodyTop notes=$notes next=" +
                            g.items.filter { it.y1 > cut - 20f && it.y0 < cut + 40f }.joinToString { "[${it.y0},${it.y1}${if (it.vector) "v" else ""}]" })
                        firstOfSec = false
                        if (cut <= y0) break
                        y0 = cut - spaceBefore(g, cut)
                    } while (y0 < g.bottom - TOL)
                }
            }
            val total = plans.size

            // 2차: 그리기
            val tmp = File(out.absolutePath + ".tmp")
            val w = DocumentWriter(tmp.absolutePath, "pdf", "compress")
            try {
                val imgCache = HashMap<String, Image?>()
                for (p in plans) {
                    if (cancelled()) throw InterruptedException()
                    val s = m.sections[p.sec]
                    val cw = s.pageW - s.left - s.right
                    val dev = w.beginPage(Rect(0f, 0f, s.pageW, s.pageH))
                    val g = bodies[p.sec].pages[p.galley]
                    // 떠 있는 그림: 기준 문단이 이 쪽에 있는 것
                    val anchors = g.marks.filter { it.uri.startsWith("ash-anc-") && it.y >= p.y0 - TOL && it.y < p.y1 - TOL }
                    val ovs = anchors.mapNotNull { a -> s.overlays.firstOrNull { it.id == a.uri.removePrefix("ash-anc-") }?.let { it to (p.bodyTop + a.y - p.y0) } }
                    fun drawOverlays(behind: Boolean) {
                        for ((o, anchorY) in ovs) if (o.behind == behind) {
                            val img = imgCache.getOrPut(o.src) { runCatching { Image(File(dir, o.src).absolutePath) }.getOrNull() } ?: continue
                            val x = when (o.relX) { "page" -> o.x; else -> s.left + o.x }
                            val y = when (o.relY) { "page" -> o.y; "margin" -> s.top + o.y; else -> anchorY + o.y }
                            dev.fillImage(img, Matrix(o.w, 0f, 0f, o.h, x, y), 1f, 0)
                        }
                    }
                    drawOverlays(true)
                    // 머리글·바닥글
                    p.kind?.let { k ->
                        val num = formatNum(p.number, s.pgFmt); val tot = total.toString()
                        s.headers[k]?.let { hf(it, cw, num, tot).pages.firstOrNull()?.list?.run(dev, Matrix.Translate(s.left, s.headerDist), null) }
                        s.footers[k]?.let { name ->
                            val l = hf(name, cw, num, tot)
                            l.pages.firstOrNull()?.list?.run(dev, Matrix.Translate(s.left, s.pageH - s.footerDist - contentH(l)), null)
                        }
                    }
                    // 본문 조각
                    val clip = rectPath(0f, p.bodyTop, s.pageW, p.bodyTop + (p.y1 - p.y0))
                    dev.clipPath(clip, false, Matrix.Identity())
                    // 쪽 밖 글자는 PDF 에 넣지 않는다(잘려 안 보여도 검색·선택·듣기에 잡히므로)
                    val slice = SliceDevice(dev, p.bodyTop, p.bodyTop + (p.y1 - p.y0))
                    try {
                        g.list.run(slice, Matrix.Translate(s.left, p.bodyTop - p.y0), Rect(0f, p.bodyTop - 2f, s.pageW, p.bodyTop + (p.y1 - p.y0) + 2f), null) // 가위 영역은 쪽 좌표
                    } finally { slice.destroy() }
                    dev.popClip()
                    // 각주
                    if (p.notes.isNotEmpty() && notesLaid != null) {
                        var y = p.notesTop
                        dev.fillPath(rectPath(s.left, y + 4f, s.left + minOf(144f, cw), y + 4.5f), false, Matrix.Identity(), ColorSpace.DeviceGray, floatArrayOf(0f), 1f, 0)
                        y += sepH
                        for (ni in p.notes) {
                            val np = notesLaid.pages.getOrNull(ni) ?: continue
                            np.list.run(dev, Matrix.Translate(s.left, y - minOf(np.top, 0f)), null)
                            y += noteH(ni)
                        }
                    }
                    drawOverlays(false)
                    w.endPage()
                }
                w.close()
            } finally {
                w.destroy()
            }
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
            return Result(total, m.sections.size)
        } finally {
            laidAll.forEach { runCatching { it.destroy() } }
            hfCache.values.forEach { runCatching { it.destroy() } }
            dir.listFiles()?.filter { it.name.startsWith(".hf-") }?.forEach { it.delete() }
        }
    }

    /** 조각 높이: 끝 표지(ash-end)까지(마지막 줄 상자·아래 간격 포함). 표지가 없으면 그린 것의 아래 끝. */
    private fun contentH(l: Laid): Float = l.pages.firstOrNull()?.let { g -> g.marks.firstOrNull { it.uri == "ash-end" }?.y ?: maxOf(g.bottom, 0f) }?.coerceAtLeast(0f) ?: 0f

    /** [lineTop] 에서 시작하는 줄이 '앞 간격'이 있는 문단의 첫 줄이면 그 간격(pt). */
    private fun spaceBefore(g: GPage, lineTop: Float): Float {
        val line = g.items.filter { !it.vector && it.y0 >= lineTop - TOL && it.y0 <= lineTop + TOL }.maxByOrNull { it.y1 } ?: return 0f
        return g.marks.firstOrNull { it.uri.startsWith("ash-sb-") && it.y >= line.y0 - TOL && it.y <= line.y1 }?.uri?.removePrefix("ash-sb-")?.toFloatOrNull() ?: 0f
    }

    /** 이 쪽에 쓸 머리글/바닥글 종류. null = 없음. */
    private fun kindFor(s: Section, firstOfSection: Boolean, number: Int): String? = when {
        firstOfSection && s.titlePg -> "first"
        s.evenOdd && number % 2 == 0 -> "even"
        else -> "default"
    }

    /**
     * 본문 조각 그리기용 장치: 글자는 기준선이 [top]..[bottom] 안인 글리프만 넘기고, 나머지는 그대로 넘긴다(잘라내기는 바깥 clip).
     */
    private class SliceDevice(private val t: Device, private val top: Float, private val bottom: Float) : Device() {
        private fun filter(text: Text, ctm: Matrix): Text? {
            var kept = 0; var dropped = 0
            val out = Text()
            text.walk { font, trm, glyph, unicode, wmode ->
                val y = trm.e * ctm.b + trm.f * ctm.d + ctm.f
                if (y > top + 0.01f && y <= bottom + 0.5f) { out.showGlyph(font, trm, glyph, unicode, wmode); kept++ } else dropped++
            }
            return when {
                kept == 0 -> { out.destroy(); null }
                dropped == 0 -> { out.destroy(); text }
                else -> out
            }
        }
        private inline fun withText(text: Text, ctm: Matrix, f: (Text) -> Unit) {
            val k = filter(text, ctm) ?: return
            try { f(k) } finally { if (k !== text) k.destroy() }
        }
        override fun close() {}
        override fun fillPath(path: Path?, evenOdd: Boolean, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) = t.fillPath(path, evenOdd, ctm, cs, color, alpha, cp)
        override fun strokePath(path: Path?, stroke: StrokeState?, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) = t.strokePath(path, stroke, ctm, cs, color, alpha, cp)
        override fun clipPath(path: Path?, evenOdd: Boolean, ctm: Matrix?) = t.clipPath(path, evenOdd, ctm)
        override fun clipStrokePath(path: Path?, stroke: StrokeState?, ctm: Matrix?) = t.clipStrokePath(path, stroke, ctm)
        override fun fillText(text: Text?, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) = if (text == null || ctm == null) Unit else withText(text, ctm) { t.fillText(it, ctm, cs, color, alpha, cp) }
        override fun strokeText(text: Text?, stroke: StrokeState?, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) = if (text == null || ctm == null) Unit else withText(text, ctm) { t.strokeText(it, stroke, ctm, cs, color, alpha, cp) }
        override fun clipText(text: Text?, ctm: Matrix?) = t.clipText(text, ctm)
        override fun clipStrokeText(text: Text?, stroke: StrokeState?, ctm: Matrix?) = t.clipStrokeText(text, stroke, ctm)
        override fun ignoreText(text: Text?, ctm: Matrix?) {}
        override fun fillShade(shd: Shade?, ctm: Matrix?, alpha: Float, cp: Int) = t.fillShade(shd, ctm, alpha, cp)
        override fun fillImage(img: Image?, ctm: Matrix?, alpha: Float, cp: Int) = t.fillImage(img, ctm, alpha, cp)
        override fun fillImageMask(img: Image?, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) = t.fillImageMask(img, ctm, cs, color, alpha, cp)
        override fun clipImageMask(img: Image?, ctm: Matrix?) = t.clipImageMask(img, ctm)
        override fun popClip() = t.popClip()
        override fun beginMask(area: Rect?, luminosity: Boolean, cs: ColorSpace?, bc: FloatArray?, cp: Int) = t.beginMask(area, luminosity, cs, bc, cp)
        override fun endMask() = t.endMask()
        override fun beginGroup(area: Rect?, cs: ColorSpace?, isolated: Boolean, knockout: Boolean, blendmode: Int, alpha: Float) = t.beginGroup(area, cs, isolated, knockout, blendmode, alpha)
        override fun endGroup() = t.endGroup()
        override fun beginTile(area: Rect?, view: Rect?, xstep: Float, ystep: Float, ctm: Matrix?, id: Int, docId: Int): Int = t.beginTile(area, view, xstep, ystep, ctm, id, docId)
        override fun endTile() = t.endTile()
        override fun renderFlags(set: Int, clear: Int) = t.renderFlags(set, clear)
        override fun setDefaultColorSpaces(dcs: DefaultColorSpaces?) = t.setDefaultColorSpaces(dcs)
        override fun beginLayer(name: String?) = t.beginLayer(name)
        override fun endLayer() = t.endLayer()
        override fun beginStructure(standard: Int, raw: String?, idx: Int) = t.beginStructure(standard, raw, idx)
        override fun endStructure() = t.endStructure()
        override fun beginMetatext(meta: Int, text: String?) = t.beginMetatext(meta, text)
        override fun endMetatext() = t.endMetatext()
    }

    private fun rectPath(x0: Float, y0: Float, x1: Float, y1: Float) = Path().apply { moveTo(x0, y0); lineTo(x1, y0); lineTo(x1, y1); lineTo(x0, y1); closePath() }

    fun formatNum(n: Int, fmt: String): String = when (fmt) {
        "lowerRoman" -> roman(n).lowercase()
        "upperRoman" -> roman(n)
        "lowerLetter" -> ('a' + (n - 1).mod(26)).toString()
        "upperLetter" -> ('A' + (n - 1).mod(26)).toString()
        else -> n.toString()
    }

    private fun roman(n: Int): String {
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n; val b = StringBuilder()
        for (i in v.indices) while (x >= v[i]) { b.append(s[i]); x -= v[i] }
        return b.toString()
    }
}
