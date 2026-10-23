// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.vici.ashreader.doc.Covers
import kr.vici.ashreader.doc.DocCaches
import kr.vici.ashreader.office.Cfb
import kr.vici.ashreader.office.CfbFile
import kr.vici.ashreader.office.FormatSniffer
import kr.vici.ashreader.office.SniffedFormat

/** 형식 묶음 (화면 설계서 1.2 형식 배지). */
enum class FormatGroup(val title: String) { EBOOK("이북"), TEXT("텍스트"), PDF("PDF·DjVu"), COMIC("만화"), HWP("한글"), WORD("Word"), ZIP("압축") }

/** 실제 형식(시그니처 기준, F-05). [engine] = 여는 엔진. */
enum class DocFormat(val badge: String, val group: FormatGroup, val engine: Engine) {
    EPUB("EPUB", FormatGroup.EBOOK, Engine.MUPDF),
    MOBI("MOBI", FormatGroup.EBOOK, Engine.CONVERT),
    AZW3("AZW3", FormatGroup.EBOOK, Engine.CONVERT),
    FB2("FB2", FormatGroup.EBOOK, Engine.MUPDF),
    HTML("HTML", FormatGroup.EBOOK, Engine.MUPDF),
    CHM("CHM", FormatGroup.EBOOK, Engine.CONVERT),
    TXT("TXT", FormatGroup.TEXT, Engine.MUPDF),
    PDF("PDF", FormatGroup.PDF, Engine.MUPDF),
    DJVU("DjVu", FormatGroup.PDF, Engine.DJVU),
    XPS("XPS", FormatGroup.PDF, Engine.MUPDF),
    CBZ("CBZ", FormatGroup.COMIC, Engine.COMIC),
    CBR("CBR", FormatGroup.COMIC, Engine.COMIC),
    HWP("HWP", FormatGroup.HWP, Engine.HWP),
    HWPX("HWPX", FormatGroup.HWP, Engine.HWP),
    DOCX("DOCX", FormatGroup.WORD, Engine.OFFICE),
    DOC("DOC", FormatGroup.WORD, Engine.OFFICE),
    RTF("RTF", FormatGroup.WORD, Engine.OFFICE),
    /** 문서가 든 압축 파일(S-46 압축 파일 안 고르기). 읽기 화면으로 바로 열지 않고 안의 문서를 고른다. */
    ZIP("ZIP", FormatGroup.ZIP, Engine.NONE),
    UNKNOWN("?", FormatGroup.TEXT, Engine.NONE);

    /** CONVERT = 합성 EPUB 으로 바꿔 MuPDF 로(MOBI·AZW3·CHM, :engine-convert), DJVU = :engine-djvu. */
    enum class Engine { MUPDF, COMIC, HWP, OFFICE, CONVERT, DJVU, NONE }

    /** 글자 크기·줄 간격을 바꿀 수 있는 이북류(이북식 보기). 나머지는 고정 쪽. */
    val reflowable: Boolean get() = group == FormatGroup.EBOOK || group == FormatGroup.TEXT || group == FormatGroup.WORD

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

object FormatDetector {
    private val IMAGE = Regex(".*\\.(jpe?g|png|webp|gif|bmp|avif)$", RegexOption.IGNORE_CASE)

    /** 서재가 모으는 확장자(F-01 수집). 실제 형식은 시그니처로 다시 본다. */
    val EXTENSIONS = setOf(
        "epub", "mobi", "azw", "azw3", "fb2", "htm", "html", "xhtml", "chm", "txt", "text", "pdf", "djvu", "djv",
        "cbz", "cbr", "zip", "hwp", "hwpx", "docx", "doc", "rtf",
    )

    fun detect(file: File, displayName: String): DocFormat = RandomAccessFile(file, "r").use { f ->
        detect(ByteSource.of(f), displayName, file)
    }

