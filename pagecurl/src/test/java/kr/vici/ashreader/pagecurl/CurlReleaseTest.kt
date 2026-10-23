// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.pagecurl

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** I-716 종이책 넘김: 위아래 줄임, 1/4 넘기면 확정, 튕김, 손 뗀 속도에서 이어지는 애니메이션. */
class CurlReleaseTest {
    @Test fun commitsAfterQuarterOrFling() {
        assertFalse(CurlMath.commitOnRelease(0.2f, 0f))
        assertTrue(CurlMath.commitOnRelease(0.3f, 0f))
        // 짧게 끌어도 넘기는 쪽으로 튕기면 넘김
        assertTrue(CurlMath.commitOnRelease(0.05f, 900f))
        // 많이 끌었어도 되돌리는 쪽으로 튕기면 제자리
        assertFalse(CurlMath.commitOnRelease(0.6f, -900f))
    }

    @Test fun liftIsDampedAndClamped() {
        val max = 100f
        assertEquals(0f, CurlMath.dampLift(0f, max), 1e-4f)
        // 조금 움직이면 조금(손가락의 약 35%)만 따라간다
        assertEquals(7f, CurlMath.dampLift(20f, max), 0.5f)
        // 아무리 크게 움직여도 상한을 넘지 않는다
        assertTrue(CurlMath.dampLift(5000f, max) <= max)
        assertTrue(CurlMath.dampLift(-5000f, max) >= -max)
        assertTrue(CurlMath.dampLift(400f, max) > CurlMath.dampLift(200f, max))
    }

    @Test fun releaseCurveContinuesVelocityWithoutJump() {
        val from = 0.3f; val to = 1f
        val v = 2.5f // 진행도/초
        val (ms, slope) = CurlMath.releasePlan(from, to, v, 450L)
        // 시작점이 그대로(튀지 않음)
        assertEquals(from, CurlMath.releaseCurve(from, to, slope, 0f), 1e-6f)
        assertEquals(to, CurlMath.releaseCurve(from, to, slope, 1f), 1e-6f)
        // 시작 속도 = 손 뗀 속도(진행도/초)
        val dk = 1e-3f
        val startV = (CurlMath.releaseCurve(from, to, slope, dk) - from) / (dk * ms / 1000f)
        assertEquals(v, startV, 0.05f)
        // 넘침 없이 한 방향으로만
        var prev = from
        for (i in 1..100) {
            val x = CurlMath.releaseCurve(from, to, slope, i / 100f)
            assertTrue(x >= prev - 1e-5f && x <= to + 1e-5f)
            prev = x
        }
    }

    @Test fun fastFlingShortensAnimation() {
        val slow = CurlMath.releasePlan(0.3f, 1f, 0.5f, 450L).first
        val fast = CurlMath.releasePlan(0.3f, 1f, 6f, 450L).first
        assertTrue(fast < slow)
        assertTrue(fast >= 110L)
    }

    @Test fun oppositeVelocityStaysBounded() {
        // 앞으로 끌다가 놓았지만 되돌아가는 경우: 잠깐 이어 가다 돌아오되 크게 벗어나지 않는다
        val (_, slope) = CurlMath.releasePlan(0.2f, 0f, 3f, 450L)
        var maxT = 0f
        for (i in 0..100) maxT = maxOf(maxT, CurlMath.releaseCurve(0.2f, 0f, slope, i / 100f))
        assertTrue(abs(maxT - 0.2f) < 0.1f)
    }
}
