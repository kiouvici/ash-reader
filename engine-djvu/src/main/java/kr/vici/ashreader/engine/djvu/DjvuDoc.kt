// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.djvu

import android.graphics.Bitmap
import java.io.Closeable
import java.io.IOException

/**
 * DjVu 문서(DjVuLibre 3.5.30, GPL-2.0-or-later). 쪽 그림·목차·숨은 글자층(OCR) 줄 글자.
 * 스레드 안전: 모든 호출은 [lock] 안에서. 닫은 뒤 부르면 [IOException].
 */
class DjvuDoc private constructor(private var h: Long) : Closeable {
    private val lock = Any()

    data class PageSize(val width: Int, val height: Int, val dpi: Int)
    data class OutlineItem(val title: String, val depth: Int, val page: Int)

    companion object {
        /** 연다. 엔진(.so)을 쓸 수 없는 기기(32비트 등)·손상 파일은 [IOException]. */
        @Throws(IOException::class)
        fun open(path: String): DjvuDoc {
            val h = try { NativeDjvu.open(path) } catch (e: UnsatisfiedLinkError) {
                throw IOException("이 기기에서는 DjVu 엔진을 쓸 수 없어요.")
            }
            if (h == 0L) throw IOException("DjVu 파일을 열 수 없어요.")
            return DjvuDoc(h)
        }

        /** 목차 배열(JNI) → 목록. 시험용으로 분리. */
        internal fun parseOutline(a: Array<String>): List<OutlineItem> =
            (0 until a.size / 3).map { i -> OutlineItem(a[i * 3], a[i * 3 + 1].toIntOrNull() ?: 0, a[i * 3 + 2].toIntOrNull() ?: -1) }
    }

    private fun handle(): Long = h.takeIf { it != 0L } ?: throw IOException("닫힌 문서")

    fun pageCount(): Int = synchronized(lock) { NativeDjvu.pageCount(handle()) }

    fun pageSize(page: Int): PageSize = synchronized(lock) {
        val a = NativeDjvu.pageInfo(handle(), page)
        PageSize(a[0].coerceAtLeast(1), a[1].coerceAtLeast(1), a[2])
    }

    /** 폭 [width]px 로 쪽 전체를 그린다(높이는 쪽 비율). 너무 큰 그림은 4천만 화소 안으로 줄인다. */
    fun renderPage(page: Int, width: Int): Bitmap = synchronized(lock) {
        val s = pageSize(page)
        var w = width.coerceAtLeast(1)
        var hh = (s.height.toLong() * w / s.width).toInt().coerceAtLeast(1)
        val px = w.toLong() * hh
        if (px > 40_000_000L) {
            val k = Math.sqrt(40_000_000.0 / px)
            w = (w * k).toInt().coerceAtLeast(1); hh = (hh * k).toInt().coerceAtLeast(1)
        }
        val bmp = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888)
        try { NativeDjvu.render(handle(), page, bmp) } catch (e: Throwable) { bmp.recycle(); throw e }
        bmp
    }

    fun outline(): List<OutlineItem> = synchronized(lock) { parseOutline(NativeDjvu.outline(handle())) }

    fun pageLines(page: Int): List<String> = synchronized(lock) { NativeDjvu.pageLines(handle(), page).map { it.trim() }.filter { it.isNotEmpty() } }

    override fun close() = synchronized(lock) {
        if (h != 0L) { NativeDjvu.close(h); h = 0L }
    }
}
