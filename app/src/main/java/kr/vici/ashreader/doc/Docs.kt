// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.doc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kr.vici.ashreader.R
import kr.vici.ashreader.data.BodyFont
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.Settings
import kr.vici.ashreader.engine.convert.ChmEpub
import kr.vici.ashreader.engine.convert.ConvertResult
import kr.vici.ashreader.engine.convert.MobiEpub
import kr.vici.ashreader.engine.convert.TextEncoding
import kr.vici.ashreader.engine.djvu.DjvuDoc
import kr.vici.ashreader.engine.hwp.HwpDoc
import kr.vici.ashreader.engine.hwp.HwpHealth
import kr.vici.ashreader.engine.mupdf.MupdfDoc
import kr.vici.ashreader.engine.mupdf.PasswordException
import kr.vici.ashreader.office.AgileDecryptor
import kr.vici.ashreader.office.CfbFile
import kr.vici.ashreader.office.DocExtractor
import kr.vici.ashreader.office.DocxConverter
import kr.vici.ashreader.office.FormatSniffer
import kr.vici.ashreader.office.RtfConverter
import kr.vici.ashreader.office.SniffedFormat
import kr.vici.ashreader.ui.ReadingColors

/** 열린 문서 하나. 모든 엔진이 '쪽 비트맵'만 내보낸다(F-17). 스레드 안전하게 구현한다(엔진 호출은 잠금 안에서). */
interface ReaderDoc : Closeable {
    val format: DocFormat
    val pageCount: Int
    /** 오→왼 읽기(만화 ComicInfo 등). */
    val rtl: Boolean get() = false

    /** [w]×[h] 크기 쪽 그림(테마 색 적용). */
    fun render(index: Int, w: Int, h: Int, colors: ReadingColors): Bitmap

    /** 쪽 글자(듣기·검색·사전용). 글자가 없는 형식은 빈 목록. */
    fun pageLines(index: Int): List<String> = emptyList()

    /** 쪽 글자와 위치(글자 선택·형광펜·검색). 글자 위치를 모르는 형식은 null. */
    fun pageText(index: Int): TextPage? = null

    /** 목차. 없거나 모르는 형식은 빈 목록. */
    fun toc(): List<TocEntry> = emptyList()
}

/** 목차 한 줄. [page] 0부터(-1 = 위치 모름). */
data class TocEntry(val title: String, val depth: Int, val page: Int)

class OpenFailed(msg: String) : Exception(msg)

/** 서점 보호(DRM)가 걸린 책(D-002, S-43 서점 보호 안내). 해제하지 않는다. */
class DrmProtected : Exception("서점 보호가 걸린 책이에요. 구매한 서점의 앱에서 읽어 주세요.")

/** 암호 걸린 문서: 암호가 필요함([wrong]=false) 또는 틀림([wrong]=true). 암호는 저장하지 않는다. */
class PasswordNeeded(val wrong: Boolean) : Exception(if (wrong) "암호가 맞지 않아요." else "암호가 걸린 문서예요.")

/** 번들 글꼴(assets/fonts) → 내부 저장소 복사. 엔진은 파일 경로로 글꼴을 읽는다. */
object BundledFonts {
    /** 본문 글꼴 파일(모두 SIL OFL 1.1, 고지는 라이선스 화면). 파일이 없으면 나눔명조로 대체. */
    private val files = mapOf(
        BodyFont.NANUM_MYEONGJO to "NanumMyeongjo-Regular.ttf",
        BodyFont.RIDI_BATANG to "RIDIBatang.otf",
        BodyFont.MARUBURI to "MaruBuri-Regular.ttf",
    )

    /** 굵은 글꼴 파일(있는 것만). 없으면 MuPDF 가 굵게를 흉내 낸다. */
    private val boldFiles = mapOf(
        BodyFont.NANUM_MYEONGJO to "NanumMyeongjo-Bold.ttf",
        BodyFont.MARUBURI to "MaruBuri-Bold.ttf",
    )

