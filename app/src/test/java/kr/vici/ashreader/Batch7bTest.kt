// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import kr.vici.ashreader.doc.HwpText
import kr.vici.ashreader.doc.NRect
import kr.vici.ashreader.doc.TextGeo
import kr.vici.ashreader.doc.TextPage
import kr.vici.ashreader.ui.PageSlot
import kr.vici.ashreader.ui.SelectGeo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 7B 원본 보기 글자 선택: 한글 원본 글자 배치 풀이·낱말 경계·화면 좌표 맞춤(확대·이동·두 쪽 펼침). */
class Batch7bTest {

    /** rhwp 글자 배치 배열 만들기: 줄기마다 (x, y, w, h, 글) — 글자 폭은 고르게. */
    private fun layout(pw: Float, ph: Float, vararg runs: Triple<Float, Float, String>, h: Float = 10f, cw: Float = 10f): FloatArray {
        val out = arrayListOf(pw, ph, runs.size.toFloat())
        for ((x, y, t) in runs) {
            val cps = t.codePoints().toArray()
            out += listOf(x, y, cps.size * cw, h, cps.size.toFloat())
            for (k in 0..cps.size) out += k * cw
            for (c in cps) out += c.toFloat()
        }
        return out.toFloatArray()
    }

    @Test fun hwpRunsJoinIntoLines() {
        // 같은 줄 맞닿은 두 줄기 + 띄어 놓은 줄기 + 다음 줄
        val a = layout(
            200f, 400f,
            Triple(10f, 20f, "바람"), Triple(30f, 20f, "과"), // 맞닿음 → 한 낱말
            Triple(60f, 20f, "별"), // 20px(글자 높이 2배) 벌어짐 → 빈칸
            Triple(10f, 40f, "노래"), // 다음 줄
        )
        val tp = HwpText.page(a)!!
        assertEquals("바람과 별\n노래\n", tp.text)
        assertEquals(2f, tp.aspect, 1e-6f)
        // 첫 글자 상자: 쪽 비율
        assertEquals(10f / 200f, tp.l(0), 1e-6f)
        assertEquals(20f / 400f, tp.t(0), 1e-6f)
        assertEquals(20f / 200f, tp.r(0), 1e-6f)
        assertEquals(30f / 400f, tp.b(0), 1e-6f)
        // 넣은 빈칸·줄바꿈은 상자 0
        assertTrue(!tp.hasBox(3) && !tp.hasBox(5))
        // 길게 누른 '람' → 낱말 '바람과'
        assertEquals("바람과", tp.text.substring(TextGeo.wordAt(tp.text, 1)))
        assertEquals("별", tp.text.substring(TextGeo.wordAt(tp.text, 4)))
    }

    @Test fun hwpSpacesInsideRunAndSurrogates() {
        val tp = HwpText.page(layout(100f, 100f, Triple(0f, 0f, "가 😀나")))!!
        assertEquals("가 😀나\n", tp.text)
        // 대리 쌍 두 칸은 같은 상자
        assertEquals(tp.l(2), tp.l(3), 0f)
        assertEquals(0.3f, tp.l(4), 1e-6f)
        // 줄기 시작이 빈칸이면 빈칸을 더 넣지 않는다
        val t2 = HwpText.page(layout(100f, 100f, Triple(0f, 0f, "가"), Triple(40f, 0f, " 나")))!!
        assertEquals("가 나\n", t2.text)
    }

    @Test fun hwpBadInput() {
        assertNull(HwpText.page(floatArrayOf(0f, 1f, 0f)))
        assertNull(HwpText.page(floatArrayOf(1f)))
        assertNull(HwpText.page(layout(100f, 100f, Triple(0f, 0f, "   "))))
        // 잘린 배열: 읽은 줄기까지만
        val a = layout(100f, 100f, Triple(0f, 0f, "가나"), Triple(0f, 20f, "다라"))
        val l = HwpText.decode(a.copyOf(a.size - 3))!!
        assertEquals(1, l.runs.size)
        // 렌더 비율을 따로 주면 그 값
        assertEquals(1.5f, HwpText.page(a, 1.5f)!!.aspect, 0f)
    }

    @Test fun wordBoundaryStripsPunctuation() {
        val t = "「나무」는 자랐다. 하늘(天)"
        assertEquals("나무", t.substring(TextGeo.wordAt(t, 2)))
        assertEquals("자랐다", t.substring(TextGeo.wordAt(t, 7)))
        assertEquals("하늘", t.substring(TextGeo.wordAt(t, 11)))
        assertEquals("天", t.substring(TextGeo.wordAt(t, 14)))
        // 전각 문장 부호·기호(한글 서식 문서에 흔함)
        val u = " ○ 시설종류： 아동양육시설｣"
        assertEquals("시설종류", u.substring(TextGeo.wordAt(u, 4)))
        assertEquals("아동양육시설", u.substring(TextGeo.wordAt(u, 10)))
    }

    /** 0.1 폭 글자 4개짜리 한 줄(쪽 비율 [aspect]). */
    private fun page(aspect: Float): TextPage {
        val s = "가나다라\n"
        val b = FloatArray(s.length * 4)
        for (i in 0 until 4) { b[4 * i] = 0.1f + i * 0.1f; b[4 * i + 1] = 0.5f; b[4 * i + 2] = 0.2f + i * 0.1f; b[4 * i + 3] = 0.55f }
        return TextPage(s, b, aspect)
    }

