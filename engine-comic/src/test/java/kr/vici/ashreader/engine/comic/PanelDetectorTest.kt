// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelDetectorTest {
    private val det = PanelDetector()

    @Test
    fun syntheticSuiteRecognitionRateAtLeast80() {
        var total = PanelScoring.Score.ZERO
        var maxMs = 0.0
        for ((i, p) in SyntheticPage.suite().withIndex()) {
            val t0 = System.nanoTime()
            val got = det.detect(p.image, p.direction)
            val ms = (System.nanoTime() - t0) / 1e6
            if (i > 0) maxMs = maxOf(maxMs, ms) // 첫 쪽은 JIT 예열
            val s = PanelScoring.score(p.truth, got.panels)
            println("ASH-PANEL page=$i dir=${p.direction} truth=${s.truth} detected=${s.detected} correct=${s.correct} wrongOrder=${s.matchedWrongOrder} ms=${"%.1f".format(ms)}")
            total += s
        }
        println("ASH-PANEL total truth=${total.truth} correct=${total.correct} rate=${"%.3f".format(total.rate)} maxMs=${"%.1f".format(maxMs)}")
        assertTrue("인식률 ${total.rate}", total.rate >= 0.8)
    }

    @Test
    fun readingOrderFollowsDirection() {
        val ltr = SyntheticPage.make(7, rows = listOf(listOf(0.5, 0.5)), dir = ReadingDirection.LTR, bubbles = false)
        val rtl = SyntheticPage.make(7, rows = listOf(listOf(0.5, 0.5)), dir = ReadingDirection.RTL, bubbles = false)
        val a = det.detect(ltr.image, ReadingDirection.LTR).panels
        val b = det.detect(rtl.image, ReadingDirection.RTL).panels
        assertEquals(2, a.size); assertEquals(2, b.size)
        assertTrue(a[0].x < a[1].x)
        assertTrue(b[0].x > b[1].x)
    }

    @Test
    fun blankPageGivesWholePage() {
        val g = GrayImage(600, 900).apply { px.fill(255.toByte()) }
        val p = det.detect(g, ReadingDirection.LTR).panels
        assertEquals(listOf(PanelRect(0, 0, 600, 900)), p)
    }
}