    /**
     * [src] 머리 표본(8KB)과 필요한 곳만 읽어 판별한다. [file] 이 있으면(경로로 읽을 수 있는 파일) ZIP·OLE 안을 자세히 보고,
     * 없으면(SAF 파일 서술자) ZIP 은 중앙 디렉터리만 직접 읽고 OLE 는 확장자로 가린다.
     */
    fun detect(src: ByteSource, displayName: String, file: File? = null): DocFormat {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val head = ByteArray(minOf(src.length, 8L shl 10).toInt()).also { src.readFully(0, it) }
        val isZip = head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()
        if (isZip) return zipKind(src, file, ext)
        if (head.size >= 7 && String(head, 0, 7, Charsets.US_ASCII).startsWith("Rar!")) return DocFormat.CBR
        if (head.size >= 16 && String(head, 0, 8, Charsets.ISO_8859_1) == "AT&TFORM" &&
            String(head, 12, 3, Charsets.ISO_8859_1) == "DJV") return DocFormat.DJVU
        if (head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "ITSF") return DocFormat.CHM
        mobiKind(src, head, ext)?.let { return it }
        // 필수 4: 머리 표본(8KB)만 본다. OLE(HWP5·DOC·암호 DOCX)는 디렉터리 섹터만 파일에서 읽어 가린다.
        val sniffed = if (Cfb.isCfb(head)) {
            if (file != null) CfbFile.sniff(file) else return byExtension(ext)
        } else FormatSniffer.sniff(head)
        return when (sniffed) {
            SniffedFormat.PDF -> DocFormat.PDF
            SniffedFormat.HWP5, SniffedFormat.HWP3 -> DocFormat.HWP
            SniffedFormat.DOC -> DocFormat.DOC
            SniffedFormat.RTF -> DocFormat.RTF
            SniffedFormat.ENCRYPTED_OOXML -> DocFormat.DOCX
            else -> byExtension(ext)
        }
    }

    /**
     * Palm 데이터베이스 머리(오프셋 60 'BOOKMOBI') → MOBI 헤더의 파일 판(8 = KF8)으로 AZW3 를 가린다.
     * KF8 겸용(옛 MOBI + KF8) 파일은 확장자가 azw3 면 AZW3, 아니면 MOBI 로 둔다(여는 길은 같다).
     */
    internal fun mobiKind(src: ByteSource, head: ByteArray, ext: String): DocFormat? {
        if (head.size < 86 || String(head, 60, 8, Charsets.ISO_8859_1) != "BOOKMOBI") return null
        val rec0 = be32(head, 78)
        val b = ByteArray(40)
        val ver = if (rec0 in 78 until src.length - 40 && src.read(rec0, b, 0, 40) == 40 && String(b, 16, 4, Charsets.ISO_8859_1) == "MOBI") be32(b, 36) else 0L
        return if (ver == 8L || ext == "azw3") DocFormat.AZW3 else DocFormat.MOBI
    }

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun zipKind(src: ByteSource, file: File?, ext: String): DocFormat {
        val listing: Pair<List<String>, String?>? = if (file != null) runCatching {
            ZipFile(file).use { z ->
                val names = z.entries().asSequence().map { it.name }.toList()
                val mime = z.getEntry("mimetype")?.let { e -> if (e.size in 0..256) z.getInputStream(e).use { it.readBytes().toString(Charsets.US_ASCII).trim() } else null }
                names to mime
            }
        }.getOrNull() else null
        val (names, mime) = listing ?: ZipNames.list(src)?.let { l -> l.map { it.name } to ZipNames.storedText(src, l, "mimetype") } ?: return byExtension(ext)
        return zipKindOf(names, mime) ?: byExtension(ext)
    }

    /** ZIP 안 이름·mimetype 으로 형식. 그림이 반 이상인 일반 ZIP 은 만화(CBZ)로 본다(ZIP 그림 묶음 = 만화). */
    internal fun zipKindOf(names: List<String>, mime: String?): DocFormat? = when {
        mime == "application/epub+zip" -> DocFormat.EPUB
        "word/document.xml" in names -> DocFormat.DOCX
        mime == "application/hwp+zip" || "Contents/section0.xml" in names -> DocFormat.HWPX
        names.any { it.endsWith(".fdseq") } -> DocFormat.XPS
        names.isNotEmpty() && names.count { IMAGE.matches(it) } * 2 >= names.count { !it.endsWith("/") } && names.any { IMAGE.matches(it) } -> DocFormat.CBZ
        names.any { isInnerDoc(it) } -> DocFormat.ZIP
        else -> null
    }

