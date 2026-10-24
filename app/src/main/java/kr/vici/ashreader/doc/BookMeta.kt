// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.doc

import android.content.Context
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.BookDao
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.FormatGroup
import kr.vici.ashreader.data.MetaRules
import kr.vici.ashreader.data.SeriesGroups
import kr.vici.ashreader.data.Settings
import kr.vici.ashreader.engine.mupdf.MupdfDoc
import kr.vici.ashreader.net.BookInfoNet

/**
 * 책 정보(작업 06, D-024 — 대표님 우선순위). 책 파일은 절대 고치지 않고 서재 정보(DB)만 바꾼다.
 * 1순위 파일 정보(정확도 높을 때만): EPUB·MOBI·AZW3·FB2 의 제목·지은이·출판사, 글자 있는 PDF 의 Title/Author.
 * 2순위 인터넷 자동 매칭: 스캔 PDF·DjVu·만화·정보 없는/틀린 이북. 한글·Word 는 자동 검색 안 함.
 *   일치도 90% 이상 + 권수 일치일 때만 바꾸고, 아니면 '확인 필요'.
 * 3순위 직접 고르기: 책 정보 화면([kr.vici.ashreader.ui.BookInfoScreen]).
 */
object BookMeta {
    /** 파일 속 정보. 믿을 수 있는 값만 채운다(아니면 ""). */
    data class FileInfo(val title: String, val author: String, val publisher: String, val isbn: String) {
        val any: Boolean get() = title.isNotEmpty() || author.isNotEmpty()
    }

    private val gate = Mutex()

    /** 인터넷 자동 매칭 대상(2순위): 이북·만화·DjVu·스캔 PDF. 한글·Word·텍스트·압축은 아님. */
    fun netAutoTarget(b: Book, scannedPdf: Boolean): Boolean = when (DocFormat.of(b.format)) {
        DocFormat.EPUB, DocFormat.MOBI, DocFormat.AZW3, DocFormat.FB2, DocFormat.CHM, DocFormat.CBZ, DocFormat.CBR, DocFormat.DJVU -> true
        DocFormat.PDF -> scannedPdf
        else -> false
    }

    /** 책 정보 화면의 [다른 정보 찾기]는 한글·Word 도 된다(직접 찾기). 압축·알 수 없는 형식만 뺀다. */
    fun netManualTarget(b: Book): Boolean = DocFormat.of(b.format).group != FormatGroup.ZIP && DocFormat.of(b.format) != DocFormat.UNKNOWN

    /** 1순위: 파일 속 정보 읽기. SAF 로 모은 책(앱 안 파일 없음)은 null. */
    suspend fun fileInfo(context: Context, b: Book): FileInfo? = withContext(Dispatchers.IO) {
        val f = File(b.path)
        if (!f.isFile) return@withContext null
        runCatching {
            when (val fmt = DocFormat.of(b.format)) {
                DocFormat.EPUB -> opfInfo(f, b.title)
                DocFormat.MOBI, DocFormat.AZW3 -> Converted.epub(context, f, fmt, b.id)?.let { opfInfo(it, b.title) }
                DocFormat.FB2 -> fb2Info(f, b.title)
                DocFormat.PDF -> MupdfDoc.open(f.absolutePath).use { d ->
                    if (d.isImageOnly()) null // 스캔 PDF 의 Title 은 스캐너·PC 가 넣은 값이 많다
                    else trusted(d.metaData("info:Title"), d.metaData("info:Author"), "", "", b.title)
                }
                else -> null
            }
        }.getOrNull()
    }

    /** 스캔(그림만) PDF 인가 — 인터넷 자동 매칭 대상 판단. */
    suspend fun scannedPdf(b: Book): Boolean = withContext(Dispatchers.IO) {
        DocFormat.of(b.format) == DocFormat.PDF && File(b.path).isFile && runCatching { MupdfDoc.open(b.path).use { it.isImageOnly() } }.getOrDefault(false)
    }

    private fun trusted(title: String?, author: String?, publisher: String?, isbn: String?, fileTitle: String) = FileInfo(
        title = title?.trim()?.takeIf { MetaRules.trustTitle(it, fileTitle) }.orEmpty(),
        author = author?.trim()?.takeIf { MetaRules.trustAuthor(it) }.orEmpty(),
        publisher = publisher?.trim()?.takeIf { it.length in 2..80 }.orEmpty(),
        isbn = MetaRules.isbn(isbn),
    )