    @Volatile private var installed = false

    fun dir(context: Context): File = File(context.filesDir, "fonts")

    /** 번들 글꼴 리소스 → 엔진이 찾는 파일 이름. */
    private val bundled = listOf(
        R.font.nanum_myeongjo_regular to "NanumMyeongjo-Regular.ttf",
        R.font.nanum_myeongjo_bold to "NanumMyeongjo-Bold.ttf",
        R.font.nanum_gothic_regular to "NanumGothic-Regular.ttf",
        R.font.ridibatang to "RIDIBatang.otf",
        R.font.maruburi_regular to "MaruBuri-Regular.ttf",
        R.font.maruburi_bold to "MaruBuri-Bold.ttf",
    )

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        val d = dir(context).apply { mkdirs() }
        for ((res, n) in bundled) {
            val out = File(d, n)
            if (!out.exists() || out.length() == 0L) context.resources.openRawResource(res).use { i -> out.outputStream().use { i.copyTo(it) } }
        }
        MupdfDoc.installFonts(d)
        HwpDoc.installFonts(listOf(d))
        installed = true
    }

    /** 고른 글꼴 파일. 없으면 나눔명조. */
    fun file(context: Context, f: BodyFont): File {
        val want = File(dir(context), files.getValue(f))
        return if (want.exists()) want else File(dir(context), files.getValue(BodyFont.NANUM_MYEONGJO))
    }

    /** 고른 글꼴의 굵은 파일. 없으면 null. 본문 글꼴이 대체됐으면(파일 없음) 대체 글꼴 기준. */
    fun boldFile(context: Context, f: BodyFont): File? {
        val base = if (available(context, f)) f else BodyFont.NANUM_MYEONGJO
        return boldFiles[base]?.let { File(dir(context), it) }?.takeIf { it.exists() }
    }

    fun available(context: Context, f: BodyFont): Boolean = File(dir(context), files.getValue(f)).exists()
}

object DocOpener {
    /**
     * [file]을 연다. 이북류는 [pageWdp]×[pageHdp] 쪽 크기(dp=pt)로 다시 배치한다.
     * [hwpEbook] = 한글 문서를 이북식(다시 배치)으로 연다(F-28). 무거운 작업이므로 백그라운드에서 부른다.
     */
    /**
     * [docId] = 문서 식별 해시(DocHash). 변환 캐시(office/·hwp-ebook/·upscale/) 폴더 이름으로 쓴다(닫을 때·시작할 때 정리 — 필수 5).
     * [comic] = 그림만 든 PDF 를 만화 보기(칸 단위·AI 화질)로 연다.
     */
    fun open(
        context: Context, file: File, format: DocFormat, pageWdp: Float, pageHdp: Float, s: Settings,
        hwpEbook: Boolean = false, password: String? = null, docId: String = DocCaches.keyOf(file), comic: Boolean = false,
        /** TXT 인코딩 수동 지정(F-32). null = 자동 판별. */
        textEncoding: TextEncoding.Kind? = null,
    ): ReaderDoc {
        BundledFonts.install(context)
        return when (format.engine) {
            DocFormat.Engine.MUPDF -> if (comic && format == DocFormat.PDF) {
                ComicReaderDoc(PdfComicPages(file, password), format, docId, ComicReaderDoc.metaFile(context, docId))
            } else if (format == DocFormat.TXT) {
                val t = TxtUtf8.convert(context, file, docId, textEncoding)
                MupdfReaderDoc(t.file, format, pageWdp, pageHdp, s, context).also { it.textKind = t.kind; it.textAuto = textEncoding == null }
            } else MupdfReaderDoc(file, format, pageWdp, pageHdp, s, context, password = password)
            DocFormat.Engine.CONVERT -> {
                val epub = Converted.epub(context, file, format, docId)
                if (epub != null) MupdfReaderDoc(epub, format, pageWdp, pageHdp, s, context, reflow = true)
                // libmobi 가 못 읽은(DRM 아닌) MOBI·AZW3 는 MuPDF 의 MOBI 처리기로 한 번 더(옛 PalmDOC 본문만)
                else MupdfReaderDoc(file, DocFormat.MOBI, pageWdp, pageHdp, s, context, reflow = true)
            }
            DocFormat.Engine.DJVU -> DjvuReaderDoc(file)
            DocFormat.Engine.HWP -> {
                HwpDiag.check(file)
                if (hwpEbook) {
                    val x = HwpXhtml.convert(context, file, docId)
                    MupdfReaderDoc(x, format, pageWdp, pageHdp, s, context, reflow = true)
                } else HwpReaderDoc(file, format)
            }
            DocFormat.Engine.COMIC -> ComicReaderDoc(ArchivePages(file, ArchivePages.workDir(context, docId)), format, docId, ComicReaderDoc.metaFile(context, docId))
            DocFormat.Engine.OFFICE -> {
                val x = OfficeXhtml.convert(context, file, format, password, docId)
                val tmp = x.temp
                val cleanup: (() -> Unit)? = if (tmp != null) { { tmp.deleteRecursively() } } else null
                try {
                    MupdfReaderDoc(x.file, format, pageWdp, pageHdp, s, context, onClose = cleanup)
                } catch (e: Exception) {
                    tmp?.deleteRecursively(); throw e
                }
            }
            DocFormat.Engine.NONE -> throw OpenFailed("이 형식은 아직 열 수 없어요.")
        }
    }

