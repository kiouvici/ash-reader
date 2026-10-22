// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.djvu

/** libash_djvu.so JNI 선언 (DjVuLibre ddjvuapi). 한 문서 핸들은 한 번에 한 스레드만 쓴다(DjvuDoc 잠금). */
internal object NativeDjvu {
    init { System.loadLibrary("ash_djvu") }

    @JvmStatic external fun open(path: String): Long
    @JvmStatic external fun pageCount(h: Long): Int
    /** [폭, 높이, dpi, 회전] */
    @JvmStatic external fun pageInfo(h: Long, page: Int): IntArray
    /** [bitmap] = ARGB_8888 android.graphics.Bitmap (JVM 시험에서 쓰지 않게 Any 로 둔다) */
    @JvmStatic external fun render(h: Long, page: Int, bitmap: Any)
    /** [제목, 깊이, 쪽, 제목, 깊이, 쪽, …] */
    @JvmStatic external fun outline(h: Long): Array<String>
    @JvmStatic external fun pageLines(h: Long, page: Int): Array<String>
    @JvmStatic external fun close(h: Long)
}
