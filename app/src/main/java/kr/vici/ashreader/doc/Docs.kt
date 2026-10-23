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

    /** 쪽 세로/가로 비율(F-22 스크롤 보기 자리). 모르면 null. 백그라운드에서 부른다. */
    fun pageAspect(index: Int): Float? = null
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
     * [hwpEbook] = 한글·Word 문서를 이북식(다시 배치)으로 연다(F-28·F-30). 무거운 작업이므로 백그라운드에서 부른다.
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
                val t = TxtUtf8.convert(context, file, docId, textEncoding, s)
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
            // Word: hwpEbook = 이북식. 원본(기본)은 Word 쪽 모양 PDF(F-30, WordOriginal)
            DocFormat.Engine.OFFICE -> if (!hwpEbook) WordOriginal.open(context, file, format, pageWdp, pageHdp, s, password, docId) else {
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
        val user = UserFonts.file(context, s.userFont)
        val font = (user ?: BundledFonts.file(context, s.font)).absolutePath
        val bold = if (user != null) null else BundledFonts.boldFile(context, s.font)?.absolutePath
        val boldFace = if (bold != null) "@font-face { font-family: ashbody; font-weight: bold; src: url(\"$bold\"); }" else ""
        fun em(tenths: Int) = "%.2fem".format(java.util.Locale.ROOT, tenths / 10f)
        val sb = StringBuilder()
        sb.append(
            """
            @font-face { font-family: ashbody; src: url("$font"); }
            $boldFace
            @page { margin: 0; }
            body { font-family: ashbody, serif; line-height: ${"%.2f".format(java.util.Locale.ROOT, s.lineHeight)};
                   text-align: justify; word-break: keep-all; color: #000; background: #fff; }
            p { margin: 0; text-indent: 1em; }
            h1, h2, h3 { line-height: 1.4; text-indent: 0; }
            img { max-width: 100%; }
            p.ash-e { text-indent: 0; }
            """.trimIndent(),
        )
        // F-19 여백(위·아래는 정보줄 몫 0.8em 을 더함)
        sb.append("\nbody { margin: ${em(s.marginV)} ${em(s.marginH)} ${em(s.marginV + 8)} ${em(s.marginH)} !important; }")
        // F-19 정렬: 왼쪽을 고르면 본문 문단을 왼쪽으로(양쪽은 문서 원래 정렬을 존중하는 기본값)
        if (s.align == 1) sb.append("\nbody, p, li, blockquote, dd { text-align: left !important; }")
        // F-19 문단 간격
        if (s.paraSpace > 0) sb.append("\np { margin-top: 0 !important; margin-bottom: ${em(s.paraSpace)} !important; } p.ash-e { margin-bottom: 0 !important; }")
        // F-37 글자 단위 줄 바꿈(기본은 띄어쓰기 단위 keep-all)
        if (s.breakChar) sb.append("\nbody, p, div, li, td, span, blockquote, h1, h2, h3, h4, h5, h6 { word-break: normal !important; }")
        // F-39 하이픈(영문 낱말만 — MuPDF 는 문서 언어(lang)에 맞는 하이픈 표가 있을 때만 끊고 한국어 표는 없다)
        sb.append(if (s.hyphen) "\nbody, p, div, li, blockquote { hyphens: auto !important; }" else "\nbody { hyphens: manual; }")
        // F-40 들여쓰기(원본 유지 = 문서 스타일 그대로)
        when (s.indent) {
            1 -> sb.append("\np { text-indent: 1em !important; }")
            2 -> sb.append("\np { text-indent: 2em !important; }")
            3 -> sb.append("\np { text-indent: 0 !important; }")
        }
        if (s.indent != 0) sb.append("\np.ash-e { text-indent: 0 !important; }")
        if (s.dialogIndent == 1) sb.append("\np.ash-dlg { text-indent: 0 !important; }")
        return sb.toString()
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

/**
 * [src]를 [w]×[h] 안에 비율 유지로 가운데 맞춤. 남는 곳은 테마 바탕색.
 * [keep] = 색 변환 뒤 원래 색으로 다시 그릴 그림 영역(쪽 기준 0..1, 4개씩) — '그림은 반전하지 않기'(F-21)·이북류 그림(M-05).
 */
internal fun fitInto(src: Bitmap, w: Int, h: Int, c: ReadingColors, applyTheme: Boolean, keep: FloatArray? = null): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.eraseColor(c.bg)
    val k = minOf(w.toFloat() / src.width, h.toFloat() / src.height)
    val dw = src.width * k; val dh = src.height * k
    val dst = RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
    val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    val filter = if (applyTheme) themeFilter(c) else null
    p.colorFilter = filter
    val canvas = Canvas(out)
    canvas.drawBitmap(src, Rect(0, 0, src.width, src.height), dst, p)
    if (filter != null && keep != null && keep.size >= 4) {
        val plain = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        var i = 0
        while (i + 3 < keep.size) {
            val sr = Rect((keep[i] * src.width).toInt(), (keep[i + 1] * src.height).toInt(), (keep[i + 2] * src.width).toInt(), (keep[i + 3] * src.height).toInt())
            val dr = RectF(dst.left + keep[i] * dw, dst.top + keep[i + 1] * dh, dst.left + keep[i + 2] * dw, dst.top + keep[i + 3] * dh)
            if (sr.width() > 0 && sr.height() > 0) canvas.drawBitmap(src, sr, dr, plain)
            i += 4
        }
    }
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
    private val reflow: Boolean = format.reflowable,
    /** 닫은 뒤 할 일(암호 문서 임시 폴더 지우기). */
    private val onClose: (() -> Unit)? = null,
    /** 암호 PDF 암호(필수 7). 저장하지 않는다. */
    password: String? = null,
) : ReaderDoc, MarginCroppable {
    private val lock = Any()
    /** F-24 여백 자르기(고정 쪽만). 렌더 전에 정한다. */
    @Volatile override var cropMargins: Boolean = false
    private val cropper = MarginCrop()
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
        val box = cropBox(index)
        if (box != null) {
            // 잘린 상자가 [w]×[h] 에 맞을 만큼 크게 그린 뒤 상자만 잘라 맞춘다
            val a = MarginCrop.aspect(rawAspect(index) ?: 1.414f, box)
            val dw = minOf(w.toFloat(), h / a)
            val rawW = (dw / (box.r - box.l)).toInt().coerceIn(1, 4096)
            val full = synchronized(lock) { doc.renderPage(index, rawW) }
            val keep = if (colors.keepImages && themeFilter(colors) != null) MarginCrop.mapRects(keepRects(index), box) else null
            return fitInto(MarginCrop.cut(full, box), w, h, colors, applyTheme = true, keep = keep)
        }
        val raw = synchronized(lock) { doc.renderPage(index, w) }
        val keep = if ((reflow || colors.keepImages) && themeFilter(colors) != null) keepRects(index) else null
        return fitInto(raw, w, h, colors, applyTheme = true, keep = keep)
    }

    /** 여백 자르기 상자. 끔·이북식·자를 것 없음이면 null. */
    private fun cropBox(index: Int): NRect? {
        if (!cropMargins || reflow) return null
        val b = cropper.box(index) { synchronized(lock) { if (closed) null else doc.renderPage(index, MarginCrop.PROBE_W) } }
        return if (MarginCrop.isFull(b)) null else b
    }

    private val imageCache = object : android.util.LruCache<Int, FloatArray>(64) {}
    private val layoutAspect = pageHdp / pageWdp.coerceAtLeast(1f)
    private val aspectCache = java.util.concurrent.ConcurrentHashMap<Int, Float>()

    private fun rawAspect(index: Int): Float? = aspectCache[index] ?: synchronized(lock) {
        if (closed) null else runCatching { doc.pageAspect(index) }.getOrNull()
    }?.also { aspectCache[index] = it }

    override fun pageAspect(index: Int): Float? = if (reflow) layoutAspect else {
        val raw = rawAspect(index)
        val box = if (raw != null) cropBox(index) else null
        if (raw != null && box != null) MarginCrop.aspect(raw, box) else raw
    }

    /** Word 원본 보기 등 바깥에서 만든 목차(합성 PDF 에 목차가 없을 때). */
    @Volatile var tocOverride: List<TocEntry>? = null

    /**
     * 색 변환에서 원래 색으로 둘 그림 영역. 쪽을 거의 다 덮는 그림 한 장(스캔 쪽)은 반전 대상이므로 빼지 않는다
     * (화면 설계서 S-26: 스캔 PDF 는 '그림은 반전 안 함'이 해당 없음).
     */
    private fun keepRects(index: Int): FloatArray? {
        val r = imageCache.get(index) ?: (synchronized(lock) { if (closed) null else runCatching { doc.imageRects(index) }.getOrNull() } ?: return null)
            .also { imageCache.put(index, it) }
        if (r.isEmpty()) return null
        if (!reflow) {
            var i = 0
            while (i + 3 < r.size) { if ((r[i + 2] - r[i]) * (r[i + 3] - r[i + 1]) >= 0.85f) return null; i += 4 }
        }
        return r
    }

    /** 쪽 [index]의 문서 안 링크(F-46 각주). 웹 주소 등 바깥 링크는 뺀다. */
    fun internalLinks(index: Int): List<MupdfDoc.LinkBox> = synchronized(lock) {
        if (closed) emptyList() else runCatching { doc.links(index) }.getOrDefault(emptyList()).filter { !it.external }
    }

    /** 각주 팝업 내용: 링크가 가리키는 쪽과 그 자리의 글. 못 찾으면 null. */
    fun footnote(uri: String): Footnote? = synchronized(lock) {
        if (closed) return@synchronized null
        val (p, y) = runCatching { doc.linkTarget(uri) }.getOrNull() ?: return@synchronized null
        val text = runCatching { doc.textFrom(p, y) }.getOrDefault("")
        Footnote(p, text)
    }

    class Footnote(val page: Int, val text: String)

    override fun pageLines(index: Int): List<String> = synchronized(lock) { runCatching { doc.pageLines(index) }.getOrDefault(emptyList()) }

    private val textCache = object : android.util.LruCache<Int, TextPage>(24) {}

    override fun pageText(index: Int): TextPage? {
        textCache.get(index)?.let { return it }
        val t = synchronized(lock) { runCatching { doc.pageText(index) }.getOrNull() } ?: return null
        val tp = TextPage(t.text, t.boxes, t.aspect)
        val box = cropBox(index)
        return (if (box != null) MarginCrop.mapText(tp, box) else tp).also { textCache.put(index, it) }
    }

    override fun toc(): List<TocEntry> = tocOverride ?: synchronized(lock) {
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
class DjvuReaderDoc(file: File) : ReaderDoc, MarginCroppable {
    override val format: DocFormat = DocFormat.DJVU
    /** F-24 여백 자르기. 렌더 전에 정한다. */
    @Volatile override var cropMargins: Boolean = false
    private val cropper = MarginCrop()
    private val doc: DjvuDoc = try { DjvuDoc.open(file.absolutePath) } catch (e: Exception) {
        throw OpenFailed(e.message?.takeIf { it.contains("기기") } ?: "DjVu 파일을 열 수 없어요. 손상되었을 수 있어요.")
    }
    override val pageCount: Int = runCatching { doc.pageCount() }.getOrDefault(0)

    override fun render(index: Int, w: Int, h: Int, colors: ReadingColors): Bitmap {
        val sz = doc.pageSize(index)
        val box = cropBox(index)
        if (box != null) {
            val a = MarginCrop.aspect(sz.height.toFloat() / sz.width.coerceAtLeast(1), box)
            val dw = minOf(w.toFloat(), h / a)
            val rawW = (dw / (box.r - box.l)).toInt().coerceIn(1, 4096)
            return fitInto(MarginCrop.cut(doc.renderPage(index, rawW), box), w, h, colors, applyTheme = true)
        }
        // 쪽 맞춤: 화면 안에 들어가는 폭으로 그린다(세로로 긴 쪽은 높이에 맞춤)
        val fitW = minOf(w.toFloat(), h.toFloat() * sz.width / sz.height).toInt().coerceAtLeast(1)
        return fitInto(doc.renderPage(index, fitW), w, h, colors, applyTheme = true)
    }

    private fun cropBox(index: Int): NRect? {
        if (!cropMargins) return null
        val b = cropper.box(index) { runCatching { doc.renderPage(index, MarginCrop.PROBE_W) }.getOrNull() }
        return if (MarginCrop.isFull(b)) null else b
    }

    override fun pageLines(index: Int): List<String> = runCatching { doc.pageLines(index) }.getOrDefault(emptyList())

    private val textCache = object : android.util.LruCache<Int, TextPage>(24) {}

    /** 숨은 글자층(OCR) 낱말 위치로 글자 선택·검색·형광펜(4A 남은 일). 글자층이 없으면 null. */
    override fun pageText(index: Int): TextPage? {
        textCache.get(index)?.let { return it }
        val words = runCatching { doc.pageWords(index) }.getOrDefault(emptyList())
        if (words.isEmpty()) return null
        val sz = runCatching { doc.pageSize(index) }.getOrNull() ?: return null
        val tp = djvuTextPage(words.map { DjvuWord(it.l, it.t, it.r, it.b, it.text, it.lineEnd) }, sz.height.toFloat() / sz.width)
        val box = cropBox(index)
        return (if (box != null) MarginCrop.mapText(tp, box) else tp).also { textCache.put(index, it) }
    }

    override fun toc(): List<TocEntry> = runCatching { doc.outline().map { TocEntry(it.title, it.depth, it.page) } }.getOrDefault(emptyList())

    override fun pageAspect(index: Int): Float? {
        val raw = runCatching { doc.pageSize(index) }.getOrNull()?.let { if (it.width > 0) it.height.toFloat() / it.width else null } ?: return null
        val box = cropBox(index)
        return if (box != null) MarginCrop.aspect(raw, box) else raw
    }

    override fun close() = doc.close()
}

/** DjVu 낱말(엔진 Word 를 옮긴 것 — 시험에서 엔진 없이 쓰려고). */
class DjvuWord(val l: Float, val t: Float, val r: Float, val b: Float, val text: String, val lineEnd: Boolean)

/** 낱말 상자 → 글자 상자(낱말 폭을 글자 수로 고르게 나눔). 낱말 사이 빈칸·줄 끝 '\n'은 상자 0. */
fun djvuTextPage(words: List<DjvuWord>, aspect: Float): TextPage {
    val sb = StringBuilder()
    val boxes = ArrayList<Float>()
    fun put(l: Float, t: Float, r: Float, b: Float) { boxes += l; boxes += t; boxes += r; boxes += b }
    for ((i, w) in words.withIndex()) {
        val n = w.text.length
        val step = (w.r - w.l) / n.coerceAtLeast(1)
        for (k in 0 until n) put(w.l + step * k, w.t, w.l + step * (k + 1), w.b)
        sb.append(w.text)
        if (w.lineEnd || i == words.size - 1) { sb.append('\n'); put(0f, 0f, 0f, 0f) }
        else { sb.append(' '); put(0f, 0f, 0f, 0f) }
    }
    return TextPage(sb.toString(), boxes.toFloatArray(), aspect)
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

    fun convert(context: Context, file: File, key: String, forced: TextEncoding.Kind?, s: Settings = Settings()): Out {
        if (file.length() > (256L shl 20)) throw OpenFailed("텍스트 파일이 너무 커요(256MB 넘음).")
        val kind = forced ?: run {
            val head = ByteArray(minOf(file.length(), TextEncoding.SAMPLE.toLong()).toInt())
            java.io.RandomAccessFile(file, "r").use { it.readFully(head) }
            TextEncoding.detect(head)
        }
        val dir = File(context.cacheDir, "txt/$key").apply { mkdirs() }
        // F-41 TXT 정리·F-33 목차·F-39 하이픈: 설정이 다르면 다른 파일(원본은 그대로)
        val tag = TxtClean.tag(s.txtBlank, s.txtJoin, s.txtTrim) + "-c${s.txtTocMask}" +
            (if (s.txtTocCustom.isBlank()) "" else "u" + Integer.toHexString(s.txtTocCustom.hashCode())) + (if (s.hyphen) "h" else "")
        val outName = "${kind.name}-$tag.xhtml"
        dir.listFiles()?.forEach { f -> if (f.name.startsWith(kind.name + "-") && f.name != outName) f.delete() }
        val out = File(dir, outName)
        if (!out.isFile || out.length() == 0L || out.lastModified() < file.lastModified()) {
            val text = TxtClean.clean(TextEncoding.decode(file.readBytes(), kind), s.txtBlank, s.txtJoin, s.txtTrim)
            val xhtml = TxtXhtml.convert(text.replace("\r\n", "\n").replace('\r', '\n'), file.nameWithoutExtension, s.txtTocMask, s.txtTocCustom, s.hyphen)
            val tmp = File(dir, kind.name + ".tmp")
            tmp.writeText(xhtml, Charsets.UTF_8)
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
        val keep = if (colors.keepImages && themeFilter(colors) != null) keepRects(index) else null
        return fitInto(raw, w, h, colors, applyTheme = true, keep = keep)
    }

    private val imageCache = object : android.util.LruCache<Int, FloatArray>(64) {}
    private val aspectCache = java.util.concurrent.ConcurrentHashMap<Int, Float>()

    /** 야간 '그림은 반전 안 함'(F-21): rhwp 쪽 SVG 의 그림 영역. 쪽을 거의 다 덮는 그림(배경·스캔)은 반전 대상으로 둔다. */
    private fun keepRects(index: Int): FloatArray? {
        val r = imageCache.get(index) ?: (synchronized(lock) { runCatching { doc.imageRects(index) }.getOrNull() } ?: return null).also { imageCache.put(index, it) }
        if (r.isEmpty()) return null
        var i = 0
        while (i + 3 < r.size) { if ((r[i + 2] - r[i]) * (r[i + 3] - r[i + 1]) >= 0.85f) return null; i += 4 }
        return r
    }

    override fun pageAspect(index: Int): Float? = aspectCache[index] ?: synchronized(lock) { runCatching { doc.pageAspect(index) }.getOrNull() }?.also { aspectCache[index] = it }

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
    private val DIRS = listOf("office", "hwp-ebook", "upscale", "convert", "txt", "zipx", "comic-x", "comic-meta")

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
        // doc-d = 대화문 표시(F-40)를 붙인 판
        val out = File(dir, "doc-d.xhtml")
        if (out.exists() && out.length() > 0) return out
        File(dir, "doc.xhtml").delete()
        val xhtml = try {
            Dialogue.mark(HwpDoc.open(file.absolutePath).use { it.toXhtml(dir, file.nameWithoutExtension) })
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
        return Converted(File(dir, "doc.xhtml").apply { writeText(runCatching { Dialogue.mark(xhtml) }.getOrDefault(xhtml)) }, temp)
    }
}
