// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** 파일 시그니처로 실제 형식을 판별한다(F-05, 기술설계서 2.1). 확장자는 보지 않는다. */
enum class SniffedFormat { DOCX, DOC, ENCRYPTED_OOXML, HWP5, HWP3, HWPX, EPUB, XPS, ZIP, RTF, PDF, OLE_OTHER, UNKNOWN }

object FormatSniffer {
    fun sniff(b: ByteArray): SniffedFormat {
        fun startsWith(s: String, off: Int = 0) = b.size >= off + s.length && (s.indices).all { b[off + it] == s[it].code.toByte() }
        val bomSkip = if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) 3 else 0
        return when {
            startsWith("{\\rtf", bomSkip) -> SniffedFormat.RTF
            startsWith("%PDF-") -> SniffedFormat.PDF
            startsWith("HWP Document File V3") -> SniffedFormat.HWP3
            startsWith("PK\u0003\u0004") -> zip(b)
            Cfb.isCfb(b) -> ole(b)
            else -> SniffedFormat.UNKNOWN
        }
    }

    private fun ole(b: ByteArray): SniffedFormat = runCatching {
        val c = Cfb.open(b)
        when {
            c.hasStream("EncryptionInfo") && c.hasStream("EncryptedPackage") -> SniffedFormat.ENCRYPTED_OOXML
            c.hasStream("WordDocument") -> SniffedFormat.DOC
            c.hasStream("FileHeader") -> SniffedFormat.HWP5
            else -> SniffedFormat.OLE_OTHER
        }
    }.getOrDefault(SniffedFormat.OLE_OTHER)

    private fun zip(b: ByteArray): SniffedFormat {
        val names = HashSet<String>()
        var mimetype: String? = null
        runCatching {
            ZipInputStream(ByteArrayInputStream(b)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    names += e.name
                    if (e.name == "mimetype") mimetype = z.readBytes().toString(Charsets.US_ASCII).trim()
                }
            }
        }
        return when {
            mimetype == "application/epub+zip" -> SniffedFormat.EPUB
            "word/document.xml" in names -> SniffedFormat.DOCX
            mimetype == "application/hwp+zip" || "Contents/section0.xml" in names -> SniffedFormat.HWPX
            "FixedDocSeq.fdseq" in names || names.any { it.endsWith(".fdseq") } -> SniffedFormat.XPS
            else -> SniffedFormat.ZIP
        }
    }
}
