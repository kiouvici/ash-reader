// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.pagecurl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurlMathTest {
    private val out = FloatArray(4)

    @Test
    fun grabbedCornerLandsOnFinger() {
        val c = CurlMath.curlFor(1000f, 1500f, 400f, 1400f, 80f)
        CurlMath.apply(1000f, 1500f, c, out, 0)
        assertEquals(400f, out[0], 0.5f)
        assertEquals(1400f, out[1], 0.5f)
        assertEquals(160f, out[2], 0.5f)
    }

    @Test
    fun pointsBeforeAxisStayFlat() {
        val c = CurlMath.curlFor(1000f, 1500f, 600f, 1500f, 50f)
        CurlMath.apply(10f, 10f, c, out, 0)
        assertEquals(10f, out[0], 0f)
        assertEquals(0f, out[2], 0f)
    }

    @Test
    fun fullTurnLandsMirrored() {
        // t=1: 반지름 0, G 가 gx - 2W 로. 쪽 왼쪽 끝(x=0)은 제자리, 오른쪽 끝은 -W.
        val w = 1000f
        val r = CurlMath.radiusFor(1f, 180f)
        assertEquals(0f, r, 0f)
        val c = CurlMath.curlFor(w, 500f, CurlMath.fingerX(w, w, 1f), 500f, r)
        CurlMath.apply(w, 500f, c, out, 0)
        assertEquals(-w, out[0], 0.5f)
        CurlMath.apply(0f, 500f, c, out, 0)
        assertEquals(0f, out[0], 0.5f)
    }

    @Test
    fun commitThreshold() {
        assertTrue(CurlMath.shouldCommit(0.5f))
        assertFalse(CurlMath.shouldCommit(0.49f))
        assertEquals(0.25f, CurlMath.progressFor(1000f, 500f, 1000f), 1e-6f)
    }
}