    private fun unescape(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").replace(Regex("\\s+"), " ").trim()

    private fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name\\b[^>]*>(.*?)</(?:\\w+:)?$name>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(xml)?.groupValues?.get(1)?.let(::unescape)

    /** EPUB(합성 EPUB 포함) OPF 의 dc:title·dc:creator·dc:publisher·ISBN. */
    private fun opfInfo(file: File, fileTitle: String): FileInfo? = ZipFile(file).use { z ->
        fun text(name: String): String? = z.getEntry(name)?.takeIf { it.size in 0..(2L shl 20) }?.let { e -> z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
        val container = text("META-INF/container.xml") ?: return@use null
        val opfPath = Regex("full-path\\s*=\\s*[\"']([^\"']+)[\"']").find(container)?.groupValues?.get(1) ?: return@use null
        val opf = text(opfPath) ?: return@use null
        val isbn = Regex("<(?:\\w+:)?identifier\\b[^>]*>([^<]*)</", RegexOption.IGNORE_CASE).findAll(opf).map { unescape(it.groupValues[1]) }
            .map { MetaRules.isbn(it.removePrefix("urn:isbn:")) }.firstOrNull { it.isNotEmpty() }
        trusted(tag(opf, "title"), tag(opf, "creator"), tag(opf, "publisher"), isbn, fileTitle)
    }

    /** FB2 의 title-info: book-title · author(이름) · publish-info 의 publisher·isbn. */
    private fun fb2Info(file: File, fileTitle: String): FileInfo? {
        if (file.length() > (8L shl 20)) return null
        val xml = file.readText(Charsets.UTF_8).take(400_000)
        val info = Regex("<title-info>(.*?)</title-info>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1) ?: return null
        val a = Regex("<author>(.*?)</author>", RegexOption.DOT_MATCHES_ALL).find(info)?.groupValues?.get(1)
        val author = a?.let { listOfNotNull(tag(it, "first-name"), tag(it, "middle-name"), tag(it, "last-name")).joinToString(" ").ifBlank { tag(it, "nickname") } }
        val pub = Regex("<publish-info>(.*?)</publish-info>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
        return trusted(tag(info, "book-title"), author, pub?.let { tag(it, "publisher") }, pub?.let { tag(it, "isbn") }, fileTitle)
    }

    /** 후보 적용(인터넷): 제목·지은이·출판사·ISBN + (받을 수 있으면) 표지. [cover] = false 면 표지는 그대로. */
    suspend fun applyCandidate(context: Context, dao: BookDao, b: Book, c: MetaRules.Candidate, source: Int, score: Int, cover: Boolean) {
        dao.setInfo(b.id, c.title.ifBlank { b.infoTitle }, c.author, c.publisher, c.isbn, source, score, false, System.currentTimeMillis())
        if (cover && c.coverUrl.isNotEmpty()) {
            val bytes = BookInfoNet.download(c.coverUrl)
            if (bytes != null && Covers.saveChosenBytes(context, b.id, bytes)) dao.setCoverSource(b.id, Book.COVER_INTERNET)
        }
    }

    /** [원래대로]: 인터넷·직접 정보와 고른 표지를 지우고 파일 정보(있으면)로. */
    suspend fun reset(context: Context, dao: BookDao, b: Book) {
        Covers.deleteChosen(context, b.id)
        dao.setCoverSource(b.id, Book.COVER_AUTO)
        val fi = fileInfo(context, b)
        if (fi != null && fi.any) dao.setInfo(b.id, fi.title, fi.author, fi.publisher, fi.isbn, Book.META_FILE, 0, false, System.currentTimeMillis())
        else dao.setInfo(b.id, "", "", "", "", Book.META_NONE, 0, false, System.currentTimeMillis())
    }

    /** 같은 시리즈의 다른 권(직접 고른 책 하나를 나머지에 맞출 때). */
    fun seriesMates(b: Book, all: List<Book>): List<Book> {
        val k = SeriesGroups.keyOf(b) ?: return emptyList()
        return SeriesGroups.groups(all)[k].orEmpty().filter { it.id != b.id }
    }

    /** 시리즈 맞추기: 지은이·출판사만 같게(제목·표지는 권마다 다르다). 직접 고른 권은 건드리지 않는다. */
    suspend fun matchSeries(dao: BookDao, from: Book, mates: List<Book>) {
        val now = System.currentTimeMillis()
        for (m in mates) {
            if (m.metaSource == Book.META_NET_PICKED || m.metaSource == Book.META_USER) continue
            dao.setInfo(m.id, m.infoTitle, from.author, from.publisher, "", Book.META_NET_PICKED, from.matchScore, false, now)
        }
    }

    /**
     * 자동으로 책 정보 채우기(서재를 열 때·새 책을 모은 뒤). 아직 찾지 않은 책([Book.metaCheckedAt] = 0)만, 한 번에 [batch] 권.
     * 1순위 파일 정보 → (모자라고 대상이면, 설정·연결이 허락하면) 2순위 인터넷 자동 매칭.
     * @return 정보가 바뀐 책 수
     */
    suspend fun autoFill(context: Context, dao: BookDao, s: Settings, books: List<Book>, batch: Int = 40): Int = gate.withLock {
        var changed = 0
        val todo = books.filter { it.hidden == 0 && it.metaCheckedAt == 0L && it.metaSource != Book.META_NET_PICKED && it.metaSource != Book.META_USER }.take(batch)
        for (b in todo) {
            Covers.reading.first { !it } // 읽는 동안에는 쉬었다가
            val now = System.currentTimeMillis()
            var cur = b
            val fi = fileInfo(context, b)
            if (fi != null && fi.any) {
                dao.setInfo(b.id, fi.title, fi.author, fi.publisher, fi.isbn, Book.META_FILE, 0, false, now)
                cur = b.copy(infoTitle = fi.title, author = fi.author, publisher = fi.publisher, isbn = fi.isbn, metaSource = Book.META_FILE)
                changed++
            }
            // 파일 정보에 지은이까지 있으면 인터넷은 찾지 않는다(정확한 정보는 그대로 둔다)
            val needNet = cur.author.isBlank() && netAutoTarget(cur, scannedPdf(cur)) && BookInfoNet.allowed(context, s, auto = true)
            if (!needNet) { dao.markChecked(b.id, now, false, cur.matchScore); continue }
            val q = MetaRules.query(cur.title)
            val ranked = MetaRules.rank(q, BookInfoNet.search(context, q))
            val best = ranked.firstOrNull()
            if (best != null && best.second.auto) {
                // 표지는 지금 표지가 없을 때만 인터넷 것으로(만화·스캔 PDF 의 첫 쪽은 이미 표지다)
                val hasCover = Covers.supported(DocFormat.of(cur.format)) && Covers.load(context, cur) != null
                applyCandidate(context, dao, cur, best.first, Book.META_NET_AUTO, best.second.score, cover = !hasCover)
                changed++
            } else {
                dao.markChecked(b.id, now, best != null && best.second.score >= 60, best?.second?.score ?: 0)
            }
        }
        changed
    }
}
