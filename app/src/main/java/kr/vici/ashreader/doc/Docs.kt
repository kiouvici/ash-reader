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
import java.util.concurrent.ConcurrentHashMap
import kr.vici.ashreader.R
import kr.vici.ashreader.data.BodyFont
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.Settings
import kr.vici.ashreader.engine.comic.ComicArchive
import kr.vici.ashreader.engine.comic.PageDecoder
import kr.vici.ashreader.engine.comic.PagePanels
import kr.vici.ashreader.engine.comic.PanelDetector
import kr.vici.ashreader.engine.comic.ReadingDirection
import kr.vici.ashreader.engine.hwp.HwpDoc
import kr.vici.ashreader.engine.hwp.HwpHealth
import kr.vici.ashreader.engine.mupdf.MupdfDoc
import kr.vici.ashreader.office.AgileDecryptor
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
    fun open(
        context: Context, file: File, format: DocFormat, pageWdp: Float, pageHdp: Float, s: Settings,
        hwpEbook: Boolean = false, password: String? = null,
    ): ReaderDoc {
        BundledFonts.install(context)
        return when (format.engine) {
            DocFormat.Engine.MUPDF -> MupdfReaderDoc(file, format, pageWdp, pageHdp, s, context)
            DocFormat.Engine.HWP -> if (hwpEbook) {
                val x = HwpXhtml.convert(context, file)
                MupdfReaderDoc(x, format, pageWdp, pageHdp, s, context, reflow = true)
            } else HwpReaderDoc(file, format)
            DocFormat.Engine.COMIC -> ComicReaderDoc(file, format)
            DocFormat.Engine.OFFICE -> {
                val x = OfficeXhtml.convert(context, file, format, password)
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
) : ReaderDoc {
    private val lock = Any()
    private val doc: MupdfDoc = try {
        MupdfDoc.open(
            file.absolutePath,
            if (reflow) MupdfDoc.LayoutSpec(pageWdp, pageHdp, s.fontSize.toFloat()) else null,
            if (reflow) DocOpener.css(context, s) else null,
        )
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

    override fun close() {
        synchronized(lock) { doc.close() }
        onClose?.invoke()
    }
}

/** 한글 원본 보기(쪽 그림). 글자 선택·검색·목차는 이북식 보기([HwpXhtml] → [MupdfReaderDoc])에서 쓴다. */
class HwpReaderDoc(file: File, override val format: DocFormat) : ReaderDoc {
    private val lock = Any()
    private val doc: HwpDoc = try { HwpDoc.open(file.absolutePath) } catch (e: Exception) {
        throw OpenFailed("한글 문서를 열 수 없어요. 배포용·암호 문서이거나 손상된 파일일 수 있어요.")
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

class ComicReaderDoc(file: File, override val format: DocFormat) : ReaderDoc {
    private val lock = Any()
    private val arc: ComicArchive = try { ComicArchive.open(file) } catch (e: Exception) {
        throw OpenFailed(e.message ?: "만화 파일을 열 수 없어요.")
    }
    private val detector = PanelDetector()
    private val panelCache = HashMap<Int, PagePanels>()
    override val pageCount: Int = arc.pages.size
    override val rtl: Boolean get() = arc.direction == ReadingDirection.RTL

    /** 파일별 캐시 열쇠(경로·크기·수정 시각 — 파일이 바뀌면 달라짐). */
    val cacheKey: String = (file.absolutePath + ":" + file.length() + ":" + file.lastModified()).hashCode().toUInt().toString(16)

    /** AI 화질 개선(F-73). 켜져 있으면 읽기 화면이 넣는다. */
    @Volatile var upscale: ComicUpscale? = null
    /** 넘김 화면에 2배 결과로 그린 쪽(바꿔 끼울 쪽 판단용). */
    private val shownUpscaled: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    fun shownUpscaled(index: Int) = index in shownUpscaled

    /** 압축 안 쪽 그림 원본 바이트. */
    fun pageBytes(index: Int): ByteArray = synchronized(lock) { arc.readPage(index) }

    /** 그림 원본 비트맵(폭 [w] 근처로 줄임). 만화 그림은 테마 색을 입히지 않는다. */
    fun pageBitmap(index: Int, w: Int): Bitmap = PageDecoder.decodeFit(pageBytes(index), w.coerceAtLeast(1))

    override fun render(index: Int, w: Int, h: Int, colors: ReadingColors): Bitmap {
        val up = upscale?.load(index, w)
        if (up != null) shownUpscaled += index else shownUpscaled -= index
        return fitInto(up ?: pageBitmap(index, w), w, h, colors, applyTheme = false)
    }

    /** 칸 인식(F-72). 결과 좌표는 [pageBitmap] 과 같은 비율의 원본 기준(width/height 함께 줌). */
    fun panels(index: Int, dir: ReadingDirection): PagePanels = synchronized(panelCache) {
        panelCache.getOrPut(index) {
            val b = pageBitmap(index, 1200)
            try { detector.detect(PageDecoder.toGray(b), dir) } finally { b.recycle() }
        }
    }

    /** 목차 = 압축 안 파일(쪽) 순서. 폴더가 여럿이면 폴더 → 파일 2단. */
    override fun toc(): List<TocEntry> {
        val pages = arc.pages
        val folders = pages.map { it.name.substringBeforeLast('/', "") }
        val multi = folders.distinct().size > 1
        val out = ArrayList<TocEntry>(pages.size + 8)
        var last: String? = null
        pages.forEachIndexed { i, p ->
            val f = folders[i]
            if (multi && f != last) { out += TocEntry(f.substringAfterLast('/').ifEmpty { "(맨 위)" }, 0, i); last = f }
            out += TocEntry(p.name.substringAfterLast('/'), if (multi) 1 else 0, i)
        }
        return out
    }

    override fun close() = synchronized(lock) { arc.close() }
}

/**
 * HWP·HWPX → XHTML(이북식 보기, F-28). rhwp 문서 모델에서 제목·문단·표·그림·각주를 옮긴다.
 * 결과는 파일 크기·수정 시각별로 캐시에 둔다(다시 열면 재사용).
 */
object HwpXhtml {
    fun convert(context: Context, file: File): File {
        val key = (file.absolutePath + ":" + file.length() + ":" + file.lastModified()).hashCode().toUInt().toString(16)
        val dir = File(context.cacheDir, "hwp-ebook/$key")
        val out = File(dir, "doc.xhtml")
        if (out.exists() && out.length() > 0) return out
        val xhtml = try {
            HwpDoc.open(file.absolutePath).use { it.toXhtml(dir, file.nameWithoutExtension) }
        } catch (e: Exception) {
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

    fun convert(context: Context, file: File, format: DocFormat, password: String? = null): Converted {
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
        val dir = (temp ?: File(context.cacheDir, "office/" + file.nameWithoutExtension)).apply { mkdirs() }
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
