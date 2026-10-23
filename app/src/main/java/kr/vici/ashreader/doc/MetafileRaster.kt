// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.doc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import kr.vici.ashreader.office.Metafile

/**
 * Word 원본 보기(F-30)의 EMF·WMF 그림을 안드로이드 Canvas 로 PNG 로 그린다(MuPDF 는 이 형식을 읽지 못한다).
 * 그리기 목록은 `:engine-office` [Metafile] 이 만든다. 바탕은 투명(쪽 바탕이 비친다), 글자는 기기 글꼴(한글 포함).
 */
object MetafileRaster {
    fun png(m: Metafile, wPx: Int, hPx: Int): ByteArray? {
        if (wPx <= 0 || hPx <= 0 || m.width <= 0f || m.height <= 0f) return null
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(bmp)
            val sx = wPx / m.width; val sy = hPx / m.height
            c.scale(sx, sy)
            c.translate(-m.left, -m.top)
            val px = 1f / ((sx + sy) / 2f) // 장치 단위 1px
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
            val img = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            var depth = 0
            for (op in m.ops) when (op) {
                is Metafile.Op.Shape -> {
                    val path = toPath(op.segs)
                    path.fillType = if (op.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                    op.fill?.let { fill.color = it; c.drawPath(path, fill) }
                    op.stroke?.let { stroke.color = it; stroke.strokeWidth = op.strokeW.coerceAtLeast(px); c.drawPath(path, stroke) }
                }
                is Metafile.Op.Text -> text(c, op)
                is Metafile.Op.Image -> {
                    val b = runCatching { BitmapFactory.decodeByteArray(op.bmp, 0, op.bmp.size) }.getOrNull()
                    if (b != null) { c.drawBitmap(b, null, RectF(op.l, op.t, op.r, op.b), img); b.recycle() }
                }
                Metafile.Op.Save -> { c.save(); depth++ }
                Metafile.Op.Restore -> if (depth > 0) { c.restore(); depth-- }
                is Metafile.Op.Clip -> c.clipRect(op.l, op.t, op.r, op.b)
            }
            val o = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, o)
            return o.toByteArray()
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        } finally {
            bmp.recycle()
        }
    }

    private fun toPath(segs: List<Metafile.Seg>): Path {
        val p = Path()
        for (s in segs) when (s) {
            is Metafile.Seg.Move -> p.moveTo(s.x, s.y)
            is Metafile.Seg.Line -> p.lineTo(s.x, s.y)
            is Metafile.Seg.Cubic -> p.cubicTo(s.x1, s.y1, s.x2, s.y2, s.x, s.y)
            Metafile.Seg.Close -> p.close()
        }
        return p
    }

    private val GOTHIC = Regex("(?i)gothic|고딕|굴림|돋움|gulim|dotum|malgun|맑은|arial|calibri|helvetica|verdana|tahoma|segoe|sans")

    private fun text(c: Canvas, t: Metafile.Op.Text) {
        if (t.text.isBlank() || t.size <= 0f) return
        val base = if (t.face != null && GOTHIC.containsMatchIn(t.face)) Typeface.SANS_SERIF else Typeface.SERIF
        val style = when { t.bold && t.italic -> Typeface.BOLD_ITALIC; t.bold -> Typeface.BOLD; t.italic -> Typeface.ITALIC; else -> Typeface.NORMAL }
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Typeface.create(base, style)
            textSize = t.size
            color = t.color
            isUnderlineText = t.underline
            textAlign = when (t.hAlign) { 1 -> Paint.Align.CENTER; 2 -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
        }
        val fm = p.fontMetrics
        val baseline = when (t.vAlign) { 0 -> t.y - fm.ascent; 2 -> t.y - fm.descent; else -> t.y }
        c.save()
        if (t.angle != 0f) c.rotate(-t.angle, t.x, t.y)
        val dx = t.dx
        if (dx != null && dx.size == t.text.length && t.hAlign == 0) {
            // 글자마다 정해진 간격(EXTTEXTOUT dx)
            var x = t.x
            for (i in t.text.indices) { c.drawText(t.text, i, i + 1, x, baseline, p); x += dx[i] }
        } else c.drawText(t.text, t.x, baseline, p)
        c.restore()
    }
}
