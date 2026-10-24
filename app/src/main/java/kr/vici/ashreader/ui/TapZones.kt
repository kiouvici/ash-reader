// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import kr.vici.ashreader.data.TapLayout

/** 탭 한 번의 뜻(F-64). */
enum class TapAction(val label: String, val code: Char) {
    PREV("이전", 'p'), NEXT("다음", 'n'), MENU("메뉴", 'm'), NONE("없음", 'x');

    companion object {
        fun of(c: Char): TapAction = entries.firstOrNull { it.code == c } ?: NONE
    }
}

/**
 * 탭 영역(F-64, 화면 설계서 4.1). 안드로이드 의존 없음 → 단위 시험.
 * [x]·[y] = 화면 기준 0..1. [rtl] = 오→왼 책(좌우 이전/다음이 바뀜), [leftHanded] = 왼손잡이(좌우 반전).
 * 둘 다 켜지면 두 번 뒤집혀 원래대로가 된다.
 */
object TapZones {
    /** 좌우 넘김 칸의 폭(화면 비율). 대표님 2026-10-25 00:27: 좌우는 좁게, 가운데(메뉴)는 넓게 — 30% → 20%. */
    const val EDGE = 0.2f

    fun action(x: Float, y: Float, layout: TapLayout, custom: String, leftHanded: Boolean, rtl: Boolean): TapAction {
        val flip = leftHanded != rtl
        // 내 방식은 사용자가 그린 그대로 쓴다(뒤집지 않음)
        val xx = if (flip && layout != TapLayout.CUSTOM) 1f - x else x
        val a = when (layout) {
            TapLayout.SIDES -> when {
                xx < EDGE -> TapAction.PREV
                xx > 1f - EDGE -> TapAction.NEXT
                else -> TapAction.MENU
            }
            TapLayout.L_SHAPE -> when {
                xx in EDGE..(1f - EDGE) && y in 0.3f..0.7f -> TapAction.MENU
                y < 0.25f || xx < EDGE -> TapAction.PREV
                else -> TapAction.NEXT
            }
            TapLayout.ANYWHERE -> if (xx in EDGE..(1f - EDGE) && y in 0.3f..0.7f) TapAction.MENU else TapAction.NEXT
            TapLayout.CUSTOM -> {
                val col = (xx * 3).toInt().coerceIn(0, 2)
                val row = (y * 3).toInt().coerceIn(0, 2)
                TapAction.of(custom.getOrElse(row * 3 + col) { 'm' })
            }
        }
        return a
    }

    /** 안내 그림(S-31)용: 3×3 칸 가운데 점마다의 뜻(실제 적용 결과). 행 우선. */
    fun grid(layout: TapLayout, custom: String, leftHanded: Boolean, rtl: Boolean): List<TapAction> =
        (0 until 9).map { i -> action((i % 3 + 0.5f) / 3f, (i / 3 + 0.5f) / 3f, layout, custom, leftHanded, rtl) }

    /** 내 방식 편집기: 칸을 누르면 이전 → 다음 → 메뉴 → 없음 순으로 바뀐다. */
    fun cycle(custom: String, index: Int): String {
        val c = custom.padEnd(9, 'm').toCharArray()
        val order = listOf('p', 'n', 'm', 'x')
        c[index] = order[(order.indexOf(c[index]).coerceAtLeast(0) + 1) % order.size]
        return String(c)
    }

    /** 내 방식에 메뉴 칸이 하나도 없으면 메뉴를 열 수 없으므로 저장 전에 확인한다. */
    fun hasMenu(custom: String) = custom.any { it == 'm' }
}
