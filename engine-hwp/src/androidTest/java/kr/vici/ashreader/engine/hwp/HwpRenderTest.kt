// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.hwp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 실험 ① (기술 설계서 9장): 에뮬레이터에서 rhwp(.so) 렌더 → 데스크톱 기준 이미지(같은 rhwp v0.8.7 + resvg, 나눔 글꼴)와 비교.
 * 합격(에뮬레이터 기능 검증): 쪽 수 = 데스크톱 값, 앞 3쪽 흑백 불일치율 ≤ 3%, F-28 오전환 0, 손상 파일 예외(앱 종료 없음).
 */
@RunWith(AndroidJUnit4::class)
class HwpRenderTest {
    companion object {
        private const val TAG = "ASH-HWP"
        // 데스크톱(rhwp CLI v0.8.7) 쪽 수
        private val EXPECTED = linkedMapOf(
            "gov-02-가족관계규칙-별표1.hwpx" to 27,
            "gov-03-한글맞춤법-고시2017-12.hwp" to 40,
            "gov-04-가족관계규칙-별표2.hwp" to 4,
        )
        private lateinit var work: File

        @BeforeClass @JvmStatic
        fun setUp() {
            val ins = InstrumentationRegistry.getInstrumentation()
            work = File(ins.targetContext.cacheDir, "hwp").apply { mkdirs() }
            val fonts = File(work, "fonts").apply { mkdirs() }
            for (n in listOf("NanumMyeongjo-Regular.ttf", "NanumMyeongjo-Bold.ttf", "NanumGothic-Regular.ttf", "NanumGothic-Bold.ttf"))
                copyAsset(n, File(fonts, n))
            HwpDoc.installFonts(listOf(fonts))
        }

        private fun copyAsset(name: String, to: File) {
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { i -> to.outputStream().use { i.copyTo(it) } }
        }

        private fun ref(prefix: String, page: Int): Bitmap? = runCatching {
            InstrumentationRegistry.getInstrumentation().context.assets.open("ref/$prefix-p$page.png").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()

        /** 흑백(문턱 160) 불일치 픽셀 비율. 기준 크기로 맞춘다. */
        private fun bwDiff(a: Bitmap, b0: Bitmap): Double {
            val b = Bitmap.createScaledBitmap(b0, a.width, a.height, true)
            var diff = 0L; val n = a.width.toLong() * a.height
            val ra = IntArray(a.width); val rb = IntArray(a.width)
            for (y in 0 until a.height) {
                a.getPixels(ra, 0, a.width, 0, y, a.width, 1); b.getPixels(rb, 0, a.width, 0, y, a.width, 1)
                for (x in 0 until a.width) {
                    val la = ((ra[x] shr 16 and 255) + (ra[x] shr 8 and 255) + (ra[x] and 255)) / 3 < 160
                    val lb = ((rb[x] shr 16 and 255) + (rb[x] shr 8 and 255) + (rb[x] and 255)) / 3 < 160
                    if (la != lb) diff++
                }
            }
            return diff.toDouble() / n
        }
    }

    @Test
    fun publicFilesMatchDesktopReference() {
        Log.i(TAG, "device: API ${Build.VERSION.SDK_INT}, ABI ${Build.SUPPORTED_ABIS.joinToString()}")
        var worst = 0.0
        for ((name, expected) in EXPECTED) {
            val f = File(work, name); copyAsset(name, f)
            val prefix = name.split("-").take(2).joinToString("-")
            val t0 = SystemClock.elapsedRealtimeNanos()
            HwpDoc.open(f.absolutePath).use { d ->
                val tOpen = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                val pages = d.pageCount()
                val t1 = SystemClock.elapsedRealtimeNanos()
                val first = d.renderPage(0, 1080)
                val tFirst = (SystemClock.elapsedRealtimeNanos() - t1) / 1e6
                val firstInk = HwpHealth.inkRatio(first)
                val f28 = HwpHealth.firstPageFailed(firstInk, d.pageTextChars(0))
                Log.i(TAG, "ASH-HWP|$prefix|pages=$pages/$expected|open_ms=${"%.0f".format(tOpen)}|first_render_ms=${"%.0f".format(tFirst)}|ink=${"%.4f".format(firstInk)}|f28=$f28")
                assertEquals("$name 쪽 수", expected, pages)
                assertFalse("$name F-28 오전환", f28)
                for (p in 1..3) {
                    val r = ref(prefix, p) ?: continue
                    val bmp = d.renderPage(p - 1, r.width)
                    val dr = bwDiff(r, bmp)
                    worst = maxOf(worst, dr)
                    Log.i(TAG, "ASH-HWP|$prefix|p$p|bwdiff=${"%.4f".format(dr)}")
                    File(work, "$prefix-p$p.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
        assertTrue("기준 이미지 대비 불일치율 최대 ${"%.4f".format(worst)} > 0.03", worst <= 0.03)
    }

    @Test
    fun brokenFilesThrowWithoutCrash() {
        val src = File(work, "gov-03.hwp"); copyAsset("gov-03-한글맞춤법-고시2017-12.hwp", src)
        val bytes = src.readBytes()
        val cases = mapOf(
            "garbage.hwp" to "not a hwp".toByteArray(),
            "trunc70.hwp" to bytes.copyOf(bytes.size * 7 / 10),
            "empty.hwp" to ByteArray(0),
        )
        for ((n, b) in cases) {
            val f = File(work, n); f.writeBytes(b)
            val r = runCatching { HwpDoc.open(f.absolutePath).use { it.pageCount() } }
            Log.i(TAG, "ASH-HWP|broken|$n|${r.exceptionOrNull()?.message ?: "opened ${r.getOrNull()} pages"}")
            assertTrue("$n 은 예외로 거부되어야 함", r.isFailure)
        }
    }

    @Test
    fun truncationHeuristic() {
        // 뒤쪽에 그림·글자 없는 빈 쪽이 2쪽 이상 연속이면 잘린 파일 의심
        assertTrue(HwpHealth.looksTruncated(listOf(0.1, 0.1, 0.0, 0.0), listOf(300, 200, 0, 0)))
        assertFalse(HwpHealth.looksTruncated(listOf(0.1, 0.1, 0.0), listOf(300, 200, 0)))   // 마지막 1쪽 빈 쪽은 정상
        assertFalse(HwpHealth.looksTruncated(listOf(0.0, 0.0, 0.0), listOf(0, 0, 0)))       // 전부 빈 문서는 별도(F-28)
        assertFalse(HwpHealth.looksTruncated(listOf(0.1, 0.0, 0.1), listOf(10, 0, 10)))     // 중간 빈 쪽은 정상
        assertTrue(HwpHealth.firstPageFailed(0.0, 50))
        assertFalse(HwpHealth.firstPageFailed(0.0, 0))
    }
}
