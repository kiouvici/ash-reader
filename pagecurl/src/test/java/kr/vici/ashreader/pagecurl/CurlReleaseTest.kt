// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.pagecurl

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** I-716 종이책 넘김: 위아래 줄임, 절반 넘기면 확정(작업 03), 튕김, 손 뗀 속도에서 이어지는 애니메이션. */
class CurlReleaseTest {
    @Test fun commitsAfterHalfOrFling() {
        assertFalse(CurlMath.commitOnRelease(0.2f, 0f))
        // 작업 03: 절반 전에 천천히 떼면 되돌아간다
        assertFalse(CurlMath.commitOnRelease(0.45f, 0f))
        assertTrue(CurlMath.commitOnRelease(0.55f, 0f))
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

    @Test fun liftVanishesAtBothEnds() {
        assertEquals(0f, CurlMath.liftWeight(0f), 1e-6f)
        assertEquals(0f, CurlMath.liftWeight(1f), 1e-6f)
        assertTrue(CurlMath.liftWeight(0.3f) > 0.5f)
        // 0 근처에서 부드럽게 오른다(되감기 끝에서 튀지 않게)
        assertTrue(CurlMath.liftWeight(0.02f) < 0.05f)
    }

    /** 작업 03: 이전 쪽 되감기(t→0)로 끝날 때 접힌 선이 세로에 가깝게 남아 윗부분이 갑자기 꺾이지 않는다. */
    @Test fun rewindEndKeepsFoldNearlyVertical() {
        val pw = 1000f; val h = 2000f
        val gx = pw; val gy = 1000f; val py = gy + 300f // 손가락을 아래로 끌며 되감음
        for (t in listOf(0.1f, 0.05f, 0.02f, 0.005f)) {
            val fx = CurlMath.fingerX(gx, pw, t)
            val fy = CurlMath.fingerY(gy, py, h, t)
            val c = CurlMath.curlFor(gx, gy, fx, fy, CurlMath.radiusFor(t, pw * 0.18f))
            // 접힌 선의 방향(dirY 가 크면 축이 기울어 위·아래가 꺾여 보인다)
            assertTrue("t=$t dirY=${c.dirY}", abs(c.dirY) < 0.35f)
        }
    }

    /** 작업 03: 잡은 높이에 따라 기울기가 반대 — 위를 잡으면 위가, 아래를 잡으면 아래가 앞선다. 가운데는 곧게. */
    @Test fun grabHeightDecidesWhichCornerLeads() {
        val pw = 1000f; val h = 2000f; val u = 0.4f
        fun dirY(gy: Float): Float {
            val c = CurlMath.curlFor(pw, gy, CurlMath.fingerX(pw, pw, u), CurlMath.fingerY(gy, gy, h, u), CurlMath.radiusFor(u, pw * 0.18f))
            return c.dirY
        }
        val top = dirY(h * 0.1f); val mid = dirY(h * 0.5f); val bottom = dirY(h * 0.9f)
        assertTrue("위·아래 기울기가 반대여야 함 top=$top bottom=$bottom", top * bottom < 0f)
        assertEquals(0f, mid, 1e-3f)
        assertEquals(abs(top), abs(bottom), 1e-3f)
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
