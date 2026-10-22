// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 최소 OLE 복합 문서(CFB, MS-CFB) 읽기. 스트림 이름 목록과 스트림 바이트만 꺼낸다.
 * 시그니처 판별(DOC/HWP/암호 OOXML 구분)과 암호 DOCX의 EncryptionInfo·EncryptedPackage 읽기에 쓴다.
 */
class Cfb private constructor(private val data: ByteArray) {
    private val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    private val sectorSize = 1 shl u16(30)
    private val miniSectorSize = 1 shl u16(32)
    private val miniCutoff = u32(56)
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries: List<Entry>
    private val miniStream: ByteArray

    class Entry(val name: String, val type: Int, val start: Int, val size: Long)

    init {
        val difat = ArrayList<Int>()
        for (i in 0 until 109) difat += bb.getInt(76 + i * 4)
        var difatSec = bb.getInt(68)
        var guard = 0
        while (difatSec >= 0 && guard++ < 10_000) {
            val off = secOff(difatSec)
            val n = sectorSize / 4 - 1
            for (i in 0 until n) difat += bb.getInt(off + i * 4)
            difatSec = bb.getInt(off + n * 4)
        }
        val fatList = ArrayList<Int>()
        for (s in difat) {
            if (s < 0) continue
            val off = secOff(s)
            for (i in 0 until sectorSize / 4) fatList += bb.getInt(off + i * 4)
        }
        fat = fatList.toIntArray()
        val dir = chain(bb.getInt(48), -1)
        val es = ArrayList<Entry>()
        var i = 0
        while (i + 128 <= dir.size) {
            val e = ByteBuffer.wrap(dir, i, 128).order(ByteOrder.LITTLE_ENDIAN)
            val nameLen = e.getShort(i + 64).toInt() and 0xFFFF
            val type = dir[i + 66].toInt()
            val name = if (nameLen >= 2) String(dir, i, nameLen - 2, Charsets.UTF_16LE) else ""
            val size = e.getInt(i + 120).toLong() and 0xFFFFFFFFL
            es += Entry(name, type, e.getInt(i + 116), size)
            i += 128
        }
        entries = es
        val root = es.firstOrNull { it.type == 5 }
        miniStream = if (root != null && root.start >= 0) chain(root.start, root.size) else ByteArray(0)
        miniFat = run {
            val mf = bb.getInt(60)
            if (mf < 0) IntArray(0) else {
                val raw = chain(mf, -1)
                val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                IntArray(raw.size / 4) { b.getInt(it * 4) }
            }
        }
    }

    val streamNames: List<String> get() = entries.filter { it.type == 2 }.map { it.name }

    fun hasStream(name: String) = entries.any { it.type == 2 && it.name == name }

    fun read(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name == name } ?: return null
        return if (e.size < miniCutoff) miniChain(e.start, e.size) else chain(e.start, e.size)
    }

    private fun u16(o: Int) = bb.getShort(o).toInt() and 0xFFFF
    private fun u32(o: Int) = bb.getInt(o)
    private fun secOff(s: Int) = (s + 1) * sectorSize

    private fun chain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        while (s >= 0 && s < fat.size && guard++ < 10_000_000) {
            val off = secOff(s)
            if (off + sectorSize > data.size) { out.write(data, off, maxOf(0, data.size - off)); break }
            out.write(data, off, sectorSize)
            if (size >= 0 && out.size() >= size) break
            s = fat[s]
        }
        val all = out.toByteArray()
        return if (size >= 0 && all.size > size) all.copyOf(size.toInt()) else all
    }

    private fun miniChain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        while (s >= 0 && s < miniFat.size && guard++ < 10_000_000) {
            val off = s * miniSectorSize
            if (off + miniSectorSize > miniStream.size) break
            out.write(miniStream, off, miniSectorSize)
            if (out.size() >= size) break
            s = miniFat[s]
        }
        return out.toByteArray().copyOf(size.toInt())
    }

    companion object {
        val MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        fun isCfb(b: ByteArray) = b.size >= 512 && (0 until 8).all { b[it] == MAGIC[it] }
        fun open(b: ByteArray): Cfb {
            require(isCfb(b)) { "OLE 복합 문서가 아닙니다" }
            return Cfb(b)
        }
    }
}
