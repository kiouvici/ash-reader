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
