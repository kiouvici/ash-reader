// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.convert

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 폴더 → EPUB(zip). 'mimetype' 을 맨 앞에 압축 없이 넣고 META-INF/container.xml 을 만든다. */
object EpubWriter {
    const val MIMETYPE = "application/epub+zip"

    fun container(opfPath: String) = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="${xmlEscape(opfPath)}" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

    /** [dir] 안 파일을 모두 묶는다. [opfPath] = dir 기준 OPF 경로. 임시 파일에 쓴 뒤 바꿔 끼운다. */
    fun zip(dir: File, opfPath: String, out: File) {
        val tmp = File(out.parentFile, out.name + ".tmp")
        ZipOutputStream(tmp.outputStream().buffered()).use { z ->
            val mt = MIMETYPE.toByteArray(Charsets.US_ASCII)
            z.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mt.size.toLong(); compressedSize = mt.size.toLong()
                crc = CRC32().apply { update(mt) }.value
            })
            z.write(mt); z.closeEntry()
            z.putNextEntry(ZipEntry("META-INF/container.xml"))
            z.write(container(opfPath).toByteArray(Charsets.UTF_8)); z.closeEntry()
            val root = dir.canonicalFile
            root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                val rel = f.canonicalFile.relativeTo(root).invariantSeparatorsPath
                if (rel == "mimetype" || rel == "META-INF/container.xml" || rel.startsWith("..")) return@forEach
                z.putNextEntry(ZipEntry(rel))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
    }

    fun xmlEscape(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&apos;")
            else -> if (c.code < 0x20 && c != '\n' && c != '\t' && c != '\r') append(' ') else append(c)
        }
    }

    /** OPF·NCX href 용 경로 인코딩: 공백·%·#·?·따옴표 등만 %xx 로(한글 등 0x80 이상 글자는 그대로 두는 IRI). XML 에 넣을 때는 [xmlEscape] 도 한다. */
    fun hrefEncode(path: String): String = buildString {
        for (ch in path) {
            when {
                ch.code >= 0x80 -> append(ch)
                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "/-._~!$&'()*+,;=:@" -> append(ch)
                else -> append('%').append("%02X".format(ch.code))
            }
        }
    }
}
