// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import kr.vici.ashreader.AppInfo
import kr.vici.ashreader.Links
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.PanelMode
import kr.vici.ashreader.data.ReadingSession
import kr.vici.ashreader.data.Settings
import kr.vici.ashreader.data.progress

@Composable
fun ScreenTitle(t: String, onBack: (() -> Unit)? = null) {
    Row(Modifier.padding(start = if (onBack == null) 20.dp else 4.dp, top = 12.dp, bottom = 8.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.Back, contentDescription = "뒤로") }
        Text(t, fontFamily = Myeongjo, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
    }
}

fun formatDuration(ms: Long): String {
    val m = ms / 60000
    return if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"
}

/** S-50 독서 기록. 기기에만 저장. */
@Composable
fun StatsScreen(weekSessions: List<ReadingSession>, streak: Int, books: List<Book>, timeByBook: Map<String, Long>) {
    val cs = MaterialTheme.colorScheme
    val total = weekSessions.sumOf { it.durationMs }
    val perDay = LongArray(7)
    val cal = Calendar.getInstance()
    for (s in weekSessions) {
        cal.timeInMillis = s.startedAt
        val idx = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // 월=0
        perDay[idx] += s.durationMs
    }
    val max = (perDay.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val today = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7
    val doneThisWeek = books.count { it.status == Book.DONE && it.lastOpenedAt >= weekStart() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("독서 기록")
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("이번 주", formatDuration(total), Modifier.weight(1.4f))
            StatCard("다 읽은 책", "${doneThisWeek}권", Modifier.weight(1f))
            StatCard("연속", "${streak}일", Modifier.weight(1f))
        }
        Text("요일별 읽은 시간", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 10.dp))
        Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            "월화수목금토일".forEachIndexed { i, d ->
                Column(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = "${d}요일 ${formatDuration(perDay[i])}" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Box(Modifier.width(22.dp).fillMaxHeight(0.85f * perDay[i] / max + 0.01f).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(if (i == today) cs.secondary else cs.primary))
                    Text(d.toString(), fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Text("최근 읽은 책", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 6.dp))
        books.filter { it.lastOpenedAt > 0 }.sortedByDescending { it.lastOpenedAt }.take(5).forEach { b ->
            val f = DocFormat.of(b.format)
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp, 48.dp).clip(RoundedCornerShape(4.dp)).background(f.badgeColor()))
                Column(Modifier.weight(1f)) {
                    Text(b.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val st = if (b.status == Book.DONE) "다 읽음" else "${(b.progress() * 100).toInt()}%"
                    Text("${f.badge} · $st · ${formatDuration(timeByBook[b.id] ?: 0)}", fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
            }
        }
        Text("기록은 이 기기에만 저장돼요", fontSize = 12.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp))
    }
}

fun weekStart(): Long = Calendar.getInstance().apply {
    firstDayOfWeek = Calendar.MONDAY
    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

/** S-51 설정(전체). */
@Composable
fun AppSettingsScreen(s: Settings, onChange: ((Settings) -> Settings) -> Unit, onClearOnce: () -> Unit) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("설정")
        Group("읽기") {
            ValueRow("본문 글꼴", "나눔명조 · 리디바탕 · 마루부리", s.font.label.substringBefore(" (")) {
                onChange { it.copy(font = kr.vici.ashreader.data.BodyFont.entries[(it.font.ordinal + 1) % 3]) }
            }
            SwitchRow("종이 넘김 효과", "끄면 밀어서 넘기기", s.curl) { v -> onChange { it.copy(curl = v) } }
            SwitchRow("볼륨 버튼으로 넘기기", "", s.volumeKeys) { v -> onChange { it.copy(volumeKeys = v) } }
            ValueRow("탭 영역 안내 다시 보기", "다음에 책을 열 때 보여 줘요", "") { onChange { it.copy(tapGuideShown = false) }; info = "다음에 책을 열면 안내가 다시 나와요." }
        }
        Group("만화") {
            ValueRow("칸 단위 보기", "칸 누르면 확대 / 자동 순서", s.panelMode.label) {
                onChange { it.copy(panelMode = PanelMode.entries[(it.panelMode.ordinal + 1) % PanelMode.entries.size]) }
            }
            // TODO(F-73): Upscaler(Real-CUGAN) 연결. 지금은 설정값만 저장.
            SwitchRow("AI 화질 개선", "기기 안에서 처리 · 저화질 만화를 선명하게 · 배터리 더 씀 (준비 중)", s.aiUpscale) { v -> onChange { it.copy(aiUpscale = v) } }
        }
        Group("연결") {
            ValueRow("사전", "단어 길게 눌러 찾기", "말글사전") {
                info = if (Links.isInstalled(context, Links.MALGEUL)) "말글사전이 설치되어 있어요. 본문 글자 선택에서 '사전'을 누르면 바로 찾아요."
                else "말글사전이 설치되어 있지 않아요. 말글사전 APK를 설치하세요. 그동안은 다른 사전 앱을 고를 수 있어요."
            }
            ValueRow("구매한 책 열기", "서재 위 바로가기", "예스24") {
                if (!Links.launch(context, Links.YES24)) info = "예스24 eBook 앱이 설치되어 있지 않아요."
            }
        }
        Group("정보") {
            // TODO(S-53): 백업·복원 파일 내보내기
            ValueRow("백업·내보내기", "서재·책갈피·메모를 파일로 저장", "") { info = "백업은 다음 판에서 열려요." }
            SwitchRow("한 번만 보기 기록 지우기", "앱 닫으면 지워요", s.clearOnceOnExit) { v -> onChange { it.copy(clearOnceOnExit = v) }; if (v) onClearOnce() }
            // TODO(C1): 라이선스 고지 화면(S-52) — 고지 파일 정리 후 연결
            ValueRow("오픈소스 라이선스", "AGPL-3.0 · 사용한 라이브러리", "") {
                info = "ASH Reader는 AGPL-3.0-or-later로 배포돼요. MuPDF(AGPL), rhwp, Apache POI, Kumiko(AGPL), 나눔글꼴(OFL) 등을 사용해요."
            }
            ValueRow("버전", "", AppInfo.VERSION_LABEL) {}
        }
        Box(Modifier.height(24.dp))
    }
    info?.let {
        AlertDialog(onDismissRequest = { info = null }, text = { Text(it) }, confirmButton = { TextButton(onClick = { info = null }) { Text("확인") } })
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
    Column(Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) { content() }
}

