// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** EMF·WMF 그림 읽기(F-30 Word 원본의 그림): 합성 파일로 도형·글자·그림 기록을 확인한다. */
class MetafileTest {
    @Test
    fun emfShapesTextAndBitmap() {
        val m = Metafile.parse(SyntheticMetafile.emf())
        assertNotNull(m); m!!
        assertEquals(0f, m.left, 0.01f); assertEquals(201f, m.right, 0.01f)
        val shapes = m.ops.filterIsInstance<Metafile.Op.Shape>()
        val texts = m.ops.filterIsInstance<Metafile.Op.Text>()
        val images = m.ops.filterIsInstance<Metafile.Op.Image>()
        assertEquals(2, shapes.size)
        assertEquals(0xFFFF0000.toInt(), shapes[0].fill) // 빨간 붓으로 칠한 사각형
        assertEquals(0xFF0000FF.toInt(), shapes[1].stroke) // 파란 펜 꺾은선
        assertEquals("가나 A", texts.single().text)
        assertEquals(20f, texts.single().size, 0.01f)
        assertTrue(texts.single().bold)
        val bmp = images.single().bmp
        assertEquals('B'.code.toByte(), bmp[0]); assertEquals('M'.code.toByte(), bmp[1])
        assertEquals(10f, images.single().l, 0.01f); assertEquals(50f, images.single().r, 0.01f)
    }

    @Test
    fun wmfPlaceableWithKoreanText() {
        val m = Metafile.parse(SyntheticMetafile.wmf())
        assertNotNull(m); m!!
        val t = m.ops.filterIsInstance<Metafile.Op.Text>().single()
        assertEquals("한글", t.text)
        assertEquals(0xFF00FF00.toInt(), m.ops.filterIsInstance<Metafile.Op.Shape>().first().fill)
        assertEquals(1000f, m.width, 1f) // 창 1000 × 500 (논리 단위 그대로)
    }

    @Test
    fun notAMetafile() {
        assertNull(Metafile.parse(ByteArray(100)))
        assertNull(Metafile.parse("<svg/>".toByteArray()))
    }
}

/** 시험용 EMF·WMF 만들기. */
object SyntheticMetafile {
    private class W {
        val o = ByteArrayOutputStream()
        fun i32(v: Int) = apply { o.write(v); o.write(v shr 8); o.write(v shr 16); o.write(v shr 24) }
        fun i16(v: Int) = apply { o.write(v); o.write(v shr 8) }
        fun bytes(b: ByteArray) = apply { o.write(b) }
        fun size() = o.size()
        fun get(): ByteArray = o.toByteArray()
    }

    private fun rec(type: Int, body: W): ByteArray {
        val b = body.get()
        return W().i32(type).i32(8 + b.size).bytes(b).get()
    }

