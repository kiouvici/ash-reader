// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.bench

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale
import kr.vici.ashreader.pagecurl.CurlConfig
import kr.vici.ashreader.pagecurl.FrameReport
import kr.vici.ashreader.pagecurl.PageCurlView
import kr.vici.ashreader.pagecurl.SpreadMode
import kr.vici.ashreader.pagecurl.TurnDirection
import kr.vici.ashreader.pagecurl.TurnEffect

/** 실험 ③: 넘김 시연 (a) + GL 스레드 지연 프레임 자동 측정 (b). 탭 대기 시험 (c) 은 TapWaitActivity. */
class CurlBenchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { CurlBenchScreen() } } }
    }

    companion object {
        /** 계측 시험이 마지막 측정 결과를 확인하는 곳 */
        @Volatile var lastReport: FrameReport? = null
        @Volatile var lastPage: Int = -1
    }
}

private enum class Theme(val label: String, val paper: Int, val ink: Int, val shadow: Float) {
    DAY("주간", Color.WHITE, Color.rgb(0x1F, 0x1F, 0x1F), 1f),
    SEPIA("세피아", Color.parseColor("#F4ECD8"), Color.parseColor("#4A3A2C"), 1f),
    NIGHT("야간", Color.parseColor("#121212"), Color.parseColor("#CFCFCF"), 0.5f),
}

@Composable
private fun CurlBenchScreen() {
    val context = LocalContext.current
    var kind by remember { mutableStateOf(SyntheticPages.Kind.TEXT) }
    var theme by remember { mutableStateOf(Theme.DAY) }
    var dir by remember { mutableStateOf(TurnDirection.LTR) }
    var spread by remember { mutableStateOf(SpreadMode.AUTO) }
    var effect by remember { mutableStateOf(TurnEffect.CURL) }
    var page by remember { mutableIntStateOf(0) }
    var spreadNow by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("밀거나 화면 가장자리를 눌러 넘기세요") }
    var measuring by remember { mutableStateOf(false) }
    val view = remember { PageCurlView(context) }
    val animating by view.isAnimating.collectAsState()

    DisposableEffect(Unit) { onDispose { view.release() } }
    LaunchedEffect(kind, theme) {
        val pages = if (kind == SyntheticPages.Kind.COMIC) SyntheticPages(kind) else SyntheticPages(kind, paper = theme.paper, ink = theme.ink)
        view.setPageSource(pages, 0)
    }
    LaunchedEffect(theme, dir, spread, effect) {
        view.setConfig(CurlConfig(direction = dir, effect = effect, spread = spread, backColor = theme.paper, shadowStrength = theme.shadow))
    }
    LaunchedEffect(Unit) {
        view.onPageChanged = { p, s -> page = p; spreadNow = s; CurlBenchActivity.lastPage = p }
        view.onTap = { x, _ ->
            val left = x < 0.3f
            val right = x > 0.7f
            val next = if (dir == TurnDirection.RTL) left else right
            val prev = if (dir == TurnDirection.RTL) right else left
            if (next) view.turn(true) else if (prev) view.turn(false)
        }
    }

    fun measure() {
        measuring = true
        status = "자동 넘김 20회 측정 중…"
        val label = "${kind.name.lowercase()} ${if (spreadNow) "펼침" else "한쪽"} ${dir.name} ${effect.name}"
        view.runAutoTurns(20, 1000) { r ->
            CurlBenchActivity.lastReport = r
            measuring = false
            val pct = r.lateRatio * 100
            status = String.format(Locale.ROOT, "지연 %.1f%% (%d/%d), %.0fHz, p95 %.1fms — %s",
                pct, r.lateFrames, r.frames, r.refreshHz, r.p95Ms, if (pct <= 5.0) "합격" else "불합격")
            val exp = "③ 책 넘김 효과"
            ResultStore.addAll(listOf(
                Measurement(exp, label, "GL 지연 프레임 비율(판정)", pct, "%", "${r.lateFrames}/${r.frames}, 기준 5% 이하"),
                Measurement(exp, label, "건너뛴 vsync 포함 비율(참고)", r.lateRatioWithMissed * 100, "%", "건너뜀 ${r.missedVsyncs}"),
                Measurement(exp, label, "프레임 지연 p50", r.p50Ms, "ms", String.format(Locale.ROOT, "주기 %.1fms", r.periodMs)),
                Measurement(exp, label, "프레임 지연 p95", r.p95Ms, "ms", ""),
                Measurement(exp, label, "프레임 지연 최댓값", r.maxMs, "ms", ""),
                Measurement(exp, label, "넘김 횟수", r.turns.toDouble(), "회", String.format(Locale.ROOT, "%.0fHz", r.refreshHz)),
            ))
        }
    }

    Column(Modifier.fillMaxSize().background(ComposeColor(theme.paper)).safeDrawingPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize().testTag("curl"))
            // 정보줄: GL 표면 밖 고정 층 (M-03). 넘김이 끝나면 값이 바뀐다.
            Text(
                text = "${SystemClockText.now()}   ${page + 1}${if (spreadNow) "–${page + 2}" else ""} / 40쪽" +
                    if (animating) "  ·" else "",
                color = ComposeColor(theme.ink).copy(alpha = 0.6f),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
            )
        }
        Column(Modifier.background(MaterialTheme.colorScheme.surface).padding(8.dp)) {
            Text(status, style = MaterialTheme.typography.bodySmall)
            ChipRow {
                Chip("글자 쪽", kind == SyntheticPages.Kind.TEXT) { kind = SyntheticPages.Kind.TEXT }
                Chip("만화 쪽", kind == SyntheticPages.Kind.COMIC) { kind = SyntheticPages.Kind.COMIC }
                Theme.entries.forEach { th -> Chip(th.label, theme == th) { theme = th } }
            }
            ChipRow {
                Chip("왼→오", dir == TurnDirection.LTR) { dir = TurnDirection.LTR }
                Chip("오→왼", dir == TurnDirection.RTL) { dir = TurnDirection.RTL }
                Chip("자동 펼침", spread == SpreadMode.AUTO) { spread = SpreadMode.AUTO }
                Chip("한 쪽", spread == SpreadMode.SINGLE) { spread = SpreadMode.SINGLE }
                Chip("두 쪽", spread == SpreadMode.SPREAD) { spread = SpreadMode.SPREAD }
            }
            ChipRow {
                Chip("종이책", effect == TurnEffect.CURL) { effect = TurnEffect.CURL }
                Chip("슬라이드", effect == TurnEffect.SLIDE) { effect = TurnEffect.SLIDE }
                Chip("없음", effect == TurnEffect.NONE) { effect = TurnEffect.NONE }
                Button(enabled = !measuring, onClick = { measure() }, modifier = Modifier.testTag("measure")) {
                    Text("자동 20회 측정")
                }
            }
        }
    }
}

private object SystemClockText {
    fun now(): String = java.text.SimpleDateFormat("HH:mm", Locale.ROOT).format(java.util.Date())
}

@Composable
internal fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
internal fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