    /** 압축 안 항목이 열 수 있는 문서인가(S-46). 맥 압축 찌꺼기·숨은 파일·안의 ZIP 은 뺀다. */
    fun isInnerDoc(name: String): Boolean {
        if (name.endsWith("/") || name.startsWith("__MACOSX/") || name.substringAfterLast('/').startsWith(".")) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext != "zip" && ext in EXTENSIONS
    }

    fun byExtension(ext: String): DocFormat = when (ext) {
        "epub" -> DocFormat.EPUB
        "mobi", "azw" -> DocFormat.MOBI
        "azw3" -> DocFormat.AZW3
        "fb2" -> DocFormat.FB2
        "htm", "html", "xhtml" -> DocFormat.HTML
        "chm" -> DocFormat.CHM
        "txt", "text", "md" -> DocFormat.TXT
        "pdf" -> DocFormat.PDF
        "djvu", "djv" -> DocFormat.DJVU
        "xps", "oxps" -> DocFormat.XPS
        "cbz" -> DocFormat.CBZ
        "cbr" -> DocFormat.CBR
        "hwp" -> DocFormat.HWP
        "hwpx" -> DocFormat.HWPX
        "docx" -> DocFormat.DOCX
        "doc" -> DocFormat.DOC
        "rtf" -> DocFormat.RTF
        else -> DocFormat.UNKNOWN
    }
}

/** 시리즈 묶기: 제목 끝의 권·화 번호. "바다 건너 마을 3" → ("바다 건너 마을", 3) */
object SeriesParser {
    private val RX = Regex("^(.*?)[\\s_\\-.]*(?:제\\s*)?(\\d{1,4})\\s*(?:권|화|부|편|vol\\.?|v)?\\s*$", RegexOption.IGNORE_CASE)
    private val VOL_FIRST = Regex("^(.*?)[\\s_\\-.]*(?:vol\\.?|v|권)\\s*(\\d{1,4}).*$", RegexOption.IGNORE_CASE)

    fun parse(title: String): Pair<String, Int>? {
        val t = title.trim()
        val m = RX.find(t) ?: VOL_FIRST.find(t) ?: return null
        val base = m.groupValues[1].trim().trimEnd('(', '[', '-', '_')
        val n = m.groupValues[2].toIntOrNull() ?: return null
        if (base.length < 2 || n > 9999) return null
        return base to n
    }
}

/** 문서 식별 해시(F-63): 크기 + 앞·가운데·뒤 64KB 표본. */
object DocHash {
    fun of(file: File): String = RandomAccessFile(file, "r").use { of(ByteSource.of(it)) }

    /** 경로 없이 읽는 파일(SAF 파일 서술자)도 같은 값이 나온다. */
    fun of(src: ByteSource): String {
        val md = MessageDigest.getInstance("SHA-256")
        val len = src.length
        md.update(len.toString().toByteArray())
        val chunk = 64 shl 10
        for (pos in listOf(0L, maxOf(0L, len / 2 - chunk / 2), maxOf(0L, len - chunk))) {
            val n = minOf(chunk.toLong(), len - pos).toInt()
            if (n <= 0) continue
            val b = ByteArray(n)
            src.readFully(pos, b); md.update(b)
        }
        return md.digest().take(16).joinToString("") { "%02x".format(it) }
    }
}

class LibraryRepo(private val context: Context, private val db: AshDb) {
    private val dao get() = db.books()
    val booksDir: File get() = File(context.filesDir, "books").apply { mkdirs() }
    val onceDir: File get() = File(context.cacheDir, "once").apply { mkdirs() }

    fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "문서"

    /** 외부 파일을 [dir] 안으로 복사한다(엔진은 경로로 연다). */
    private fun copy(uri: Uri, dir: File, name: String): File {
        val tmp = File(dir, "tmp-" + System.nanoTime())
        context.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            ?: error("파일을 읽을 수 없어요")
        return tmp
    }

    data class Opened(val file: File, val title: String, val format: DocFormat, val id: String)

