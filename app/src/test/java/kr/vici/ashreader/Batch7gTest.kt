// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import kr.vici.ashreader.doc.CoverRule
import kr.vici.ashreader.doc.PageFrame
import kr.vici.ashreader.doc.TextGeo
import kr.vici.ashreader.doc.TextPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 7G: 길게 누른 곳 낱말 붙기(빈칸 선택 없음), 표지 단독 판정, 한 번만 보기 정리 시점, 정보줄 띠. */
class Batch7gTest {

    /**
     * 줄마다 글을 고른 폭으로 놓은 쪽(정규화). 쪽 100×100, 글자 폭 [cw], 줄 높이 [lh], 줄 사이 [gap].
     * 줄 끝에는 '\n'(상자 0).
     */
    private fun page(vararg lines: String, cw: Float = 0.04f, lh: Float = 0.04f, gap: Float = 0.03f, x0: Float = 0.1f, y0: Float = 0.1f): TextPage {
        val sb = StringBuilder()
        val boxes = ArrayList<Float>()
        lines.forEachIndexed { li, line ->
            val top = y0 + li * (lh + gap)
            line.forEachIndexed { k, c ->
                sb.append(c)
                val l = x0 + k * cw
                boxes += listOf(l, top, l + cw, top + lh)
            }
            sb.append('\n'); boxes += listOf(0f, 0f, 0f, 0f)
        }
        return TextPage(sb.toString(), boxes.toFloatArray(), 1f)
    }

    private fun wordAtPress(p: TextPage, x: Float, y: Float, tol: Float = 0.06f): String? {
        val i = TextGeo.wordCharNear(p, x, y, tol, tol)
        if (i < 0) return null
        val w = TextGeo.wordAt(p.text, i)
        return p.text.substring(w.first, w.last + 1)
    }

    @Test fun pressOnSpaceSnapsToNeighbourWord() {
        val p = page("바람과 별을", "노래하는 마음")
        // '바람과' 다음 빈칸(4번째 칸) 한가운데
        val x = 0.1f + 3.5f * 0.04f
        val got = wordAtPress(p, x, 0.12f)
        assertTrue("빈칸을 눌렀는데 낱말이 아님: $got", got == "바람과" || got == "별을")
    }

    @Test fun pressBetweenLinesSnapsToNearestLine() {
        val p = page("바람과 별을", "노래하는 마음")
        // 첫 줄 아래(0.14)와 둘째 줄 위(0.17) 사이, 둘째 줄 쪽에 가깝게
        assertEquals("노래하는", wordAtPress(p, 0.15f, 0.165f))
        assertEquals("바람과", wordAtPress(p, 0.15f, 0.145f))
    }

    @Test fun pressFarFromTextSelectsNothing() {
        val p = page("바람과 별을", "노래하는 마음")
        // 글 아래 한참(줄 높이·24dp 보다 멀리) — 아무것도 고르지 않는다
        assertEquals(null, wordAtPress(p, 0.2f, 0.6f))
        // 오른쪽 여백 멀리
        assertEquals(null, wordAtPress(p, 0.95f, 0.12f))
    }

    @Test fun neverPicksWhitespace() {
        // 빈칸만 있는 줄(상자는 있음)
        val p = page("    ", "가")
        val i = TextGeo.wordCharNear(p, 0.15f, 0.12f, 0.06f, 0.06f)
        assertTrue(i < 0 || !p.text[i].isWhitespace())
        assertTrue(TextGeo.blank(p.text, 0, 4))
        assertFalse(TextGeo.blank(p.text, 0, 7))
    }

    @Test fun coverRule() {
        // 쪽 거의 전체 그림 + 글자 없음 → 표지
        assertTrue(CoverRule.isCover(floatArrayOf(0f, 0f, 1f, 0.95f), 0, 20))
        // 그림 + 제목 몇 자 → 표지
        assertTrue(CoverRule.isCover(floatArrayOf(0.05f, 0.1f, 0.95f, 0.8f), 12, 20))
        // 글로 시작(그림 없음·작은 그림) → 표지 아님
        assertFalse(CoverRule.isCover(floatArrayOf(), 600, 20))
        assertFalse(CoverRule.isCover(floatArrayOf(0.3f, 0.1f, 0.6f, 0.3f), 400, 20))
        // 큰 그림이지만 글이 가득 → 표지 아님
        assertFalse(CoverRule.isCover(floatArrayOf(0f, 0f, 1f, 0.6f), 500, 20))
        // 한 쪽짜리는 펼칠 일이 없음
        assertFalse(CoverRule.isCover(floatArrayOf(0f, 0f, 1f, 1f), 0, 1))
        // 쪽 밖으로 넘친 그림은 쪽 안 넓이만
        assertFalse(CoverRule.isCover(floatArrayOf(0f, 0.8f, 1f, 2f), 0, 20))
    }

    @Test fun onceCopyKeptWhileActivityIsRecreated() {
        // 앱 안에서 읽기 화면을 닫음 → 정리
        assertTrue(OnceClose.realClose(destroying = false, finishing = false, changingConfig = false))
        // 회전
        assertFalse(OnceClose.realClose(destroying = true, finishing = false, changingConfig = true))
        // '활동 유지 안 함'·메모리 부족으로 Activity 만 없어짐(다시 살아남) → 지우지 않음
        assertFalse(OnceClose.realClose(destroying = true, finishing = false, changingConfig = false))
        // 정말 끝냄
        assertTrue(OnceClose.realClose(destroying = true, finishing = true, changingConfig = false))
    }

    @Test fun infoLineSitsInsideReservedBand() {
        // 정보줄 띠: 쪽 배치가 비운 아래 몫 = 막대 + 정보줄 + 틈. 정보줄을 그 안에 놓으면 본문 마지막 줄(몫 위)과 겹치지 않는다.
        for (scale in listOf(1f, 1.3f, 2f)) for (nav in listOf(0f, 24f, 48f, 60f)) {
            val f = PageFrame.of(24f, 0f, 0f, 0f, 0f, nav, 0f, 0f, infoShown = true, infoTop = false, fontScale = scale)
            val band = PageFrame.infoLineDp(scale)
            val bar = f.bottom - band - PageFrame.INFO_GAP_DP
            assertEquals(nav, bar, 0.01f)
            // 정보줄 위 끝(화면 아래에서) = bar + band ≤ 본문 아래 끝(화면 아래에서) - 틈
            assertTrue(bar + band <= f.bottom - PageFrame.INFO_GAP_DP + 0.01f)
        }
    }
}
