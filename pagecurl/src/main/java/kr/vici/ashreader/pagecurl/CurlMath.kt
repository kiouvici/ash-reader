// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.pagecurl

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 원통 말림 계산 (안드로이드 의존 없음 → 단위 시험).
 *
 * 좌표: 가상 화면 픽셀(y 아래 방향). 쪽을 잡은 점 G(오른쪽 가장자리)가 손가락 P로 끌려간다.
 * 말림 축은 G-P에 수직인 직선이고, 축 너머(G 쪽) 정점은 반지름 r 원통에 감긴 뒤 다시 평평하게 뒤집혀 놓인다.
 * G가 정확히 P에 놓이도록 축 위치를 정한다: a = (|G-P| + πr) / 2, pos = G - dir·a.
 * 반지름 0이면 축에 대한 단순 반사(완전히 접혀 뒤집힌 상태).
 */
object CurlMath {
    data class Curl(
        val posX: Float,
        val posY: Float,
        val dirX: Float,
        val dirY: Float,
        val radius: Float,
    )

    private val PI_F = PI.toFloat()

    fun curlFor(gx: Float, gy: Float, px: Float, py: Float, radius: Float): Curl {
        val dx = gx - px
        val dy = gy - py
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-3f) return Curl(gx + 1e6f, gy, 1f, 0f, 0f)
        val dirX = dx / len
        val dirY = dy / len
        // 반지름은 끈 거리보다 클 수 없다(t≈0 에서 거의 평평)
        val r = min(radius, len / PI_F)
        val a = (len + PI_F * r) / 2f
        return Curl(gx - dirX * a, gy - dirY * a, dirX, dirY, r)
    }

    /** 정점 (x, y)를 말아 out[off..off+3] = x', y', z, 밝기 로 쓴다. */
    fun apply(x: Float, y: Float, c: Curl, out: FloatArray, off: Int) {
        val dist = (x - c.posX) * c.dirX + (y - c.posY) * c.dirY
        if (dist <= 0f) {
            out[off] = x; out[off + 1] = y; out[off + 2] = 0f; out[off + 3] = 1f
            return
        }
        // 축 위로 내린 점
        val bx = x - c.dirX * dist
        val by = y - c.dirY * dist
        val r = c.radius
        val halfCirc = PI_F * r
        if (dist < halfCirc) {
            val theta = dist / r
            val s = r * sin(theta)
            out[off] = bx + c.dirX * s
            out[off + 1] = by + c.dirY * s
            out[off + 2] = r * (1f - cos(theta))
            out[off + 3] = 0.6f + 0.4f * abs(cos(theta))
        } else {
            val back = dist - halfCirc
            out[off] = bx - c.dirX * back
            out[off + 1] = by - c.dirY * back
            out[off + 2] = 2f * r
            out[off + 3] = 1f
        }
    }

    /**
     * 진행도 t(0 = 평평, 1 = 다 넘어감)에서 말림 반지름. 처음과 끝에서 0이 되어
     * t=1 에서 쪽이 정확히 뒤집혀 평평하게 놓인다(한 쪽: 화면 밖, 펼침: 왼쪽 쪽 위).
     */
    fun radiusFor(t: Float, maxRadius: Float): Float = maxRadius * 4f * t * (1f - t)

    /** 진행도 t 에서 잡은 점 G 의 목표 x. 이동 거리는 쪽 너비의 2배. */
    fun fingerX(gx: Float, pageW: Float, t: Float): Float = gx - t * 2f * pageW

    /** 손가락 x 로부터 진행도 t (0..1). */
    fun progressFor(gx: Float, px: Float, pageW: Float): Float = ((gx - px) / (2f * pageW)).coerceIn(0f, 1f)

    /** 손을 뗐을 때 넘길지(true) 되돌릴지. 넘어간 정도 50% 기준 (화면 설계서 4.2). 탭·볼륨 넘김 등 손가락 없는 경우. */
    fun shouldCommit(t: Float): Boolean = t >= 0.5f

    /** 손가락으로 넘길 때 넘김이 확정되는 끈 거리(쪽 너비 비율, I-716). 실제 책처럼 쪽 너비의 약 1/4. */
    const val COMMIT_DRAG = 0.27f

    /** 이 속도(dp/초) 넘게 넘기는 쪽으로 튕기면 거리가 짧아도 넘긴다. 반대쪽으로 튕기면 되돌린다. */
    const val FLING_DP_S = 450f

    /**
     * 손을 뗐을 때 넘김 확정(I-716). [dragFrac] = 넘기는 방향으로 끈 거리 / 쪽 너비,
     * [velDpS] = 넘기는 방향 속도(dp/초, 반대 방향이면 음수).
     */
    fun commitOnRelease(dragFrac: Float, velDpS: Float): Boolean = when {
        velDpS >= FLING_DP_S -> true
        velDpS <= -FLING_DP_S -> false
        else -> dragFrac >= COMMIT_DRAG
    }

    /**
     * 손가락 위아래 움직임을 줄여 말림 모서리에 옮긴다(I-716). 처음엔 [follow] 비율로 조금 따라가고
     * [maxLift] 에 가까워질수록 부드럽게 멈춘다(tanh). 예전에는 손가락 위아래를 그대로 따라 쪽이 크게 들렸다.
     */
    fun dampLift(dy: Float, maxLift: Float, follow: Float = 0.35f): Float {
        if (maxLift <= 0f) return 0f
        val x = dy * follow / maxLift
        // tanh(x) = (e^2x - 1)/(e^2x + 1)
        val e = kotlin.math.exp(2f * x.coerceIn(-10f, 10f))
        return maxLift * (e - 1f) / (e + 1f)
    }

    /**
     * 손을 뗀 뒤 애니메이션(I-716): 시작 기울기 [slope] (= 뗄 때 속도 × 길이, 진행도 단위)에서 끝 기울기 0 으로 가는
     * 3차 에르미트 곡선. 손을 떼는 순간 속도가 이어져 튀지 않고, 끝에서 부드럽게 멈춘다. k = 0..1(시간 비율).
     */
    fun releaseCurve(from: Float, to: Float, slope: Float, k: Float): Float {
        val u = k.coerceIn(0f, 1f)
        val u2 = u * u; val u3 = u2 * u
        val h00 = 2f * u3 - 3f * u2 + 1f
        val h10 = u3 - 2f * u2 + u
        val h01 = -2f * u3 + 3f * u2
        return h00 * from + h10 * slope + h01 * to
    }

    /**
     * 뗄 때 진행도 속도 [vPerS](진행도/초)로 [from]→[to] 애니메이션 길이(ms)와 시작 기울기를 정한다.
     * 기본 길이는 [baseMs]×거리. 같은 방향으로 빠르면 짧게(넘침 없이: 기울기 ≤ 3×거리), 반대 방향이면 기울기를 거리만큼으로 줄인다.
     */
    fun releasePlan(from: Float, to: Float, vPerS: Float, baseMs: Long, minMs: Long = 110L): Pair<Long, Float> {
        val dist = to - from
        val ad = abs(dist)
        if (ad < 1e-4f) return minMs to 0f
        var ms = max(minMs.toFloat(), baseMs * ad)
        val toward = vPerS * dist > 0f
        if (toward && abs(vPerS) > 1e-3f) ms = ms.coerceAtMost(max(minMs.toFloat(), 1.5f * ad / abs(vPerS) * 1000f))
        var slope = vPerS * ms / 1000f
        slope = if (toward) slope.coerceIn(-3f * ad, 3f * ad) else slope.coerceIn(-ad, ad)
        return ms.toLong() to slope
    }
}
