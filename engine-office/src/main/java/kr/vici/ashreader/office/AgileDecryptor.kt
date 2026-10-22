// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * ECMA-376 Agile 암호화(Office 2010+) 해제 자체 구현 (MS-OFFCRYPTO 2.3.4.10~2.3.4.15).
 * Standard 암호화(Office 2007, 버전 x.2)는 [StandardDecryptor] 로 넘긴다.
 * javax.crypto(AES-CBC)와 MessageDigest만 쓴다. 결과(ZIP 바이트)는 메모리에만 둔다(F-34).
 */
object AgileDecryptor {
    sealed class Result {
        class Ok(val zip: ByteArray) : Result()
        object WrongPassword : Result()
        class Unsupported(val reason: String) : Result()
    }

    private val BLK_VERIFIER_INPUT = hex("fea7d2763b4b9e79")
    private val BLK_VERIFIER_VALUE = hex("d7aa0f6d3061344e")
    private val BLK_KEY_VALUE = hex("146e0be7abacd0d6")

    fun isEncryptedOoxml(file: ByteArray): Boolean =
        Cfb.isCfb(file) && runCatching { Cfb.open(file).let { it.hasStream("EncryptionInfo") && it.hasStream("EncryptedPackage") } }.getOrDefault(false)

    fun decrypt(file: ByteArray, password: String): Result {
        val cfb = Cfb.open(file)
        val info = cfb.read("EncryptionInfo") ?: return Result.Unsupported("EncryptionInfo 없음")
        val pkg = cfb.read("EncryptedPackage") ?: return Result.Unsupported("EncryptedPackage 없음")
        val vMajor = (info[0].toInt() and 0xFF) or ((info[1].toInt() and 0xFF) shl 8)
        val vMinor = (info[2].toInt() and 0xFF) or ((info[3].toInt() and 0xFF) shl 8)
        if (vMinor == 2 && vMajor in 2..4) return StandardDecryptor.decrypt(info, pkg, password)
        if (!(vMajor == 4 && vMinor == 4)) return Result.Unsupported("암호화 버전 $vMajor.$vMinor")
        val xml = info.copyOfRange(8, info.size)
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(xml.inputStream())
        val keyData = doc.getElementsByTagNameNS("*", "keyData").item(0) as Element
        val ek = doc.getElementsByTagNameNS("*", "encryptedKey").item(0) as? Element
            ?: return Result.Unsupported("암호 키 항목 없음(인증서 방식)")
        if (keyData.getAttribute("cipherAlgorithm") != "AES" || ek.getAttribute("cipherAlgorithm") != "AES") {
            return Result.Unsupported("AES 아님")
        }
        if (ek.getAttribute("cipherChaining") != "ChainingModeCBC") return Result.Unsupported("CBC 아님")

        val hashAlg = javaHash(ek.getAttribute("hashAlgorithm")) ?: return Result.Unsupported("해시 ${ek.getAttribute("hashAlgorithm")}")
        val spin = ek.getAttribute("spinCount").toInt()
        val salt = b64(ek.getAttribute("saltValue"))
        val keyBytes = ek.getAttribute("keyBits").toInt() / 8
        val blockSize = ek.getAttribute("blockSize").toInt()

        val md = MessageDigest.getInstance(hashAlg)
        var h = md.digest(salt + password.toByteArray(Charsets.UTF_16LE))
        val iter = ByteArray(4)
        for (i in 0 until spin) {
            ByteBuffer.wrap(iter).order(ByteOrder.LITTLE_ENDIAN).putInt(0, i)
            md.update(iter); md.update(h); h = md.digest()
        }
        fun derive(block: ByteArray) = fix(md.digest(h + block), keyBytes)

        val verInput = aes(derive(BLK_VERIFIER_INPUT), salt, b64(ek.getAttribute("encryptedVerifierHashInput")))
            .copyOf(salt.size)
        val verHash = aes(derive(BLK_VERIFIER_VALUE), salt, b64(ek.getAttribute("encryptedVerifierHashValue")))
        val expect = md.digest(verInput)
        if (!verHash.copyOf(expect.size).contentEquals(expect)) return Result.WrongPassword
        val secret = aes(derive(BLK_KEY_VALUE), salt, b64(ek.getAttribute("encryptedKeyValue"))).copyOf(keyBytes)

        // 패키지: 8바이트 원래 크기 + 4096바이트 구간
        val kdHash = javaHash(keyData.getAttribute("hashAlgorithm")) ?: return Result.Unsupported("keyData 해시")
        val kdSalt = b64(keyData.getAttribute("saltValue"))
        val kdBlock = keyData.getAttribute("blockSize").toInt().takeIf { it > 0 } ?: blockSize
        val total = ByteBuffer.wrap(pkg, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong(0)
        val out = ByteArray(total.toInt())
        val kmd = MessageDigest.getInstance(kdHash)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val key = SecretKeySpec(secret, "AES")
        var seg = 0
        var pos = 8
        var written = 0
        while (pos < pkg.size && written < out.size) {
            val len = minOf(4096, pkg.size - pos).let { it - it % 16 }
            if (len <= 0) break
            val segBytes = ByteArray(4).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(0, seg) }
            val iv = fix(kmd.digest(kdSalt + segBytes), kdBlock)
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
            val plain = cipher.doFinal(pkg, pos, len)
            val n = minOf(plain.size, out.size - written)
            System.arraycopy(plain, 0, out, written, n)
            written += n; pos += len; seg++
        }
        if (out.size < 4 || out[0] != 'P'.code.toByte() || out[1] != 'K'.code.toByte()) {
            return Result.Unsupported("해제 결과가 ZIP 아님")
        }
        return Result.Ok(out)
    }

    private fun aes(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(fix(iv, 16)))
        return c.doFinal(data)
    }

    private fun fix(b: ByteArray, n: Int): ByteArray =
        if (b.size >= n) b.copyOf(n) else ByteArray(n) { if (it < b.size) b[it] else 0x36 }

    private fun javaHash(n: String): String? = when (n.uppercase()) {
        "SHA512", "SHA-512" -> "SHA-512"
        "SHA384", "SHA-384" -> "SHA-384"
        "SHA256", "SHA-256" -> "SHA-256"
        "SHA1", "SHA-1" -> "SHA-1"
        else -> null
    }

    private fun b64(s: String) = Base64.getDecoder().decode(s)
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