    @Test fun zoomedSlotMapsTouchToChar() {
        // 쪽 비트맵 500×1000(비율 2) 을 화면에 기본 배율 1 × 확대 2 로, 왼쪽 위로 (-300, -400) 옮겨 그림
        val tp = page(2f)
        val slot = SelectGeo.zoomSlot(3, -300f, -400f, 500, 1000, 2f)
        assertEquals(PageSlot(3, -300f, -400f, 1000f, 2000f), slot)
        // '다'(쪽 x 0.3~0.4, y 0.5~0.55) 가운데 → 화면 (-300 + 0.35*1000, -400 + 0.525*2000)
        val x = -300f + 350f; val y = -400f + 1050f
        val (nx, ny) = SelectGeo.toPage(slot, tp, x, y)
        assertEquals(0.35f, nx, 1e-5f); assertEquals(0.525f, ny, 1e-5f)
        assertEquals(2, SelectGeo.charAt(slot, tp, x, y))
        // 왕복: 글자 상자 → 화면 → 같은 글자
        val r = SelectGeo.toScreen(slot, tp, NRect(tp.l(1), tp.t(1), tp.r(1), tp.b(1)))
        assertEquals(1, SelectGeo.charAt(slot, tp, (r.l + r.r) / 2, (r.t + r.b) / 2))
    }

    @Test fun overlayBitmapCentersPage() {
        // 확대 덧화면은 화면 비율(1000×2000)로 그린 그림에 쪽(비율 1)을 가운데 둔다 → 위아래 500px 여백
        val tp = page(1f)
        val slot = SelectGeo.zoomSlot(0, 0f, 0f, 1000, 2000, 1.5f) // 1.5배 → 1500×3000
        val c = slot.content(tp)
        assertEquals(0f, c.l, 1e-4f); assertEquals(750f, c.t, 1e-4f); assertEquals(1500f, c.r, 1e-4f); assertEquals(2250f, c.b, 1e-4f)
        val (nx, ny) = SelectGeo.toPage(slot, tp, 750f, 1500f)
        assertEquals(0.5f, nx, 1e-5f); assertEquals(0.5f, ny, 1e-5f)
    }

    @Test fun spreadRightHalf() {
        // 두 쪽 펼침 오른쪽 칸(화면 2000×1200 의 오른쪽 반), 쪽 비율 1.2 → 칸 1000×1200 을 꽉 채움
        val tp = page(1.2f)
        val slot = PageSlot(7, 1000f, 0f, 1000f, 1200f)
        // '가' 가운데 = 1000 + 0.15*1000, 0.525*1200
        assertEquals(0, SelectGeo.charAt(slot, tp, 1150f, 630f))
        // 왼쪽 칸에 해당하는 점은 이 쪽 글자에서 멀다
        assertEquals(-1, SelectGeo.charAt(slot, tp, 150f, 630f))
    }

    @Test fun scrollZoomMapsBack() {
        // 스크롤 보기 2배 확대(화면 1000×2000 가운데 기준) + 가로 100px 이동, 칸 = 화면 폭 쪽(비율 2) 이 위로 300px 스크롤
        val tp = page(2f)
        val slot = PageSlot(4, 0f, -300f, 1000f, 2000f)
        val z = SelectGeo.scaledSlot(slot, 500f, 1000f, 2f, 100f)
        assertEquals(PageSlot(4, -400f, -1600f, 2000f, 4000f), z)
        // '나'(쪽 0.25, 0.525) → 확대 전 자리 (250, -300+1050=750) → 화면 ((250-500)*2+500+100, (750-1000)*2+1000)
        val sx = (250f - 500f) * 2f + 500f + 100f; val sy = (750f - 1000f) * 2f + 1000f
        assertEquals(1, SelectGeo.charAt(z, tp, sx, sy))
        // 화면 y → 확대 전 y (쪽 찾기용)
        assertEquals(750f, SelectGeo.unscale(sy, 1000f, 2f, 0f), 1e-4f)
        assertEquals(250f, SelectGeo.unscale(sx, 500f, 2f, 100f), 1e-4f)
    }

    @Test fun barStaysOnScreen() {
        // 확대로 고른 글자가 화면 오른쪽 밖 → 띠는 화면 안 오른쪽 끝
        val (x, y) = SelectGeo.barPos(centerX = 3000f, selTop = 500f, selBottom = 540f, barW = 300f, barH = 56f,
            screenW = 1080f, screenH = 2000f, topLimit = 60f, below = 28f)
        assertEquals(1080f - 300f - 8f, x, 1e-4f)
        assertEquals(500f - 56f - 8f, y, 1e-4f)
        // 위가 좁으면 아래
        val (_, y2) = SelectGeo.barPos(500f, 80f, 120f, 300f, 56f, 1080f, 2000f, 60f, 28f)
        assertEquals(148f, y2, 1e-4f)
        // 글자가 화면 아래 밖이면 화면 안으로
        val (x3, y3) = SelectGeo.barPos(-200f, 5000f, 5040f, 300f, 56f, 1080f, 2000f, 60f, 28f)
        assertEquals(8f, x3, 1e-4f)
        assertEquals(2000f - 56f - 8f, y3, 1e-4f)
        assertNotNull(x3)
    }
}
