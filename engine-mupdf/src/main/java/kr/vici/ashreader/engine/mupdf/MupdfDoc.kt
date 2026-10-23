// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.mupdf

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Context
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredTextWalker
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import java.io.Closeable
import java.io.File

/**
 * :bench 등에서 쓰는 단순 API (spike). 기술 설계서 1.2의 ReflowDocument 시제품.
 *
 *     val doc = MupdfDoc.open(path, LayoutSpec(widthPt = 360f, heightPt = 640f, emPt = 12f), userCss)
 *     doc.pageCount(); doc.renderPage(0, 1080); doc.pageLines(0); doc.close()
 */
class MupdfDoc private constructor(private val doc: Document) : Closeable {

    data class LayoutSpec(val widthPt: Float, val heightPt: Float, val emPt: Float)

    fun pageCount(): Int = doc.countPages()

    /** 쪽을 폭 [widthPx]에 맞춰 렌더한다. */
    fun renderPage(i: Int, widthPx: Int): Bitmap {
        val page = doc.loadPage(i)
        try {
            val b = page.bounds
            val scale = widthPx / (b.x1 - b.x0)
            val h = ((b.y1 - b.y0) * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            val dev = AndroidDrawDevice(bmp, 0, 0, 0, 0, widthPx, h)
            try {
                page.run(dev, Matrix(scale, scale), null)
                dev.close()
            } finally {
                dev.destroy()
            }
            return bmp
        } finally {
            page.destroy()
        }
    }

    /** 쪽의 줄 텍스트(검사용). */
    fun pageLines(i: Int): List<String> {
        val page = doc.loadPage(i)
        try {
            val st = page.toStructuredText()
            val out = ArrayList<String>()
            for (block in st.blocks ?: emptyArray()) {
                for (line in block.lines ?: emptyArray()) {
                    val sb = StringBuilder()
                    for (ch in line.chars ?: emptyArray()) sb.appendCodePoint(ch.c)
                    if (sb.isNotBlank()) out += sb.toString()
                }
            }
            st.destroy()
            return out
        } finally {
            page.destroy()
        }
    }

    /**
     * 쪽 글자와 글자 상자(S-28 글자 선택·검색·형광펜).
     * [text]의 i번째 UTF-16 글자 상자 = [boxes] 4i..4i+3 (left, top, right, bottom, 쪽 크기 기준 0..1).
     * 줄 끝마다 '\n'(상자 0)을 넣는다. [aspect] = 쪽 높이/폭.
     */
    class PageText(val text: String, val boxes: FloatArray, val aspect: Float)

    fun pageText(i: Int): PageText {
        val page = doc.loadPage(i)
        try {
            val b = page.bounds
            val pw = (b.x1 - b.x0).takeIf { it > 0f } ?: 1f
            val ph = (b.y1 - b.y0).takeIf { it > 0f } ?: 1f
            val st = page.toStructuredText()
            val sb = StringBuilder()
            val boxes = ArrayList<Float>()
            fun put(l: Float, t: Float, r: Float, bt: Float) { boxes += l; boxes += t; boxes += r; boxes += bt }
            try {
                for (block in st.blocks ?: emptyArray()) {
                    for (line in block.lines ?: emptyArray()) {
                        val chars = line.chars ?: continue
                        if (chars.isEmpty()) continue
                        for (ch in chars) {
                            val q = ch.quad
                            val l = (minOf(q.ul_x, q.ll_x, q.ur_x, q.lr_x) - b.x0) / pw
                            val r = (maxOf(q.ul_x, q.ll_x, q.ur_x, q.lr_x) - b.x0) / pw
                            val t = (minOf(q.ul_y, q.ll_y, q.ur_y, q.lr_y) - b.y0) / ph
                            val bt = (maxOf(q.ul_y, q.ll_y, q.ur_y, q.lr_y) - b.y0) / ph
                            val n = sb.length
                            sb.appendCodePoint(ch.c)
                            repeat(sb.length - n) { put(l, t, r, bt) }
                        }
                        sb.append('\n'); put(0f, 0f, 0f, 0f)
                    }
                }
            } finally {
                st.destroy()
            }
            return PageText(sb.toString(), boxes.toFloatArray(), ph / pw)
        } finally {
            page.destroy()
        }
    }

    /** 목차 한 줄. [page] = 0부터, 못 찾으면 -1. */
    data class TocItem(val title: String, val depth: Int, val page: Int)

    /** 문서 목차(outline)를 펼친 목록. 목차가 없으면 빈 목록. */
    fun outline(): List<TocItem> {
        val out = ArrayList<TocItem>()
        fun walk(items: Array<com.artifex.mupdf.fitz.Outline>?, depth: Int) {
            for (o in items ?: return) {
                val p = runCatching { o.uri?.let { doc.pageNumberFromLocation(doc.resolveLink(it)) } ?: -1 }.getOrDefault(-1)
                out += TocItem(o.title?.trim().orEmpty(), depth, p)
                walk(o.down, depth + 1)
            }
        }
        walk(runCatching { doc.loadOutline() }.getOrNull(), 0)
        return out
    }

    /** 쪽에 든 그림 요약(만화 PDF 판정용). [chars] = 공백 아닌 글자 수, [cover] = 가장 큰 그림이 쪽을 덮는 비율. */
    class ImageInfo(val chars: Int, val images: Int, val cover: Float, val imageW: Int, val imageH: Int)

    fun imageInfo(i: Int): ImageInfo {
        val page = doc.loadPage(i)
        try {
            val pb = page.bounds
            val pageArea = ((pb.x1 - pb.x0) * (pb.y1 - pb.y0)).takeIf { it > 0f } ?: 1f
            var chars = 0; var images = 0; var best = 0f; var bw = 0; var bh = 0
            val st = page.toStructuredText("preserve-images")
            try {
                st.walk(object : StructuredTextWalker {
                    override fun onImageBlock(bbox: Rect?, transform: Matrix?, image: Image?) {
                        if (bbox == null) return
                        val x0 = maxOf(bbox.x0, pb.x0); val y0 = maxOf(bbox.y0, pb.y0)
                        val x1 = minOf(bbox.x1, pb.x1); val y1 = minOf(bbox.y1, pb.y1)
                        val a = if (x1 > x0 && y1 > y0) (x1 - x0) * (y1 - y0) / pageArea else 0f
                        if (a >= 0.05f) images++
                        if (a > best) { best = a; bw = image?.width ?: 0; bh = image?.height ?: 0 }
                    }
                    override fun beginTextBlock(bbox: Rect?, flags: Int) {}
                    override fun endTextBlock() {}
                    override fun beginLine(bbox: Rect?, wmode: Int, dir: Point?) {}
                    override fun endLine() {}
                    override fun onChar(c: Int, origin: Point?, font: Font?, size: Float, q: Quad?, argb: Int, flags: Int, bidi: Int) {
                        if (!Character.isWhitespace(c)) chars++
                    }
                    override fun beginStruct(standard: String?, raw: String?, index: Int) {}
                    override fun endStruct() {}
                    override fun onVector(bbox: Rect?, info: StructuredTextWalker.VectorInfo?, argb: Int) {}
                })
            } finally {
                st.destroy()
            }
            return ImageInfo(chars, images, best, bw, bh)
        } finally {
            page.destroy()
        }
    }

    /**
     * 그림만 든 PDF(스캔 만화 등)인지: 고르게 뽑은 쪽(최대 [samples]쪽)이 모두 '글자 없음 + 쪽을 거의 다 덮는 그림 1장'.
     * 판정 실패(예외)는 false.
     */
    fun isImageOnly(samples: Int = 6): Boolean = runCatching {
        val n = pageCount()
        if (n <= 0) return false
        val idx = if (n <= samples) (0 until n).toList() else (0 until samples).map { it * (n - 1) / (samples - 1) }.distinct()
        idx.all { i -> imageInfo(i).let { it.chars == 0 && it.images == 1 && it.cover >= 0.85f } }
    }.getOrDefault(false)

    /**
     * 쪽 안 그림 영역(쪽 크기 기준 0..1: left, top, right, bottom 차례로 4개씩).
     * 테마 색 변환에서 그림은 원래 색으로 되돌려 그릴 때 쓴다(F-21 '그림은 반전하지 않기', M-05).
     */
    fun imageRects(i: Int): FloatArray {
        val page = doc.loadPage(i)
        try {
            val pb = page.bounds
            val pw = (pb.x1 - pb.x0).takeIf { it > 0f } ?: 1f
            val ph = (pb.y1 - pb.y0).takeIf { it > 0f } ?: 1f
            val out = ArrayList<Float>()
            val st = page.toStructuredText("preserve-images")
            try {
                st.walk(object : StructuredTextWalker {
                    override fun onImageBlock(bbox: Rect?, transform: Matrix?, image: Image?) {
                        val r = bbox ?: return
                        val l = ((r.x0 - pb.x0) / pw).coerceIn(0f, 1f); val t = ((r.y0 - pb.y0) / ph).coerceIn(0f, 1f)
                        val rr = ((r.x1 - pb.x0) / pw).coerceIn(0f, 1f); val b = ((r.y1 - pb.y0) / ph).coerceIn(0f, 1f)
                        if (rr > l && b > t) { out += l; out += t; out += rr; out += b }
                    }
                    override fun beginTextBlock(bbox: Rect?, flags: Int) {}
                    override fun endTextBlock() {}
                    override fun beginLine(bbox: Rect?, wmode: Int, dir: Point?) {}
                    override fun endLine() {}
                    override fun onChar(c: Int, origin: Point?, font: Font?, size: Float, q: Quad?, argb: Int, flags: Int, bidi: Int) {}
                    override fun beginStruct(standard: String?, raw: String?, index: Int) {}
                    override fun endStruct() {}
                    override fun onVector(bbox: Rect?, info: StructuredTextWalker.VectorInfo?, argb: Int) {}
                })
            } finally {
                st.destroy()
            }
            return out.toFloatArray()
        } finally {
            page.destroy()
        }
    }

    /** 쪽 링크 하나. 위치는 쪽 크기 기준 0..1. [external] = 웹 주소 등 문서 밖. */
    class LinkBox(val l: Float, val t: Float, val r: Float, val b: Float, val uri: String, val external: Boolean)

    /** 쪽의 링크(F-46 각주). */
    fun links(i: Int): List<LinkBox> {
        val page = doc.loadPage(i)
        try {
            val pb = page.bounds
            val pw = (pb.x1 - pb.x0).takeIf { it > 0f } ?: 1f
            val ph = (pb.y1 - pb.y0).takeIf { it > 0f } ?: 1f
            val out = ArrayList<LinkBox>()
            for (lk in page.links ?: emptyArray()) {
                try {
                    val uri = lk.uri ?: continue
                    if (uri.isEmpty()) continue
                    val r = lk.bounds ?: continue
                    out += LinkBox((r.x0 - pb.x0) / pw, (r.y0 - pb.y0) / ph, (r.x1 - pb.x0) / pw, (r.y1 - pb.y0) / ph, uri, com.artifex.mupdf.fitz.Link.isExternal(uri))
                } finally {
                    lk.destroy()
                }
            }
            return out
        } finally {
            page.destroy()
        }
    }

    /** 문서 안 링크가 가리키는 곳: (쪽 0부터, 쪽 안 세로 위치 0..1 — 모르면 0). 못 찾으면 null. */
    fun linkTarget(uri: String): Pair<Int, Float>? = runCatching {
        val d = doc.resolveLinkDestination(uri) ?: return null
        val n = doc.pageNumberFromLocation(d)
        if (n < 0) return null
        val page = doc.loadPage(n)
        val y = try {
            val pb = page.bounds
            val ph = (pb.y1 - pb.y0).takeIf { it > 0f } ?: 1f
            if (d.y.isNaN()) 0f else ((d.y - pb.y0) / ph).coerceIn(0f, 1f)
        } finally {
            page.destroy()
        }
        n to y
    }.getOrNull()

    /**
     * 쪽 [i]의 세로 위치 [y](0..1)부터 나오는 문단 글(각주 팝업). 첫 문단이 아주 짧으면(번호만 등) 다음 문단까지 붙인다.
     * 최대 [max]자.
     */
    fun textFrom(i: Int, y: Float, max: Int = 1200): String {
        val page = doc.loadPage(i)
        try {
            val pb = page.bounds
            val ph = (pb.y1 - pb.y0).takeIf { it > 0f } ?: 1f
            val st = page.toStructuredText()
            try {
                val sb = StringBuilder()
                var paras = 0
                for (block in st.blocks ?: emptyArray()) {
                    val bb = block.bbox ?: continue
                    if ((bb.y1 - pb.y0) / ph <= y + 0.002f) continue
                    val para = StringBuilder()
                    for (line in block.lines ?: emptyArray()) {
                        val ls = StringBuilder()
                        for (ch in line.chars ?: emptyArray()) ls.appendCodePoint(ch.c)
                        val t = ls.toString().trim()
                        if (t.isEmpty()) continue
                        if (para.isNotEmpty()) para.append(' ')
                        para.append(t)
                    }
                    if (para.isEmpty()) continue
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(para)
                    paras++
                    if (sb.length >= max || (paras >= 1 && sb.length >= 12)) break
                }
                return if (sb.length > max) sb.substring(0, max) + "…" else sb.toString()
            } finally {
                st.destroy()
            }
        } finally {
            page.destroy()
        }
    }

    override fun close() = doc.destroy()

    companion object {
        init {
            Context.init()
        }

        @Volatile private var fontDir: File? = null

        /**
         * 번들 글꼴 폴더를 지정한다. MuPDF 안드로이드 빌드는 CJK 내장 글꼴이 없으므로(TOFU_CJK)
         * 한국어 대체 글꼴을 FontLoader로 공급한다(기술 설계서 3.5).
         */
        fun installFonts(dir: File) {
            fontDir = dir
            Font.installFontLoader { _, script, bold, _ ->
                val d = fontDir ?: return@installFontLoader null
                val name = when {
                    script == "KR" || script == "undefined" ->
                        if (bold) "NanumMyeongjo-Bold.ttf" else "NanumMyeongjo-Regular.ttf"
                    else -> null
                } ?: return@installFontLoader null
                val f = File(d, name)
                if (f.exists()) Font(f.absolutePath) else null
            }
        }

        /**
         * 문서를 연다. 암호 PDF 는 [password] 로 푼다(필수 7). 암호가 없거나 틀리면 [PasswordException].
         * 암호는 저장하지 않는다.
         */
        fun open(path: String, spec: LayoutSpec? = null, userCss: String? = null, password: String? = null): MupdfDoc {
            // 사용자 CSS 는 전역이라 바꾸고 조판할 때까지 잠근다(Word 원본 합성기와 공유 — MupdfLock)
            val d = synchronized(MupdfLock) {
                if (userCss != null) Context.setUserCSS(userCss)
                val d = Document.openDocument(path)
                try {
                    if (d.needsPassword()) {
                        if (password.isNullOrEmpty()) throw PasswordException(wrong = false)
                        if (!d.authenticatePassword(password)) throw PasswordException(wrong = true)
                    }
                    if (spec != null && d.isReflowable) d.layout(spec.widthPt, spec.heightPt, spec.emPt)
                } catch (e: Throwable) {
                    d.destroy(); throw e
                }
                d
            }
            return MupdfDoc(d)
        }
    }
}

/** 암호 걸린 문서: 암호 없음([wrong]=false) 또는 틀림([wrong]=true). */
class PasswordException(val wrong: Boolean) : Exception(if (wrong) "암호가 맞지 않아요." else "암호가 필요해요.")
