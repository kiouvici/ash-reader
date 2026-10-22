// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.pagecurl

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
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

    /** 손을 뗐을 때 넘길지(true) 되돌릴지. 넘어간 정도 50% 기준 (화면 설계서 4.2). */
    fun shouldCommit(t: Float): Boolean = t >= 0.5f
}
