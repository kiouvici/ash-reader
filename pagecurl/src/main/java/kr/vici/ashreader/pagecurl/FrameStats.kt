// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.pagecurl

import kotlin.math.roundToInt

/**
 * N-06 판정값: GL 렌더 스레드에서 vsync 시각(Choreographer frameTimeNanos) 대비
 * eglSwapBuffers 완료가 한 프레임 주기를 넘은 프레임 비율 (기술 설계서 9.0).
 */
data class FrameReport(
    val refreshHz: Float,
    val frames: Int,
    val lateFrames: Int,
    /** 넘김 중 GL 스레드가 아예 그리지 못하고 건너뛴 vsync 수 (참고) */
    val missedVsyncs: Int,
    val turns: Int,
    val p50Ms: Double,
    val p95Ms: Double,
    val maxMs: Double,
    /** 넘김 중 실제로 그린 초당 프레임 (넘김 구간 합 기준) */
    val effectiveFps: Double = 0.0,
) {
    val periodMs: Double get() = 1000.0 / refreshHz
    /** 기존 판정값(v0.6.2): 늦은 프레임 / 그린 프레임. 건너뛴 vsync 를 못 본다(S26U 120Hz 실측에서 0% 였지만 실제 약 60fps). */
    val lateRatio: Double get() = if (frames == 0) 0.0 else lateFrames.toDouble() / frames
    /** 제안 판정값: 건너뛴 vsync 도 지연으로 셈 = 넘김 중 새 그림이 나오지 않은 vsync 비율 */
    val lateRatioWithMissed: Double
        get() = if (frames + missedVsyncs == 0) 0.0 else (lateFrames + missedVsyncs).toDouble() / (frames + missedVsyncs)
}

class FrameStatsRecorder {
    private val latencies = ArrayList<Long>(4096)
    private var periodNs = 16_666_667L
    private var refreshHz = 60f
    private var late = 0
    private var missed = 0
    private var lastVsync = 0L
    private var turns = 0
    private var turnStart = 0L
    private var activeNs = 0L
    @Volatile var recording = false
        private set

    @Synchronized
    fun start(refreshHz: Float) {
        this.refreshHz = refreshHz
        periodNs = (1_000_000_000.0 / refreshHz).toLong()
        latencies.clear()
        late = 0
        missed = 0
        lastVsync = 0L
        turns = 0
        turnStart = 0L
        activeNs = 0L
        recording = true
    }

    @Synchronized
    fun onFrame(vsyncNs: Long, swapDoneNs: Long, continuing: Boolean) {
        if (!recording) return
        val lat = swapDoneNs - vsyncNs
        latencies += lat
        if (lat > periodNs) late++
        if (lastVsync == 0L) turnStart = vsyncNs
        if (continuing && lastVsync != 0L) {
            val gap = vsyncNs - lastVsync
            val skipped = (gap.toDouble() / periodNs).roundToInt() - 1
            if (skipped > 0) missed += skipped
        }
        lastVsync = vsyncNs
    }

    /** 넘김 한 번이 끝났을 때. 다음 넘김의 첫 프레임은 건너뜀 계산에서 뺀다. */
    @Synchronized
    fun onTurnEnd() {
        if (!recording) return
        turns++
        if (lastVsync != 0L) activeNs += lastVsync - turnStart + periodNs
        lastVsync = 0L
    }

    @Synchronized
    fun stop(): FrameReport {
        recording = false
        val sorted = latencies.sorted()
        fun pct(p: Double): Double =
            if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * p).roundToInt()] / 1e6
        return FrameReport(
            refreshHz = refreshHz,
            frames = latencies.size,
            lateFrames = late,
            missedVsyncs = missed,
            turns = turns,
            p50Ms = pct(0.5),
            p95Ms = pct(0.95),
            maxMs = if (sorted.isEmpty()) 0.0 else sorted.last() / 1e6,
            effectiveFps = if (activeNs > 0) latencies.size / (activeNs / 1e9) else 0.0,
        )
    }
}
