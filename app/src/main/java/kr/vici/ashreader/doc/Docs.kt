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
import kr.vici.ashreader.engine.mupdf.MupdfDoc
import kr.vici.ashreader.office.DocExtractor
import kr.vici.ashreader.office.DocxConverter
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
}

class OpenFailed(msg: String) : Exception(msg)

/** 번들 글꼴(assets/fonts) → 내부 저장소 복사. 엔진은 파일 경로로 글꼴을 읽는다. */
object BundledFonts {
    /** 본문 글꼴 파일. 리디바탕·마루부리는 라이선스 원문 확인 전이라 아직 번들하지 않음(TODO C1) → 나눔명조로 대체. */
    private val files = mapOf(
        BodyFont.NANUM_MYEONGJO to "NanumMyeongjo-Regular.ttf",
        BodyFont.RIDI_BATANG to "RIDIBatang.otf",
        BodyFont.MARUBURI to "MaruBuri-Regular.ttf",
    )

    @Volatile private var installed = false

    fun dir(context: Context): File = File(context.filesDir, "fonts")

    /** 번들 글꼴 리소스 → 엔진이 찾는 파일 이름. */
    private val bundled = listOf(
        R.font.nanum_myeongjo_regular to "NanumMyeongjo-Regular.ttf",
        R.font.nanum_myeongjo_bold to "NanumMyeongjo-Bold.ttf",
        R.font.nanum_gothic_regular to "NanumGothic-Regular.ttf",
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

    fun available(context: Context, f: BodyFont): Boolean = File(dir(context), files.getValue(f)).exists()
}

object DocOpener {
    /**
     * [file]을 연다. 이북류는 [pageWdp]×[pageHdp] 쪽 크기(dp=pt)로 다시 배치한다.
     * 무거운 작업이므로 백그라운드에서 부른다.
     */
    fun open(context: Context, file: File, format: DocFormat, pageWdp: Float, pageHdp: Float, s: Settings): ReaderDoc {
        BundledFonts.install(context)
        return when (format.engine) {
            DocFormat.Engine.MUPDF -> MupdfReaderDoc(file, format, pageWdp, pageHdp, s, context)
            DocFormat.Engine.HWP -> HwpReaderDoc(file, format)
            DocFormat.Engine.COMIC -> ComicReaderDoc(file, format)
            DocFormat.Engine.OFFICE -> {
                val x = OfficeXhtml.convert(context, file, format)
                MupdfReaderDoc(x, format, pageWdp, pageHdp, s, context)
            }
            DocFormat.Engine.NONE -> throw OpenFailed("이 형식은 아직 열 수 없어요.")
        }
    }

    fun css(context: Context, s: Settings): String {
        val font = BundledFonts.file(context, s.font).absolutePath
        return """
            @font-face { font-family: ashbody; src: url("$font"); }
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
) : ReaderDoc {
    private val lock = Any()
    private val doc: MupdfDoc = try {
        MupdfDoc.open(
            file.absolutePath,
            if (format.reflowable) MupdfDoc.LayoutSpec(pageWdp, pageHdp, s.fontSize.toFloat()) else null,
            if (format.reflowable) DocOpener.css(context, s) else null,
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

    override fun close() = synchronized(lock) { doc.close() }
}

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

    /** 그림 원본 비트맵(폭 [w] 근처로 줄임). 만화 그림은 테마 색을 입히지 않는다. */
    fun pageBitmap(index: Int, w: Int): Bitmap {
        val bytes = synchronized(lock) { arc.readPage(index) }
        return PageDecoder.decodeFit(bytes, w)
    }

    override fun render(index: Int, w: Int, h: Int, colors: ReadingColors): Bitmap =
        fitInto(pageBitmap(index, w), w, h, colors, applyTheme = false)

    /** 칸 인식(F-72). 결과 좌표는 [pageBitmap] 과 같은 비율의 원본 기준(width/height 함께 줌). */
    fun panels(index: Int, dir: ReadingDirection): PagePanels = synchronized(panelCache) {
        panelCache.getOrPut(index) {
            val b = pageBitmap(index, 1200)
            try { detector.detect(PageDecoder.toGray(b), dir) } finally { b.recycle() }
        }
    }

    override fun close() = synchronized(lock) { arc.close() }
}

/** DOCX·DOC → XHTML(이북식 보기). 그림은 같은 폴더에 쓴다. */
object OfficeXhtml {
    fun convert(context: Context, file: File, format: DocFormat): File {
        val bytes = file.readBytes()
        val dir = File(context.cacheDir, "office/" + file.nameWithoutExtension).apply { mkdirs() }
        val xhtml = try {
            if (format == DocFormat.DOCX) {
                val r = DocxConverter(DocxConverter.Options(simplified = true)).convert(bytes)
                for ((name, data) in r.images) File(dir, name).apply { parentFile?.mkdirs() }.writeBytes(data)
                r.xhtml
            } else {
                DocExtractor.convert(bytes).xhtml
            }
        } catch (e: Exception) {
            // TODO(S-42): 암호 DOCX 는 암호 입력 창 → AgileDecryptor. 지금은 안내만.
            throw OpenFailed("Word 문서를 열 수 없어요. 암호가 걸렸거나 손상된 파일일 수 있어요.")
        }
        return File(dir, "doc.xhtml").apply { writeText(xhtml) }
    }
}
