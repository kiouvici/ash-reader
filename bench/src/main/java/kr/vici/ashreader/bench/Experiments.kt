// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.bench

import android.content.Context
import android.os.SystemClock

/** 실험 하나. 시제품이 준비되면 run 을 채운다 (기술 설계서 9장 ①~⑤). */
interface Experiment {
    val id: String
    val title: String
    val description: String
    val ready: Boolean

    /** 측정 대신 별도 화면을 여는 실험 (화면 → 결과는 ResultStore 로) */
    val screens: List<Pair<String, Class<*>>> get() = emptyList()

    suspend fun run(context: Context, files: List<TestFile>): List<Measurement>
}

private class Placeholder(
    override val id: String,
    override val title: String,
    override val description: String,
) : Experiment {
    override val ready = false
    override suspend fun run(context: Context, files: List<TestFile>): List<Measurement> = emptyList()
}

/** ⓪ 점검: 시험 파일을 끝까지 읽는 시간. 표·CSV 경로와 파일 접근(assets·SAF)이 되는지 확인용. */
private object ReadCheck : Experiment {
    override val id = "0"
    override val title = "⓪ 시험 파일 읽기 (점검)"
    override val description = "assets·고른 폴더의 파일을 끝까지 읽어 시간 측정"
    override val ready = true

    override suspend fun run(context: Context, files: List<TestFile>): List<Measurement> {
        val buf = ByteArray(256 * 1024)
        val rows = mutableListOf<Measurement>()
        val times = mutableListOf<Double>()
        for (f in files) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val result = runCatching {
                var total = 0L
                f.open(context).use { s ->
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        total += n
                    }
                }
                total
            }
            val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            result.onSuccess { bytes ->
                times += ms
                rows += Measurement(title, f.name, "읽기 시간", ms, "ms", "${f.origin}, ${bytes}B")
            }.onFailure { e ->
                rows += Measurement(title, f.name, "읽기 실패", Double.NaN, "", "${f.origin}, ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return rows + Stats.summarize(title, "읽기 시간", "ms", times)
    }
}

object Experiments {
    val all: List<Experiment> = listOf(
        ReadCheck,
        HwpRenderExperiment,
        OfficeExperiment,
        CurlExperiment,
        LineBreakExperiment,
        Placeholder("5", "⑤ 만화 칸 인식·AI 화질", "칸 인식 1초·화질 향상 5초(N-10), 발열"),
    )
}

/** ③ 책 넘김: 화면 두 개 (넘김 시연·자동 측정, 탭 대기 체감) */
private object CurlExperiment : Experiment {
    override val id = "3"
    override val title = "③ 책 넘김 효과"
    override val description = "합성 쪽 넘김 시연, GL 스레드 지연 프레임 자동 20회(N-06), 탭 대기 200/250/300ms"
    override val ready = true
    override val screens: List<Pair<String, Class<*>>> = listOf(
        "넘김" to CurlBenchActivity::class.java,
        "탭 대기" to TapWaitActivity::class.java,
    )

    override suspend fun run(context: Context, files: List<TestFile>): List<Measurement> = emptyList()
}
