// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.hwp

import android.graphics.Bitmap
import java.io.Closeable
import java.io.File

internal object HwpNative {
    init { System.loadLibrary("ash_hwp") }
    @JvmStatic external fun nativeInitFonts(dirs: String)
    @JvmStatic external fun nativeOpen(path: String): Long
    @JvmStatic external fun nativeLastError(): String
    @JvmStatic external fun nativePageCount(h: Long): Int
    @JvmStatic external fun nativePageSizePx96(h: Long, i: Int): IntArray
    @JvmStatic external fun nativeRenderPage(h: Long, i: Int, bitmap: Bitmap): Int
    @JvmStatic external fun nativePageTextChars(h: Long, i: Int): Int
    @JvmStatic external fun nativeToXhtml(h: Long, dir: String, title: String): String?
    @JvmStatic external fun nativeImageRects(h: Long, i: Int): FloatArray?
    @JvmStatic external fun nativeClose(h: Long)
}

class HwpException(msg: String) : Exception(msg)

/**
 * HWP 5.0·HWPX 원본 보기 (rhwp v0.8.7 + resvg). :bench 용 단순 API.
 *
 *     HwpDoc.installFonts(listOf(bundleFontDir))
 *     HwpDoc.open(path).use { d -> d.pageCount(); d.renderPage(0, 1080) }
 */
class HwpDoc private constructor(private var handle: Long) : Closeable {

    fun pageCount(): Int = HwpNative.nativePageCount(handle).also { if (it < 0) throw HwpException("쪽 수를 읽지 못했어요: ${HwpNative.nativeLastError()}") }

    /** 쪽을 폭 [widthPx]로 렌더(높이는 쪽 비율). */
    fun renderPage(i: Int, widthPx: Int): Bitmap {
        val (w96, h96) = HwpNative.nativePageSizePx96(handle, i).let { it[0] to it[1] }
        if (w96 <= 0) throw HwpException("쪽 크기 오류: ${HwpNative.nativeLastError()}")
        val h = (h96.toLong() * widthPx / w96).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
        val r = HwpNative.nativeRenderPage(handle, i, bmp)
        if (r != 0) throw HwpException("렌더 실패($r): ${HwpNative.nativeLastError()}")
        return bmp
    }

    /** 쪽 세로/가로 비율(F-22 스크롤 보기). 모르면 null. */
    fun pageAspect(i: Int): Float? {
        val (w96, h96) = HwpNative.nativePageSizePx96(handle, i).let { it[0] to it[1] }
        return if (w96 > 0 && h96 > 0) h96.toFloat() / w96 else null
    }

    /** 쪽 안 그림 영역(쪽 기준 0..1, l t r b 반복). 야간 '그림은 반전 안 함'(F-21)에 쓴다. 없으면 빈 배열. */
    fun imageRects(i: Int): FloatArray = HwpNative.nativeImageRects(handle, i) ?: FloatArray(0)

    /** 쪽 본문 글자 수(공백 제외). */
    fun pageTextChars(i: Int): Int = HwpNative.nativePageTextChars(handle, i)

    /**
     * 이북식 보기(F-28)용 XHTML. 제목·문단·표·그림·각주를 옮기고, 그림은 [dir]/images 에 쓴다.
     * 결과 XHTML 은 [dir] 기준 상대 경로로 그림을 가리킨다.
     */
    fun toXhtml(dir: File, title: String): String {
        dir.mkdirs()
        return HwpNative.nativeToXhtml(handle, dir.absolutePath, title)
            ?: throw HwpException("이북식 변환 실패: ${HwpNative.nativeLastError()}")
    }

    override fun close() {
        if (handle != 0L) { HwpNative.nativeClose(handle); handle = 0L }
    }

    companion object {
        /** 번들 글꼴 폴더 + 기기 CJK 글꼴 폴더(/system/fonts)를 등록한다. */
        fun installFonts(dirs: List<File>) {
            HwpNative.nativeInitFonts((dirs.map { it.absolutePath } + "/system/fonts").joinToString(":"))
        }

        fun open(path: String): HwpDoc {
            val h = HwpNative.nativeOpen(path)
            if (h == 0L) throw HwpException(HwpNative.nativeLastError())
            return HwpDoc(h)
        }
    }
}

/**
 * F-28 자동 전환·잘린 파일 판정 (기술 설계서 3.3).
 * 입력: 쪽별 잉크 비율(렌더 결과)과 쪽별 글자 수.
 */
object HwpHealth {
    const val BLANK_INK = 0.001

    /** 첫 쪽이 비었는데 그 쪽 글자는 있으면 → 원본 보기 실패로 보고 이북식 전환. */
    fun firstPageFailed(firstInk: Double, firstChars: Int): Boolean = firstInk < BLANK_INK && firstChars > 0

    /**
     * 잘린 파일 의심: 뒤쪽에서 연속으로 '그림도 없고 글자도 없는' 쪽이 [minRun]쪽 이상이고
     * 그 앞에는 내용 있는 쪽이 있으면 true. (rhwp는 일부 잘린 HWP를 오류 없이 열기 때문)
     */
    fun looksTruncated(inks: List<Double>, chars: List<Int>, minRun: Int = 2): Boolean {
        val n = minOf(inks.size, chars.size)
        if (n < minRun + 1) return false
        var run = 0
        for (i in n - 1 downTo 0) {
            if (inks[i] < BLANK_INK && chars[i] == 0) run++ else break
        }
        return run >= minRun && run < n
    }

    fun inkRatio(bmp: Bitmap): Double {
        val w = bmp.width; val h = bmp.height
        val mx = (w * 0.02).toInt(); val my = (h * 0.02).toInt()
        val row = IntArray(w)
        var ink = 0L; var total = 0L
        for (y in my until h - my step 2) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in mx until w - mx step 2) {
                val p = row[x]
                val d = 765 - ((p shr 16) and 255) - ((p shr 8) and 255) - (p and 255)
                total++; if (d > 60) ink++
            }
        }
        return if (total == 0L) 0.0 else ink.toDouble() / total
    }
}