    fun css(context: Context, s: Settings): String {
        val font = BundledFonts.file(context, s.font).absolutePath
        val bold = BundledFonts.boldFile(context, s.font)?.absolutePath
        val boldFace = if (bold != null) "@font-face { font-family: ashbody; font-weight: bold; src: url(\"$bold\"); }" else ""
        return """
            @font-face { font-family: ashbody; src: url("$font"); }
            $boldFace
            @page { margin: 0; }
            body { font-family: ashbody, serif; line-height: ${"%.2f".format(java.util.Locale.ROOT, s.lineHeight)};
                   margin: 1.4em 1.5em 2.2em 1.5em; text-align: justify; word-break: keep-all; color: #000; background: #fff; }
            p { margin: 0; text-indent: 1em; }
            h1, h2, h3 { line-height: 1.4; text-indent: 0; }
            img { max-width: 100%; }
        """.trimIndent()
    }
}

/** 흰 종이·검은 글자를 테마 바탕·글자색으로 옮기는 선형 색 변환(주간=거의 그대로, 세피아=종이색 곱, 야간=반전). */
internal fun themeFilter(c: ReadingColors): ColorMatrixColorFilter? {
    if (c.bg == 0xFFFFFFFF.toInt() && c.fg == 0xFF000000.toInt()) return null
    fun ch(v: Int, shift: Int) = ((v shr shift) and 0xFF).toFloat()
    val m = FloatArray(20)
    for ((row, shift) in listOf(0 to 16, 1 to 8, 2 to 0)) {
        val bg = ch(c.bg, shift); val fg = ch(c.fg, shift)
        m[row * 5 + row] = (bg - fg) / 255f
        m[row * 5 + 4] = fg
    }
    m[18] = 1f
    return ColorMatrixColorFilter(ColorMatrix(m))
}

/** [src]를 [w]×[h] 안에 비율 유지로 가운데 맞춤. 남는 곳은 테마 바탕색. */
internal fun fitInto(src: Bitmap, w: Int, h: Int, c: ReadingColors, applyTheme: Boolean): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.eraseColor(c.bg)
    val k = minOf(w.toFloat() / src.width, h.toFloat() / src.height)
    val dw = src.width * k; val dh = src.height * k
    val dst = RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
    val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    if (applyTheme) p.colorFilter = themeFilter(c)
    Canvas(out).drawBitmap(src, Rect(0, 0, src.width, src.height), dst, p)
    if (src !== out) src.recycle()
    return out
}

