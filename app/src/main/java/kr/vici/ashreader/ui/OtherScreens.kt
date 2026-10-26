// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.vici.ashreader.AppInfo
import kr.vici.ashreader.Links
import kr.vici.ashreader.ash
import kr.vici.ashreader.data.Backup
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.InfoItem
import kr.vici.ashreader.data.Rotation
import kr.vici.ashreader.data.ScreenTimeout
import kr.vici.ashreader.data.TapLayout
import kr.vici.ashreader.engine.convert.TextEncoding
import kr.vici.ashreader.tts.Listen
import kr.vici.ashreader.data.Prefs
import kr.vici.ashreader.data.ReadingSession
import kr.vici.ashreader.data.Settings
import kr.vici.ashreader.data.progress
import kr.vici.ashreader.doc.ComicUpscale

@Composable
fun ScreenTitle(t: String, onBack: (() -> Unit)? = null) {
    Row(Modifier.padding(start = if (onBack == null) 20.dp else 4.dp, top = 12.dp, bottom = 8.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.Back, contentDescription = "뒤로") }
        // 애플식 제목(대표님 01:10): 맨 위 화면은 큰 제목(34, 아주 굵게, 글자색), 들어간 화면은 22 굵게 — 서재·설정과 같은 모양
        if (onBack == null) Text(t, fontWeight = FontWeight.Black, fontSize = LARGE_TITLE.sp, letterSpacing = (-0.02).em, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.semantics { heading() })
        else Text(t, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.semantics { heading() })
    }
}

fun formatDuration(ms: Long): String {
    val m = ms / 60000
    // 몇 초만 읽은 날이 '0분'인데 막대는 서 있어 헷갈리던 것(대표님 2026-10-26)
    if (ms in 1 until 60000) return "1분 미만"
    return if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"
}

