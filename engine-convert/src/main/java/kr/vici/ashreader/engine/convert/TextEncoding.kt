// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.convert

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 한글 텍스트 인코딩 판별(F-32): UTF-8 / UTF-16(LE·BE) / EUC-KR·CP949.
 * 순서: ① BOM ② BOM 없는 UTF-16(0 바이트가 한쪽 자리에만 몰림) ③ UTF-8 엄격 검사 통과 ④ CP949 엄격 검사 통과 ⑤ 오류가 적은 쪽(같으면 CP949).
 * CP949 는 안드로이드(ICU)와 JDK 의 이름이 달라 [cp949] 가 실제로 '똠'(0x8C 0x63, EUC-KR 에 없는 글자)을 읽는 이름을 고른다.
 */
object TextEncoding {
    enum class Kind(val label: String) {
        UTF8("UTF-8"), UTF16LE("UTF-16 LE"), UTF16BE("UTF-16 BE"), CP949("EUC-KR (CP949)");

        companion object {
            fun of(name: String?): Kind? = entries.firstOrNull { it.name == name }
        }
    }

    /** 판별에 보는 머리 표본 크기. */
    const val SAMPLE = 64 * 1024

    private val cp949Charset: Charset by lazy { pickCp949() }

    /** CP949 로 쓸 Charset. 후보 중 '똠'을 바르게 읽는 첫 이름, 없으면 EUC-KR(확장 글자는 깨짐). */
    fun cp949(): Charset = cp949Charset

    /** 고른 CP949 Charset 이름(진단·시험용). */
    fun cp949Name(): String = cp949Charset.name()

    internal val CP949_CANDIDATES = listOf("x-windows-949", "windows-949", "MS949", "CP949", "ms949", "EUC-KR")

    private fun pickCp949(): Charset {
        val probe = byteArrayOf(0x8C.toByte(), 0x63, 0xB0.toByte(), 0xA1.toByte()) // "똠가"
        var fallback: Charset? = null
        for (n in CP949_CANDIDATES) {
            val cs = runCatching { if (Charset.isSupported(n)) Charset.forName(n) else null }.getOrNull() ?: continue
            if (fallback == null) fallback = cs
            val ok = runCatching { strict(cs, probe, probe.size) == "똠가" }.getOrDefault(false)
            if (ok) return cs
        }
        return fallback ?: Charsets.UTF_8
    }

    fun charset(k: Kind): Charset = when (k) {
        Kind.UTF8 -> Charsets.UTF_8
        Kind.UTF16LE -> Charsets.UTF_16LE
        Kind.UTF16BE -> Charsets.UTF_16BE
        Kind.CP949 -> cp949()
    }

    /** [b] 의 앞 [len] 바이트(파일 머리 표본)로 판별한다. 표본 끝에서 잘린 여러 바이트 글자는 오류로 치지 않는다. */
    fun detect(b: ByteArray, len: Int = b.size): Kind {
        val n = minOf(len, b.size)
        if (n >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) return Kind.UTF8
        if (n >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return Kind.UTF16LE
        if (n >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return Kind.UTF16BE
        utf16NoBom(b, n)?.let { return it }
        val u8 = utf8Errors(b, n)
        if (u8 == 0) return Kind.UTF8
        val cp = cp949Errors(b, n)
        if (cp == 0) return Kind.CP949
        return if (u8 < cp) Kind.UTF8 else Kind.CP949
    }

    /** BOM 없는 UTF-16: 0 바이트가 한쪽 자리(짝·홀)에 10% 넘게 있고 다른 쪽은 그 1/10 미만이면. */
    private fun utf16NoBom(b: ByteArray, n: Int): Kind? {
        val m = minOf(n, 8192) and 1.inv()
        if (m < 16) return null
        var even = 0; var odd = 0
        for (i in 0 until m) if (b[i].toInt() == 0) { if (i and 1 == 0) even++ else odd++ }
        val half = m / 2
        return when {
            odd * 10 > half && even * 10 < odd -> Kind.UTF16LE
            even * 10 > half && odd * 10 < even -> Kind.UTF16BE
            else -> null
        }
    }

    /** UTF-8 규칙 위반 수(넘친 길이·과잉 표현·대리 영역 포함). 표본 끝에서 잘린 글자는 세지 않는다. */
    internal fun utf8Errors(b: ByteArray, n: Int): Int {
        var err = 0
        var i = 0
        while (i < n) {
            val c = b[i].toInt() and 0xFF
            val need: Int; val min: Int
            when {
                c < 0x80 -> { i++; continue }
                c in 0xC2..0xDF -> { need = 1; min = 0x80 }
                c in 0xE0..0xEF -> { need = 2; min = 0x800 }
                c in 0xF0..0xF4 -> { need = 3; min = 0x10000 }
                else -> { err++; i++; continue }
            }
            if (i + need >= n) {
                // 표본 끝에서 잘림: 남은 바이트가 모두 이어짐 바이트면 오류 아님
                var ok = true
                for (j in i + 1 until n) if ((b[j].toInt() and 0xC0) != 0x80) ok = false
                if (ok) break
            }
            var cp = c and (0x3F shr need)
            var bad = false
            for (j in 1..need) {
                if (i + j >= n) { bad = true; break }
                val d = b[i + j].toInt() and 0xFF
                if (d and 0xC0 != 0x80) { bad = true; break }
                cp = (cp shl 6) or (d and 0x3F)
            }
            if (bad || cp < min || cp > 0x10FFFF || cp in 0xD800..0xDFFF) { err++; i++ } else i += need + 1
        }
        return err
    }

    /** CP949 로 읽을 때 나오는 오류 수(표본 끝에서 잘린 앞 바이트 하나는 세지 않음). */
    internal fun cp949Errors(b: ByteArray, n: Int): Int {
        var err = 0
        var i = 0
        while (i < n) {
            val c = b[i].toInt() and 0xFF
            if (c < 0x80) { i++; continue }
            if (c == 0x80 || c == 0xFF) { err++; i++; continue }
            if (i + 1 >= n) break
            val d = b[i + 1].toInt() and 0xFF
            // CP949 둘째 바이트: 0x41-0x5A, 0x61-0x7A, 0x81-0xFE (0x81-0xC6 첫 바이트), EUC-KR 영역 0xA1-0xFE
            val ok = if (c in 0x81..0xC6) d in 0x41..0x5A || d in 0x61..0x7A || d in 0x81..0xFE else d in 0xA1..0xFE
            if (ok) i += 2 else { err++; i++ }
        }
        return err
    }

    /** 바이트 전체를 [k] 로 읽는다(BOM 제거, 잘못된 바이트는 대체 문자). */
    fun decode(bytes: ByteArray, k: Kind): String {
        var off = 0
        when (k) {
            Kind.UTF8 -> if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) off = 3
            Kind.UTF16LE -> if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) off = 2
            Kind.UTF16BE -> if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) off = 2
            Kind.CP949 -> Unit
        }
        return String(bytes, off, bytes.size - off, charset(k))
    }

    private fun strict(cs: Charset, b: ByteArray, n: Int): String? = try {
        cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b, 0, n)).toString()
    } catch (e: CharacterCodingException) { null }
}