class MupdfReaderDoc(
    file: File,
    override val format: DocFormat,
    pageWdp: Float,
    pageHdp: Float,
    s: Settings,
    context: Context,
    /** 다시 배치(이북식). 기본은 형식에 따름. 한글 이북식 보기는 true 로 넘긴다. */
    reflow: Boolean = format.reflowable,
    /** 닫은 뒤 할 일(암호 문서 임시 폴더 지우기). */
    private val onClose: (() -> Unit)? = null,
    /** 암호 PDF 암호(필수 7). 저장하지 않는다. */
    password: String? = null,
) : ReaderDoc {
    private val lock = Any()
    private val doc: MupdfDoc = try {
        MupdfDoc.open(
            file.absolutePath,
            if (reflow) MupdfDoc.LayoutSpec(pageWdp, pageHdp, s.fontSize.toFloat()) else null,
            if (reflow) DocOpener.css(context, s) else null,
            password,
        )
    } catch (e: PasswordException) {
        throw PasswordNeeded(e.wrong)
    } catch (e: Exception) {
        throw OpenFailed("문서를 열 수 없어요. 파일이 손상되었거나 지원하지 않는 형식일 수 있어요.")
    }
    override val pageCount: Int = synchronized(lock) { doc.pageCount() }

    override fun render(index: Int, w: Int, h: Int, colors: ReadingColors): Bitmap {
        val raw = synchronized(lock) { doc.renderPage(index, w) }
        return fitInto(raw, w, h, colors, applyTheme = true)
    }

    override fun pageLines(index: Int): List<String> = synchronized(lock) { runCatching { doc.pageLines(index) }.getOrDefault(emptyList()) }

    private val textCache = object : android.util.LruCache<Int, TextPage>(24) {}

    override fun pageText(index: Int): TextPage? {
        textCache.get(index)?.let { return it }
        val t = synchronized(lock) { runCatching { doc.pageText(index) }.getOrNull() } ?: return null
        return TextPage(t.text, t.boxes, t.aspect).also { textCache.put(index, it) }
    }

    override fun toc(): List<TocEntry> = synchronized(lock) {
        runCatching { doc.outline().map { TocEntry(it.title, it.depth, it.page) } }.getOrDefault(emptyList())
    }

    @Volatile private var closed = false

    /** TXT 로 열었을 때 쓴 인코딩(F-32)과 자동 판별 여부(읽기 메뉴 표시용). */
    @Volatile var textKind: TextEncoding.Kind? = null
    @Volatile var textAuto: Boolean = true

    /** 그림만 든 PDF(스캔 만화)인지 — '만화로 보기' 제안용. 닫힌 뒤에는 false. */
    fun isImageOnly(): Boolean = format == DocFormat.PDF && synchronized(lock) { !closed && doc.isImageOnly() }

    override fun close() {
        synchronized(lock) { if (!closed) { closed = true; doc.close() } }
        onClose?.invoke()
    }
}

/** DjVu(F-26 고정 쪽, DjVuLibre). 쪽 그림 + 목차 + 숨은 글자층 줄 글자(듣기·검색). 글자 위치는 쓰지 않는다. */
class DjvuReaderDoc(file: File) : ReaderDoc {
    override val format: DocFormat = DocFormat.DJVU
    private val doc: DjvuDoc = try { DjvuDoc.open(file.absolutePath) } catch (e: Exception) {
        throw OpenFailed(e.message?.takeIf { it.contains("기기") } ?: "DjVu 파일을 열 수 없어요. 손상되었을 수 있어요.")
    }
    override val pageCount: Int = runCatching { doc.pageCount() }.getOrDefault(0)

    override fun render(index: Int, w: Int, h: Int, colors: ReadingColors): Bitmap {
        val sz = doc.pageSize(index)
        // 쪽 맞춤: 화면 안에 들어가는 폭으로 그린다(세로로 긴 쪽은 높이에 맞춤)
        val fitW = minOf(w.toFloat(), h.toFloat() * sz.width / sz.height).toInt().coerceAtLeast(1)
        return fitInto(doc.renderPage(index, fitW), w, h, colors, applyTheme = true)
    }

