// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.comic

import android.content.res.AssetManager
import android.graphics.Bitmap

/** libash_comic.so JNI 선언 (libarchive + ncnn/Real-CUGAN). */
internal object NativeComic {
    init { System.loadLibrary("ash_comic") }

    fun interface Progress { fun onEntry(index: Int) }

    /** [이름 바이트0, 크기(ASCII)0, …]. 이름은 압축 안 원래 바이트(UTF-8 또는 CP949). */
    @JvmStatic external fun archiveList(path: String): Array<ByteArray>
    /** [index] = archiveList 순서의 일반 파일 번호. */
    @JvmStatic external fun archiveRead(path: String, index: Int, maxBytes: Long): ByteArray
    @JvmStatic external fun archiveExtractAll(path: String, dir: String, maxTotal: Long, progress: Progress?): Int

    @JvmStatic external fun vulkanInfo(): String
    @JvmStatic external fun upscalerCreate(am: AssetManager, param: String, bin: String, useGpu: Boolean, threads: Int, tile: Int): Long
    @JvmStatic external fun upscalerIsGpu(handle: Long): Boolean
    @JvmStatic external fun upscalerRun(handle: Long, src: Bitmap, dst: Bitmap)
    @JvmStatic external fun upscalerDestroy(handle: Long)
}
