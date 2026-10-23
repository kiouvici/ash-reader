// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.data

import java.io.EOFException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** 위치를 정해 읽는 바이트 원본(경로 있는 파일·SAF 파일 서술자 공용). 원본에는 쓰지 않는다(N-04). */
interface ByteSource {
    val length: Long

    /** [pos] 부터 최대 [len] 바이트를 읽어 읽은 수를 돌려준다(끝이면 0 이하). */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int

    fun readFully(pos: Long, buf: ByteArray) {
        var done = 0
        while (done < buf.size) {
            val n = read(pos + done, buf, done, buf.size - done)
            if (n <= 0) throw EOFException()
            done += n
        }
    }

    companion object {
        fun of(f: RandomAccessFile): ByteSource = of(f.channel)

        fun of(ch: FileChannel): ByteSource = object : ByteSource {
            override val length: Long = ch.size()
            override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int = ch.read(ByteBuffer.wrap(buf, off, len), pos)
        }

        fun of(bytes: ByteArray): ByteSource = object : ByteSource {
            override val length: Long = bytes.size.toLong()
            override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
                if (pos >= bytes.size) return -1
                val n = minOf(len.toLong(), bytes.size - pos).toInt()
                System.arraycopy(bytes, pos.toInt(), buf, off, n)
                return n
            }
        }
    }
}

/**
 * ZIP 중앙 디렉터리만 읽어 항목 이름을 얻는다(경로 없는 SAF 파일을 판별할 때, 전체를 풀지 않음).
 * ZIP64·여러 디스크는 다루지 않는다(null → 확장자로 판별).
 */
object ZipNames {
    class Entry(val name: String, val method: Int, val compressedSize: Long, val localOffset: Long)

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)

    fun list(src: ByteSource, maxEntries: Int = 100_000): List<Entry>? = runCatching {
        val len = src.length
        if (len < 22) return null
        val tailLen = minOf(len, 22L + 65535).toInt()
        val tail = ByteArray(tailLen).also { src.readFully(len - tailLen, it) }
        var e = -1
        for (i in tailLen - 22 downTo 0) if (u32(tail, i) == 0x06054b50L) { e = i; break }
        if (e < 0) return null
        val count = u16(tail, e + 10)
        val cdSize = u32(tail, e + 12)
        val cdOff = u32(tail, e + 16)
        if (count == 0xFFFF || cdOff == 0xFFFFFFFFL || cdSize > (64L shl 20) || cdOff + cdSize > len || count > maxEntries) return null
        val cd = ByteArray(cdSize.toInt()).also { src.readFully(cdOff, it) }
        val out = ArrayList<Entry>(count)
        var p = 0
        repeat(count) {
            if (p + 46 > cd.size || u32(cd, p) != 0x02014b50L) return null
            val flags = u16(cd, p + 8)
            val method = u16(cd, p + 10)
            val csize = u32(cd, p + 20)
            val nl = u16(cd, p + 28); val xl = u16(cd, p + 30); val cl = u16(cd, p + 32)
            val local = u32(cd, p + 42)
            if (p + 46 + nl > cd.size) return null
            val cs = if (flags and 0x800 != 0) Charsets.UTF_8 else Charsets.ISO_8859_1
            out += Entry(String(cd, p + 46, nl, cs), method, csize, local)
            p += 46 + nl + xl + cl
        }
        out
    }.getOrNull()

    /** 압축 없이 담긴 작은 항목([name], 256바이트 이하)의 글자. EPUB 'mimetype' 확인용. */
    fun storedText(src: ByteSource, entries: List<Entry>, name: String): String? = runCatching {
        val e = entries.firstOrNull { it.name == name } ?: return null
        if (e.method != 0 || e.compressedSize !in 0..256) return null
        val h = ByteArray(30).also { src.readFully(e.localOffset, it) }
        if (u32(h, 0) != 0x04034b50L) return null
        val data = ByteArray(e.compressedSize.toInt())
        src.readFully(e.localOffset + 30 + u16(h, 26) + u16(h, 28), data)
        String(data, Charsets.US_ASCII).trim()
    }.getOrNull()
}
