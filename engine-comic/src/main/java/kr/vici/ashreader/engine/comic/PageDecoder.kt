// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.comic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build

/** 쪽 이미지 디코딩. 한 변이 4096px 를 넘으면 타일(BitmapRegionDecoder)로 나눠 필요한 영역만 디코딩한다. */
object PageDecoder {
    const val TILE_LIMIT = 4096

    fun bounds(bytes: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return o.outWidth to o.outHeight
    }

    fun needsTiles(w: Int, h: Int) = maxOf(w, h) > TILE_LIMIT

    /** 스트림에서 머리만 읽어 크기(전체를 읽지 않음). 모르면 null. */
    fun bounds(stream: java.io.InputStream): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(stream, null, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    /** 영역 디코더(F-53 큰 그림 타일). 호출한 쪽이 recycle 한다. */
    fun regionDecoder(bytes: ByteArray): BitmapRegionDecoder =
        (if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)) ?: error("디코딩 실패")

    /** 목표 폭에 맞춰 inSampleSize 로 줄여 전체 쪽을 디코딩 (화면 표시·칸 인식용). */
    fun decodeFit(bytes: ByteArray, targetW: Int): Bitmap {
        val (w, _) = bounds(bytes)
        var s = 1
        while (w / (s * 2) >= targetW) s *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) ?: error("디코딩 실패")
    }

    /** 원본 좌표 [rect] 영역만 디코딩 (큰 이미지 확대 시 타일). */
    fun decodeRegion(bytes: ByteArray, rect: Rect, sample: Int = 1): Bitmap {
        // newInstance(bytes, off, len) 는 API 31. 그 아래는 (isShareable) 판을 쓴다(minSdk 26).
        val d = (if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)) ?: error("디코딩 실패")
        try {
            val o = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            return d.decodeRegion(rect, o)
        } finally { d.recycle() }
    }

    /** 타일 격자 (한 변 [tile] px). */
    fun tiles(w: Int, h: Int, tile: Int = 2048): List<Rect> =
        (0 until h step tile).flatMap { y -> (0 until w step tile).map { x -> Rect(x, y, minOf(x + tile, w), minOf(y + tile, h)) } }

    /** Bitmap → 회색조(칸 인식 입력). */
    fun toGray(b: Bitmap): GrayImage {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return GrayImage.fromArgb(b.width, b.height, px)
    }
}
