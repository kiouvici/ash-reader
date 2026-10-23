// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.comic

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** 암호 걸린 CBR (기술 설계서 6장 안내 문구). */
class EncryptedArchiveException : IOException("암호가 걸린 만화 압축 파일은 열 수 없어요.")

/**
 * CBZ(java.util.zip)·CBR(libarchive) 공통 열기. 쪽 목록·쪽 바이트·ComicInfo 방향.
 * solid CBR 은 [extractAll] 로 처음 열 때 한 번에 풀어 캐시에 둔다(N-02, N-09 예외).
 */
class ComicArchive private constructor(
    val file: File,
    val kind: ComicFormat.Kind,
    val solid: Boolean,
    private val zip: ZipFile?,
) : Closeable {
    val pages: List<PageEntry>
    val direction: ReadingDirection?
    /** CBR: archiveList 순서의 항목 이름(풀기 진행의 번호 → 이름). 같은 이름이 여럿이면 겹치지 않게 바꾼 이름(모두 남긴다). */
    private val rarNames: List<String>
    private val rarIndex = HashMap<String, Int>()

    init {
        val all: List<Pair<String, Long>> = if (zip != null) {
            zip.entries().asSequence().filter { !it.isDirectory }.map { it.name to it.size }.toList()
        } else {
            // 항목 번호(archiveList 순서)로 읽으므로, 같은 이름이 여럿이어도 이름만 바꿔 모두 쪽으로 남긴다
            val raw = rar { NativeComic.archiveList(file.path) }.toList().chunked(2)
                .map { ComicFormat.decodeName(it[0]) to (String(it[1], Charsets.US_ASCII).toLongOrNull() ?: -1L) }
            ComicFormat.uniqueNames(raw.map { it.first }).zip(raw) { n, (_, size) -> n to size }
        }
        rarNames = if (zip == null) all.map { it.first } else emptyList()
        rarNames.forEachIndexed { i, n -> rarIndex[n] = i }
        pages = ComicFormat.pages(all)
        direction = all.firstOrNull { it.first.substringAfterLast('/').equals("ComicInfo.xml", true) }
            ?.let { ComicFormat.directionFromComicInfo(String(readEntry(it.first, 4L shl 20), Charsets.UTF_8)) }
    }

    fun readPage(index: Int, maxBytes: Long = 200L shl 20): ByteArray = readEntry(pages[index].name, maxBytes)

    /** CBZ 쪽 그림 크기(머리만 읽음). CBR 은 null(풀어 둔 파일에서 읽는다). */
    fun pageBounds(index: Int): Pair<Int, Int>? {
        val z = zip ?: return null
        val e = z.getEntry(pages[index].name) ?: return null
        return z.getInputStream(e).use { PageDecoder.bounds(it) }
    }

    private fun readEntry(name: String, maxBytes: Long): ByteArray = if (zip != null) {
        val e = zip.getEntry(name) ?: throw IOException("항목 없음: $name")
        if (e.size > maxBytes) throw IOException("항목이 너무 큼")
        zip.getInputStream(e).use { ComicFormat.readBounded(it, maxBytes) }
    } else rar { NativeComic.archiveRead(file.path, rarIndex[name] ?: throw IOException("항목 없음: $name"), maxBytes) }

    /**
     * 전체를 앞에서부터 풀어 dir/000000.bin… 로 쓴다(압축 순서). 반환: 쓴 항목 수.
     * 상한 [maxTotal] 은 실제로 푼 바이트로 센다(N-09: 머리의 크기를 속인 압축 폭탄 대비).
     */
    fun extractAll(dir: File, maxTotal: Long = ComicFormat.MAX_EXTRACT, onEntry: (Int, String) -> Unit = { _, _ -> }): Int {
        dir.mkdirs()
        return if (zip != null) {
            var total = 0L; var i = 0
            for (e in zip.entries()) {
                if (e.isDirectory) continue
                if (e.size > 0 && total + e.size > maxTotal) break
                val out = File(dir, "%06d.bin".format(i))
                val n = out.outputStream().use { o -> zip.getInputStream(e).use { ComicFormat.copyBounded(it, o, maxTotal - total) } }
                if (n < 0) { out.delete(); break }
                total += n; onEntry(i, e.name); i++
            }
            i
        } else rar { NativeComic.archiveExtractAll(file.path, dir.path, maxTotal) { i -> onEntry(i, rarNames.getOrElse(i) { "" }) } }
    }

    override fun close() { zip?.close() }

    private inline fun <T> rar(block: () -> T): T = try { block() } catch (e: IOException) {
        if (e.message == "ASH_ENCRYPTED") throw EncryptedArchiveException() else throw e
    }

    companion object {
        fun open(file: File): ComicArchive {
            val head = ComicFormat.readHead(file.inputStream())
            return when (val k = ComicFormat.sniff(head)) {
                ComicFormat.Kind.ZIP -> ComicArchive(file, k, false, ComicFormat.openZip(file))
                ComicFormat.Kind.RAR4, ComicFormat.Kind.RAR5 -> ComicArchive(file, k, ComicFormat.isSolid(head), null)
                ComicFormat.Kind.UNKNOWN -> throw IOException("만화 압축 형식이 아님")
            }
        }
    }
}