@Composable
private fun ValueRow(name: String, sub: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp)
            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value.isNotEmpty()) Text(value, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun SwitchRow(name: String, sub: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onToggle(!on) }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp)
            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = onToggle)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
}

/** S-13 시리즈 안 화면. */
@Composable
fun SeriesScreen(key: String, vols: List<Book>, onBack: () -> Unit, onOpen: (Book) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val done = vols.count { it.status == Book.DONE }
    val next = vols.firstOrNull { it.status != Book.DONE }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle(key, onBack)
        Text("전 ${vols.size}권 · ${if (done > 0) "${vols.filter { it.status == Book.DONE }.maxOf { it.volume }}권까지 읽음" else "아직 안 읽음"}${next?.let { " · 다음은 ${it.volume}권" } ?: ""}",
            fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
        vols.forEach { b ->
            val state = when {
                b.status == Book.DONE -> "다 읽음"
                b == next -> "다음 차례"
                b.status == Book.READING -> "${(b.progress() * 100).toInt()}% 읽는 중"
                else -> "안 읽음"
            }
            Row(Modifier.fillMaxWidth().clickable { onOpen(b) }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Cover(b, Modifier.width(56.dp))
                Column(Modifier.weight(1f)) {
                    Text("${b.volume}권", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(state, fontSize = 12.sp, color = if (b == next) cs.secondary else cs.onSurfaceVariant)
                }
                Badge(DocFormat.of(b.format))
            }
        }
    }
}

/** S-45 다음 권 확인창. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextVolumeSheet(done: Book, next: Book, onLibrary: () -> Unit, onOpenNext: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text("${done.volume}권을 다 읽었어요", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("이어서 ${next.volume}권을 열까요?", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onLibrary, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("서재로") }
                Button(onClick = onOpenNext, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("${next.volume}권 열기") }
            }
        }
    }
}

/** S-31 탭 영역 안내(첫 실행). */
@Composable
fun TapGuide(rtl: Boolean, panelMode: Boolean, onOk: () -> Unit) {
    val prevLeft = !rtl
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onOk)) {
        Row(Modifier.fillMaxSize()) {
            Zone(if (prevLeft) "이전 쪽" else "다음 쪽", Modifier.weight(if (panelMode) 0.15f else 0.3f), Color(0x552E4A6B))
            Zone("메뉴", Modifier.weight(if (panelMode) 0.7f else 0.4f), Color(0x338C5A2B))
            Zone(if (prevLeft) "다음 쪽" else "이전 쪽", Modifier.weight(if (panelMode) 0.15f else 0.3f), Color(0x552E4A6B))
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(20.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(20.dp),
        ) {
            Text("이렇게 넘겨요", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                if (panelMode) "칸을 누르면 크게 보여요. 메뉴는 화면 맨 위를 누르세요. 양쪽 끝을 누르면 이전·다음 칸으로 가요."
                else "화면 양쪽을 누르거나 밀어서 넘기고, 가운데를 누르면 메뉴가 열려요. 오른쪽에서 왼쪽으로 읽는 만화는 좌우가 바뀌어요. 볼륨 아래 버튼도 다음 쪽이에요(설정에서 끌 수 있어요).",
                fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            Button(onClick = onOk, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors()) { Text("알겠어요") }
        }
    }
}

@Composable
private fun Zone(label: String, modifier: Modifier, color: Color) {
    Box(modifier.fillMaxHeight().background(color), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
