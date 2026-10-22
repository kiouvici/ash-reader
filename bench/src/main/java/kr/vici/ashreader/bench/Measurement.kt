// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.bench

import androidx.compose.runtime.mutableStateListOf

/** 측정값 한 줄. CSV 한 행이 된다. */
data class Measurement(
    val experiment: String,
    val item: String,
    val metric: String,
    val value: Double,
    val unit: String,
    val note: String = "",
    val timestampMs: Long = System.currentTimeMillis(),
)

/** 측정 결과 보관 (앱이 살아 있는 동안). */
object ResultStore {
    val results = mutableStateListOf<Measurement>()

    fun addAll(items: List<Measurement>) {
        results.addAll(items)
    }

    fun clear() = results.clear()
}

object Stats {
    /** 중앙값 (짝수 개면 가운데 두 값의 평균). 측정 규칙 9.0: 10회 중앙값으로 판정, 최댓값도 보고. */
    fun median(values: List<Double>): Double {
        require(values.isNotEmpty()) { "값이 없습니다" }
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }

    /** 중앙값·최댓값 요약 행 두 개 */
    fun summarize(experiment: String, metric: String, unit: String, values: List<Double>): List<Measurement> {
        if (values.isEmpty()) return emptyList()
        val note = "n=${values.size}"
        return listOf(
            Measurement(experiment, "요약", "$metric 중앙값", median(values), unit, note),
            Measurement(experiment, "요약", "$metric 최댓값", values.max(), unit, note),
        )
    }
}
