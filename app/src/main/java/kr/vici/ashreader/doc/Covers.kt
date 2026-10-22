// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.doc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.Settings
import kr.vici.ashreader.engine.comic.ComicArchive
import kr.vici.ashreader.engine.comic.ComicFormat
import kr.vici.ashreader.engine.hwp.HwpDoc
import kr.vici.ashreader.engine.mupdf.MupdfDoc
import kr.vici.ashreader.office.Cfb

/**
 * 서재 표지 썸네일(실제 책 표지). 백그라운드에서 한 권씩 뽑아 앱 files/covers 에 폭 [WIDTH]px WebP 로 둔다.
 * 파일 이름 = 책 id + 파일 크기 + 수정 시각 → 파일이 바뀌면 새로 만든다. 못 뽑은 책은 표시 파일(.none)로 다시 시도하지 않는다.
 * - EPUB: OPF 표지(cover-image / meta cover) → 그림 중 첫 장
 * - PDF·XPS: 첫 쪽 / CBZ·CBR: 첫 그림
 * - DOCX: 첫 내장 그림 → 없으면 첫 쪽(이북식 변환)
 * - HWP·HWPX: 미리보기 그림(PrvImage) → 없으면 첫 쪽(rhwp)
 * - TXT 등: 없음(색 상자 유지)
 */
object Covers {
    const val WIDTH = 300
    private const val MAX_H = WIDTH * 16 / 10

    private val mem = object : LruCache<String, Bitmap>(12 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** 책 id → 마지막으로 쓴 캐시 열쇠(화면이 바로 꺼내 쓰기용). */
    private val lastKey = ConcurrentHashMap<String, String>()
    private val gate = Mutex()

    fun dir(context: Context) = File(context.filesDir, "covers")

    fun supported(f: DocFormat) = when (f) {
        DocFormat.EPUB, DocFormat.PDF, DocFormat.XPS, DocFormat.CBZ, DocFormat.CBR,
        DocFormat.DOCX, DocFormat.HWP, DocFormat.HWPX -> true
        else -> false
    }

    /** 메모리에 있는 표지(없으면 null). 화면 스레드에서 불러도 된다. */
    fun peek(b: Book): Bitmap? = lastKey[b.id]?.let { mem.get(it) }

    /** 표지(없으면 만든다). 만들 수 없으면 null. */
    suspend fun load(context: Context, b: Book): Bitmap? = withContext(Dispatchers.IO) { loadBlocking(context, b) }

    private suspend fun loadBlocking(context: Context, b: Book): Bitmap? {
        val fmt = DocFormat.of(b.format)
        if (!supported(fmt)) return null
        val src = File(b.path)
        if (!src.exists()) return null
        val k = b.id + "-" + src.length().toString(16) + "-" + src.lastModified().toString(16)
        mem.get(k)?.let { return it }
        val d = dir(context).apply { mkdirs() }
        val out = File(d, "$k.webp")
        val none = File(d, "$k.none")
        if (none.exists()) return null
        if (!out.exists()) gate.withLock {
            if (!out.exists() && !none.exists()) {
                // 같은 책의 예전 표지(파일이 바뀐 경우) 지우기
                d.listFiles()?.forEach { if (it.name.startsWith(b.id + "-") && !it.name.startsWith(k)) it.delete() }
                val bmp = try { extract(context, src, fmt) } catch (t: Throwable) { null }
                if (bmp == null) runCatching { none.createNewFile() } else try { save(bmp, out) } finally { bmp.recycle() }
            }
        }
        if (!out.exists()) return null
        val bmp = BitmapFactory.decodeFile(out.path) ?: return null
        mem.put(k, bmp)
        lastKey[b.id] = k
        return bmp
    }

    fun delete(context: Context, id: String) {
        lastKey.remove(id)?.let { mem.remove(it) }
        dir(context).listFiles()?.forEach { if (it.name.startsWith("$id-")) it.delete() }
    }

    private fun extract(context: Context, file: File, fmt: DocFormat): Bitmap? = when (fmt) {
        DocFormat.EPUB -> epub(file)
        DocFormat.PDF, DocFormat.XPS -> {
            BundledFonts.install(context)
            MupdfDoc.open(file.absolutePath).use { d -> if (d.pageCount() > 0) d.renderPage(0, WIDTH) else null }
        }
        DocFormat.CBZ, DocFormat.CBR -> ComicArchive.open(file).use { a -> if (a.pages.isEmpty()) null else decode(a.readPage(0)) }
        DocFormat.DOCX -> docxImage(file) ?: docxFirstPage(context, file)
        DocFormat.HWPX -> hwpxPreview(file) ?: hwpFirstPage(context, file)
        DocFormat.HWP -> hwpPreview(file) ?: hwpFirstPage(context, file)
        else -> null
    }

    /** 그림 바이트 → 폭 [WIDTH] 이상 남게 줄여 디코딩. 못 읽으면 null. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (s < 64 && o.outWidth / (s * 2) >= WIDTH) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun save(bmp: Bitmap, out: File) {
        val h = (bmp.height.toLong() * WIDTH / bmp.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        var scaled = if (bmp.width != WIDTH) Bitmap.createScaledBitmap(bmp, WIDTH, h, true) else bmp
        if (scaled.height > MAX_H) { // 세로로 아주 긴 그림(웹툰 등)은 위쪽만
            val c = Bitmap.createBitmap(scaled, 0, 0, WIDTH, MAX_H)
            if (scaled !== bmp && scaled !== c) scaled.recycle()
            scaled = c
        }
        try {
            val tmp = File(out.parentFile, out.name + ".tmp")
            tmp.outputStream().use { o -> scaled.compress(webpFormat(), 82, o) }
            if (!tmp.renameTo(out)) tmp.delete()
        } finally {
            if (scaled !== bmp) scaled.recycle()
        }
    }

    // ── EPUB ──
    private fun attr(tag: String, name: String): String? =
        Regex("(?<![\\w:-])" + Regex.escape(name) + "\\s*=\\s*[\"']([^\"']*)[\"']").find(tag)?.groupValues?.get(1)

    private fun epub(file: File): Bitmap? = ZipFile(file).use { z ->
        fun text(name: String): String? = z.getEntry(name)?.let { e -> z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
        val container = text("META-INF/container.xml")
        val opfPath = container?.let { Regex("full-path\\s*=\\s*[\"']([^\"']+)[\"']").find(it)?.groupValues?.get(1) }
        val opf = opfPath?.let { text(it) }
        val candidates = ArrayList<String>()
        if (opf != null && opfPath != null) {
            val base = opfPath.substringBeforeLast('/', "")
            val items = Regex("<(?:\\w+:)?item\\b[^>]*>").findAll(opf).map { it.value }.toList()
            val images = items.filter { attr(it, "media-type")?.startsWith("image/") == true }
            items.firstOrNull { attr(it, "properties")?.split(' ')?.contains("cover-image") == true }?.let { attr(it, "href") }?.let { candidates += it }
            val coverId = Regex("<(?:\\w+:)?meta\\b[^>]*>").findAll(opf).map { it.value }
                .firstOrNull { attr(it, "name") == "cover" }?.let { attr(it, "content") }
            if (coverId != null) items.firstOrNull { attr(it, "id") == coverId }?.let { attr(it, "href") }?.let { candidates += it }
            images.firstOrNull { (attr(it, "id") + " " + attr(it, "href")).contains("cover", ignoreCase = true) }?.let { attr(it, "href") }?.let { candidates += it }
            images.firstOrNull()?.let { attr(it, "href") }?.let { candidates += it }
            for (i in candidates.indices) candidates[i] = resolve(base, Uri.decode(candidates[i]))
        }
        // 그래도 없으면 압축 안 첫 그림
        z.entries().asSequence().filter { !it.isDirectory && ComicFormat.isImage(it.name) }.map { it.name }
            .sortedWith(ComicFormat.naturalOrder).firstOrNull()?.let { candidates += it }
        for (name in candidates.distinct().take(4)) {
            val e = z.getEntry(name) ?: continue
            if (e.size > (40L shl 20)) continue
            decode(z.getInputStream(e).use { it.readBytes() })?.let { return@use it }
        }
        null
    }

    /** OPF 기준 상대 경로 → 압축 안 경로("../", "./" 정리). */
    private fun resolve(base: String, href: String): String {
        val parts = ArrayList<String>()
        for (p in ((if (base.isEmpty()) "" else "$base/") + href.substringBefore('#')).split('/')) {
            when (p) {
                "", "." -> Unit
                ".." -> { if (parts.isNotEmpty()) parts.removeAt(parts.size - 1) }
                else -> parts.add(p)
            }
        }
        return parts.joinToString("/")
    }

    // ── DOCX ──
    private fun docxImage(file: File): Bitmap? = runCatching {
        ZipFile(file).use { z ->
            val media = z.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("word/media/") }.map { it.name }
                .sortedWith(ComicFormat.naturalOrder).toList()
            for (name in media.take(6)) {
                val e = z.getEntry(name) ?: continue
                if (e.size > (40L shl 20)) continue
                decode(z.getInputStream(e).use { it.readBytes() })?.let { return@use it }
            }
            null
        }
    }.getOrNull() // 암호 DOCX 는 ZIP 이 아니다 → null

