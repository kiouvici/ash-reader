// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.comic

import org.junit.Assert.assertEquals
import org.junit.Test

/** 7C 읽기 순서(줄 띠 → 단) 와 칸 맞춤(PanelFraming) 단위 시험. 상자만으로 순서를 본다(그림 없음). */
class PanelOrderTest {
    private val det = PanelDetector()
    private fun r(x: Int, y: Int, rr: Int, b: Int) = PanelRect(x, y, rr, b)
    private fun check(expected: List<PanelRect>, dir: ReadingDirection) {
        // 입력 순서를 섞어도 같은 순서가 나와야 한다
        for (seed in 0 until 5) {
            val shuffled = expected.shuffled(kotlin.random.Random(seed))
            assertEquals("seed=$seed", expected, det.order(shuffled, dir))
        }
    }

    @Test fun misalignedRowsLtrAndRtl() {
        // 같은 줄의 오른칸이 7% 아래로 어긋남(다음 줄 왼칸과 위아래가 겹침)
        val a = r(50, 50, 480, 490); val b = r(504, 120, 950, 560)
        val c = r(50, 520, 480, 960); val d = r(504, 590, 950, 1030)
        check(listOf(a, b, c, d), ReadingDirection.LTR)
        check(listOf(b, a, d, c), ReadingDirection.RTL)
    }

    @Test fun fullWidthPanelBetweenRows() {
        val a = r(50, 50, 480, 400); val b = r(504, 50, 950, 400)
        val wide = r(50, 424, 950, 560)                         // 줄 전체를 차지하는 가로로 긴 칸
        val c = r(50, 584, 300, 1000); val d = r(324, 584, 620, 1000); val e = r(644, 584, 950, 1000)
        check(listOf(a, b, wide, c, d, e), ReadingDirection.LTR)
        check(listOf(b, a, wide, e, d, c), ReadingDirection.RTL)
    }

    @Test fun tallPanelBesideStack() {
        // 세로로 긴 칸 옆에 두 줄짜리 칸 묶음(줄마다 두 칸) — 묶음을 줄 단위로 다 읽고 긴 칸으로
        val p1 = r(0, 0, 264, 310); val p2 = r(265, 0, 494, 310)
        val p3 = r(0, 330, 245, 475); val p4 = r(247, 330, 494, 476)
        val tall = r(496, 0, 768, 476)
        val bottom = r(0, 500, 768, 800)
        check(listOf(p1, p2, p3, p4, tall, bottom), ReadingDirection.LTR)
        // 오른→왼: 긴 칸이 오른쪽이면 먼저
        check(listOf(tall, p2, p1, p4, p3, bottom), ReadingDirection.RTL)
    }

    @Test fun framingWidePanelFitsWidth() {
        // 휴대폰 세로 화면 1080×2340 에 가로로 긴 칸(1000×150)
        val f = PanelFraming.fit(40f, 900f, 1040f, 1050f, 1080f, 2340f)
        val left = f.tx + 40f * f.scale; val right = f.tx + 1040f * f.scale
        val top = f.ty + 900f * f.scale; val bottom = f.ty + 1050f * f.scale
        assertEquals(1080f * 0.96f, right - left, 0.5f)       // 폭 맞춤, 좌우 2% 여백
        assertEquals((1080f - (right - left)) / 2f, left, 0.5f)
        assertEquals(2340f / 2f, (top + bottom) / 2f, 0.5f)   // 위아래 가운데
        assertEquals(150f * f.scale, bottom - top, 0.5f)
    }

    @Test fun framingTallPanelFitsHeight() {
        val f = PanelFraming.fit(100f, 0f, 300f, 2000f, 1080f, 2340f)
        assertEquals(2340f * 0.96f, 2000f * f.scale, 0.5f)
        assertEquals(1080f / 2f, f.tx + 200f * f.scale, 0.5f)
    }

    @Test fun framingDegenerate() {
        assertEquals(PanelFraming.Fit(1f, 0f, 0f), PanelFraming.fit(10f, 10f, 10f, 20f, 100f, 100f))
    }
}
