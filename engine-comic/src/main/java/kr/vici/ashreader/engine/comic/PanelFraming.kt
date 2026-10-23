// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.comic

/**
 * 칸 단위 보기(F-72)에서 고른 칸을 화면에 맞추는 배율·이동 (안드로이드 API 없이 JVM 시험 가능).
 *
 * 칸 사각형(화면 좌표, 확대 전)을 화면 [viewW]×[viewH] 가운데에 놓고, 칸이 화면에 다 들어가는 가장 큰 배율에
 * 여백 [margin](양쪽 합, 기본 4%)을 둔다. 맞추는 축은 칸 비율로 정해진다:
 *  - 가로로 긴 칸(칸 비율 > 화면 비율): 폭 맞춤 — 좌우에 작은 여백, 위아래는 칸 높이만큼만(남는 곳에 이웃 칸이 보임)
 *  - 세로로 긴 칸: 높이 맞춤
 * 결과: 화면 좌표 = [tx] + [scale] × 원래 좌표 (변환 원점 0,0).
 */
object PanelFraming {
    data class Fit(val scale: Float, val tx: Float, val ty: Float)

    fun fit(left: Float, top: Float, right: Float, bottom: Float, viewW: Float, viewH: Float, margin: Float = 0.04f): Fit {
        val w = right - left; val h = bottom - top
        if (w <= 0f || h <= 0f || viewW <= 0f || viewH <= 0f) return Fit(1f, 0f, 0f)
        val s = minOf(viewW / w, viewH / h) * (1f - margin)
        val cx = (left + right) / 2f; val cy = (top + bottom) / 2f
        return Fit(s, viewW / 2f - cx * s, viewH / 2f - cy * s)
    }
}
