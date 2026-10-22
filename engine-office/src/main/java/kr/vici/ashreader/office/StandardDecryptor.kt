// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * ECMA-376 Standard 암호화(Office 2007, EncryptionInfo 버전 x.2) 해제 (MS-OFFCRYPTO 2.3.4.5~2.3.4.9).
 * AES-ECB + SHA-1(50000회). RC4(CryptoAPI) 방식은 지원하지 않는다. 결과는 메모리에만 둔다(F-34).
 */
internal object StandardDecryptor {
    private const val SPIN = 50_000

    fun decrypt(info: ByteArray, pkg: ByteArray, password: String): AgileDecryptor.Result {
        val bb = ByteBuffer.wrap(info).order(ByteOrder.LITTLE_ENDIAN)
        if (info.size < 12 + 32) return AgileDecryptor.Result.Unsupported("EncryptionInfo 너무 짧음")
        val headerSize = bb.getInt(8)
        val h = 12
        if (headerSize < 32 || h + headerSize + 4 > info.size) return AgileDecryptor.Result.Unsupported("헤더 크기 오류")
        val algId = bb.getInt(h + 8)
        val algHash = bb.getInt(h + 12)
        var keyBits = bb.getInt(h + 16)
        val keyBytes = when (algId) {
            0x660E -> 16; 0x660F -> 24; 0x6610 -> 32
            0 -> { if (keyBits == 0) keyBits = 128; keyBits / 8 } // 0 = 플래그로 결정(AES 가정)
            else -> return AgileDecryptor.Result.Unsupported("알고리즘 0x%04X (RC4 등)".format(algId))
        }
        if (algHash != 0 && algHash != 0x8004) return AgileDecryptor.Result.Unsupported("해시 0x%04X".format(algHash))
        if (keyBytes !in setOf(16, 24, 32)) return AgileDecryptor.Result.Unsupported("키 길이 $keyBits")

        var p = h + headerSize
        val saltSize = bb.getInt(p); p += 4
        if (saltSize != 16 || p + 16 + 16 + 4 + 32 > info.size) return AgileDecryptor.Result.Unsupported("검증자 형식 오류")
        val salt = info.copyOfRange(p, p + 16); p += 16
        val encVerifier = info.copyOfRange(p, p + 16); p += 16
        val verifierHashSize = bb.getInt(p); p += 4
        val encVerifierHash = info.copyOfRange(p, p + 32)

        val key = deriveKey(salt, password, keyBytes)
        val ecb = Cipher.getInstance("AES/ECB/NoPadding")
        ecb.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val verifier = ecb.doFinal(encVerifier)
        val vHash = ecb.doFinal(encVerifierHash)
        val expect = MessageDigest.getInstance("SHA-1").digest(verifier)
        val n = minOf(verifierHashSize.coerceAtLeast(20), expect.size)
        if (!vHash.copyOf(n).contentEquals(expect.copyOf(n))) return AgileDecryptor.Result.WrongPassword

        if (pkg.size < 8) return AgileDecryptor.Result.Unsupported("EncryptedPackage 너무 짧음")
        val total = ByteBuffer.wrap(pkg, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong(0)
        val body = pkg.size - 8
        val len = body - body % 16
        if (total < 0 || total > len) return AgileDecryptor.Result.Unsupported("패키지 크기 오류")
        val plain = ecb.doFinal(pkg, 8, len)
        val out = plain.copyOf(total.toInt())
        if (out.size < 4 || out[0] != 'P'.code.toByte() || out[1] != 'K'.code.toByte()) {
            return AgileDecryptor.Result.Unsupported("해제 결과가 ZIP 아님")
        }
        return AgileDecryptor.Result.Ok(out)
    }

    /** 2.3.4.7: H0 = SHA1(salt+암호), Hn = SHA1(n+Hn-1), Hfinal = SHA1(H+블록0), X1·X2(0x36·0x5C) 이어 붙여 자른다. */
    private fun deriveKey(salt: ByteArray, password: String, keyBytes: Int): ByteArray {
        val md = MessageDigest.getInstance("SHA-1")
        var h = md.digest(salt + password.toByteArray(Charsets.UTF_16LE))
        val iter = ByteArray(4)
        for (i in 0 until SPIN) {
            ByteBuffer.wrap(iter).order(ByteOrder.LITTLE_ENDIAN).putInt(0, i)
            md.update(iter); md.update(h); h = md.digest()
        }
        val hFinal = md.digest(h + ByteArray(4))
        fun x(c: Int): ByteArray {
            val buf = ByteArray(64) { c.toByte() }
            for (i in hFinal.indices) buf[i] = (buf[i].toInt() xor hFinal[i].toInt()).toByte()
            return md.digest(buf)
        }
        return (x(0x36) + x(0x5C)).copyOf(keyBytes)
    }
}