    override fun pageLines(index: Int): List<String> = runCatching { doc.pageLines(index) }.getOrDefault(emptyList())

    override fun toc(): List<TocEntry> = runCatching { doc.outline().map { TocEntry(it.title, it.depth, it.page) } }.getOrDefault(emptyList())

    override fun close() = doc.close()
}

/**
 * MOBI·AZW3·CHM → 합성 EPUB(:engine-convert). 결과는 cache/convert/<docId>.epub 에 두고 다시 열 때 쓴다(필수 5 의 변환 캐시 규칙).
 * null = libmobi 가 읽지 못함(MuPDF 로 한 번 더). DRM 은 [DrmProtected].
 */
object Converted {
    fun epub(context: Context, file: File, format: DocFormat, key: String): File? {
        val dir = File(context.cacheDir, "convert/$key").apply { mkdirs() }
        val out = File(dir, "book.epub")
        if (out.isFile && out.length() > 0) return out
        val work = File(context.cacheDir, "convert-work/" + UUID.randomUUID())
        val r = when (format) {
            DocFormat.CHM -> ChmEpub.convert(file, work, out, file.nameWithoutExtension)
            else -> MobiEpub.convert(file, work, out)
        }
        return when (r) {
            is ConvertResult.Ok -> r.epub
            ConvertResult.Drm -> throw DrmProtected()
            is ConvertResult.Unsupported -> throw OpenFailed(r.why)
            is ConvertResult.Failed -> if (format == DocFormat.CHM) throw OpenFailed(r.why) else null
        }
    }
}

/** TXT → UTF-8(F-32). 자동 판별(BOM·UTF-16·UTF-8·CP949) 또는 사용자가 고른 인코딩. 결과는 cache/txt/<docId>/<인코딩>.txt. */
object TxtUtf8 {
    class Out(val file: File, val kind: TextEncoding.Kind)

    fun convert(context: Context, file: File, key: String, forced: TextEncoding.Kind?): Out {
        if (file.length() > (256L shl 20)) throw OpenFailed("텍스트 파일이 너무 커요(256MB 넘음).")
        val kind = forced ?: run {
            val head = ByteArray(minOf(file.length(), TextEncoding.SAMPLE.toLong()).toInt())
            java.io.RandomAccessFile(file, "r").use { it.readFully(head) }
            TextEncoding.detect(head)
        }
        val dir = File(context.cacheDir, "txt/$key").apply { mkdirs() }
        val out = File(dir, kind.name + ".txt")
        if (!out.isFile || out.length() == 0L || out.lastModified() < file.lastModified()) {
            val text = TextEncoding.decode(file.readBytes(), kind)
            val tmp = File(dir, kind.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        }
        return Out(out, kind)
    }
}

/** 한글 원본 보기(쪽 그림). 글자 선택·검색·목차는 이북식 보기([HwpXhtml] → [MupdfReaderDoc])에서 쓴다. */
class HwpReaderDoc(file: File, override val format: DocFormat) : ReaderDoc {
    private val lock = Any()
    private val doc: HwpDoc = try { HwpDoc.open(file.absolutePath) } catch (e: Exception) {
        HwpDiag.check(file, afterFailure = true)
        throw OpenFailed("한글 문서를 열 수 없어요. 손상되었거나 지원하지 않는 판의 파일일 수 있어요.")
    }
    override val pageCount: Int = synchronized(lock) { doc.pageCount() }

    override fun render(index: Int, w: Int, h: Int, colors: ReadingColors): Bitmap {
        val raw = synchronized(lock) { doc.renderPage(index, w) }
        return fitInto(raw, w, h, colors, applyTheme = true)
    }

