// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.convert

import org.junit.Assert.assertEquals
import org.junit.Test

class TextEncodingTest {
    private val ko = "그는 창밖을 오래 바라보았다. 똠방각하 뷁, 비는 그칠 기색이 없었다.\n둘째 줄 ABC 123\n"

    @Test fun cp949PicksCharsetThatReadsExtendedHangul() {
        assertEquals("똠", String(byteArrayOf(0x8C.toByte(), 0x63), TextEncoding.cp949()))
    }

    @Test fun utf8() = assertEquals(TextEncoding.Kind.UTF8, TextEncoding.detect(ko.toByteArray(Charsets.UTF_8)))

    @Test fun utf8Bom() {
        val b = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + ko.toByteArray(Charsets.UTF_8)
        assertEquals(TextEncoding.Kind.UTF8, TextEncoding.detect(b))
        assertEquals(ko, TextEncoding.decode(b, TextEncoding.Kind.UTF8))
    }

    @Test fun cp949() {
        val b = ko.toByteArray(TextEncoding.cp949())
        assertEquals(TextEncoding.Kind.CP949, TextEncoding.detect(b))
        assertEquals(ko, TextEncoding.decode(b, TextEncoding.Kind.CP949))
    }

    @Test fun eucKrOnly() {
        val s = "가나다라 마바사 아자차카 타파하.\n"
        assertEquals(TextEncoding.Kind.CP949, TextEncoding.detect(s.toByteArray(TextEncoding.cp949())))
    }

    @Test fun utf16WithBom() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + ko.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + ko.toByteArray(Charsets.UTF_16BE)
        assertEquals(TextEncoding.Kind.UTF16LE, TextEncoding.detect(le))
        assertEquals(TextEncoding.Kind.UTF16BE, TextEncoding.detect(be))
        assertEquals(ko, TextEncoding.decode(le, TextEncoding.Kind.UTF16LE))
        assertEquals(ko, TextEncoding.decode(be, TextEncoding.Kind.UTF16BE))
    }

    @Test fun utf16WithoutBomMostlyAscii() {
        val s = "Chapter 1\nThe quick brown fox jumps over the lazy dog. 한글 조금\n".repeat(4)
        assertEquals(TextEncoding.Kind.UTF16LE, TextEncoding.detect(s.toByteArray(Charsets.UTF_16LE)))
        assertEquals(TextEncoding.Kind.UTF16BE, TextEncoding.detect(s.toByteArray(Charsets.UTF_16BE)))
    }

    @Test fun truncatedUtf8AtSampleEndIsStillUtf8() {
        val b = ko.repeat(50).toByteArray(Charsets.UTF_8)
        // 3바이트 글자 한가운데서 자른 표본
        var cut = 1000
        while ((b[cut].toInt() and 0xC0) != 0x80) cut++
        assertEquals(TextEncoding.Kind.UTF8, TextEncoding.detect(b, cut + 1))
    }

    @Test fun asciiIsUtf8() = assertEquals(TextEncoding.Kind.UTF8, TextEncoding.detect("hello\r\nworld".toByteArray()))
}