    /** '한 번만 보기'(S-40): 캐시에만 두고 서재에는 넣지 않는다. */
    suspend fun openOnce(uri: Uri): Opened = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val tmp = copy(uri, onceDir, name)
        val fmt = FormatDetector.detect(tmp, name)
        val id = DocHash.of(tmp)
        val f = File(onceDir, id + "." + fmt.badge.lowercase())
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        Opened(f, name.substringBeforeLast('.'), fmt, id)
    }

    suspend fun import(uri: Uri): Book = withContext(Dispatchers.IO) {
        val o = openOnce(uri)
        addOpened(o, folderOf(uri))
    }

    /** 이미 연 문서(한 번만 보기)를 서재에 넣는다. 예전에 '서재에서 빼기'한 같은 문서면 다시 보이게 한다. */
    suspend fun addOpened(o: Opened, folder: String = ""): Book = withContext(Dispatchers.IO) {
        dao.get(o.id)?.let { old ->
            if (old.hidden == 0) return@withContext old
            // 빼 둔(또는 파일이 없어진) 기록의 읽은 위치·책갈피는 살리고, 파일은 앱 안 사본으로 둔다
            val dest = File(booksDir, o.file.name)
            if (o.file.absolutePath != dest.absolutePath) o.file.copyTo(dest, overwrite = true)
            val b = old.copy(path = dest.absolutePath, origin = Book.ORIGIN_COPY, sourceUri = "", hidden = 0, sizeBytes = dest.length())
            dao.upsert(b)
            return@withContext b
        }
        val dest = File(booksDir, o.file.name)
        if (o.file.absolutePath != dest.absolutePath) o.file.copyTo(dest, overwrite = true)
        val s = SeriesParser.parse(o.title)
        val b = Book(
            id = o.id, title = o.title, format = o.format.name, path = dest.absolutePath, folder = folder,
            sizeBytes = dest.length(), seriesKey = s?.first.orEmpty(), volume = s?.second ?: 0,
        )
        dao.upsert(b)
        b
    }

    private fun folderOf(uri: Uri): String {
        val p = Uri.decode(uri.toString())
        val seg = p.substringBeforeLast('/', "").substringAfterLast('/').substringAfterLast(':')
        return seg.ifBlank { "가져온 파일" }
    }

    fun clearOnce() {
        onceDir.listFiles()?.forEach { it.deleteRecursively() }
        // 암호 문서를 풀어 둔 임시 폴더(읽기 화면을 닫을 때 지우지만, 비정상 종료 대비)
        File(context.cacheDir, "office-enc").deleteRecursively()
    }

    /**
     * 한 번만 보기 문서를 닫을 때(F-06, 필수 5). 서재에 없는 문서면 변환 캐시(office·hwp-ebook·upscale)를 지우고,
     * '한 번만 보기 기록 지우기'([clearCopy])가 켜져 있으면 캐시 사본도 지운다. 꺼져 있으면 사본(기록)은 남긴다.
     */
    suspend fun closeOnce(o: Opened, clearCopy: Boolean) = withContext(Dispatchers.IO) {
        if (dao.get(o.id) == null) DocCaches.deleteFor(context, o.id)
        if (clearCopy) {
            val f = runCatching { o.file.canonicalFile }.getOrNull()
            if (f != null && f.parentFile == onceDir.canonicalFile) f.delete()
        }
    }

    /**
     * 앱 시작·종료 때 쓸기(필수 5): 서재에 없는 문서의 변환 캐시는 늘 지우고,
     * [clearCopies](설정 '한 번만 보기 기록 지우기')가 켜져 있으면 한 번만 보기 사본도 지운다.
     */
    suspend fun sweep(clearCopies: Boolean) = withContext(Dispatchers.IO) {
        val keep = dao.allBooks().map { it.id }.toSet()
        DocCaches.sweep(context, keep)
        trimSafCache(dao.allBooks().filter { it.hidden == 0 }.map { it.id }.toSet())
        if (clearCopies) clearOnce() else File(context.cacheDir, "office-enc").deleteRecursively()
        onceDir.listFiles()?.filter { it.name.startsWith("tmp-") }?.forEach { it.delete() }
    }

