// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicImageOpsTest {
    private val white = -0x1
    private val black = -0x1000000

    /** [w]×[h] 테두리 색 바탕에 (x0,y0)-(x1,y1) 영역만 무늬(회색 줄)를 그린 그림. */
    private fun page(w: Int, h: Int, bg: Int, x0: Int, y0: Int, x1: Int, y1: Int): IntArray {
        val px = IntArray(w * h) { bg }
        for (y in y0 until y1) for (x in x0 until x1) px[y * w + x] = if ((x + y) % 7 < 3) 0xFF404040.toInt() else 0xFFB0B0B0.toInt()
        return px
    }

    @Test
    fun cropWhiteBorder() {
        val b = BorderCrop.detect(page(400, 600, white, 40, 30, 370, 560), 400, 600)
        assertTrue("left ${b.l}", b.l in 36..40)
        assertTrue("top ${b.t}", b.t in 26..30)
        assertTrue("right ${b.r}", b.r in 370..374)
        assertTrue("bottom ${b.b}", b.b in 560..564)
    }

    @Test
    fun cropBlackBorder() {
        val b = BorderCrop.detect(page(300, 300, black, 20, 20, 280, 280), 300, 300)
        assertTrue(b.l in 17..20 && b.r in 280..283)
    }

    @Test
    fun noBorderKeepsAll() {
        val b = BorderCrop.detect(page(200, 300, white, 0, 0, 200, 300), 200, 300)
        assertEquals(CropBox(0, 0, 200, 300), b)
    }

    @Test
    fun cropNeverRemovesMostOfPage() {
        // 거의 빈 쪽(작은 점 하나): 최대 30%까지만 자른다
        val b = BorderCrop.detect(page(300, 300, white, 148, 148, 152, 152), 300, 300)
        assertTrue(b.l <= 90 && b.width >= 120)
    }

    @Test
    fun sensitivityAllowsNoise() {
        val px = page(300, 300, white, 30, 30, 270, 270)
        // 테두리에 옅은 얼룩(밝기 225)
        for (x in 0 until 300) px[10 * 300 + x] = 0xFFE1E1E1.toInt()
        val weak = BorderCrop.detect(px, 300, 300, 0)
        val strong = BorderCrop.detect(px, 300, 300, 2)
        assertTrue("weak ${weak.t}", weak.t <= 10)
        assertTrue("strong ${strong.t}", strong.t >= 26)
    }

    @Test
    fun symmetricCropForSpread() {
        val s = BorderCrop.symmetric(CropBox(10, 0, 380, 100), 400)
        assertEquals(CropBox(10, 0, 390, 100), s)
    }

    @Test
    fun adjustIdentityAndInvertGray() {
        assertTrue(Adjust().isIdentity)
        val px = intArrayOf(0xFF102030.toInt())
        Adjust(invert = true).apply(px)
        assertEquals(0xFFEFDFCF.toInt(), px[0])
        val g = intArrayOf(0xFFFF0000.toInt())
        Adjust(gray = true).apply(g)
        val r = (g[0] shr 16) and 0xff
        assertEquals(r, (g[0] shr 8) and 0xff); assertEquals(r, g[0] and 0xff)
        val lut = Adjust(brightness = 0.4f).lut()
        assertTrue(lut[100] > 100)
        val gam = Adjust(gamma = 2f).lut()
        assertTrue(gam[64] > 64 && gam[0] == 0 && gam[255] == 255)
    }

    @Test
    fun sharpenIncreasesEdgeContrast() {
        val w = 10; val h = 10
        val px = IntArray(w * h) { i -> if (i % w < 5) 0xFF606060.toInt() else 0xFFA0A0A0.toInt() }
        Sharpen.apply(px, w, h, 2)
        val left = px[5 * w + 4] and 0xff; val right = px[5 * w + 5] and 0xff
        assertTrue("$left $right", left < 0x60 && right > 0xA0)
        val flat = IntArray(25) { 0xFF808080.toInt() }
        Sharpen.apply(flat, 5, 5, 3)
        assertTrue(flat.all { it == 0xFF808080.toInt() })
    }

    @Test
    fun spreadLayoutSinglePortraitSplitsInReadingOrder() {
        val sizes = listOf(600 to 900, 1200 to 900, 600 to 900)
        val ltr = SpreadLayout.build(sizes, spread = false, landscape = false, wide = WideMode.SPLIT, rtl = false, coverAlone = true)
        assertEquals(listOf(PageSlot(0, SlotPart.WHOLE), PageSlot(1, SlotPart.LEFT), PageSlot(1, SlotPart.RIGHT), PageSlot(2, SlotPart.WHOLE)), ltr)
        val rtl = SpreadLayout.build(sizes, spread = false, landscape = false, wide = WideMode.SPLIT, rtl = true, coverAlone = true)
        assertEquals(SlotPart.RIGHT, rtl[1].part)
        val land = SpreadLayout.build(sizes, spread = false, landscape = true, wide = WideMode.SPLIT, rtl = false, coverAlone = true)
        assertEquals(3, land.size)
        val rot = SpreadLayout.build(sizes, spread = false, landscape = false, wide = WideMode.ROTATE, rtl = false, coverAlone = true)
        assertEquals(SlotPart.ROTATE, rot[1].part)
    }

    @Test
    fun spreadLayoutAlignsWidePageToPair() {
        // 표지 단독: 펼침 첫 자리는 홀수 번호(1, 3, 5…)
        val sizes = listOf(600 to 900, 600 to 900, 1200 to 900, 600 to 900)
        val s = SpreadLayout.build(sizes, spread = true, landscape = true, wide = WideMode.SPLIT, rtl = false, coverAlone = true)
        // 0 | 1 BLANK | 2L 2R | 3
        assertEquals(listOf(PageSlot(0, SlotPart.WHOLE), PageSlot(1, SlotPart.WHOLE), PageSlot(-1, SlotPart.BLANK), PageSlot(2, SlotPart.LEFT), PageSlot(2, SlotPart.RIGHT), PageSlot(3, SlotPart.WHOLE)), s)
        val idx = s.indexOf(PageSlot(2, SlotPart.LEFT))
        assertEquals(1, idx % 2)
    }

    @Test
    fun translateKeepsSamePage() {
        val old = listOf(PageSlot(0, SlotPart.WHOLE), PageSlot(1, SlotPart.LEFT), PageSlot(1, SlotPart.RIGHT), PageSlot(2, SlotPart.WHOLE))
        assertEquals(1, SpreadLayout.translate(old, null, 2))
        assertEquals(3, SpreadLayout.translate(null, old, 2))
        assertEquals(1, SpreadLayout.translate(old, old, 2))
    }

    @Test
    fun webtoonSuggestion() {
        val tall = 800 to 4000; val normal = 800 to 1200
        assertTrue(Webtoon.suggest(List(6) { tall } + List(4) { normal }))
        assertFalse(Webtoon.suggest(List(5) { tall } + List(5) { normal }))
        assertTrue(Webtoon.suggest(List(3) { tall }))
        assertFalse(Webtoon.suggest(emptyList()))
        assertFalse(Webtoon.suggest(List(10) { null }))
    }

    @Test
    fun webtoonStripsCoverWholePage() {
        val s = Webtoon.strips(800, 20000, 1080, 1200)
        assertEquals(0, s.first()[0]); assertEquals(20000, s.last()[1])
        for (i in 1 until s.size) assertEquals(s[i - 1][1], s[i][0])
        assertTrue(s.all { it[1] - it[0] <= 1200 * 800 / 1080 + 300 })
        assertEquals(1, Webtoon.sampleFor(800, 1080))
        assertEquals(2, Webtoon.sampleFor(2400, 1080))
    }
}