    /**
     * F-28 자동 전환 판정: 첫 쪽(작게 렌더)이 비었는데 글자는 있거나, 렌더 자체가 실패하면 true.
     * 글자 없는 빈 문서는 false(이북식으로 바꿔도 나을 게 없음).
     */
    fun firstPageFailed(): Boolean = synchronized(lock) {
        if (pageCount <= 0) return@synchronized false
        val chars = runCatching { doc.pageTextChars(0) }.getOrDefault(0)
        val bmp = try { doc.renderPage(0, 480) } catch (e: Exception) { return@synchronized true }
        try { HwpHealth.firstPageFailed(HwpHealth.inkRatio(bmp), chars) } finally { bmp.recycle() }
    }

    override fun close() = synchronized(lock) { doc.close() }
}

/**
 * 한글 문서 암호·배포용 판별(필수 7). 해당하면 알맞은 안내로 [OpenFailed].
 * 암호 문서는 열기 전에 막고, 배포용 문서는 엔진이 열지 못했을 때만([afterFailure]) 그 이유로 안내한다.
 */
object HwpDiag {
    fun check(file: File, afterFailure: Boolean = false) {
        val props = CfbFile.hwpProperties(file)
        if (props != null) {
            if (props and 0x2 != 0) throw OpenFailed("암호가 걸린 한글 문서예요. 한컴오피스 한글에서 문서 암호를 해제한 뒤 다시 열어 주세요.")
            if (afterFailure && props and 0x4 != 0) throw OpenFailed("배포용 한글 문서(복사·인쇄 제한)를 열지 못했어요. 한컴오피스 한글에서 일반 문서로 저장한 뒤 열어 주세요.")
            return
        }
        // HWPX: 암호 문서는 META-INF/manifest.xml 에 암호화 정보가 들어간다
        val encrypted = runCatching {
            ZipFile(file).use { z ->
                val e = z.getEntry("META-INF/manifest.xml") ?: return@use false
                if (e.size > (1L shl 20)) return@use false
                z.getInputStream(e).use { it.readBytes() }.toString(Charsets.UTF_8).contains("encryption-data")
            }
        }.getOrDefault(false)
        if (encrypted) throw OpenFailed("암호가 걸린 한글 문서(HWPX)예요. 한컴오피스 한글에서 문서 암호를 해제한 뒤 다시 열어 주세요.")
    }
}

/**
 * 문서별 변환 캐시(필수 5): office/<id>, hwp-ebook/<id>, upscale/<id>.
 * 한 번만 보기 문서는 닫을 때 지우고, 앱 시작 때 서재에 없는 문서의 캐시를 쓸어 낸다.
 */
object DocCaches {
    private val DIRS = listOf("office", "hwp-ebook", "upscale", "convert", "txt", "comic-x", "comic-meta")

    /** 파일 이름이 'id.확장자'(서재·한 번만 보기 사본 규칙)면 id, 아니면 경로 기반 SHA-256 앞부분. */
    fun keyOf(file: File): String {
        val n = file.nameWithoutExtension
        if (n.length == 32 && n.all { it in '0'..'9' || it in 'a'..'f' }) return n
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update((file.absolutePath + ":" + file.length() + ":" + file.lastModified()).toByteArray())
        return md.digest().take(16).joinToString("") { "%02x".format(it) }
    }

    fun deleteFor(context: Context, id: String) {
        if (id.isEmpty() || id.contains('/') || id.contains("..")) return
        for (d in DIRS) File(context.cacheDir, "$d/$id").deleteRecursively()
    }