    /**
     * 읽기 화면이 열 파일 경로(F-01). 앱 안 사본·경로 수집 문서는 그 경로,
     * SAF 로 모은 문서는 캐시(saf/<id>.<확장자>)로 복사해 연다(크기가 같으면 다시 쓰지 않음). 원본은 읽기만 한다.
     */
    suspend fun localPath(b: Book): String = withContext(Dispatchers.IO) {
        if (b.origin == Book.ORIGIN_ZIP) {
            if (File(b.path).isFile) return@withContext b.path
            // 캐시가 지워졌으면 압축 파일에서 다시 푼다
            val zipId = b.sourceUri.substringBefore('\n')
            val entry = b.sourceUri.substringAfter('\n', "")
            val zipBook = dao.get(zipId) ?: throw java.io.FileNotFoundException("압축 파일이 서재에 없어요.")
            val zipPath = localPath(zipBook)
            val f = extractEntry(File(zipPath), zipId, entry)
            dao.upsert(b.copy(path = f.path))
            return@withContext f.path
        }
        if (b.origin != Book.ORIGIN_SAF) {
            if (!File(b.path).isFile) throw java.io.FileNotFoundException("파일이 없어졌어요. 옮겨졌거나 지워졌을 수 있어요.")
            return@withContext b.path
        }
        val ext = DocFormat.of(b.format).badge.lowercase()
        val out = File(safDir, b.id + "." + ext)
        if (out.isFile && out.length() == b.sizeBytes) { out.setLastModified(System.currentTimeMillis()); return@withContext out.path }
        val tmp = File(safDir, "tmp-" + System.nanoTime())
        try {
            context.contentResolver.openInputStream(Uri.parse(b.sourceUri))?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                ?: throw java.io.FileNotFoundException("파일을 읽을 수 없어요. 폴더 접근 권한을 다시 확인해 주세요.")
        } catch (e: SecurityException) {
            tmp.delete(); throw java.io.FileNotFoundException("폴더 접근 권한이 없어졌어요. 설정 > 서재 > 모으는 폴더에서 다시 골라 주세요.")
        } catch (e: java.io.IOException) {
            tmp.delete(); throw e
        }
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        trimSafCache()
        out.path
    }

    /** SAF 문서 캐시(필수 5 와 같은 취지): 서재에 없는 것은 지우고, 1GB 를 넘으면 오래 안 연 것부터. */
    private fun trimSafCache(keep: Set<String>? = null) {
        val files = safDir.listFiles()?.filter { it.isFile } ?: return
        if (keep != null) files.filter { it.name.substringBefore('.') !in keep }.forEach { it.delete() }
        var total = safDir.listFiles()?.sumOf { it.length() } ?: 0L
        for (f in (safDir.listFiles() ?: emptyArray()).sortedBy { it.lastModified() }) {
            if (total <= SAF_CACHE_MAX) break
            total -= f.length(); f.delete()
        }
    }

    val safDir: File get() = File(context.cacheDir, "saf").apply { mkdirs() }

    // ───────── S-46 압축 파일 안 고르기 ─────────

    /** 압축 안 문서 한 개. [format] 은 확장자로 본 것(열 때 시그니처로 다시 본다). */
    data class ZipItem(val name: String, val size: Long, val format: DocFormat) {
        val title: String get() = name.substringAfterLast('/').substringBeforeLast('.')
    }

    /** 압축 안의 열 수 있는 문서 목록(이름 자연 순서). 이름이 UTF-8 이 아니면 CP949 로 다시 읽는다. */
    suspend fun zipItems(zip: File): List<ZipItem> = withContext(Dispatchers.IO) {
        openZip(zip).use { z ->
            z.entries().asSequence()
                .filter { !it.isDirectory && FormatDetector.isInnerDoc(it.name) && it.size != 0L }
                .map { ZipItem(it.name, it.size, FormatDetector.byExtension(it.name.substringAfterLast('.', "").lowercase())) }
                .filter { it.format != DocFormat.UNKNOWN }
                .sortedWith(compareBy(kr.vici.ashreader.engine.comic.ComicFormat.naturalOrder) { it.name })
                .toList()
        }
    }