    private fun docxFirstPage(context: Context, file: File): Bitmap? {
        BundledFonts.install(context)
        val x = try { OfficeXhtml.convert(context, file, DocFormat.DOCX) } catch (e: Exception) { return null } // 암호 문서는 표지 없음
        return try {
            MupdfDoc.open(x.file.absolutePath, MupdfDoc.LayoutSpec(300f, 420f, 11f), DocOpener.css(context, Settings())).use { d ->
                if (d.pageCount() > 0) d.renderPage(0, WIDTH) else null
            }
        } finally { x.temp?.deleteRecursively() }
    }

    // ── HWP·HWPX ──
    private fun hwpxPreview(file: File): Bitmap? = runCatching {
        ZipFile(file).use { z ->
            val e = z.entries().asSequence().firstOrNull { !it.isDirectory && it.name.startsWith("Preview/PrvImage", ignoreCase = true) }
            e?.let { decode(z.getInputStream(it).use { s -> s.readBytes() }) }
        }
    }.getOrNull()

    private fun hwpPreview(file: File): Bitmap? = runCatching {
        if (file.length() > (64L shl 20)) return null
        Cfb.open(file.readBytes()).read("PrvImage")?.let { decode(it) }
    }.getOrNull()

    private fun hwpFirstPage(context: Context, file: File): Bitmap? {
        BundledFonts.install(context)
        return HwpDoc.open(file.absolutePath).use { d -> if (d.pageCount() > 0) d.renderPage(0, WIDTH) else null }
    }
}