    /** [keep](서재 문서 id)에 없는 문서의 변환 캐시를 지운다. 서재 문서의 AI 화질 캐시는 상한으로만 관리한다(F-73). */
    fun sweep(context: Context, keep: Set<String>) {
        File(context.cacheDir, "convert-work").deleteRecursively()
        for (d in DIRS) {
            File(context.cacheDir, d).listFiles()?.forEach { f -> if (f.name !in keep) f.deleteRecursively() }
        }
    }
}

/**
 * HWP·HWPX → XHTML(이북식 보기, F-28). rhwp 문서 모델에서 제목·문단·표·그림·각주를 옮긴다.
 * 결과는 파일 크기·수정 시각별로 캐시에 둔다(다시 열면 재사용).
 */
object HwpXhtml {
    /** [key] = 문서 식별 해시(DocHash). 같은 문서를 다시 열면 재사용한다. */
    fun convert(context: Context, file: File, key: String = DocCaches.keyOf(file)): File {
        val dir = File(context.cacheDir, "hwp-ebook/$key")
        val out = File(dir, "doc.xhtml")
        if (out.exists() && out.length() > 0) return out
        val xhtml = try {
            HwpDoc.open(file.absolutePath).use { it.toXhtml(dir, file.nameWithoutExtension) }
        } catch (e: Exception) {
            HwpDiag.check(file, afterFailure = true)
            throw OpenFailed("한글 문서를 이북식으로 바꾸지 못했어요. 원본 보기로 읽어 주세요.")
        }
        out.writeText(xhtml)
        return out
    }
}

/**
 * DOCX·DOC·RTF → XHTML(이북식 보기). 그림은 같은 폴더에 쓴다.
 * 암호 DOCX(Agile·Standard)는 [password] 로 풀어 메모리에서 변환하고, 결과는 읽기 화면을 닫을 때 지우는 임시 폴더에 둔다.
 * 암호 자체는 어디에도 저장하지 않는다(F-34).
 */
object OfficeXhtml {
    /** [temp] = 닫을 때 지울 폴더(암호 문서만). */
    class Converted(val file: File, val temp: File?)

    fun convert(context: Context, file: File, format: DocFormat, password: String? = null, key: String = DocCaches.keyOf(file)): Converted {
        val raw = file.readBytes()
        val sniffed = FormatSniffer.sniff(raw)
        var temp: File? = null
        val bytes: ByteArray = when {
            sniffed == SniffedFormat.ENCRYPTED_OOXML -> {
                if (password.isNullOrEmpty()) throw PasswordNeeded(wrong = false)
                val r = try { AgileDecryptor.decrypt(raw, password) } catch (e: Exception) {
                    throw OpenFailed("암호가 걸린 Word 문서를 풀 수 없어요. 파일이 손상되었을 수 있어요.")
                }
                when (r) {
                    is AgileDecryptor.Result.Ok -> r.zip
                    is AgileDecryptor.Result.WrongPassword -> throw PasswordNeeded(wrong = true)
                    is AgileDecryptor.Result.Unsupported -> throw OpenFailed("이 암호 방식은 아직 열 수 없어요. (${r.reason})")
                }.also { temp = File(context.cacheDir, "office-enc/" + UUID.randomUUID()) }
            }
            sniffed == SniffedFormat.DOC && DocExtractor.isEncrypted(raw) ->
                throw OpenFailed("암호가 걸린 DOC(Word 97-2003) 문서는 아직 열 수 없어요. Word에서 암호를 풀거나 DOCX로 저장한 뒤 열어 주세요.")
            else -> raw
        }
        val dir = (temp ?: File(context.cacheDir, "office/$key")).apply { mkdirs() }
        val xhtml = try {
            when {
                sniffed == SniffedFormat.RTF -> RtfConverter.convert(bytes).xhtml
                bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> {
                    val r = DocxConverter(DocxConverter.Options(simplified = true)).convert(bytes)
                    for ((name, data) in r.images) File(dir, name).apply { parentFile?.mkdirs() }.writeBytes(data)
                    r.xhtml
                }
                else -> DocExtractor.convert(bytes).xhtml
            }
        } catch (e: Exception) {
            temp?.deleteRecursively()
            throw OpenFailed("Word 문서를 열 수 없어요. 손상되었거나 지원하지 않는 형식일 수 있어요.")
        }
        return Converted(File(dir, "doc.xhtml").apply { writeText(xhtml) }, temp)
    }
}
