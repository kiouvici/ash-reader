// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 칸 인식 (F-72). Kumiko(njean42/kumiko v1.5, AGPL-3.0) lib/page.py·panel.py 의 처리 순서를 순수 Kotlin 으로 옮겼다.
//   회색조 → Sobel(|gx|·0.5+|gy|·0.5) → 임계 100 → 바깥 윤곽(여기서는 8-연결 성분의 외접 사각형)
//   → 아주 작은 조각 버림 → 작은 조각 묶기(가까운 것끼리) → 큰 칸 나누기(거터 투영, Kumiko 의 선분 분할 대신)
//   → 작은 칸 제외 → 포함된 칸 병합 → 겹침 해소 → 이웃·거터까지 확장 → 읽기 순서(ltr/rtl) 보정.
// 다른 점: Kumiko 는 다각형 근사·LSD 선분으로 칸을 쪼개고 '큰 칸 묶기'를 하는데, 이 판은 OpenCV 없이
// 성분 내부의 빈 줄(거터) 투영으로 쪼갠다. 결과 형식(사각형 목록 + 순서)은 Kumiko 와 같다.
package kr.vici.ashreader.engine.comic

class PanelDetector(
    /** 처리 해상도: 긴 변을 이 크기로 줄여 계산 (속도). */
    private val workSide: Int = 1200,
    /** Kumiko DEFAULT_MIN_PANEL_SIZE_RATIO */
    private val minPanelRatio: Double = 1.0 / 10,
    private val sobelThreshold: Int = 100,
) {
    fun detect(img: GrayImage, direction: ReadingDirection): PagePanels {
        val (g, f) = img.downscale(workSide)
        val panels = detectWork(g, direction).map { it.scaled(f) }.map {
            PanelRect(it.x.coerceIn(0, img.width), it.y.coerceIn(0, img.height), it.r.coerceIn(0, img.width), it.b.coerceIn(0, img.height))
        }
        return PagePanels(img.width, img.height, direction, panels)
    }

    private fun detectWork(g: GrayImage, dir: ReadingDirection): List<PanelRect> {
        val w = g.width; val h = g.height
        val edge = sobelMask(g)
        val minW = w * minPanelRatio; val minH = h * minPanelRatio
        fun small(p: PanelRect, k: Double = 1.0) = p.w < minW * k || p.h < minH * k

        // 1) 성분 → 외접 사각형, 아주 작은 것(작은 칸 기준의 1/10) 버림
        var panels = components(edge, w, h).filter { !small(it, 0.1) }.toMutableList()
        // 2) 작은 조각 묶기
        panels = groupSmall(panels, ::small)
        // 2') 서로 겹치는 사각형은 합친 뒤 나눈다 (말풍선이 거터를 건너 두 칸이 한 성분이 된 경우)
        panels = unionOverlapping(panels)
        // 3) 거터로 나누기 (재귀)
        panels = panels.flatMap { split(edge, w, it, ::small) }.toMutableList()
        // 4) 작은 칸 제외, 5) 병합, 6) 겹침 해소, 4') 다시 제외
        panels = panels.filter { !small(it) }.toMutableList()
        panels = mergeContained(panels)
        panels = deoverlap(panels)
        panels = panels.filter { !small(it) }.toMutableList()
        if (panels.isEmpty()) return listOf(PanelRect(0, 0, w, h))
        // 7) 확장
        panels = expand(panels)
        // 8) 순서
        return order(panels, dir)
    }

    // ---- Sobel + 임계 ----
    private fun sobelMask(src: GrayImage): BooleanArray {
        val g = blur3(src) // 스캔 잡티 억제 (Kumiko 에는 없음, 합성 잡음 쪽에서 성분이 모두 이어지는 문제 방지)
        val w = g.width; val h = g.height
        val m = BooleanArray(w * h)
        fun p(x: Int, y: Int) = g[x.coerceIn(0, w - 1), y.coerceIn(0, h - 1)] // BORDER_REPLICATE 근사
        for (y in 0 until h) for (x in 0 until w) {
            val gx = -p(x - 1, y - 1) - 2 * p(x - 1, y) - p(x - 1, y + 1) + p(x + 1, y - 1) + 2 * p(x + 1, y) + p(x + 1, y + 1)
            val gy = -p(x - 1, y - 1) - 2 * p(x, y - 1) - p(x + 1, y - 1) + p(x - 1, y + 1) + 2 * p(x, y + 1) + p(x + 1, y + 1)
            val v = (minOf(255, Math.abs(gx)) + minOf(255, Math.abs(gy))) / 2.0
            m[y * w + x] = v > sobelThreshold
        }
        return m
    }

    private fun blur3(g: GrayImage): GrayImage {
        val w = g.width; val h = g.height; val o = GrayImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            for (dy in -1..1) for (dx in -1..1) sum += g[(x + dx).coerceIn(0, w - 1), (y + dy).coerceIn(0, h - 1)]
            o[x, y] = sum / 9
        }
        return o
    }

    // ---- 8-연결 성분 외접 사각형 (findContours RETR_EXTERNAL + boundingRect 에 해당) ----
    private fun components(m: BooleanArray, w: Int, h: Int): List<PanelRect> {
        val seen = BooleanArray(m.size)
        val stack = IntArray(m.size)
        val out = ArrayList<PanelRect>()
        for (start in m.indices) {
            if (!m[start] || seen[start]) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            while (sp > 0) {
                val i = stack[--sp]; val x = i % w; val y = i / w
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (m[j] && !seen[j]) { seen[j] = true; stack[sp++] = j }
                }
            }
            out += PanelRect(x0, y0, x1 + 1, y1 + 1)
        }
        // 안쪽에 완전히 들어간 사각형은 바깥 윤곽이 아니므로 버림
        return out.filter { a -> out.none { b -> b !== a && b.x <= a.x && b.y <= a.y && b.r >= a.r && b.b >= a.b && b.area > a.area } }
    }

    // ---- Kumiko group_small_panels: 가까운 작은 조각을 하나로 ----
    private fun groupSmall(ps: MutableList<PanelRect>, small: (PanelRect, Double) -> Boolean): MutableList<PanelRect> {
        val smalls = ps.filter { small(it, 1.0) }
        if (smalls.size < 2) return ps
        val parent = IntArray(smalls.size) { it }
        fun find(i: Int): Int { var a = i; while (parent[a] != a) a = parent[a]; return a }
        fun close(a: PanelRect, b: PanelRect) =
            Math.abs((a.x + a.r) - (b.x + b.r)) / 2.0 <= (a.w + b.w) * 0.75 && Math.abs((a.y + a.b) - (b.y + b.b)) / 2.0 <= (a.h + b.h) * 0.75
        for (i in smalls.indices) for (j in i + 1 until smalls.size) if (close(smalls[i], smalls[j])) parent[find(i)] = find(j)
        val groups = smalls.indices.groupBy { find(it) }.values.filter { it.size > 1 }
        val out = ps.toMutableList()
        for (gIdx in groups) {
            gIdx.forEach { out.remove(smalls[it]) }
            out += gIdx.map { smalls[it] }.reduce { a, b -> a.union(b) }
        }
        return out
    }

    // ---- 나누기: 사각형 안에서 가장자리 화소가 거의 없는 가로/세로 띠(거터)로 자른다 ----
    private fun split(m: BooleanArray, w: Int, p: PanelRect, small: (PanelRect, Double) -> Boolean): List<PanelRect> {
        if (small(p, 2.0) && p.w < 2 * w * minPanelRatio && p.h < 2 * w * minPanelRatio) return listOf(p)
        val cut = findGutter(m, w, p, horizontal = true, small) ?: findGutter(m, w, p, horizontal = false, small)
            ?: return listOf(trim(m, w, p))
        return cut.flatMap { split(m, w, it, small) }
    }

    private fun findGutter(m: BooleanArray, w: Int, p: PanelRect, horizontal: Boolean, small: (PanelRect, Double) -> Boolean): List<PanelRect>? {
        val len = if (horizontal) p.h else p.w
        val span = if (horizontal) p.w else p.h
        val prof = IntArray(len)
        for (y in p.y until p.b) for (x in p.x until p.r) if (m[y * w + x]) prof[if (horizontal) y - p.y else x - p.x]++
        val tol = maxOf(1, (span * 0.25).toInt()) // 거터를 넘는 말풍선·꼬리가 폭의 1/4 까지 있어도 거터로 본다
        // 줄 i 의 가장자리 화소 비율 (칸 테두리 확인용)
        fun cover(i: Int): Double = if (i < 0 || i >= len) 0.0 else prof[i].toDouble() / span
        // Kumiko 는 나누는 선이 LSD 선분과 50% 넘게 겹칠 때만 나눈다. 여기서는 빈 띠 양옆(10px 안)에
        // 칸 테두리처럼 길게 이어진 가장자리 줄(폭의 50% 이상)이 있어야 거터로 인정한다.
        fun framed(from: Int, step: Int) = (0 until 10).any { cover(from + it * step) >= 0.5 }
        // 거터 폭 상한: 처리 해상도 긴 변의 6% (칸 내부의 빈 영역을 거터로 오인하지 않게)
        val maxGutter = maxOf(4, (maxOf(p.w, p.h) * 0.06).toInt())
        var best: IntRange? = null
        var i = 0
        while (i < len) {
            if (prof[i] <= tol) {
                var j = i; while (j < len && prof[j] <= tol) j++
                val run = i until j
                if (i > 0 && j < len && run.count() <= maxGutter && framed(i - 1, -1) && framed(j, 1) && (best == null || run.count() > best.count())) best = run
                i = j
            } else i++
        }
        val g = best ?: return null
        val a: PanelRect; val b: PanelRect
        if (horizontal) { a = PanelRect(p.x, p.y, p.r, p.y + g.first); b = PanelRect(p.x, p.y + g.last + 1, p.r, p.b) }
        else { a = PanelRect(p.x, p.y, p.x + g.first, p.b); b = PanelRect(p.x + g.last + 1, p.y, p.r, p.b) }
        // 둘 다 칸이 될 만큼 커야 자른다
        if (small(a, 1.0) || small(b, 1.0)) return null
        return listOf(trim(m, w, a), trim(m, w, b))
    }

    /** 가장자리 화소가 있는 범위로 줄임 */
    private fun trim(m: BooleanArray, w: Int, p: PanelRect): PanelRect {
        var x0 = p.r; var y0 = p.b; var x1 = p.x - 1; var y1 = p.y - 1
        for (y in p.y until p.b) for (x in p.x until p.r) if (m[y * w + x]) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        return if (x1 < x0) p else PanelRect(x0, y0, x1 + 1, y1 + 1)
    }

    private fun unionOverlapping(ps: MutableList<PanelRect>): MutableList<PanelRect> {
        val out = ps.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in out.indices) for (j in i + 1 until out.size) if (out[i].overlapArea(out[j]) > 0) {
                out[i] = out[i].union(out[j]); out.removeAt(j); changed = true; break@loop
            }
        }
        return out
    }

    // ---- Kumiko merge_panels: 다른 칸에 50% 넘게 들어간 칸은 합침 ----
    private fun mergeContained(ps: MutableList<PanelRect>): MutableList<PanelRect> {
        val out = ps.sortedByDescending { it.area }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in out.indices) for (j in out.indices) {
                if (i == j) continue
                val a = out[i]; val b = out[j]
                if (a.overlapArea(b) > b.area / 2) { out[i] = a.union(b); out.removeAt(j); changed = true; break@loop }
            }
        }
        return out
    }

    // ---- Kumiko deoverlap_panels ----
    private fun deoverlap(ps: MutableList<PanelRect>): MutableList<PanelRect> {
        val out = ps.toMutableList()
        for (i in out.indices) for (j in out.indices) {
            if (i == j) continue
            val a = out[i]; val b = out[j]
            val ox = minOf(a.r, b.r) - maxOf(a.x, b.x); val oy = minOf(a.b, b.b) - maxOf(a.y, b.y)
            if (ox <= 0 || oy <= 0) continue
            if (ox < oy && a.x < b.x) { val mid = (maxOf(a.x, b.x) + minOf(a.r, b.r)) / 2; out[i] = a.copy(r = mid); out[j] = b.copy(x = mid) }
            else if (oy <= ox && a.y < b.y) { val mid = (maxOf(a.y, b.y) + minOf(a.b, b.b)) / 2; out[i] = a.copy(b = mid); out[j] = b.copy(y = mid) }
        }
        return out
    }

    // ---- 같은 줄/열 판정 (Kumiko same_row / same_col) ----
    private fun sameRow(a: PanelRect, b: PanelRect): Boolean {
        val (up, dn) = if (a.y <= b.y) a to b else b to a
        if (dn.y > up.b) return false
        if (dn.b < up.b) return true
        val inter = minOf(up.b, dn.b) - dn.y; val mh = minOf(up.h, dn.h)
        return mh == 0 || inter.toDouble() / mh >= 1.0 / 3
    }

    private fun sameCol(a: PanelRect, b: PanelRect): Boolean {
        val (l, rr) = if (a.x <= b.x) a to b else b to a
        if (rr.x > l.r) return false
        if (rr.r < l.r) return true
        val inter = minOf(l.r, rr.r) - rr.x; val mw = minOf(l.w, rr.w)
        return mw == 0 || inter.toDouble() / mw >= 1.0 / 3
    }

    // ---- Kumiko expand_panels: 이웃 칸 가장자리(거터 남김) 또는 전체 칸 테두리까지 넓힘 ----
    private fun expand(ps: MutableList<PanelRect>): MutableList<PanelRect> {
        val gx = ps.mapNotNull { p -> ps.filter { it.r <= p.x && sameRow(it, p) }.maxOfOrNull { p.x - it.r } }.minOrNull() ?: 1
        val gy = ps.mapNotNull { p -> ps.filter { it.b <= p.y && sameCol(it, p) }.maxOfOrNull { p.y - it.b } }.minOrNull() ?: 1
        val fx = ps.minOf { it.x }; val fy = ps.minOf { it.y }; val fr = ps.maxOf { it.r }; val fb = ps.maxOf { it.b }
        return ps.map { p ->
            val left = ps.filter { it !== p && it.r <= p.x && sameRow(it, p) }.maxByOrNull { it.r }
            val right = ps.filter { it !== p && it.x >= p.r && sameRow(it, p) }.minByOrNull { it.x }
            val top = ps.filter { it !== p && it.b <= p.y && sameCol(it, p) }.maxByOrNull { it.b }
            val bottom = ps.filter { it !== p && it.y >= p.b && sameCol(it, p) }.minByOrNull { it.y }
            PanelRect(
                minOf(p.x, left?.let { it.r + gx } ?: fx),
                minOf(p.y, top?.let { it.b + gy } ?: fy),
                maxOf(p.r, right?.let { it.x - gx } ?: fr),
                maxOf(p.b, bottom?.let { it.y - gy } ?: fb),
            )
        }.toMutableList()
    }

    // ---- 읽기 순서: Kumiko Panel.__lt__ 로 정렬 후 fix_panels_numbering ----
    internal fun order(ps: List<PanelRect>, dir: ReadingDirection): List<PanelRect> {
        fun before(a: PanelRect, o: PanelRect): Boolean {
            val ht = a.h / 10.0; val wt = a.w / 10.0
            if (o.y >= a.b - ht && o.y >= a.y - ht) return true
            if (a.y >= o.b - ht && a.y >= o.y - ht) return false
            if (o.x >= a.r - wt && o.x >= a.x - wt) return dir == ReadingDirection.LTR
            if (a.x >= o.r - wt && a.x >= o.x - wt) return dir == ReadingDirection.RTL
            return true
        }
        // 삽입 정렬 (비교가 추이적이지 않을 수 있어 sortWith 대신)
        val out = ArrayList<PanelRect>()
        for (p in ps.sortedBy { it.y }) {
            var k = out.size
            while (k > 0 && before(p, out[k - 1])) k--
            out.add(k, p)
        }
        // fix_panels_numbering: 위 칸·같은 줄의 앞 칸이 뒤에 있으면 앞으로
        var guard = 0
        do {
            var changed = false
            outer@ for (i in out.indices) {
                val p = out[i]
                val top = out.filter { it.b <= p.y && sameCol(it, p) }.maxByOrNull { it.b }
                val sides = out.filter { sameRow(it, p) && if (dir == ReadingDirection.LTR) it.r <= p.x else it.x >= p.r }
                for (n in listOfNotNull(top) + sides) {
                    val ni = out.indexOf(n)
                    if (i < ni) { out.add(ni, out.removeAt(i)); changed = true; break@outer }
                }
            }
        } while (changed && ++guard < 1000)
        return out
    }
}