/** S-50 독서 기록(F-68). 기기에만 저장. [sessions] = [statsSince] 이후 기록(주간·월간 모두 여기서 센다). */
@Composable
fun StatsScreen(sessions: List<ReadingSession>, streak: Int, books: List<Book>, timeByBook: Map<String, Long>) {
    val cs = MaterialTheme.colorScheme
    var monthly by rememberSaveable { mutableStateOf(false) }
    // 애플식(대표님 01:10): 설정과 같은 회색 바탕 + 흰 카드, 큰 제목, 구분 단추, 파란 막대, 책은 표지와 책 제목으로
    val dark = LocalAshDark.current
    Column(Modifier.fillMaxSize().background(if (dark) Color.Black else Color(0xFFF2F2F7)).verticalScroll(rememberScrollState())) {
        ScreenTitle("독서 기록")
        AppleSegmented(listOf("주간", "월간"), if (monthly) 1 else 0, { monthly = it == 1 }, Modifier.padding(horizontal = 20.dp).padding(bottom = 14.dp).widthIn(max = 280.dp))
        if (!monthly) WeekStats(sessions, streak, books) else MonthStats(sessions, streak, books)
        StatsHead("최근 읽은 책")
        books.filter { it.lastOpenedAt > 0 }.sortedByDescending { it.lastOpenedAt }.take(5).forEach { b ->
            val f = DocFormat.of(b.format)
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShelfCover(b, Modifier.size(36.dp, 50.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.shownTitle, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val st = if (b.status == Book.DONE) "다 읽음" else "${(b.progress() * 100).toInt()}%"
                    Text("${f.badge} · $st · ${formatDuration(timeByBook[b.id] ?: 0)}", fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
            }
        }
        Text("기록은 이 기기에만 저장돼요", fontSize = 12.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp))
        // 아래 내비게이션 밑까지 목록이 지나가므로 끝에 막대 높이만큼 비운다(대표님 2026-10-26)
        Spacer(Modifier.height(barBottom()))
    }
}

@Composable
private fun WeekStats(sessions: List<ReadingSession>, streak: Int, books: List<Book>) {
    val cs = MaterialTheme.colorScheme
    val from = weekStart()
    val week = sessions.filter { it.startedAt >= from }
    val total = week.sumOf { it.durationMs }
    val perDay = LongArray(7)
    val cal = Calendar.getInstance()
    for (s in week) {
        cal.timeInMillis = s.startedAt
        val idx = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // 월=0
        perDay[idx] += s.durationMs
    }
    val today = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7
    val doneThisWeek = books.count { it.status == Book.DONE && it.lastOpenedAt >= from }
    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard("이번 주", formatDuration(total), Modifier.weight(1.4f))
        StatCard("다 읽은 책", "${doneThisWeek}권", Modifier.weight(1f))
        StatCard("연속", "${streak}일", Modifier.weight(1f))
    }
    StatsHead("요일별 읽은 시간")
    Bars("월화수목금토일".map { it.toString() }, perDay, today, barWidth = 22, describe = { i -> "${"월화수목금토일"[i]}요일 ${formatDuration(perDay[i])}" })
    if (total == 0L) Text("이번 주에는 아직 기록이 없어요.", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
}

/** F-68 월간: 이번 달 합계·읽은 날·다 읽은 책, 날짜별 막대, 최근 6개월 막대. */
@Composable
private fun MonthStats(sessions: List<ReadingSession>, streak: Int, books: List<Book>) {
    val cs = MaterialTheme.colorScheme
    val now = Calendar.getInstance()
    val monthFrom = monthStart(0)
    val days = now.getActualMaximum(Calendar.DAY_OF_MONTH)
    val perDay = LongArray(days)
    val cal = Calendar.getInstance()
    for (s in sessions) if (s.startedAt >= monthFrom) {
        cal.timeInMillis = s.startedAt
        val d = cal.get(Calendar.DAY_OF_MONTH) - 1
        if (d in 0 until days) perDay[d] += s.durationMs
    }
    val total = perDay.sum()
    val readDays = perDay.count { it > 0 }
    val doneThisMonth = books.count { it.status == Book.DONE && it.lastOpenedAt >= monthFrom }
    val month = now.get(Calendar.MONTH) + 1
    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard("${month}월", formatDuration(total), Modifier.weight(1.4f))
        StatCard("읽은 날", "${readDays}일", Modifier.weight(1f))
        StatCard("다 읽은 책", "${doneThisMonth}권", Modifier.weight(1f))
    }
    StatsHead("날짜별 읽은 시간")
    val today = now.get(Calendar.DAY_OF_MONTH) - 1
    Bars((1..days).map { d -> if (d == 1 || d % 5 == 0) "$d" else "" }, perDay, today, barWidth = 6,
        describe = { i -> "${month}월 ${i + 1}일 ${formatDuration(perDay[i])}" })
    // 최근 6개월
    val months = LongArray(6)
    val labels = ArrayList<String>(6)
    for (k in 5 downTo 0) {
        val from = monthStart(k); val to = monthStart(k - 1)
        months[5 - k] = sessions.filter { it.startedAt in from until to }.sumOf { it.durationMs }
        labels += "${Calendar.getInstance().apply { timeInMillis = from }.get(Calendar.MONTH) + 1}월"
    }
    StatsHead("달별 읽은 시간 (최근 6개월)")
    Bars(labels, months, 5, barWidth = 22, describe = { i -> "${labels[i]} ${formatDuration(months[i])}" })
    Text("이번 달 하루 평균 ${formatDuration(if (readDays == 0) 0 else total / readDays)} (읽은 날 기준) · 연속 ${streak}일",
        fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 10.dp, end = 20.dp))
}

/** 막대 그래프. [highlight] = 강조할 칸(오늘·이번 달). */
@Composable
private fun Bars(labels: List<String>, values: LongArray, highlight: Int, barWidth: Int, describe: (Int) -> String) {
    val cs = MaterialTheme.colorScheme
    // 막대 높이의 바닥값 10분: 몇 초만 읽어도 막대가 꽉 차 보이지 않게(대표님 2026-10-26)
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(10 * 60_000L)
    Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        labels.forEachIndexed { i, d ->
            Column(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = describe(i) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Box(Modifier.width(barWidth.dp).fillMaxHeight(0.85f * values[i] / max + 0.01f).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(if (values[i] == 0L) cs.onSurfaceVariant.copy(alpha = 0.2f) else if (i == highlight) AppleBlue else AppleBlue.copy(alpha = 0.45f)))
                Text(d, fontSize = 12.sp, maxLines = 1, softWrap = false, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp).wrapContentWidth(unbounded = true))
            }
        }
    }
}

