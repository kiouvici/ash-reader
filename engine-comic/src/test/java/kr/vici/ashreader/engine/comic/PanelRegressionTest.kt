// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 7C 칸 인식 회귀 시험 (I-731). [PanelRegressionPages] 의 어려운 합성 쪽을 채점해 정밀도·재현율을 찍고 하한을 건다.
 * 정밀도 = 맞은 상자(IoU ≥ 0.7) ÷ 인식 상자, 재현율 = 맞은 상자 ÷ 정답 상자, 순서 재현율 = 순서까지 맞은 상자 ÷ 정답 상자.
 * 7C 전(1차 판) 측정: 정밀도 0.905, 재현율 0.833 (114칸). 7C 후: 0.973 / 0.939.
 */
class PanelRegressionTest {
    private val det = PanelDetector()

    private fun run(): Map<String, PanelScoring.Score> {
        val out = LinkedHashMap<String, PanelScoring.Score>()
        var maxMs = 0.0
        for ((i, c) in PanelRegressionPages.suite().withIndex()) {
            val t0 = System.nanoTime()
            val got = det.detect(c.page.image, c.page.direction)
            val ms = (System.nanoTime() - t0) / 1e6
            if (i > 1) maxMs = maxOf(maxMs, ms) // 첫 쪽들은 JIT 예열
            val s = PanelScoring.score(c.page.truth, got.panels)
            println("ASH-PANEL7C ${c.name} truth=${s.truth} detected=${s.detected} correct=${s.correct} wrongOrder=${s.matchedWrongOrder} ms=${"%.1f".format(ms)}")
            out[c.name] = s
        }
        val t = out.values.fold(PanelScoring.Score.ZERO) { a, b -> a + b }
        val matched = t.correct + t.matchedWrongOrder
        println(
            "ASH-PANEL7C total truth=${t.truth} detected=${t.detected} matched=$matched ordered=${t.correct} " +
                "precision=${"%.3f".format(matched.toDouble() / t.detected)} recall=${"%.3f".format(matched.toDouble() / t.truth)} " +
                "orderedRecall=${"%.3f".format(t.rate)} maxMs=${"%.1f".format(maxMs)}",
        )
        // 속도 예산: 쪽당 1초(기준 휴대폰). JVM 시험 기계에서는 그보다 훨씬 빨라야 한다.
        assertTrue("느림 ${maxMs}ms", maxMs < 1000)
        return out
    }

    @Test
    fun precisionAndRecall() {
        val r = run()
        val t = r.values.fold(PanelScoring.Score.ZERO) { a, b -> a + b }
        val matched = t.correct + t.matchedWrongOrder
        assertTrue("정밀도 ${matched.toDouble() / t.detected}", matched.toDouble() / t.detected >= 0.95)
        assertTrue("재현율 ${matched.toDouble() / t.truth}", matched.toDouble() / t.truth >= 0.92)
        assertTrue("순서 재현율 ${t.rate}", t.rate >= 0.92)
        // 7C 에서 고친 경우는 모두 맞아야 한다
        for (name in listOf("wide-strips-5", "wide-strips-7", "wide-strips-9", "wide-mixed", "wide-mixed-rtl", "tall-columns",
            "tall-left-ltr", "tall-right-rtl", "no-margin", "bleed-top", "thin-gutter-6", "art-bleeds-border", "borderless",
            "black-background", "gray-background", "splash-bleed", "splash-framed", "gutter-fragments",
            "misaligned-ltr", "misaligned-rtl", "scan-shadow")) {
            val s = r.getValue(name)
            assertEquals("$name: $s", s.truth, s.correct)
            assertEquals("$name 조각: $s", s.truth, s.detected)
        }
    }
}
