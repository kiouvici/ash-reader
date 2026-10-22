// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 파일에서 필요한 섹터만 읽는 OLE 복합 문서(CFB) 읽기 (필수 4: 형식 판별에 파일 전체를 메모리에 올리지 않는다).
 * 헤더·DIFAT·디렉터리 섹터와, 읽으려는 작은 스트림(FileHeader 등)이 걸친 섹터만 읽는다.
 * 손상된 파일은 예외로 알린다(호출하는 쪽이 잡는다).
 */
class CfbFile private constructor(private val raf: RandomAccessFile) : AutoCloseable {
    private val len = raf.length()
    private val header = readAt(0, 512)
    private val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    private val sectorSize: Int = 1 shl (hb.getShort(30).toInt() and 0xFFFF).coerceIn(7, 16)
    private val miniSectorSize: Int = 1 shl (hb.getShort(32).toInt() and 0xFFFF).coerceIn(4, 12)
    private val miniCutoff: Long = hb.getInt(56).toLong() and 0xFFFFFFFFL
    /** FAT 섹터 번호 목록(DIFAT). */
    private val difat: IntArray
    private val entries: List<Cfb.Entry>
    private val fatCache = HashMap<Int, IntArray>()

    init {
        val d = ArrayList<Int>()
        for (i in 0 until 109) d += hb.getInt(76 + i * 4)
        var sec = hb.getInt(68)
        var guard = 0
        while (sec >= 0 && guard++ < 4096) {
            val b = ByteBuffer.wrap(readSector(sec)).order(ByteOrder.LITTLE_ENDIAN)
            val n = sectorSize / 4 - 1
            for (i in 0 until n) d += b.getInt(i * 4)
            sec = b.getInt(n * 4)
        }
        difat = d.filter { it >= 0 }.toIntArray()
        val dir = readChain(hb.getInt(48), MAX_DIR_BYTES)
        val es = ArrayList<Cfb.Entry>()
        var i = 0
        val db = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
        while (i + 128 <= dir.size) {
            val nameLen = db.getShort(i + 64).toInt() and 0xFFFF
            val type = dir[i + 66].toInt()
            val name = if (nameLen in 2..64) String(dir, i, nameLen - 2, Charsets.UTF_16LE) else ""
            val size = db.getInt(i + 120).toLong() and 0xFFFFFFFFL
            es += Cfb.Entry(name, type, db.getInt(i + 116), size)
            i += 128
        }
        entries = es
    }

    val streamNames: List<String> get() = entries.filter { it.type == 2 }.map { it.name }

    fun hasStream(name: String) = entries.any { it.type == 2 && it.name == name }

