// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.comic

import kotlin.random.Random

/**
 * 합성 만화 쪽: 흰 바탕에 검은 테두리 칸, 칸 안에 그림(선·채운 도형·글자 흉내), 일부는 거터를 넘는 말풍선.
 * 정답 칸 상자와 함께 만든다. 대표님 샘플이 오기 전 칸 인식기·채점기 시험용.
 */
object SyntheticPage {
    data class Page(val image: GrayImage, val truth: List<PanelRect>, val direction: ReadingDirection)

    /** rows: 줄마다 칸 폭 비율. 읽기 순서는 [dir] 에 따라 줄 안에서 왼→오 또는 오→왼. */
    fun make(seed: Int, w: Int = 1000, h: Int = 1500, rows: List<List<Double>>, dir: ReadingDirection,
             margin: Int = 50, gutter: Int = 24, border: Int = 4, bubbles: Boolean = true, noise: Int = 0,
             background: Int = 255): Page {
        val rnd = Random(seed)
        val g = GrayImage(w, h)
        g.px.fill(background.toByte())
        val truth = ArrayList<PanelRect>()
        val rowH = (h - 2 * margin - gutter * (rows.size - 1)) / rows.size
        rows.forEachIndexed { ri, cols ->
            val y = margin + ri * (rowH + gutter)
            val avail = w - 2 * margin - gutter * (cols.size - 1)
            var x = margin
            val rowPanels = ArrayList<PanelRect>()
            cols.forEachIndexed { ci, frac ->
                val pw = if (ci == cols.lastIndex) w - margin - x else (avail * frac).toInt()
                val p = PanelRect(x, y, x + pw, y + rowH)
                drawPanel(g, p, border, rnd)
                rowPanels += p
                x += pw + gutter
            }
            truth += if (dir == ReadingDirection.LTR) rowPanels else rowPanels.reversed()
        }
        if (bubbles) {
            // 말풍선: 칸 위쪽 테두리를 살짝 넘는 흰 타원 + 검은 윤곽 (Kumiko 가 '병합'으로 다루는 경우)
            for (p in truth.filterIndexed { i, _ -> i % 3 == 1 }) ellipse(g, p.x + p.w / 2, p.y + 8, minOf(90, p.w / 4), 40)
        }
        if (noise > 0) for (i in g.px.indices) g.px[i] = ((g.px[i].toInt() and 0xff) + rnd.nextInt(-noise, noise + 1)).coerceIn(0, 255).toByte()
        return Page(g, truth, dir)
    }

    private fun rect(g: GrayImage, x0: Int, y0: Int, x1: Int, y1: Int, v: Int) {
        for (y in maxOf(0, y0) until minOf(g.height, y1)) for (x in maxOf(0, x0) until minOf(g.width, x1)) g[x, y] = v
    }

    private fun drawPanel(g: GrayImage, p: PanelRect, b: Int, rnd: Random) {
        rect(g, p.x, p.y, p.r, p.b, 0)
        rect(g, p.x + b, p.y + b, p.r - b, p.b - b, 245 - rnd.nextInt(0, 30))
        // 그림: 채운 도형·선·글자 흉내
        repeat(6) {
            val cx = rnd.nextInt(p.x + 20, p.r - 20); val cy = rnd.nextInt(p.y + 20, p.b - 20)
            val rw = rnd.nextInt(10, maxOf(11, p.w / 4)); val rh = rnd.nextInt(10, maxOf(11, p.h / 4))
            rect(g, maxOf(p.x + b + 2, cx - rw / 2), maxOf(p.y + b + 2, cy - rh / 2), minOf(p.r - b - 2, cx + rw / 2), minOf(p.b - b - 2, cy + rh / 2), rnd.nextInt(0, 160))
        }
        repeat(8) {
            val yy = rnd.nextInt(p.y + 20, p.b - 20); val xx = rnd.nextInt(p.x + 20, p.r - 60)
            rect(g, xx, yy, minOf(p.r - b - 4, xx + rnd.nextInt(20, 120)), yy + 2, 20)
        }
    }

    private fun ellipse(g: GrayImage, cx: Int, cy: Int, rx: Int, ry: Int) {
        for (y in cy - ry - 3..cy + ry + 3) for (x in cx - rx - 3..cx + rx + 3) {
            if (x !in 0 until g.width || y !in 0 until g.height) continue
            val d = ((x - cx).toDouble() / rx).let { it * it } + ((y - cy).toDouble() / ry).let { it * it }
            if (d <= 1.0) g[x, y] = 255 else if (d <= 1.12) g[x, y] = 0
        }
    }

    /** 대표 배치 묶음 (왼→오·오→왼·불규칙). */
    fun suite(): List<Page> {
        val layouts = listOf(
            listOf(listOf(1.0), listOf(0.5, 0.5), listOf(0.35, 0.65)),
            listOf(listOf(0.5, 0.5), listOf(0.5, 0.5), listOf(0.5, 0.5)),
            listOf(listOf(0.3, 0.3, 0.4), listOf(1.0), listOf(0.6, 0.4), listOf(0.5, 0.5)),
            listOf(listOf(0.7, 0.3), listOf(0.4, 0.6)),
            listOf(listOf(1.0), listOf(1.0), listOf(0.33, 0.33, 0.34)),
        )
        val out = ArrayList<Page>()
        var seed = 1
        for (dir in ReadingDirection.values()) for (l in layouts) {
            out += make(seed++, rows = l, dir = dir)
            out += make(seed++, rows = l, dir = dir, noise = 12, background = 235) // 누런 스캔 느낌
        }
        return out
    }
}
