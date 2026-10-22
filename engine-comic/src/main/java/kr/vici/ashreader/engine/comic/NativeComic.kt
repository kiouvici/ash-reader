// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.comic

import android.content.res.AssetManager
import android.graphics.Bitmap

/** libash_comic.so JNI 선언 (libarchive + ncnn/Real-CUGAN). */
internal object NativeComic {
    init { System.loadLibrary("ash_comic") }

    fun interface Progress { fun onEntry(index: Int, name: String) }

    @JvmStatic external fun archiveList(path: String): Array<String>
    @JvmStatic external fun archiveRead(path: String, name: String, maxBytes: Long): ByteArray
    @JvmStatic external fun archiveExtractAll(path: String, dir: String, maxTotal: Long, progress: Progress?): Int

    @JvmStatic external fun vulkanInfo(): String
    @JvmStatic external fun upscalerCreate(am: AssetManager, param: String, bin: String, useGpu: Boolean, threads: Int, tile: Int): Long
    @JvmStatic external fun upscalerIsGpu(handle: Long): Boolean
    @JvmStatic external fun upscalerRun(handle: Long, src: Bitmap, dst: Bitmap)
    @JvmStatic external fun upscalerDestroy(handle: Long)
}
