// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.comic

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelScoringTest {
    private val a = PanelRect.xywh(0, 0, 100, 100)
    private val b = PanelRect.xywh(120, 0, 100, 100)
    private val c = PanelRect.xywh(0, 120, 220, 100)

    @Test
    fun iou() {
        assertEquals(1.0, a.iou(a), 1e-9)
        assertEquals(0.0, a.iou(b), 1e-9)
        assertEquals(50.0 * 100 / (100 * 100 * 2 - 50 * 100), a.iou(PanelRect.xywh(50, 0, 100, 100)), 1e-9)
    }

    @Test
    fun perfectAndShifted() {
        assertEquals(1.0, PanelScoring.score(listOf(a, b, c), listOf(a, b, c)).rate, 1e-9)
        // IoU 0.7 경계: 폭 100 칸을 15px 밀면 IoU = 85/115 ≈ 0.739 → 맞음, 20px 면 80/120 ≈ 0.667 → 틀림
        assertEquals(1, PanelScoring.score(listOf(a), listOf(PanelRect.xywh(15, 0, 100, 100))).correct)
        assertEquals(0, PanelScoring.score(listOf(a), listOf(PanelRect.xywh(20, 0, 100, 100))).correct)
    }

    @Test
    fun wrongOrderCountsAsWrong() {
        val s = PanelScoring.score(listOf(a, b, c), listOf(b, a, c))
        assertEquals(1, s.correct) // c 만 순서가 맞음
        assertEquals(2, s.matchedWrongOrder)
    }

    @Test
    fun missingAndExtra() {
        val s = PanelScoring.score(listOf(a, b, c), listOf(a, c, PanelRect.xywh(500, 500, 10, 10)))
        assertEquals(2, s.correct); assertEquals(3, s.truth); assertEquals(3, s.detected)
    }

    @Test
    fun jsonRoundTripAndBook() {
        val g = PanelScoring.GroundTruth("001.jpg", ReadingDirection.RTL, 1000, 1500, listOf(a, b))
        val back = PanelScoring.parse(PanelScoring.toJson(g)).single()
        assertEquals(g, back)
        val book = """{"book": "시험", "pages": [${PanelScoring.toJson(g)}, {"page":"002.jpg","direction":"ltr","size":[10,20],"panels":[]}]}"""
        val ps = PanelScoring.parse(book)
        assertEquals(2, ps.size); assertEquals(ReadingDirection.LTR, ps[1].direction)
    }

    @Test
    fun sampleGroundTruthFileParses() {
        val txt = javaClass.getResource("/panels-example.json")!!.readText()
        val ps = PanelScoring.parse(txt)
        assertEquals(2, ps.size)
    }
}
