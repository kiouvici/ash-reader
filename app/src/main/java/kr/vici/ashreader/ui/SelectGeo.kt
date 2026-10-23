// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import kr.vici.ashreader.doc.NRect
import kr.vici.ashreader.doc.TextGeo
import kr.vici.ashreader.doc.TextPage

/**
 * 화면 안 한 쪽이 차지하는 칸(px). 쪽 그림은 이 칸 안에 비율 유지로 가운데 맞춰 그려진다([TextGeo.contentRect]).
 * 확대·이동(ZoomPager)에서는 칸이 화면보다 크거나 화면 밖으로 나갈 수 있다(왼쪽·위가 음수).
 */
data class PageSlot(val page: Int, val left: Float, val top: Float, val width: Float, val height: Float) {
    fun content(tp: TextPage): NRect {
        val c = TextGeo.contentRect(tp.aspect, width, height)
        return NRect(left + c.l, top + c.t, left + c.r, top + c.b)
    }
}

/** 글자 선택 좌표 계산(7B 원본 보기 글자 선택). 순수 계산 → 단위 시험. */
object SelectGeo {
    /**
     * 확대 화면(ZoomPager)의 쪽 칸: 비트맵([bmpW]×[bmpH])을 배율 [scale] 로 ([ox], [oy]) 에 그렸을 때의 화면 칸.
     * 비트맵은 쪽을 비율 유지로 가운데 맞춘 그림([fitInto])이라, 칸 안 내용 상자 = 쪽.
     */
    fun zoomSlot(page: Int, ox: Float, oy: Float, bmpW: Int, bmpH: Int, scale: Float) =
        PageSlot(page, ox, oy, bmpW * scale, bmpH * scale)

    /**
     * 가운데([cx], [cy]) 기준 [zoom] 배 + 가로 [panX] 이동(graphicsLayer, 스크롤 보기 확대)으로 그린 칸의 화면 자리.
     */
    fun scaledSlot(s: PageSlot, cx: Float, cy: Float, zoom: Float, panX: Float) = PageSlot(
        s.page, (s.left - cx) * zoom + cx + panX, (s.top - cy) * zoom + cy, s.width * zoom, s.height * zoom,
    )

    /** 화면 좌표 하나(px)를 확대 전 자리로: (v - pan - c) / zoom + c. */
    fun unscale(v: Float, c: Float, zoom: Float, pan: Float): Float = (v - pan - c) / (if (zoom > 0f) zoom else 1f) + c

    /** 화면 점(px) → 쪽 정규 좌표(0..1, 쪽 밖이면 범위 밖 값). */
    fun toPage(slot: PageSlot, tp: TextPage, x: Float, y: Float): Pair<Float, Float> {
        val b = slot.content(tp)
        val w = (b.r - b.l).takeIf { it > 0f } ?: 1f
        val h = (b.b - b.t).takeIf { it > 0f } ?: 1f
        return (x - b.l) / w to (y - b.t) / h
    }

    /** 쪽 정규 상자 → 화면 상자(px). */
    fun toScreen(slot: PageSlot, tp: TextPage, r: NRect): NRect {
        val b = slot.content(tp)
        val w = b.r - b.l; val h = b.b - b.t
        return NRect(b.l + r.l * w, b.t + r.t * h, b.l + r.r * w, b.t + r.b * h)
    }

    /** 화면 점에 가장 가까운 글자(없으면 -1). */
    fun charAt(slot: PageSlot, tp: TextPage, x: Float, y: Float, maxDist: Float = 0.08f): Int {
        val (nx, ny) = toPage(slot, tp, x, y)
        return TextGeo.charAt(tp, nx, ny, maxDist)
    }

    /**
     * 복사·사전·검색 띠 자리(왼쪽 위 px). 고른 글자 위([selTop])에 두고, 위가 좁으면 아래([selBottom] + [below]).
     * 화면([screenW]×[screenH]) 밖으로 나가지 않게 [margin] 안쪽으로 붙인다(확대해서 칸이 화면보다 클 때).
     * [centerX] = 고른 글자 가운데 x.
     */
    fun barPos(
        centerX: Float, selTop: Float, selBottom: Float,
        barW: Float, barH: Float, screenW: Float, screenH: Float,
        topLimit: Float, below: Float, margin: Float = 8f,
    ): Pair<Float, Float> {
        val x = (centerX - barW / 2f).coerceIn(margin, (screenW - barW - margin).coerceAtLeast(margin))
        val above = selTop - barH - margin
        val y = if (above >= topLimit) above else selBottom + below
        return x to y.coerceIn(topLimit.coerceAtMost(screenH - barH - margin), (screenH - barH - margin).coerceAtLeast(0f))
    }
}
