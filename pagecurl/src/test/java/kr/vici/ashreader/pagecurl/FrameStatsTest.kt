// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.pagecurl

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameStatsTest {
    @Test
    fun lateAndMissedFrames() {
        val r = FrameStatsRecorder()
        r.start(60f)
        val p = 16_666_667L
        // 프레임 4개: 지연 5ms, 20ms(늦음), 5ms, 다음 vsync 하나 건너뜀
        r.onFrame(0, 5_000_000, true)
        r.onFrame(p, p + 20_000_000, true)
        r.onFrame(2 * p, 2 * p + 5_000_000, true)
        r.onFrame(4 * p, 4 * p + 5_000_000, true)
        r.onTurnEnd()
        val rep = r.stop()
        assertEquals(4, rep.frames)
        assertEquals(1, rep.lateFrames)
        assertEquals(1, rep.missedVsyncs)
        assertEquals(1, rep.turns)
        assertEquals(0.25, rep.lateRatio, 1e-9)
        assertEquals(0.4, rep.lateRatioWithMissed, 1e-9)
        // 넘김 구간 = 0 ~ 4p + 한 주기 = 5 주기에 4 프레임 → 48fps
        assertEquals(48.0, rep.effectiveFps, 0.01)
    }

    @Test
    fun turnBoundaryNotCountedAsMissed() {
        val r = FrameStatsRecorder()
        r.start(120f)
        r.onFrame(0, 1_000_000, true)
        r.onTurnEnd()
        r.onFrame(1_000_000_000, 1_001_000_000, true)
        assertEquals(0, r.stop().missedVsyncs)
    }
}