/** [monthsAgo] 달 전 1일 0시(0 = 이번 달, -1 = 다음 달). */
fun monthStart(monthsAgo: Int): Long = Calendar.getInstance().apply {
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    add(Calendar.MONTH, -monthsAgo)
}.timeInMillis

/** 독서 기록 화면이 읽어 둘 기록의 시작(주간·월간 모두 덮게: 이번 주 월요일과 5달 전 1일 중 이른 쪽). */
fun statsSince(): Long = minOf(weekStart(), monthStart(5))

fun weekStart(): Long = Calendar.getInstance().apply {
    firstDayOfWeek = Calendar.MONDAY
    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    val dark = LocalAshDark.current
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(if (dark) Color(0xFF1C1C1E) else Color.White).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

/** 독서 기록 묶음 제목(20 굵게, 서재 '모든 책'과 같은 모양). */
@Composable
private fun StatsHead(t: String) {
    Text(t, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 10.dp).semantics { heading() })
}

/** S-45 다음 권 확인창. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextVolumeSheet(done: Book, next: Book, onLibrary: () -> Unit, onOpenNext: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetBars()
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text("${kr.vici.ashreader.data.SeriesGroups.volumeLabel(done)}을 다 읽었어요", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("이어서 ${kr.vici.ashreader.data.SeriesGroups.volumeLabel(next)}을 열까요?", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onLibrary, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("서재로") }
                Button(onClick = onOpenNext, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("다음 권 열기") }
            }
        }
    }
}

/**
 * S-31 탭 영역 안내: 지금 적용되는 실제 배치(F-64: 좌우·L자·어디든·내 방식, 왼손잡이·오→왼 반영)를 3×3 칸으로 보여 준다.
 * [grid] = 칸별 뜻(행 우선 9개). 아무 곳이나 누르면 닫힘.
 */
@Composable
fun TapGuide(rtl: Boolean, grid: List<TapAction> = emptyList(), onChange: (() -> Unit)? = null, edge: Float = TapZones.EDGE, onOk: () -> Unit) {
    val prevLeft = !rtl
    // 칸 폭 = 실제 탭 영역(좌우 [edge], 가운데 나머지). 내 방식(3등분)은 edge = 1/3
    val colW = listOf(edge, 1f - 2 * edge, edge)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onOk)) {
        if (grid.size != 9) Row(Modifier.fillMaxSize()) {
            Zone(if (prevLeft) "이전 쪽" else "다음 쪽", Modifier.weight(colW[0]), Color(0x552E4A6B))
            Zone("메뉴", Modifier.weight(colW[1]), Color(0x338C5A2B))
            Zone(if (prevLeft) "다음 쪽" else "이전 쪽", Modifier.weight(colW[2]), Color(0x552E4A6B))
        } else Column(Modifier.fillMaxSize()) {
            for (row in 0 until 3) Row(Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0 until 3) {
                    val a = grid[row * 3 + col]
                    Zone(
                        when (a) { TapAction.PREV -> "이전 쪽"; TapAction.NEXT -> "다음 쪽"; TapAction.MENU -> "메뉴"; TapAction.NONE -> "" },
                        Modifier.weight(colW[col]), zoneColor(a),
                    )
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(20.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(20.dp),
        ) {
            Text("화면을 누르는 곳에 따라 쪽을 넘겨요", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "밀어서 넘길 수도 있어요. 볼륨 아래 버튼도 다음 쪽이에요(설정에서 끌 수 있어요). 왼쪽 가장자리를 위아래로 밀면 밝기가 바뀌어요.",
                fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onOk, modifier = Modifier.weight(1f).heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors()) { Text("알겠어요") }
                if (onChange != null) OutlinedButton(onClick = onChange, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("바꾸기 ▸") }
            }
        }
    }
}

@Composable
private fun Zone(label: String, modifier: Modifier, color: Color) {
    Box(modifier.fillMaxHeight().background(color), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** 바이트 → "300MB"·"1GB"·"1.5GB". */
internal fun mbLabel(bytes: Long): String {
    val mb = bytes / (1L shl 20)
    return when {
        mb < 1024 -> "${mb}MB"
        mb % 1024 == 0L -> "${mb / 1024}GB"
        else -> "%.1fGB".format(java.util.Locale.ROOT, mb / 1024.0)
    }
}
