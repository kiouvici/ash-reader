// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.comic

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.os.SystemClock
import java.io.Closeable

/**
 * AI 화질 향상 (F-73): ncnn + Real-CUGAN 2x. Vulkan 이 있으면 GPU, 없거나 [preferGpu]=false 면 CPU.
 * 모델은 assets/realcugan/ (fetch-third-party.sh 가 SHA-256 확인 후 넣음).
 */
class Upscaler(
    assets: AssetManager,
    val model: Model = Model.SE_CONSERVATIVE,
    preferGpu: Boolean = true,
    threads: Int = 0,
    tile: Int = 200,
) : Closeable {
    enum class Model(val param: String, val bin: String) {
        SE_CONSERVATIVE("realcugan/se-up2x-conservative.param", "realcugan/se-up2x-conservative.bin"),
        NOSE("realcugan/nose-up2x-no-denoise.param", "realcugan/nose-up2x-no-denoise.bin"),
    }

    data class Result(val bitmap: Bitmap, val millis: Double, val gpu: Boolean)

    private var handle = NativeComic.upscalerCreate(assets, model.param, model.bin, preferGpu, threads, tile)
    val isGpu: Boolean get() = NativeComic.upscalerIsGpu(handle)

    /** 쪽 하나 2배. 처리 시간(디코딩 제외, N-10)을 함께 돌려준다. */
    fun upscale(src: Bitmap): Result {
        val s = if (src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, false)
        val dst = Bitmap.createBitmap(s.width * 2, s.height * 2, Bitmap.Config.ARGB_8888)
        val t0 = SystemClock.elapsedRealtimeNanos()
        NativeComic.upscalerRun(handle, s, dst)
        return Result(dst, (SystemClock.elapsedRealtimeNanos() - t0) / 1e6, isGpu)
    }

    override fun close() { if (handle != 0L) { NativeComic.upscalerDestroy(handle); handle = 0 } }

    companion object {
        /** Vulkan 정보 (장치|API 버전|드라이버). 없으면 null. */
        fun vulkanInfo(): String? = NativeComic.vulkanInfo().ifEmpty { null }

        /** F-73: 쪽의 짧은 변이 화면 짧은 변보다 작을 때만 처리. */
        fun shouldUpscale(pageW: Int, pageH: Int, screenW: Int, screenH: Int) = minOf(pageW, pageH) < minOf(screenW, screenH)
    }
}