    /** 작은 스트림 읽기. [maxBytes] 보다 크면 앞 [maxBytes] 만. 없으면 null. */
    fun read(name: String, maxBytes: Int = 64 shl 10): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name == name } ?: return null
        val want = minOf(e.size, maxBytes.toLong()).toInt()
        if (want <= 0) return ByteArray(0)
        return if (e.size < miniCutoff) readMini(e.start, want) else readChain(e.start, want)
    }

    private fun readAt(pos: Long, n: Int): ByteArray {
        require(pos >= 0 && pos + n <= len) { "CFB 범위 밖" }
        val b = ByteArray(n)
        raf.seek(pos); raf.readFully(b)
        return b
    }

    private fun readSector(s: Int): ByteArray {
        val off = (s.toLong() + 1) * sectorSize
        val n = minOf(sectorSize.toLong(), len - off).toInt()
        require(n > 0) { "CFB 섹터 범위 밖" }
        return readAt(off, n)
    }

    /** 섹터 [s] 의 다음 섹터(FAT). FAT 섹터는 필요할 때 하나씩 읽는다. */
    private fun next(s: Int): Int {
        val per = sectorSize / 4
        val fi = s / per
        if (fi >= difat.size) return -2
        val fat = fatCache.getOrPut(fi) {
            val b = ByteBuffer.wrap(readSector(difat[fi])).order(ByteOrder.LITTLE_ENDIAN)
            IntArray(b.capacity() / 4) { b.getInt(it * 4) }
        }
        return fat.getOrElse(s % per) { -2 }
    }

    private fun readChain(start: Int, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        while (s >= 0 && out.size() < maxBytes && guard++ < 1_000_000) {
            out.write(readSector(s))
            s = next(s)
        }
        val all = out.toByteArray()
        return if (all.size > maxBytes) all.copyOf(maxBytes) else all
    }

    /** 미니 스트림(루트 항목의 체인) 안의 작은 스트림. 필요한 미니 섹터가 든 섹터만 읽는다. */
    private fun readMini(start: Int, size: Int): ByteArray {
        val root = entries.firstOrNull { it.type == 5 } ?: return ByteArray(0)
        val miniFatStart = hb.getInt(60)
        val out = java.io.ByteArrayOutputStream()
        var m = start
        var guard = 0
        while (m >= 0 && out.size() < size && guard++ < 1_000_000) {
            // 미니 섹터 m 의 위치: 루트 체인의 (m*mini / sectorSize) 번째 섹터 안
            val byteOff = m.toLong() * miniSectorSize
            val nth = (byteOff / sectorSize).toInt()
            var s = root.start
            repeat(nth) { if (s >= 0) s = next(s) }
            if (s < 0) break
            val sec = readSector(s)
            val inner = (byteOff % sectorSize).toInt()
            out.write(sec, inner, minOf(miniSectorSize, sec.size - inner))
            m = miniNext(miniFatStart, m)
        }
        val all = out.toByteArray()
        return if (all.size > size) all.copyOf(size) else all
    }

    private fun miniNext(miniFatStart: Int, m: Int): Int {
        val per = sectorSize / 4
        var s = miniFatStart
        repeat(m / per) { if (s >= 0) s = next(s) }
        if (s < 0) return -2
        val b = ByteBuffer.wrap(readSector(s)).order(ByteOrder.LITTLE_ENDIAN)
        val idx = (m % per) * 4
        return if (idx + 4 <= b.capacity()) b.getInt(idx) else -2
    }

    override fun close() = raf.close()

    companion object {
        private const val MAX_DIR_BYTES = 4 shl 20

        /** CFB 가 아니면 null. */
        fun open(file: File): CfbFile? {
            val raf = RandomAccessFile(file, "r")
            return try {
                if (raf.length() < 512) { raf.close(); return null }
                val head = ByteArray(8); raf.seek(0); raf.readFully(head)
                if (!(0 until 8).all { head[it] == Cfb.MAGIC[it] }) { raf.close(); return null }
                CfbFile(raf)
            } catch (e: Exception) {
                raf.close(); throw e
            }
        }

        /** OLE 파일의 형식(DOC·HWP5·암호 OOXML). 머리 표본으로 이미 CFB 임을 안 뒤 부른다. */
        fun sniff(file: File): SniffedFormat = runCatching {
            open(file)?.use { c ->
                when {
                    c.hasStream("EncryptionInfo") && c.hasStream("EncryptedPackage") -> SniffedFormat.ENCRYPTED_OOXML
                    c.hasStream("WordDocument") -> SniffedFormat.DOC
                    c.hasStream("FileHeader") -> SniffedFormat.HWP5
                    else -> SniffedFormat.OLE_OTHER
                }
            } ?: SniffedFormat.UNKNOWN
        }.getOrDefault(SniffedFormat.OLE_OTHER)

        /** HWP 5.0 FileHeader 속성(36번째 바이트부터 4바이트). bit1 = 암호, bit2 = 배포용. 읽지 못하면 null. */
        fun hwpProperties(file: File): Int? = runCatching {
            open(file)?.use { c ->
                val h = c.read("FileHeader", 256) ?: return@use null
                if (h.size < 40) null else ByteBuffer.wrap(h, 36, 4).order(ByteOrder.LITTLE_ENDIAN).int
            }
        }.getOrNull()
    }
}
