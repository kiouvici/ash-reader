// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.doc

/**
 * 문서 그림 AI 화질 향상(F-73 확장, D-021·작업 03)의 대상 고르기. 안드로이드 클래스를 쓰지 않는다(단위 시험).
 *
 * 대상: 화면에 원래 크기보다 '크게' 보이는 래스터 그림(JPEG·PNG·WebP·BMP) — 작은 그림·삽화.
 * 빼는 것: SVG(벡터라 이미 선명), GIF(움직이는 그림·색이 적은 그림), 아이콘처럼 아주 작은 그림(짧은 변 64px 미만),
 * 이미 충분히 큰 그림(짧은 변 1000px 초과 또는 화면에 원래 크기 이하로 보임), 색이 아주 적은 단순 그림(이미 선명).
 */
object EpubUpscaleRules {
    /** 아이콘으로 보고 건너뛰는 짧은 변(px). */
    const val MIN_SIDE = 64
    /** 이보다 짧은 변이 크면 이미 충분히 크다고 본다(px). */
    const val MAX_SIDE = 1000
    /** 화면에서 원래 크기의 이 배율 넘게 커져야 처리한다(조금 키운 정도는 차이가 없다). */
    const val MIN_ENLARGE = 1.15f
    /** 입력 화소 상한(2배 결과 메모리). */
    const val MAX_PIXELS = 4_000_000L
    /** 색 수가 이 이하이면 단순 그림(선·글자 그림, GIF 같은 팔레트 그림)으로 보고 건너뛴다. */
    const val FLAT_COLORS = 48

    /** 그림 파일 종류(EPUB 안 파일 이름과 크기로 맞춘다 — MuPDF 는 그림 형식을 알려 주지 않음). */
    enum class Kind { RASTER, GIF, SVG, UNKNOWN }

    /** 확장자로 종류. */
    fun kindOfName(name: String): Kind = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "webp", "bmp" -> Kind.RASTER
        "gif" -> Kind.GIF
        "svg", "svgz" -> Kind.SVG
        else -> Kind.UNKNOWN
    }

    /**
     * EPUB 안 그림 파일 목록([name], 화소 [w]×[h])으로 '크기 → 종류' 표를 만든다. 같은 크기에 래스터가 하나라도 있으면 래스터.
     * SVG 는 MuPDF 가 화소 크기를 따로 정하므로 표에 없다 — 표에 없는 크기는 [fallback](EPUB 은 [Kind.UNKNOWN] = 건너뜀).
     */
    class Index(files: List<Triple<String, Int, Int>>, private val fallback: Kind = Kind.UNKNOWN) {
        private val bySize = HashMap<Long, Kind>()

        init {
            for ((name, w, h) in files) {
                if (w <= 0 || h <= 0) continue
                val k = kindOfName(name)
                if (k != Kind.RASTER && k != Kind.GIF) continue
                for (key in listOf(key(w, h), key(h, w))) { // EXIF 회전으로 가로세로가 바뀐 그림도
                    val cur = bySize[key]
                    if (cur != Kind.RASTER) bySize[key] = k
                }
            }
        }

        private fun key(w: Int, h: Int) = (w.toLong() shl 32) or h.toLong()

        fun kindOf(w: Int, h: Int): Kind = bySize[key(w, h)] ?: fallback
    }

    /**
     * 처리 대상인가. [w]×[h] = 그림 원래 화소, [dispW]×[dispH] = 화면에 그려지는 화소, [kind] = 파일 종류.
     */
    fun eligible(w: Int, h: Int, dispW: Float, dispH: Float, kind: Kind, orientation: Int = 0): Boolean {
        if (kind != Kind.RASTER) return false
        if (orientation > 1) return false
        if (w <= 0 || h <= 0) return false
        val short = minOf(w, h)
        if (short < MIN_SIDE || short > MAX_SIDE) return false
        if (w.toLong() * h > MAX_PIXELS) return false
        val dispShort = minOf(dispW, dispH)
        return dispShort > short * MIN_ENLARGE
    }

    /** 단순 그림인가: 고른 화소 [samples] 의 서로 다른 색(알파 무시)이 [FLAT_COLORS] 이하. */
    fun isFlat(samples: IntArray): Boolean {
        val seen = HashSet<Int>()
        for (p in samples) {
            seen += p and 0xFFFFFF
            if (seen.size > FLAT_COLORS) return false
        }
        return true
    }
}
