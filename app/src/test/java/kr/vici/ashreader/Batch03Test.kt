// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import kr.vici.ashreader.doc.GrayKeep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 작업 03: AI 화질이 흑백 스캔을 붉게 물들이지 않게(단색 스캔 색조 지키기). */
class Batch03Test {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8) and 0xFF
    private fun b(p: Int) = p and 0xFF

    @Test fun pureGrayScan() {
        val px = IntArray(1000) { i -> if (i % 2 == 0) rgb(240, 240, 240) else rgb(30, 30, 30) }
        px[0] = rgb(200, 60, 60) // 0.1% 잡티
        val t = GrayKeep.monoTint(px)
        assertNotNull(t)
        assertEquals(1f, t!!.r, 0.02f); assertEquals(1f, t.b, 0.02f)
    }

    @Test fun sepiaPaperScanIsMono() {
        // 누런 종이(236,226,200) + 검은 선 + 회색 망점(종이 색조의 어두운 판)
        val paper = rgb(236, 226, 200)
        val tone = rgb(130, 124, 110)
        val px = IntArray(1200) { i -> when (i % 3) { 0 -> paper; 1 -> tone; else -> rgb(28, 27, 25) } }
        val t = GrayKeep.monoTint(px)
        assertNotNull("누런 흑백 스캔도 단색으로 봐야 함", t)
        // AI 가 붉게 물들인 화소 → 밝기는 지키고 종이 색조로(붉은 기 제거)
        val fixed = GrayKeep.apply(rgb(200, 90, 90), t!!)
        assertTrue("붉은 기가 남음: ${r(fixed)},${g(fixed)},${b(fixed)}", r(fixed) - g(fixed) < 15)
        assertTrue(r(fixed) >= b(fixed)) // 누런 색조는 남는다
    }

    @Test fun colorArtIsNotMono() {
        val px = IntArray(1000) { i -> when (i % 4) { 0 -> rgb(220, 40, 40); 1 -> rgb(40, 60, 210); 2 -> rgb(50, 180, 70); else -> rgb(245, 245, 245) } }
        assertNull(GrayKeep.monoTint(px))
        assertNull(GrayKeep.monoTint(IntArray(0)))
    }

    @Test fun applyKeepsAlpha() {
        val p = GrayKeep.apply((0x80 shl 24) or (200 shl 16) or (40 shl 8) or 40, GrayKeep.Tint(1f, 1f, 1f))
        assertEquals(0x80, (p ushr 24) and 0xFF)
        assertEquals(r(p), g(p)); assertEquals(g(p), b(p))
    }
}
