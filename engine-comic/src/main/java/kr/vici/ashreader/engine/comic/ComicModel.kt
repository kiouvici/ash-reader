// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.comic

import java.io.File
import java.io.InputStream
import kr.vici.ashreader.engine.convert.TextEncoding
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
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

    /**
     * 같은 이름이 여러 번 든 압축(RAR 은 같은 경로 항목을 허용)에서 뒤의 것도 버리지 않도록 이름을 겹치지 않게 만든다.
     * 두 번째부터 확장자 앞에 " (2)", " (3)"… 을 붙인다(목록 순서·번호는 그대로).
     */
    fun uniqueNames(names: List<String>): List<String> {
        val seen = HashSet<String>(names.size * 2)
        seen.addAll(names)
        val used = HashSet<String>(names.size * 2)
        return names.map { n ->
            if (used.add(n)) n else {
                val slash = n.lastIndexOf('/')
                val dot = n.lastIndexOf('.').takeIf { it > slash + 1 } ?: n.length
                var k = 2
                var c: String
                do { c = n.substring(0, dot) + " ($k)" + n.substring(dot); k++ } while (c in seen || !used.add(c))
                c
            }
        }
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

    /** 풀기 상한(N-09). */
    const val MAX_EXTRACT = 500L shl 20

    /** ZIP 파일명 인코딩: UTF-8 엄격 디코드 실패 시 CP949 (기술 설계서 6장). 실패한 첫 ZipFile 은 닫는다. */
    fun openZip(file: File): ZipFile {
        var z: ZipFile? = null
        return try {
            ZipFile(file, Charsets.UTF_8).also { z = it; it.entries().asSequence().forEach { e -> e.name } }
        } catch (e: Exception) { // IllegalArgumentException 또는 ZipException(잘못된 이름)
            runCatching { z?.close() }
            ZipFile(file, TextEncoding.cp949())
        }
    }

    /** RAR 항목 이름 바이트: UTF-8 엄격 → 안 되면 CP949(TXT 인코딩 판별기와 같은 CP949 Charset). */
    fun decodeName(b: ByteArray): String {
        if (b.isEmpty()) return ""
        val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try { dec.decode(ByteBuffer.wrap(b)).toString() } catch (e: CharacterCodingException) {
            TextEncoding.decode(b, TextEncoding.Kind.CP949)
        }
    }

    /** 최대 [max] 바이트까지 읽는다. 넘으면 IOException. */
    fun readBounded(input: InputStream, max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        if (copyBounded(input, out, max) < 0) throw java.io.IOException("항목이 너무 큼")
        return out.toByteArray()
    }

    /** [max] 바이트까지 복사해 복사한 바이트 수를, 넘으면 -1 을 돌려준다. */
    fun copyBounded(input: InputStream, out: java.io.OutputStream, max: Long): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) return total
            if (total + n > max) return -1
            out.write(buf, 0, n); total += n
        }
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
