// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.mupdf

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Context
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Matrix
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

        fun open(path: String, spec: LayoutSpec? = null, userCss: String? = null): MupdfDoc {
            if (userCss != null) Context.setUserCSS(userCss)
            val d = Document.openDocument(path)
            if (spec != null && d.isReflowable) d.layout(spec.widthPt, spec.heightPt, spec.emPt)
            return MupdfDoc(d)
        }
    }
}
