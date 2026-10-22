// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.bench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kr.vici.ashreader.pagecurl.PageSource
import kotlin.random.Random

/** 실험 ③용 합성 쪽 (시험 파일 없이 넘김 시연·측정). 강조(형광펜)도 쪽에 구워 넣는다(M-03). */
class SyntheticPages(
    private val kind: Kind,
    override val pageCount: Int = 40,
    private val paper: Int = Color.WHITE,
    private val ink: Int = Color.rgb(0x1F, 0x1F, 0x1F),
) : PageSource {
    enum class Kind { TEXT, COMIC }

    override fun render(index: Int, width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        when (kind) {
            Kind.TEXT -> drawText(c, index, width, height)
            Kind.COMIC -> drawComic(c, index, width, height)
        }
        return bmp
    }

    private fun drawText(c: Canvas, index: Int, w: Int, h: Int) {
        c.drawColor(paper)
        val scale = w / 400f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 17f * scale; typeface = Typeface.SERIF }
        val hl = Paint().apply { color = Color.argb(110, 255, 214, 0) }
        val title = Paint(p).apply { textSize = 22f * scale; isFakeBoldText = true }
        val margin = 26f * scale
        var y = 56f * scale
        if (index % 6 == 0) {
            c.drawText("제${index / 6 + 1}장 비 오는 밤", margin, y, title)
            y += 44f * scale
        }
        val line = 30f * scale
        val rnd = Random(index)
        val words = listOf("그날", "밤은", "유난히", "비가", "많이", "왔다.", "창문을", "두드리는", "빗소리에", "잠이", "오지", "않았다.", "책장을", "넘기며", "생각했다.", "\"정말", "그랬을까?\"")
        var lineNo = 0
        while (y < h - 40f * scale) {
            val sb = StringBuilder(if (lineNo == 0) "　" else "")
            while (true) {
                val wd = words[rnd.nextInt(words.size)]
                if (p.measureText("$sb $wd") > w - 2 * margin) break
                if (sb.isNotEmpty() && sb.toString() != "　") sb.append(' ')
                sb.append(wd)
            }
            if (lineNo == 3 && index % 3 == 1) {
                c.drawRect(margin, y - p.textSize, margin + p.measureText(sb.toString()), y + 6f * scale, hl)
            }
            c.drawText(sb.toString(), margin, y, p)
            y += line
            lineNo++
        }
        val num = Paint(p).apply { textSize = 12f * scale; textAlign = Paint.Align.CENTER; alpha = 150 }
        c.drawText("${index + 1}", w / 2f, h - 14f * scale, num)
    }

    private fun drawComic(c: Canvas, index: Int, w: Int, h: Int) {
        c.drawColor(Color.WHITE)
        val rnd = Random(index * 31 + 7)
        val gutter = w * 0.03f
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.006f; color = Color.BLACK }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val rows = 3 + rnd.nextInt(2)
        var y = gutter
        val rowH = (h - gutter * (rows + 1)) / rows
        for (r in 0 until rows) {
            val cols = 1 + rnd.nextInt(3)
            var x = gutter
            val colW = (w - gutter * (cols + 1)) / cols
            for (k in 0 until cols) {
                val rect = RectF(x, y, x + colW, y + rowH)
                val shade = 120 + rnd.nextInt(120)
                fill.shader = android.graphics.LinearGradient(
                    rect.left, rect.top, rect.right, rect.bottom,
                    Color.rgb(shade, shade - 40, 255 - shade / 2), Color.rgb(240, 240, 240),
                    android.graphics.Shader.TileMode.CLAMP,
                )
                c.drawRect(rect, fill)
                fill.shader = null
                fill.color = Color.argb(200, 30, 30, 30)
                c.drawCircle(rect.centerX(), rect.centerY(), minOf(rect.width(), rect.height()) * 0.22f, fill)
                fill.color = Color.WHITE
                val bubble = RectF(rect.left + rect.width() * 0.08f, rect.top + rect.height() * 0.08f, rect.left + rect.width() * 0.5f, rect.top + rect.height() * 0.3f)
                c.drawOval(bubble, fill)
                c.drawOval(bubble, border)
                c.drawRect(rect, border)
                x += colW + gutter
            }
            y += rowH + gutter
        }
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = w * 0.035f; textAlign = Paint.Align.RIGHT }
        c.drawText("${index + 1}", w - gutter, h - gutter * 0.2f, num)
    }
}