    fun emf(): ByteArray {
        val recs = ArrayList<ByteArray>()
        recs += rec(17, W().i32(1)) // MM_TEXT
        recs += rec(39, W().i32(1).i32(0).i32(0x000000FF).i32(0)) // 붓 1: 빨강
        recs += rec(37, W().i32(1))
        recs += rec(43, W().i32(0).i32(0).i32(100).i32(50)) // 사각형
        recs += rec(38, W().i32(2).i32(0).i32(2).i32(0).i32(0x00FF0000)) // 펜 2: 파랑, 굵기 2
        recs += rec(37, W().i32(2))
        recs += rec(37, W().i32(0x80000005.toInt())) // NULL_BRUSH
        // POLYLINE16: bounds(16) count, points
        recs += rec(87, W().i32(0).i32(0).i32(0).i32(0).i32(3).i16(0).i16(60).i16(100).i16(80).i16(200).i16(60))
        // EXTCREATEFONTINDIRECTW: ih, LOGFONTW(height -20, weight 700, face "Batang")
        val lf = W().i32(-20).i32(0).i32(0).i32(0).i32(700).bytes(byteArrayOf(0, 0, 0, 129.toByte(), 0, 0, 0, 0))
        val face = "Batang"
        for (k in 0 until 32) lf.i16(if (k < face.length) face[k].code else 0)
        recs += rec(82, W().i32(3).bytes(lf.get()))
        recs += rec(37, W().i32(3))
        recs += rec(24, W().i32(0x00000000))
        // EXTTEXTOUTW: bounds(16) mode(4) exScale(4) eyScale(4) | EMRTEXT: ref(8) nChars offString options rcl(16) offDx
        val text = "가나 A"
        val textOff = 36 + 40
        val t = W().i32(0).i32(0).i32(0).i32(0).i32(1).i32(0).i32(0)
            .i32(10).i32(100).i32(text.length).i32(textOff).i32(0).i32(0).i32(0).i32(0).i32(0).i32(0)
        for (c in text) t.i16(c.code)
        if (text.length % 2 == 1) t.i16(0)
        recs += rec(84, t)
        // STRETCHDIBITS: bounds(16) xDest yDest xSrc ySrc cxSrc cySrc offBmi cbBmi offBits cbBits usage rop cxDest cyDest + BMI + bits
        val bmi = W().i32(40).i32(2).i32(2).i16(1).i16(24).i32(0).i32(16).i32(0).i32(0).i32(0).i32(0).get()
        val bits = ByteArray(16) { 0x7F }
        val head = 8 + 72
        val sd = W().i32(0).i32(0).i32(0).i32(0).i32(10).i32(110).i32(0).i32(0).i32(2).i32(2)
            .i32(head).i32(bmi.size).i32(head + bmi.size).i32(bits.size).i32(0).i32(0x00CC0020).i32(40).i32(30)
            .bytes(bmi).bytes(bits)
        recs += rec(81, sd)
        recs += rec(14, W().i32(0).i32(0).i32(20))
        val body = recs.fold(ByteArray(0)) { a, b -> a + b }
        // EMR_HEADER(108 바이트): bounds 0,0-200,150 / frame / ' EMF' / version / bytes / records / handles ...
        val hdr = W().i32(1).i32(108).i32(0).i32(0).i32(200).i32(150).i32(0).i32(0).i32(5000).i32(4000)
            .i32(0x464D4520).i32(0x10000).i32(108 + body.size).i32(recs.size + 1).i16(4).i16(0)
            .i32(0).i32(0).i32(0).i32(1920).i32(1080).i32(508).i32(286).i32(0).i32(0).i32(0).i32(508000).i32(286000)
        while (hdr.size() < 108) hdr.i32(0)
        return hdr.get() + body
    }

    fun wmf(): ByteArray {
        val recs = W()
        fun r(fn: Int, vararg words: Int) { recs.i32(3 + words.size).i16(fn); for (w in words) recs.i16(w) }
        r(0x0103, 8) // MM_ANISOTROPIC
        r(0x020B, 0, 0) // 창 원점
        r(0x020C, 500, 1000) // 창 크기(y, x)
        r(0x02FC, 0, 0xFF00, 0x0000, 0) // 붓: 초록(0x0000FF00)
        r(0x012D, 0)
        r(0x041B, 400, 900, 100, 100) // 사각형 bottom right top left
        r(0x02FB, -30, 0, 0, 0, 400, 0, 0x8100, 0, 0) // 글꼴: 높이 30, charset 129
        r(0x012D, 1)
        val s = "한글".toByteArray(charset("x-windows-949"))
        val words = IntArray((s.size + 1) / 2) { k -> (s[k * 2].toInt() and 0xFF) or ((if (k * 2 + 1 < s.size) s[k * 2 + 1].toInt() and 0xFF else 0) shl 8) }
        r(0x0521, s.size, *words, 200, 300) // TEXTOUT: count, 글자, y, x
        r(0x0000)
        val body = recs.get()
        val std = W().i16(1).i16(9).i16(0x300).i32((18 + body.size) / 2).i16(2).i32(10).i16(0).get()
        val placeable = W().i32(0x9AC6CDD7.toInt()).i16(0).i16(0).i16(0).i16(1000).i16(500).i16(1440).i32(0).i16(0).get()
        return placeable + std + body
    }
}
