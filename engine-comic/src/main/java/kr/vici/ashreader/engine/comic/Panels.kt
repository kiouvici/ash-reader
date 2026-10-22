// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.comic

/** 칸 사각형 (원본 쪽 픽셀 좌표, 오른쪽·아래 끝 미포함). */
data class PanelRect(val x: Int, val y: Int, val r: Int, val b: Int) {
    val w get() = r - x
    val h get() = b - y
    val area get() = w.toLong() * h

    fun iou(o: PanelRect): Double {
        val ix = maxOf(0, minOf(r, o.r) - maxOf(x, o.x)); val iy = maxOf(0, minOf(b, o.b) - maxOf(y, o.y))
        val inter = ix.toLong() * iy
        val uni = area + o.area - inter
        return if (uni <= 0) 0.0 else inter.toDouble() / uni
    }

    fun overlapArea(o: PanelRect): Long =
        maxOf(0, minOf(r, o.r) - maxOf(x, o.x)).toLong() * maxOf(0, minOf(b, o.b) - maxOf(y, o.y))

    fun union(o: PanelRect) = PanelRect(minOf(x, o.x), minOf(y, o.y), maxOf(r, o.r), maxOf(b, o.b))
    fun scaled(f: Double) = PanelRect((x * f).toInt(), (y * f).toInt(), (r * f).toInt(), (b * f).toInt())

    companion object { fun xywh(x: Int, y: Int, w: Int, h: Int) = PanelRect(x, y, x + w, y + h) }
}

/** 한 쪽의 칸 목록 (읽기 순서대로). */
data class PagePanels(val width: Int, val height: Int, val direction: ReadingDirection, val panels: List<PanelRect>)
