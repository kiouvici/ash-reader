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
import kr.vici.ashreader.office.FormatSniffer
import kr.vici.ashreader.office.SniffedFormat

/** 형식 묶음 (화면 설계서 1.2 형식 배지). */
enum class FormatGroup(val title: String) { EBOOK("이북"), TEXT("텍스트"), PDF("PDF·DjVu"), COMIC("만화"), HWP("한글"), WORD("Word"), ZIP("압축") }

/** 실제 형식(시그니처 기준, F-05). [engine] = 여는 엔진. */
enum class DocFormat(val badge: String, val group: FormatGroup, val engine: Engine) {
    EPUB("EPUB", FormatGroup.EBOOK, Engine.MUPDF),
    MOBI("MOBI", FormatGroup.EBOOK, Engine.MUPDF),
    FB2("FB2", FormatGroup.EBOOK, Engine.MUPDF),
    HTML("HTML", FormatGroup.EBOOK, Engine.MUPDF),
    TXT("TXT", FormatGroup.TEXT, Engine.MUPDF),
    PDF("PDF", FormatGroup.PDF, Engine.MUPDF),
    XPS("XPS", FormatGroup.PDF, Engine.MUPDF),
    CBZ("CBZ", FormatGroup.COMIC, Engine.COMIC),
    CBR("CBR", FormatGroup.COMIC, Engine.COMIC),
    HWP("HWP", FormatGroup.HWP, Engine.HWP),
    HWPX("HWPX", FormatGroup.HWP, Engine.HWP),
    DOCX("DOCX", FormatGroup.WORD, Engine.OFFICE),
    DOC("DOC", FormatGroup.WORD, Engine.OFFICE),
    UNKNOWN("?", FormatGroup.TEXT, Engine.NONE);

    enum class Engine { MUPDF, COMIC, HWP, OFFICE, NONE }

    /** 글자 크기·줄 간격을 바꿀 수 있는 이북류(이북식 보기). 나머지는 고정 쪽. */
    val reflowable: Boolean get() = group == FormatGroup.EBOOK || group == FormatGroup.TEXT || group == FormatGroup.WORD

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

object FormatDetector {
    private val IMAGE = Regex(".*\\.(jpe?g|png|webp|gif|bmp|avif)$", RegexOption.IGNORE_CASE)

    fun detect(file: File, displayName: String): DocFormat {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val head = RandomAccessFile(file, "r").use { f ->
            val n = minOf(f.length(), 8L shl 10).toInt()
            ByteArray(n).also { f.readFully(it) }
        }
        val isZip = head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()
        if (isZip) return zipKind(file, ext)
        if (head.size >= 7 && String(head, 0, 7, Charsets.US_ASCII).startsWith("Rar!")) return DocFormat.CBR
        // OLE(HWP5·DOC)는 디렉터리 판별에 전체 바이트가 필요. 64MB 넘는 OLE 는 확장자로 판단.
        val sniffBytes = if (file.length() <= (64L shl 20)) file.readBytes() else head
        return when (FormatSniffer.sniff(sniffBytes)) {
            SniffedFormat.PDF -> DocFormat.PDF
            SniffedFormat.HWP5, SniffedFormat.HWP3 -> DocFormat.HWP
            SniffedFormat.DOC -> DocFormat.DOC
            SniffedFormat.RTF -> DocFormat.DOC // TODO(2단계): RTF 전용 경로. 지금은 DOC 경로(POI)로 시도
            SniffedFormat.ENCRYPTED_OOXML -> DocFormat.DOCX
            else -> byExtension(ext)
        }
    }

    private fun zipKind(file: File, ext: String): DocFormat = runCatching {
        ZipFile(file).use { z ->
            val names = z.entries().asSequence().map { it.name }.toList()
            val mime = z.getEntry("mimetype")?.let { e -> z.getInputStream(e).use { it.readBytes().toString(Charsets.US_ASCII).trim() } }
            when {
                mime == "application/epub+zip" -> DocFormat.EPUB
                "word/document.xml" in names -> DocFormat.DOCX
                mime == "application/hwp+zip" || "Contents/section0.xml" in names -> DocFormat.HWPX
                names.any { it.endsWith(".fdseq") } -> DocFormat.XPS
                names.isNotEmpty() && names.count { IMAGE.matches(it) } * 2 >= names.count { !it.endsWith("/") } -> DocFormat.CBZ
                else -> byExtension(ext)
            }
        }
    }.getOrElse { byExtension(ext) }

    private fun byExtension(ext: String): DocFormat = when (ext) {
        "epub" -> DocFormat.EPUB
        "mobi", "azw", "azw3" -> DocFormat.MOBI
        "fb2" -> DocFormat.FB2
        "htm", "html", "xhtml" -> DocFormat.HTML
        "txt", "text", "md" -> DocFormat.TXT
        "pdf" -> DocFormat.PDF
        "xps", "oxps" -> DocFormat.XPS
        "cbz" -> DocFormat.CBZ
        "cbr" -> DocFormat.CBR
        "hwp" -> DocFormat.HWP
        "hwpx" -> DocFormat.HWPX
        "docx" -> DocFormat.DOCX
        "doc", "rtf" -> DocFormat.DOC
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
    fun of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val len = file.length()
        md.update(len.toString().toByteArray())
        RandomAccessFile(file, "r").use { f ->
            val chunk = 64 shl 10
            for (pos in listOf(0L, maxOf(0L, len / 2 - chunk / 2), maxOf(0L, len - chunk))) {
                val n = minOf(chunk.toLong(), len - pos).toInt()
                if (n <= 0) continue
                val b = ByteArray(n)
                f.seek(pos); f.readFully(b); md.update(b)
            }
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

    /** 이미 연 문서(한 번만 보기)를 서재에 넣는다. */
    suspend fun addOpened(o: Opened, folder: String = ""): Book = withContext(Dispatchers.IO) {
        dao.get(o.id)?.let { return@withContext it }
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

    fun clearOnce() { onceDir.listFiles()?.forEach { it.delete() } }

    suspend fun remove(b: Book) = withContext(Dispatchers.IO) {
        dao.delete(b.id)
        File(b.path).delete()
    }
}
