// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.comic

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipFile

/** 넘김 방향 (F-48). */
enum class ReadingDirection { LTR, RTL }

/** 압축 안 쪽 이미지 하나. */
data class PageEntry(val index: Int, val name: String, val size: Long)

/** 만화 압축 형식 판별·쪽 정렬·ComicInfo 해석 — 안드로이드 API 없이 JVM 에서 시험 가능. */
object ComicFormat {
    enum class Kind { ZIP, RAR4, RAR5, UNKNOWN }

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "jxl")

    /** 확장자가 아니라 시그니처로 판별 (cbz 이름의 rar 등). */
    fun sniff(head: ByteArray): Kind = when {
        head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2].toInt() == 3 && head[3].toInt() == 4 -> Kind.ZIP
        head.size >= 8 && head.copyOfRange(0, 8).contentEquals(RAR5_SIG) -> Kind.RAR5
        head.size >= 7 && head.copyOfRange(0, 7).contentEquals(RAR4_SIG) -> Kind.RAR4
        else -> Kind.UNKNOWN
    }

    private val RAR4_SIG = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
    private val RAR5_SIG = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

    /**
     * solid 여부 (기술 설계서 7.2). RAR4: 시그니처 뒤 MAIN_HEAD(type 0x73) HEAD_FLAGS & 0x0008.
     * RAR5: 첫 헤더가 main archive header(type 1)이면 archive flags & 0x0004.
     */
    fun isSolid(head: ByteArray): Boolean = when (sniff(head)) {
        Kind.RAR4 -> head.size >= 7 + 5 && (head[7 + 2].toInt() and 0xff) == 0x73 &&
            ((head[7 + 3].toInt() and 0xff) or ((head[7 + 4].toInt() and 0xff) shl 8)) and 0x0008 != 0
        Kind.RAR5 -> rar5Solid(head)
        else -> false
    }

    private fun rar5Solid(b: ByteArray): Boolean {
        var p = 8 + 4 // 시그니처 + CRC32
        fun vint(): Long {
            var r = 0L; var s = 0
            while (p < b.size) { val c = b[p++].toInt() and 0xff; r = r or ((c and 0x7f).toLong() shl s); if (c and 0x80 == 0) return r; s += 7 }
            return -1
        }
        vint() // header size
        val type = vint(); if (type != 1L) return false
        val hflags = vint()
        if (hflags and 0x1L != 0L) vint() // extra area size
        if (hflags and 0x2L != 0L) vint() // data size
        val aflags = vint()
        return aflags >= 0 && aflags and 0x4L != 0L
    }

    fun isImage(name: String): Boolean {
        val n = name.substringAfterLast('/')
        if (n.startsWith(".") || name.contains("__MACOSX/")) return false
        return n.substringAfterLast('.', "").lowercase() in IMAGE_EXT
    }

    /** 자연 정렬 (1, 2, 10). 폴더 경로 포함 비교. */
    val naturalOrder: Comparator<String> = Comparator { a, b ->
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val bx = x.trimStart('0').ifEmpty { "0" }; val by = y.trimStart('0').ifEmpty { "0" }
                if (bx.length != by.length) bx.length - by.length else bx.compareTo(by)
            } else x.compareTo(y)
            if (c != 0) return@Comparator c
        }
        ra.size - rb.size
    }

    fun pages(names: List<Pair<String, Long>>): List<PageEntry> =
        names.filter { isImage(it.first) }.sortedWith(compareBy(naturalOrder) { it.first })
            .mapIndexed { i, (n, s) -> PageEntry(i, n, s) }

    /** ComicInfo.xml 의 Manga=YesAndRightToLeft → RTL (F-49). 없거나 다른 값이면 null(설정값 따름). */
    fun directionFromComicInfo(xml: String): ReadingDirection? {
        val m = Regex("<Manga>\\s*([^<]+?)\\s*</Manga>", RegexOption.IGNORE_CASE).find(xml) ?: return null
        return when (m.groupValues[1].lowercase()) {
            "yesandrighttoleft" -> ReadingDirection.RTL
            "no", "yes" -> ReadingDirection.LTR
            else -> null
        }
    }

    /** ZIP 파일명 인코딩: UTF-8 엄격 디코드 실패 시 CP949 (기술 설계서 6장). */
    fun openZip(file: File): ZipFile = try {
        ZipFile(file, Charsets.UTF_8).also { z -> z.entries().asSequence().forEach { it.name } }
    } catch (e: Exception) { // IllegalArgumentException 또는 ZipException(잘못된 이름)
        ZipFile(file, Charset.forName("x-windows-949"))
    }

    /** 앞 [n]바이트(파일이 짧으면 그만큼). InputStream.readNBytes 는 API 33 이라 직접 읽는다(minSdk 26). */
    fun readHead(input: InputStream, n: Int = 64): ByteArray = input.use { s ->
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = s.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        if (off == n) buf else buf.copyOf(off)
    }
}
