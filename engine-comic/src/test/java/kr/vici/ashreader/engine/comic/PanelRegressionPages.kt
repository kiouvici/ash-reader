// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.comic

import kotlin.random.Random

/**
 * 칸 인식 회귀 시험용 합성 쪽 (7C). [SyntheticPage] 가 못 다루던 어려운 배치를 정답 상자와 함께 만든다.
 *  - 가로로 긴 칸(띠), 세로로 긴 칸, 쪽 끝에 붙은 칸(거터 없음), 아주 좁은 거터, 테두리를 넘는 그림,
 *  - 테두리 없는 칸, 검은·회색 바탕, 한 장짜리 큰 그림(스플래시), 거터를 건너는 말풍선·거터 속 작은 조각,
 *  - 어긋난 줄, 줄 전체를 차지하는 칸, 오른→왼(만화) 순서, 스캔 가장자리 그림자.
 * 좌표·순서가 정답이다(읽기 순서대로).
 */
object PanelRegressionPages {
    data class Case(val name: String, val page: SyntheticPage.Page)

    private const val W = 1000
    private const val H = 1500

    /** 칸 하나: [r] 사각형, 테두리 두께 [border](0 = 테두리 없음), 안쪽 바탕 [fill]. */
    private class Spec(val r: PanelRect, val border: Int = 4, val fill: Int = -1, val ink: Int = 0)

    private class Canvas(val bg: Int, seed: Int) {
        val g = GrayImage(W, H).apply { px.fill(bg.toByte()) }
        val rnd = Random(seed)

        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, v: Int) {
            for (y in maxOf(0, y0) until minOf(H, y1)) for (x in maxOf(0, x0) until minOf(W, x1)) g[x, y] = v
        }

        fun ellipse(cx: Int, cy: Int, rx: Int, ry: Int, outline: Int = 0, inside: Int = 255) {
            for (y in cy - ry - 4..cy + ry + 4) for (x in cx - rx - 4..cx + rx + 4) {
                if (x !in 0 until W || y !in 0 until H) continue
                val d = ((x - cx).toDouble() / rx).let { it * it } + ((y - cy).toDouble() / ry).let { it * it }
                if (d <= 1.0) g[x, y] = inside else if (d <= 1.14) g[x, y] = outline
            }
        }

        /** 칸 그리기: 테두리 + 바탕 + 그림(채운 도형·선·글자 흉내) */
        fun panel(s: Spec) {
            val p = s.r; val b = s.border
            val fill = if (s.fill >= 0) s.fill else 245 - rnd.nextInt(0, 30)
            if (b > 0) rect(p.x, p.y, p.r, p.b, s.ink)
            rect(p.x + b, p.y + b, p.r - b, p.b - b, fill)
            art(PanelRect(p.x + b, p.y + b, p.r - b, p.b - b))
        }