    private fun openZip(zip: File): ZipFile {
        val z = ZipFile(zip)
        return try {
            z.entries().asSequence().forEach { it.name } // UTF-8 이 아닌 이름이면 여기서 IllegalArgumentException
            z
        } catch (e: IllegalArgumentException) {
            z.close()
            ZipFile(zip, java.nio.charset.Charset.forName("EUC-KR"))
        }
    }

    /** 항목을 캐시(zipx/<압축 id>/)에 푼다. 파일 이름은 항목 이름 해시(경로 넘김 방지). */
    private fun extractEntry(zip: File, zipId: String, entry: String): File {
        val dir = File(context.cacheDir, "zipx/$zipId").apply { mkdirs() }
        val md = MessageDigest.getInstance("SHA-1").digest(entry.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
        val ext = entry.substringAfterLast('.', "bin").lowercase().filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
        val out = File(dir, "$md.$ext")
        if (out.isFile && out.length() > 0) return out
        openZip(zip).use { z ->
            val e = z.getEntry(entry) ?: throw java.io.FileNotFoundException("압축 파일 안에서 문서를 찾지 못했어요.")
            if (e.size > (1L shl 30)) throw java.io.IOException("압축 안 문서가 너무 커요(1GB 넘음).")
            val tmp = File(dir, "tmp-" + System.nanoTime())
            z.getInputStream(e).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        }
        return out
    }

    /**
     * 압축 안 문서를 연다. [zipId] = 압축 파일 식별값. [once] 가 아니면 서재 기록(숨김, [Book.ORIGIN_ZIP])을 만들어
     * 읽은 위치·책갈피를 남긴다. 같은 문서가 이미 서재에 따로 있으면 그 기록으로 이어 읽는다.
     */
    suspend fun openInner(zip: File, zipId: String, item: ZipItem, once: Boolean): Pair<Opened, Book?> = withContext(Dispatchers.IO) {
        val f = extractEntry(zip, zipId, item.name)
        val fmt = FormatDetector.detect(f, item.name.substringAfterLast('/'))
        val id = DocHash.of(f)
        val o = Opened(f, item.title, fmt, id)
        if (once) return@withContext o to dao.get(id)?.takeIf { it.hidden == 0 }
        val cur = dao.get(id)
        val b = when {
            cur == null -> Book(
                id = id, title = item.title, format = fmt.name, path = f.path, folder = "", sizeBytes = f.length(),
                origin = Book.ORIGIN_ZIP, sourceUri = zipId + "\n" + item.name, hidden = Book.HIDDEN_INNER,
            ).also { dao.upsert(it) }
            cur.origin == Book.ORIGIN_ZIP -> cur.copy(path = f.path, sourceUri = zipId + "\n" + item.name).also { dao.upsert(it) }
            else -> cur
        }
        o to b
    }

    /**
     * 서재에서 빼기(S-14): 기록만 지우고 기기의 파일은 지우지 않는다.
     * 폴더에서 모은 문서는 다시 모으지 않도록 '뺌' 표시만 남긴다(책갈피·읽은 위치는 '책 추가'로 다시 넣으면 이어짐).
     */
    suspend fun remove(b: Book) = withContext(Dispatchers.IO) {
        if (b.origin != Book.ORIGIN_COPY) {
            dao.setHidden(b.id, Book.HIDDEN_REMOVED)
            File(safDir, b.id + "." + DocFormat.of(b.format).badge.lowercase()).delete()
            return@withContext
        }
        dao.delete(b.id)
        // 필수 6: 서재 폴더 안의 파일만 지운다(DB 경로가 바깥을 가리키면 지우지 않음)
        insideBooks(booksDir, b.path)?.delete()
        Covers.delete(context, b.id)
        DocCaches.deleteFor(context, b.id)
    }

    companion object {
        const val SAF_CACHE_MAX = 1L shl 30

        /** [path] 가 [booksDir] 안의 파일이면 정규화한 파일, 아니면 null(../·심볼릭 링크로 밖을 가리키는 경로 거부). */
        fun insideBooks(booksDir: File, path: String): File? {
            if (path.isEmpty()) return null
            return runCatching {
                val root = booksDir.canonicalFile
                val f = File(path).canonicalFile
                if (f.parentFile == root) f else null
            }.getOrNull()
        }
    }
}
