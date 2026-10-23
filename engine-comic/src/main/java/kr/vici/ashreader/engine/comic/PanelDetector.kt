// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 칸 인식 (F-72). Kumiko(njean42/kumiko v1.5, AGPL-3.0) lib/page.py·panel.py 의 처리 순서를 순수 Kotlin 으로 옮겼다.
//   회색조 → Sobel(|gx|·0.5+|gy|·0.5) → 임계 100 → 바깥 윤곽(여기서는 8-연결 성분의 외접 사각형)
//   → 아주 작은 조각 버림 → 작은 조각 묶기(가까운 것끼리) → 큰 칸 나누기(거터 투영, Kumiko 의 선분 분할 대신)
//   → 작은 칸 제외 → 포함된 칸 병합 → 겹침 해소 → 이웃·거터까지 확장 → 읽기 순서(ltr/rtl) 보정.
// 다른 점: Kumiko 는 다각형 근사·LSD 선분으로 칸을 쪼개고 '큰 칸 묶기'를 하는데, 이 판은 OpenCV 없이
// 성분 내부의 빈 줄(거터) 투영으로 쪼갠다. 결과 형식(사각형 목록 + 순서)은 Kumiko 와 같다.
//
// 7C(I-731) 보강 — 실제 만화 32쪽 손 정답·합성 회귀 쪽으로 재며 고쳤다(docs/이슈목록.md):
//   · 가로/세로로 긴 칸(띠): '작은 칸' 기준(폭·높이 1/10)에 걸려 버려지던 것을, 긴 변이 쪽의 절반 이상이면 남긴다.
//     띠를 만드는 자르기는 거터 양 끝을 칸 테두리가 꿰뚫지 않을 때만(칸 안 설명 상자 밑을 자르지 않게).
//   · 굵은 잉크 선(지평선·기둥)의 속을 거터로 오인해 칸·스플래시가 갈리던 것: 거터는 양옆보다 밝아야 한다.
//   · 스플래시(쪽 대부분을 덮는 칸 하나)는 작은 조각을 버리고 쪽 전체로, 테두리 없이 쪽 끝까지 가는 단색 칸은 빈 띠 채우기로.
//   · 읽기 순서: 줄 띠(어긋난 줄 허용) → 단(한 번에 하나씩) 의 XY 순서. 못 가르면 Kumiko 정렬.
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
        val pageArea = w.toLong() * h
        val ink = InkCheck(g, ringBackground(g))
        val edge = sobelMask(g)
        val minW = w * minPanelRatio; val minH = h * minPanelRatio
        // 7C: 가로/세로로 긴 칸(띠)은 한 변이 1/10 보다 짧아도 칸이다 — 긴 변이 쪽의 절반 이상이고 짧은 변이 4%(세로)·6%(가로) 이상
        fun small(p: PanelRect, k: Double = 1.0): Boolean {
            if (p.w >= minW * k && p.h >= minH * k) return false
            val strip = (p.w >= w * 0.5 * k && p.h >= h * 0.04 * k) || (p.h >= h * 0.5 * k && p.w >= w * 0.06 * k)
            return !strip
        }

        // 1) 성분 → 외접 사각형, 아주 작은 것(작은 칸 기준의 1/10) 버림
        var panels = components(edge, w, h).filter { !small(it, 0.1) }.toMutableList()
        // 2) 작은 조각 묶기
        panels = groupSmall(panels, ::small)
        // 2') 서로 겹치는 사각형은 합친 뒤 나눈다 (말풍선이 거터를 건너 두 칸이 한 성분이 된 경우)
        panels = unionOverlapping(panels)
        // 3) 거터로 나누기 (재귀)
        panels = panels.flatMap { split(edge, w, it, ::small, ink) }.toMutableList()
        // 4) 작은 칸 제외, 5) 병합, 6) 겹침 해소, 4') 다시 제외
        panels = panels.filter { !small(it) }.toMutableList()
        panels = mergeContained(panels)
        panels = deoverlap(panels)
        panels = panels.filter { !small(it) }.toMutableList()
        if (panels.isEmpty()) return listOf(PanelRect(0, 0, w, h))
        // 6') 7C 스플래시: 큰 칸 하나가 쪽 대부분(62%)이면 나머지 작은 조각(제목 글자·말풍선·그림 조각, 쪽의 10% 미만)은 버린다
        val big = panels.maxByOrNull { it.area }!!
        if (big.area >= pageArea * 0.62) panels = panels.filter { it === big || it.area >= pageArea * 0.10 }.toMutableList()
        // 7) 확장
        panels = expand(panels)
        // 7') 7C 빈 띠 채우기: 칸들 위·아래·왼·오른쪽 쪽 끝 띠에 칸이 없는데 그림이 꽉 차 있으면(테두리 없이 쪽 끝까지 가는
        // 단색·그러데이션 칸: Sobel 로는 속이 비어 조각만 남는다) 그 띠를 칸으로 더한다. 제목 글자·여백은 차 있지 않아 빠진다.
        panels = fillEdgeBands(g, ink.bg, panels)
        // 7'') 한 칸만 남고 쪽 절반 넘게 덮으면 스플래시: 쪽 전체
        if (panels.size == 1 && panels[0].area >= pageArea / 2) panels = mutableListOf(PanelRect(0, 0, w, h))
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
    private fun split(m: BooleanArray, w: Int, p: PanelRect, small: (PanelRect, Double) -> Boolean, ink: InkCheck): List<PanelRect> {
        if (small(p, 2.0) && p.w < 2 * w * minPanelRatio && p.h < 2 * w * minPanelRatio) return listOf(p)
        val cut = findGutter(m, w, p, horizontal = true, small, ink) ?: findGutter(m, w, p, horizontal = false, small, ink)
            ?: return listOf(trim(m, w, p))
        return cut.flatMap { split(m, w, it, small, ink) }
    }

    private fun findGutter(m: BooleanArray, w: Int, p: PanelRect, horizontal: Boolean, small: (PanelRect, Double) -> Boolean, ink: InkCheck): List<PanelRect>? {
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
                if (i > 0 && j < len && run.count() <= maxGutter && framed(i - 1, -1) && framed(j, 1) && (best == null || run.count() > best.count()) &&
                    !ink.dark(if (horizontal) PanelRect(p.x, p.y + i, p.r, p.y + j) else PanelRect(p.x + i, p.y, p.x + j, p.b), horizontal)) best = run
                i = j
            } else i++
        }
        val g = best ?: return null
        val a: PanelRect; val b: PanelRect
        if (horizontal) { a = PanelRect(p.x, p.y, p.r, p.y + g.first); b = PanelRect(p.x, p.y + g.last + 1, p.r, p.b) }
        else { a = PanelRect(p.x, p.y, p.x + g.first, p.b); b = PanelRect(p.x + g.last + 1, p.y, p.r, p.b) }
        // 둘 다 칸이 될 만큼 커야 자른다
        if (small(a, 1.0) || small(b, 1.0)) return null
        // 7C: 띠 칸(한 변이 1/10 미만)이 생기는 자르기는 거터 양 끝을 테두리가 꿰뚫지 않을 때만(칸 안 설명 상자 밑을 자르지 않게)
        val minW = w * minPanelRatio; val minH = m.size / w * minPanelRatio
        fun boxy(q: PanelRect) = q.w >= minW && q.h >= minH
        if (!boxy(a) || !boxy(b)) {
            val band = maxOf(2, (span * 0.03).toInt()); val run = g.count(); val need = maxOf(1, (run * 0.8).toInt())
            for (c in (0 until band) + (span - band until span)) {
                var n = 0
                for (k in g) { val x = if (horizontal) p.x + c else p.x + k; val y = if (horizontal) p.y + k else p.y + c; if (m[y * w + x]) n++ }
                if (n >= need) return null
            }
        }
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

    /**
     * 7C: 거터 밝기 확인. Sobel 로는 굵은(2px+) 검은 선의 속이 가장자리 화소 없는 빈 띠로 보이고 양옆이 선의 가장자리라
     * '테두리 낀 거터'로 오인된다(스플래시 속 지평선·기둥, 칸 안 굵은 선). 거터는 바탕색(밝음)이고 양옆 테두리·그림보다
     * 밝다. 바탕이 밝은 쪽에서 띠가 바로 옆 띠보다 어두우면(잉크 선) 거터가 아니다. (검은 바탕 쪽은 거터가 검으므로 보지 않는다.)
     */
    private class InkCheck(val g: GrayImage, val bg: Int) {
        private fun mean(r: PanelRect): Int {
            val x0 = r.x.coerceIn(0, g.width); val x1 = r.r.coerceIn(0, g.width); val y0 = r.y.coerceIn(0, g.height); val y1 = r.b.coerceIn(0, g.height)
            if (x1 <= x0 || y1 <= y0) return -1
            var s = 0L; var n = 0
            val sy = maxOf(1, (y1 - y0) / 32); val sx = maxOf(1, (x1 - x0) / 64)
            for (y in y0 until y1 step sy) for (x in x0 until x1 step sx) { s += g[x, y]; n++ }
            return (s / n).toInt()
        }
        /** 띠 [r] (가로 거터면 horizontal) 이 잉크 선인가 */
        fun dark(r: PanelRect, horizontal: Boolean): Boolean {
            if (bg < 100 || r.w <= 0 || r.h <= 0) return false
            val m = mean(r)
            if (m < bg - 90) return true
            val k = 4
            val a = if (horizontal) mean(PanelRect(r.x, r.y - k, r.r, r.y)) else mean(PanelRect(r.x - k, r.y, r.x, r.b))
            val b = if (horizontal) mean(PanelRect(r.x, r.b, r.r, r.b + k)) else mean(PanelRect(r.r, r.y, r.r + k, r.b))
            val side = listOf(a, b).filter { it >= 0 }
            return side.isNotEmpty() && m < side.average() - 40
        }
    }

    private fun fillEdgeBands(g: GrayImage, bg: Int, ps: MutableList<PanelRect>): MutableList<PanelRect> {
        val w = g.width; val h = g.height
        val out = ps.toMutableList()
        val minY = ps.minOf { it.y }; val maxB = ps.maxOf { it.b }; val minX = ps.minOf { it.x }; val maxR = ps.maxOf { it.r }
        val gap = maxOf(2, (minOf(w, h) * 0.01).toInt())
        val bands = listOf(
            PanelRect(0, 0, w, minY - gap), PanelRect(0, maxB + gap, w, h),
            PanelRect(0, minY, minX - gap, maxB), PanelRect(maxR + gap, minY, w, maxB),
        )
        for (b in bands) {
            if (b.w < w * 0.06 || b.h < h * 0.06 || b.area < w.toLong() * h * 0.06) continue
            // 내용(바탕과 42 넘게 다른 화소)의 범위와 채움 비율
            var x0 = b.r; var y0 = b.b; var x1 = b.x; var y1 = b.y; var n = 0L; var tot = 0L
            for (y in b.y until b.b step 2) for (x in b.x until b.r step 2) {
                tot++
                if (Math.abs(g[x, y] - bg) > 42) { n++; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
            }
            if (n == 0L) continue
            val r = PanelRect(x0, y0, minOf(b.r, x1 + 2), minOf(b.b, y1 + 2))
            val fill = n.toDouble() / maxOf(1L, tot) * b.area / maxOf(1L, r.area)
            if (r.area >= w.toLong() * h * 0.06 && fill >= 0.6) out += r
        }
        return out
    }

    /** 쪽 가장자리 고리(긴 변의 2%)에서 가장 흔한 밝기(16단계 묶음 → 묶음 안 평균) */
    private fun ringBackground(g: GrayImage): Int {
        val w = g.width; val h = g.height
        val ring = maxOf(2, (maxOf(w, h) * 0.02).toInt())
        val hist = IntArray(16); val sum = LongArray(16)
        for (y in 0 until h step 2) for (x in 0 until w step 2) {
            if (x < ring || y < ring || x >= w - ring || y >= h - ring) { val v = g[x, y]; hist[v shr 4]++; sum[v shr 4] += v.toLong() }
        }
        var b = 0; for (i in 1 until 16) if (hist[i] > hist[b]) b = i
        return if (hist[b] == 0) 255 else (sum[b] / hist[b]).toInt()
    }

    // ---- 읽기 순서 ----
    /**
     * 칸 사각형들을 줄 띠 → 단 순으로 재귀 정렬(XY-cut 순서).
     *  - 가로로 가르는 선이 어느 칸도(위아래 각 18% 여유를 빼고) 지나지 않으면 줄 띠로 나눠 위→아래.
     *    같은 줄의 칸 위아래가 조금 어긋나도 한 줄로 묶이고, 줄 전체를 차지하는 넓은 칸은 혼자 한 띠가 된다.
     *  - 아니면 세로 선으로 단을 나눠 ltr 은 왼→오, rtl 은 오→왼(세로로 긴 칸 옆에 쌓인 칸들).
     *  - 둘 다 안 되면(바람개비 배치 등) Kumiko 정렬로.
     */
    internal fun order(ps: List<PanelRect>, dir: ReadingDirection): List<PanelRect> {
        if (ps.size <= 1) return ps
        bands(ps, horizontal = true)?.let { bs -> return bs.flatMap { order(it, dir) } }
        bands(ps, horizontal = false)?.let { bs -> return (if (dir == ReadingDirection.LTR) bs else bs.reversed()).flatMap { order(it, dir) } }
        return kumikoOrder(ps, dir)
    }

    private fun bands(ps: List<PanelRect>, horizontal: Boolean): List<List<PanelRect>>? {
        fun lo(p: PanelRect) = if (horizontal) p.y + p.h * 0.18 else p.x + p.w * 0.18
        fun hi(p: PanelRect) = if (horizontal) p.b - p.h * 0.18 else p.r - p.w * 0.18
        val sorted = ps.sortedBy { lo(it) }
        val out = ArrayList<MutableList<PanelRect>>()
        var end = Double.NEGATIVE_INFINITY
        for (p in sorted) {
            if (out.isEmpty() || lo(p) >= end) { out += mutableListOf(p); end = hi(p) }
            else { out.last() += p; end = maxOf(end, hi(p)) }
        }
        if (out.size < 2) return null
        if (horizontal || out.size == 2) return out
        // 단은 한 번에 하나씩 가른다: 실제 틈(줄이지 않은 좌표)이 가장 넓은 곳에서 둘로. 그래야 각 쪽이 다시 줄 띠로
        // 묶인다(왼쪽 칸 묶음 + 세로로 긴 오른칸일 때 왼쪽 묶음을 줄 단위로 읽음).
        var best = 1; var bestGap = Int.MIN_VALUE
        for (i in 1 until out.size) {
            val gap = out[i].minOf { it.x } - out.subList(0, i).maxOf { g -> g.maxOf { it.r } }
            if (gap > bestGap) { bestGap = gap; best = i }
        }
        return listOf(out.subList(0, best).flatten(), out.subList(best, out.size).flatten())
    }

    /** Kumiko Panel.__lt__ 로 정렬 후 fix_panels_numbering (XY 순서로 못 가를 때만) */
    private fun kumikoOrder(ps: List<PanelRect>, dir: ReadingDirection): List<PanelRect> {
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