        fun art(p: PanelRect) {
            if (p.w < 30 || p.h < 30) return
            val n = 3 + (p.area / 40000).toInt().coerceAtMost(8)
            repeat(n) {
                val cx = rnd.nextInt(p.x + 8, p.r - 8); val cy = rnd.nextInt(p.y + 8, p.b - 8)
                val rw = rnd.nextInt(8, maxOf(9, p.w / 4)); val rh = rnd.nextInt(8, maxOf(9, p.h / 3))
                rect(maxOf(p.x + 2, cx - rw / 2), maxOf(p.y + 2, cy - rh / 2), minOf(p.r - 2, cx + rw / 2), minOf(p.b - 2, cy + rh / 2), rnd.nextInt(0, 170))
            }
            repeat(n + 2) {
                val yy = rnd.nextInt(p.y + 6, maxOf(p.y + 7, p.b - 6)); val xx = rnd.nextInt(p.x + 4, maxOf(p.x + 5, p.r - 40))
                rect(xx, yy, minOf(p.r - 3, xx + rnd.nextInt(15, 110)), yy + 2, 25)
            }
        }
    }

    private fun page(name: String, seed: Int, specs: List<Spec>, dir: ReadingDirection, bg: Int = 255,
                     truth: List<PanelRect> = specs.map { it.r }, extra: (Canvas) -> Unit = {}): Case {
        val c = Canvas(bg, seed)
        specs.forEach { c.panel(it) }
        extra(c)
        return Case(name, SyntheticPage.Page(c.g, truth, dir))
    }

    /** 줄마다 칸 폭 비율 → 사각형 (줄 안 순서는 [dir]). 줄 높이 비율 [heights](null = 같게). */
    private fun grid(rows: List<List<Double>>, dir: ReadingDirection, margin: Int = 50, gutter: Int = 24,
                     heights: List<Double>? = null, mx: Int = margin, my: Int = margin): List<PanelRect> {
        val out = ArrayList<PanelRect>()
        val availH = H - 2 * my - gutter * (rows.size - 1)
        val hs = heights ?: List(rows.size) { 1.0 / rows.size }
        var y = my
        rows.forEachIndexed { ri, cols ->
            val rh = if (ri == rows.lastIndex) H - my - y else (availH * hs[ri]).toInt()
            val avail = W - 2 * mx - gutter * (cols.size - 1)
            var x = mx
            val row = ArrayList<PanelRect>()
            cols.forEachIndexed { ci, f ->
                val pw = if (ci == cols.lastIndex) W - mx - x else (avail * f).toInt()
                row += PanelRect(x, y, x + pw, y + rh); x += pw + gutter
            }
            out += if (dir == ReadingDirection.LTR) row else row.reversed()
            y += rh + gutter
        }
        return out
    }

    private val L = ReadingDirection.LTR
    private val R = ReadingDirection.RTL

    fun suite(): List<Case> {
        val out = ArrayList<Case>()
        var seed = 100
        fun add(c: Case) { out += c }
        fun specs(rs: List<PanelRect>, border: Int = 4, fill: Int = -1, ink: Int = 0) = rs.map { Spec(it, border, fill, ink) }

        // 1) 가로로 긴 칸 띠: 5줄(비 3.4), 7줄(비 5.4), 9줄(비 7.4)
        for (n in listOf(5, 7, 9)) add(page("wide-strips-$n", seed++, specs(grid(List(n) { listOf(1.0) }, L)), L))
        // 2) 긴 띠 + 2·3칸 줄 섞기 (줄 높이 다르게)
        add(page("wide-mixed", seed++, specs(grid(listOf(listOf(1.0), listOf(0.5, 0.5), listOf(1.0), listOf(0.3, 0.3, 0.4), listOf(1.0)), L,
            heights = listOf(0.12, 0.3, 0.12, 0.3, 0.16))), L))
        add(page("wide-mixed-rtl", seed++, specs(grid(listOf(listOf(1.0), listOf(0.4, 0.6), listOf(1.0), listOf(0.5, 0.5)), R,
            heights = listOf(0.12, 0.38, 0.12, 0.38))), R))
        // 3) 세로로 긴 칸: 3단(비 1:5), 긴 왼칸 + 오른쪽 2칸
        add(page("tall-columns", seed++, specs(grid(listOf(listOf(0.33, 0.33, 0.34)), L)), L))
        run {
            val tall = PanelRect(50, 50, 450, 1450); val a = PanelRect(474, 50, 950, 738); val b = PanelRect(474, 762, 950, 1450)
            add(page("tall-left-ltr", seed++, specs(listOf(tall, a, b)), L))
            val tallR = PanelRect(550, 50, 950, 1450); val c = PanelRect(50, 50, 526, 738); val d = PanelRect(50, 762, 526, 1450)
            add(page("tall-right-rtl", seed++, specs(listOf(tallR, c, d)), R))
        }
        // 4) 쪽 끝에 붙은 칸(바깥 여백 없음, 칸 사이 거터만)
        add(page("no-margin", seed++, specs(grid(listOf(listOf(0.5, 0.5), listOf(1.0), listOf(0.4, 0.6)), L, margin = 0)), L))
        // 5) 맨 위 칸만 쪽 끝까지(블리드), 아래는 보통 여백
        run {
            val top = PanelRect(0, 0, W, 520)
            val rest = grid(listOf(listOf(0.5, 0.5), listOf(1.0)), L, my = 0).map { it.copy(y = it.y * 880 / 1500 + 570, b = it.b * 880 / 1500 + 570) }
            add(page("bleed-top", seed++, listOf(Spec(top, border = 0, fill = 150)) + specs(rest), L))
        }
        // 6) 좁은 거터 (6px, 3px)
        add(page("thin-gutter-6", seed++, specs(grid(listOf(listOf(0.5, 0.5), listOf(0.3, 0.7), listOf(1.0)), L, gutter = 6)), L))
        add(page("thin-gutter-3", seed++, specs(grid(listOf(listOf(0.5, 0.5), listOf(1.0), listOf(0.6, 0.4)), L, gutter = 3, margin = 30), border = 3), L))
        // 7) 테두리를 넘는 그림: 칸 그림 일부가 거터를 건너 옆 칸 테두리에 닿음
        run {
            val rs = grid(listOf(listOf(0.5, 0.5), listOf(0.5, 0.5), listOf(1.0)), L)
            add(page("art-bleeds-border", seed++, specs(rs), L) { c ->
                // 위 왼칸 → 오른칸으로 건너는 검은 덩어리(높이 25%), 가운데 줄 → 아래 줄로 건너는 덩어리(폭 18%)
                c.rect(rs[0].r - 60, rs[0].y + 120, rs[1].x + 50, rs[0].y + 220, 30)
                c.rect(rs[2].x + 150, rs[2].b - 50, rs[2].x + 300, rs[4].y + 60, 40)
            })
        }
        // 8) 테두리 없는 칸(흰 바탕 위 그림 덩어리)
        add(page("borderless", seed++, specs(grid(listOf(listOf(0.5, 0.5), listOf(1.0), listOf(0.35, 0.65)), L, gutter = 30), border = 0, fill = 170), L))
        // 9) 검은 바탕(거터가 검정), 회색 바탕
        add(page("black-background", seed++, specs(grid(listOf(listOf(0.6, 0.4), listOf(1.0), listOf(0.5, 0.5)), L), border = 3, ink = 255), L, bg = 15))
        add(page("gray-background", seed++, specs(grid(listOf(listOf(0.5, 0.5), listOf(0.3, 0.7), listOf(1.0)), L)), L, bg = 150))
        // 10) 스플래시: 한 장 그림(쪽 끝까지) — 그림 안 긴 직선·흰 빈 곳이 있어도 한 칸
        run {
            val full = PanelRect(0, 0, W, H)
            add(page("splash-bleed", seed++, emptyList(), L, truth = listOf(full)) { c ->
                // 쪽 전체 그림: 위→아래로 어두워지는 하늘 + 망점(스크린톤) + 도형. 단색 평면이 아니라 실제 스플래시처럼 결이 있다.
                for (y in 0 until H) for (x in 0 until W) c.g[x, y] = 200 - y * 110 / H - (if ((x / 3 + y / 3) % 2 == 0) 18 else 0)
                c.art(PanelRect(0, 0, W, H)); c.art(PanelRect(0, 0, W, H))
                // 지평선·기둥: 얇은 잉크 선(2px). 쪽 끝에서 끝까지 곧게 가로지르는 굵은 선은 '테두리 공유 칸'과 구별할 수 없어 일부만
                c.rect(0, 700, 760, 702, 10)
                c.rect(480, 300, 482, H, 10)
                c.rect(100, 300, 400, 420, 255)     // 흰 빈 곳
                c.ellipse(700, 200, 150, 60)        // 말풍선
                c.ellipse(300, 1200, 130, 50)
            })
            add(page("splash-framed", seed++, listOf(Spec(PanelRect(40, 40, 960, 1460), border = 5)), L) { c ->
                c.ellipse(500, 300, 200, 70); c.rect(45, 745, 955, 749, 0)
                c.rect(60, 1300, 400, 1380, 255); c.rect(60, 1300, 400, 1303, 0)  // 설명 상자
            })
        }
        // 11) 거터를 건너는 큰 말풍선 + 거터 속 작은 조각(쪽 번호·효과음)
        run {
            val rs = grid(listOf(listOf(0.5, 0.5), listOf(0.5, 0.5), listOf(0.5, 0.5)), L)
            add(page("balloons-cross-gutters", seed++, specs(rs), L) { c ->
                c.ellipse((rs[0].r + rs[1].x) / 2, rs[0].y + 150, 120, 55)           // 세로 거터를 건넘
                c.ellipse(rs[2].x + 200, (rs[2].b + rs[0].b) / 2 + 0, 100, 45)      // 가로 거터(1·2줄 사이)
                c.ellipse(rs[5].x + rs[5].w / 2, (rs[2].b + rs[4].y) / 2, 140, 60)  // 가로 거터(2·3줄 사이)
            })
            add(page("gutter-fragments", seed++, specs(rs), L) { c ->
                c.rect(480, 1465, 520, 1490, 0)                    // 쪽 번호
                c.rect(rs[0].r + 4, rs[0].y + 300, rs[1].x - 4, rs[0].y + 330, 0) // 거터 속 작은 효과음
                c.ellipse(500, 25, 60, 18)                         // 위 여백 작은 말풍선
            })
        }
        // 12) 어긋난 줄(같은 줄의 칸 위아래가 3% 어긋남), 오른→왼 판도
        for ((nm, dir) in listOf("misaligned-ltr" to L, "misaligned-rtl" to R)) {
            val rs = ArrayList<PanelRect>()
            val ys = listOf(50, 520, 990); val hh = 440
            for ((ri, y) in ys.withIndex()) {
                val left = PanelRect(50, y + if (ri == 1) 0 else 0, 480, y + hh + if (ri == 2) 0 else 0)
                val right = PanelRect(504, y + 40, 950, minOf(1450, y + hh + 40))
                val l2 = if (ri == 2) left.copy(b = 1450) else left
                rs += if (dir == L) listOf(l2, right) else listOf(right, l2)
            }
            add(page(nm, seed++, specs(rs), dir))
        }
        // 13) 스캔 가장자리 그림자(왼쪽 검은 띠) + 누런 바탕 + 잡음, 긴 띠 칸
        run {
            val rs = grid(listOf(listOf(1.0), listOf(0.5, 0.5), listOf(1.0), listOf(1.0)), L, heights = listOf(0.15, 0.4, 0.15, 0.3))
            val case = page("scan-shadow", seed++, specs(rs), L, bg = 228) { c ->
                c.rect(0, 0, 18, H, 40)
                val r = Random(5)
                for (i in c.g.px.indices) c.g.px[i] = ((c.g.px[i].toInt() and 0xff) + r.nextInt(-10, 11)).coerceIn(0, 255).toByte()
            }
            add(case)
        }
        return out
    }
}
