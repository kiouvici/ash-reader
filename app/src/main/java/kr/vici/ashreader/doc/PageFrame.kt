// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.doc

import java.util.Locale

/**
 * eBook 보기(다시 배치) 쪽의 가장자리 몫(I-711). 단위는 dp(= MuPDF 배치 단위 pt).
 *
 * 쪽 그림은 화면 전체(상태 표시줄·카메라 구멍·내비게이션 줄 아래까지)에 그려지므로, 글자는
 * 위쪽 = 상태 표시줄·카메라 구멍, 아래쪽 = 정보줄(F-45) + 내비게이션 줄을 비우고 배치해야 한다.
 * 이 값은 MuPDF `@page` 여백으로 들어가 **모든 쪽**에 적용된다(본문 `body` 여백은 장 첫 쪽·끝 쪽에만 걸려
 * 중간 쪽 글자가 화면 맨 위에 붙고 맨 아래 줄이 정보줄과 겹치던 문제).
 */
data class PageFrame(val top: Float, val bottom: Float, val left: Float, val right: Float) {
    companion object {
        /** 가장자리 몫 없음(표지 만들기·스크롤 보기). */
        val NONE = PageFrame(0f, 0f, 0f, 0f)

        /** 정보줄 글자 줄 높이(sp). ReaderInfoLine 이 이 값으로 줄 높이를 고정한다. */
        const val INFO_LINE_SP = 16f

        /** 정보줄 위아래 안쪽 여백 합(dp, 6+6). */
        const val INFO_PAD_DP = 12f

        /** 정보줄과 본문 마지막 줄 사이 숨 쉴 틈(dp). */
        const val INFO_GAP_DP = 4f

        /** 정보줄 한 줄이 차지하는 높이(dp). 글자 크기 배율이 크면 그만큼 키운다(비선형 배율보다 늘 크거나 같다). */
        fun infoLineDp(fontScale: Float): Float = INFO_PAD_DP + INFO_LINE_SP * fontScale.coerceAtLeast(1f)

        /**
         * 화면 가장자리 값(dp)으로 쪽 가장자리 몫을 만든다.
         * [statusTop]·[navBottom] 등은 막대가 숨어 있어도 잡히는 값(…IgnoringVisibility)을 넘겨야
         * 메뉴를 열고 닫을 때 쪽 배치가 바뀌지 않는다.
         * [infoShown] = 정보줄에 보일 항목이 있음, [infoTop] = 정보줄이 위(F-45 '정보줄을 위에').
         */
        fun of(
            statusTop: Float, cutoutTop: Float, cutoutBottom: Float, cutoutLeft: Float, cutoutRight: Float,
            navBottom: Float, navLeft: Float, navRight: Float,
            infoShown: Boolean, infoTop: Boolean, fontScale: Float,
        ): PageFrame {
            val info = if (infoShown) infoLineDp(fontScale) + INFO_GAP_DP else 0f
            val topBar = maxOf(statusTop, cutoutTop, 0f)
            val bottomBar = maxOf(navBottom, cutoutBottom, 0f)
            return PageFrame(
                top = topBar + if (infoTop) info else 0f,
                bottom = bottomBar + if (infoTop) 0f else info,
                left = maxOf(cutoutLeft, navLeft, 0f),
                right = maxOf(cutoutRight, navRight, 0f),
            )
        }

        /**
         * 쪽 여백 CSS. [marginVTenths]·[marginHTenths] = 사용자 여백(F-19, 0.1em 단위), [em] = 배치 글자 크기(pt).
         * 위아래는 `@page`(모든 쪽), 좌우 사용자 여백은 `body`(문서 기본 여백을 덮어씀)로 넣는다.
         */
        fun css(frame: PageFrame, marginVTenths: Int, marginHTenths: Int, em: Float): String {
            val v = marginVTenths / 10f * em
            fun px(x: Float) = "%.1fpx".format(Locale.ROOT, x)
            fun ems(t: Int) = "%.2fem".format(Locale.ROOT, t / 10f)
            return "@page { margin: ${px(frame.top + v)} ${px(frame.right)} ${px(frame.bottom + v)} ${px(frame.left)} !important; }\n" +
                "body { margin: 0 ${ems(marginHTenths)} 0 ${ems(marginHTenths)} !important; }"
        }

        /** 글자가 놓이는 높이(쪽 나누기에 쓰는 높이). */
        fun contentHeight(pageH: Float, frame: PageFrame, marginVTenths: Int, em: Float): Float =
            pageH - frame.top - frame.bottom - 2 * (marginVTenths / 10f * em)
    }
}
