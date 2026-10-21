// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.bench

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 실험 ③ (c): 칸 단위 보기 탭 규칙(화면 설계서 4.3) 체감 시험.
 * 칸 위 한 번 탭은 [waitMs] 동안 두 번째 탭을 기다렸다가 처리(누르는 즉시 테두리 강조),
 * 좌우 15% 는 즉시 넘김, 위 12% 띠 = 메뉴. 몰입 모드(상태 표시줄 숨김)에서 위 띠와 상태 표시줄 내리기 충돌 확인.
 */
class TapWaitActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { TapWaitScreen() } } }
    }
}

private data class Panel(val l: Float, val t: Float, val r: Float, val b: Float)

private val PANELS = listOf(
    Panel(0.05f, 0.14f, 0.95f, 0.40f),
    Panel(0.05f, 0.42f, 0.48f, 0.70f), Panel(0.52f, 0.42f, 0.95f, 0.70f),
    Panel(0.05f, 0.72f, 0.95f, 0.96f),
)

@Composable
private fun TapWaitScreen() {
    val scope = rememberCoroutineScope()
    var waitMs by remember { mutableLongStateOf(250L) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var pressed by remember { mutableIntStateOf(-1) }
    var zoomed by remember { mutableIntStateOf(-1) }
    var menu by remember { mutableStateOf(false) }
    var panel by remember { mutableIntStateOf(1) }
    var log by remember { mutableStateOf("칸을 누르거나 두 번 눌러 보세요. 대기 ${waitMs}ms") }
    var singles by remember { mutableIntStateOf(0) }
    var doubles by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<Job?>(null) }
    var lastUp by remember { mutableLongStateOf(0L) }
    var lastIdx by remember { mutableIntStateOf(-1) }
    val density = LocalDensity.current

    fun hit(o: Offset): Int {
        if (size.width == 0) return -1
        val x = o.x / size.width
        val y = o.y / size.height
        return PANELS.indexOfFirst { x in it.l..it.r && y in it.t..it.b }
    }

    fun onTap(o: Offset) {
        val x = o.x / size.width
        val y = o.y / size.height
        val now = SystemClock.uptimeMillis()
        when {
            y < 0.12f -> { menu = !menu; log = "위 띠: 메뉴 ${if (menu) "표시" else "숨김"} (즉시)" }
            x < 0.15f -> { panel = (panel - 1).coerceAtLeast(1); zoomed = -1; log = "왼쪽 가장자리: 이전 칸 (즉시)" }
            x > 0.85f -> { panel = (panel + 1).coerceAtMost(PANELS.size); zoomed = -1; log = "오른쪽 가장자리: 다음 칸 (즉시)" }
            else -> {
                val idx = hit(o)
                pressed = idx // 누름 반응은 즉시
                if (pending != null && now - lastUp <= waitMs && idx == lastIdx) {
                    pending?.cancel(); pending = null
                    doubles++
                    zoomed = -1
                    log = "두 번 탭: 그 지점 2배 확대 (간격 ${now - lastUp}ms)"
                    pressed = -1
                } else {
                    lastUp = now
                    lastIdx = idx
                    pending = scope.launch {
                        delay(waitMs)
                        pending = null
                        pressed = -1
                        singles++
                        if (idx >= 0) {
                            zoomed = if (zoomed == idx) -1 else idx
                            log = "한 번 탭: 칸 ${idx + 1} ${if (zoomed == idx) "확대" else "쪽 전체"} (${waitMs}ms 뒤 처리)"
                        } else {
                            log = "칸 밖 여백: 기존 탭 영역 동작"
                        }
                    }
                }
            }
        }
    }

    fun rate(label: String) {
        ResultStore.addAll(
            listOf(
                Measurement("③ 탭 대기 체감", "대기 ${waitMs}ms", "평가: $label", waitMs.toDouble(), "ms", "한 번 $singles, 두 번 $doubles"),
            ),
        )
        log = "기록했습니다: ${waitMs}ms = $label"
        singles = 0; doubles = 0
    }

    LaunchedEffect(waitMs) { log = "대기 ${waitMs}ms 로 바꿨습니다" }

    Box(
        Modifier.fillMaxSize().background(Color(0xFFF7F7F7))
            .onSizeChanged { size = it }
            .pointerInput(waitMs, size) { detectTapGestures(onTap = { onTap(it) }) },
    ) {
        if (size.width > 0) {
            val w = with(density) { size.width.toDp() }
            val h = with(density) { size.height.toDp() }
            PANELS.forEachIndexed { i, p ->
                val active = i == zoomed
                Box(
                    Modifier.offset(w * p.l, h * p.t).size(w * (p.r - p.l), h * (p.b - p.t))
                        .background(if (active) Color(0xFFB9D4F5) else Color(0xFFDDDDDD))
                        .border(
                            width = if (pressed == i) 4.dp else 2.dp,
                            color = if (pressed == i) Color(0xFF1F5FA8) else Color.Black,
                        ),
                    contentAlignment = Alignment.Center,
                ) { Text("칸 ${i + 1}${if (i + 1 == panel) " ◀" else ""}") }
            }
            // 영역 표시 (위 띠 12%, 좌우 15%)
            Box(Modifier.fillMaxWidth().size(w, h * 0.12f).background(Color(0x221F5FA8)))
            Box(Modifier.offset(0.dp, h * 0.12f).size(w * 0.15f, h * 0.88f).background(Color(0x14000000)))
            Box(Modifier.offset(w * 0.85f, h * 0.12f).size(w * 0.15f, h * 0.88f).background(Color(0x14000000)))
        }
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("위 띠 = 메뉴 · 좌우 15% = 이전/다음 칸 · 칸 = 확대", style = MaterialTheme.typography.labelSmall)
            Text(log, style = MaterialTheme.typography.bodySmall, color = Color(0xFF1F5FA8))
        }
        if (menu) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White).padding(8.dp),
            ) {
                ChipRow {
                    listOf(200L, 250L, 300L).forEach { v -> Chip("${v}ms", waitMs == v) { waitMs = v } }
                }
                ChipRow {
                    OutlinedButton(onClick = { rate("느림") }) { Text("느림") }
                    Button(onClick = { rate("적당") }) { Text("적당") }
                    OutlinedButton(onClick = { rate("두 번 탭이 잘 안 됨") }) { Text("두 번 탭 실패") }
                    OutlinedButton(onClick = { rate("위 띠·상태 표시줄 충돌") }) { Text("위 띠 충돌") }
                }
            }
        }
    }
}

