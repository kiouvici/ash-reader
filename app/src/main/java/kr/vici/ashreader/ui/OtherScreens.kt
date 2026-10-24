// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
        Text(t, fontFamily = Myeongjo, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
    }
}

fun formatDuration(ms: Long): String {
    val m = ms / 60000
    return if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"
}

/** S-50 독서 기록(F-68). 기기에만 저장. [sessions] = [statsSince] 이후 기록(주간·월간 모두 여기서 센다). */
@Composable
fun StatsScreen(sessions: List<ReadingSession>, streak: Int, books: List<Book>, timeByBook: Map<String, Long>) {
    val cs = MaterialTheme.colorScheme
    var monthly by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("독서 기록")
        Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("주간", !monthly) { monthly = false }
            Pill("월간", monthly) { monthly = true }
        }
        if (!monthly) WeekStats(sessions, streak, books) else MonthStats(sessions, streak, books)
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
    Text("요일별 읽은 시간", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 10.dp))
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
    Text("날짜별 읽은 시간", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 10.dp))
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
    Text("달별 읽은 시간 (최근 6개월)", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 10.dp))
    Bars(labels, months, 5, barWidth = 22, describe = { i -> "${labels[i]} ${formatDuration(months[i])}" })
    Text("이번 달 하루 평균 ${formatDuration(if (readDays == 0) 0 else total / readDays)} (읽은 날 기준) · 연속 ${streak}일",
        fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 10.dp, end = 20.dp))
}

/** 막대 그래프. [highlight] = 강조할 칸(오늘·이번 달). */
@Composable
private fun Bars(labels: List<String>, values: LongArray, highlight: Int, barWidth: Int, describe: (Int) -> String) {
    val cs = MaterialTheme.colorScheme
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        labels.forEachIndexed { i, d ->
            Column(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = describe(i) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Box(Modifier.width(barWidth.dp).fillMaxHeight(0.85f * values[i] / max + 0.01f).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(if (i == highlight) cs.secondary else cs.primary))
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
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

/** S-51 설정(전체). */
@Composable
fun AppSettingsScreen(
    s: Settings, onChange: ((Settings) -> Settings) -> Unit, onClearOnce: () -> Unit, onLicenses: () -> Unit = {},
    onStorage: () -> Unit = {}, onFolders: () -> Unit = {}, onStoreEdit: () -> Unit = {}, onExportAll: () -> Unit = {},
    /** S-53 백업 가져오기(폴더에서 백업 파일 고르기) 화면으로. */
    onBackupImport: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<String?>(null) }
    var backupOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var upTick by remember { mutableIntStateOf(0) }
    var warmTime by remember { mutableStateOf(false) }
    var tapEditor by remember { mutableStateOf(false) }
    var infoItems by remember { mutableStateOf(false) }
    val upUsed by produceState<Long?>(null, upTick, s.upscaleCacheMb) {
        // 상한을 바꾸면 앱(AshRoot)이 넘치는 만큼 지운다. 잠깐 기다렸다가 쓰는 양을 다시 잰다.
        if (upTick > 0 || s.upscaleCacheMb != 1024) kotlinx.coroutines.delay(300)
        value = withContext(Dispatchers.IO) { ComicUpscale.usedBytes(context.applicationContext) }
    }
    // S-53 백업: 저장 위치는 사용자가 고른다(SAF, 기억한 백업 폴더에서 시작). 가져오기는 백업 가져오기 화면. 네트워크 없음.
    val exportBackup = rememberBackupExporter(onBusy = { busy = it }, onResult = { info = it })
    // S-17 서재 폴더 줄: 폴더 이름들 · 개수
    val libFolders by remember { context.ash.db.books().scanFolders() }.collectAsState(initial = emptyList())
    val folderNames = libFolders.filter { it.include }.map { f ->
        val c = kr.vici.ashreader.data.StorageAccess.canonOf(f.path)
        if (c.startsWith("path:") || c.startsWith("tree:")) f.label.substringAfterLast('/')
        else kr.vici.ashreader.data.FolderRules.nameOf(c) { v -> kr.vici.ashreader.data.StorageAccess.volumeLabel(context, v) }
    }
    val aiCap = remember { kr.vici.ashreader.doc.AiCapability.cached(context) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("설정")
        Group("서재") {
            val access = remember { kr.vici.ashreader.data.StorageAccess.hasFileAccess(context) }
            ValueRow("파일 접근 권한", "기기 안 책·문서를 찾는 권한(S-02 다시 보기)", if (access) "허용됨" else "허용 안 됨", onStorage)
            ValueRow("서재 폴더", if (folderNames.isEmpty()) "아직 고른 폴더가 없어요" else folderNames.joinToString(" · "), "${folderNames.size}개", onFolders)
            SwitchRow("서점 바로가기 줄 보이기", "서재 위 '내 서점 앱' 줄", s.showStores) { v -> onChange { it.copy(showStores = v) } }
            ValueRow("서점 바로가기 편집", "서재 위 '내 서점 앱' 줄 · 더하기·빼기·순서", "", onStoreEdit)
        }
        Group("글자와 화면") {
            ValueRow("본문 글꼴", "나눔명조 · ASH 바탕 · ASH 부리", s.font.label.substringBefore(" (")) {
                onChange { it.copy(font = kr.vici.ashreader.data.BodyFont.entries[(it.font.ordinal + 1) % 3]) }
            }
            ValueRow("TXT 빈 줄", "TXT 정리(화면에만, 파일은 그대로)", listOf("그대로", "하나로", "모두 없앰")[s.txtBlank]) { onChange { it.copy(txtBlank = (it.txtBlank + 1) % 3) } }
            SwitchRow("TXT 끊긴 줄 합치기", "문장이 끝나지 않은 줄을 다음 줄과 이어요", s.txtJoin) { v -> onChange { it.copy(txtJoin = v) } }
            SwitchRow("TXT 줄 앞 공백 정리", "빈칸·전각 빈칸·탭", s.txtTrim) { v -> onChange { it.copy(txtTrim = v) } }
            val encs = listOf("") + TextEncoding.Kind.entries.map { it.name }
            ValueRow("TXT 기본 인코딩", "책마다 따로 정한 것이 없을 때", TextEncoding.Kind.of(s.txtDefaultEncoding)?.label ?: "자동") {
                onChange { it.copy(txtDefaultEncoding = encs[(encs.indexOf(it.txtDefaultEncoding).coerceAtLeast(0) + 1) % encs.size]) }
            }
        }
        Group("테마와 밝기") {
            SwitchRow("가장자리로 밝기 조절", "화면 왼쪽 가장자리 가운데를 위아래로 밀어요", s.edgeBrightness) { v -> onChange { it.copy(edgeBrightness = v) } }
            ValueRow("따뜻한 색 자동 시각", if (s.warmAuto) "이 시간에만 따뜻한 색을 켜요" else "꺼짐 · 강도는 읽기 화면 '밝기'에서", if (s.warmAuto) "${LightMath.hm(s.warmFrom)}~${LightMath.hm(s.warmTo)}" else "끔") { warmTime = true }
            if (s.warmAuto) SwitchRow("따뜻한 색 자동 켜기", "", true) { v -> onChange { it.copy(warmAuto = v) } }
        }
        Group("넘김과 조작") {
            ValueRow("넘김 효과", "", when { s.noTurnEffect -> "없음"; s.curl -> "종이책"; else -> "슬라이드" }) {
                onChange { when { it.noTurnEffect -> it.copy(noTurnEffect = false, curl = true); it.curl -> it.copy(curl = false); else -> it.copy(noTurnEffect = true) } }
            }
            ValueRow("화면 누르는 곳", "좌우 · L자 · 어디든 다음 · 내 방식", s.tapLayout.label) {
                onChange { it.copy(tapLayout = TapLayout.entries[(it.tapLayout.ordinal + 1) % TapLayout.entries.size], tapGuideShown = false) }
            }
            ValueRow("내 방식 편집", "3×3 칸마다 이전·다음·메뉴·없음", "") { tapEditor = true }
            SwitchRow("왼손잡이(좌우 바꾸기)", "", s.leftHanded) { v -> onChange { it.copy(leftHanded = v, tapGuideShown = false) } }
            SwitchRow("볼륨 버튼으로 넘기기", "듣는 동안에는 소리 크기", s.volumeKeys) { v -> onChange { it.copy(volumeKeys = v) } }
            val gaps = listOf(5, 10, 15, 20, 30, 60)
            ValueRow("자동 넘김 간격", "읽기 화면 '넘김'에서 켜요", "${s.autoTurnSec}초") {
                onChange { it.copy(autoTurnSec = gaps[(gaps.indexOf(it.autoTurnSec).let { i -> if (i < 0) 0 else i + 1 }) % gaps.size]) }
            }
            SwitchRow("스크롤 보기", "쪽을 위아래로 이어서 보기(넘김 효과는 쓰지 않아요)", s.scrollView) { v -> onChange { it.copy(scrollView = v) } }
            ValueRow("자동 스크롤 속도", "스크롤 보기에서 '넘김'의 자동 스크롤", "${s.autoScrollSpeed}단계") {
                onChange { it.copy(autoScrollSpeed = it.autoScrollSpeed % 10 + 1) }
            }
            ValueRow("탭 영역 안내 다시 보기", "다음에 책을 열 때 보여 줘요", "") { onChange { it.copy(tapGuideShown = false) }; info = "다음에 책을 열면 안내가 다시 나와요." }
        }
        Group("읽는 동안") {
            ValueRow("화면 꺼짐 시간", "", s.screenTimeout.label) { onChange { it.copy(screenTimeout = ScreenTimeout.entries[(it.screenTimeout.ordinal + 1) % ScreenTimeout.entries.size]) } }
            ValueRow("화면 회전", "읽기 메뉴의 회전 잠금 버튼과 같아요", s.rotation.label) { onChange { it.copy(rotation = Rotation.entries[(it.rotation.ordinal + 1) % Rotation.entries.size]) } }
            ValueRow("정보줄 항목", "시계·배터리·이번 장 남은 쪽·진행률·남은 시간", "${InfoItem.entries.count { s.infoItems and it.bit != 0 }}개") { infoItems = true }
            SwitchRow("정보줄을 위에", "끄면 아래", s.infoTop) { v -> onChange { it.copy(infoTop = v) } }
            SwitchRow("두 쪽 펼침에서 쪽마다 정보줄", "태블릿 가로: 각 쪽 아래에 쪽 번호. 끄면 전체 아래 한 줄", s.infoPerPage) { v -> onChange { it.copy(infoPerPage = v) } }
        }
        Group("문서(PDF·한글)") {
            SwitchRow("야간에 그림은 반전 안 함", "글자 있는 PDF 등 고정 쪽 문서", s.nightKeepImages) { v -> onChange { it.copy(nightKeepImages = v) } }
        }
        Group("듣기") {
            ValueRow("읽어 주기 엔진 설정 열기", "한국어 음성 받기·엔진 고르기", "") {
                if (Listen.settingsIntents().none { i -> runCatching { context.startActivity(i) }.isSuccess }) info = "읽어 주기 설정 화면을 열 수 없어요."
            }
            val rates = listOf(0.8f, 1.0f, 1.2f, 1.5f, 2.0f)
            ValueRow("기본 속도", "", "${"%.1f".format(java.util.Locale.ROOT, s.ttsRate)}×") {
                onChange { it.copy(ttsRate = rates[(rates.indexOfFirst { r -> r >= it.ttsRate - 0.01f }.let { i -> if (i < 0) 0 else i + 1 }) % rates.size]) }
            }
            ValueRow("잠자기 타이머 기본", "", SLEEP_CHOICES.firstOrNull { it.first == s.ttsSleep }?.second ?: "끄기") {
                onChange { cur -> cur.copy(ttsSleep = SLEEP_CHOICES[(SLEEP_CHOICES.indexOfFirst { it.first == cur.ttsSleep } + 1).mod(SLEEP_CHOICES.size)].first) }
            }
        }
        // 작업 04: AI 화질 향상 한 묶음(만화 새 책 기본값 · 이북·PDF 문서 그림 · 저장 공간). 읽는 중에는 보기 메뉴 맨 위 같은 칸.
        Group("AI 화질 향상") {
            val bad = aiCap?.verdict?.let { it != kr.vici.ashreader.doc.AiCapability.Verdict.OK } == true
            if (bad) ValueRow("AI 화질 향상", "이 기기에서는 쓸 수 없어요(메모리·처리 속도)", "쓸 수 없음") {}
            else {
                // F-73: 만화는 책마다 켜고 끈다(기본 끔). 여기서는 아직 정하지 않은 새 만화의 기본값만.
                SwitchRow("새 만화에 기본으로 켜기", "만화는 책마다 · 보기 메뉴 맨 위에서 켜고 꺼요 · 배터리 더 씀", s.aiUpscale) { v -> onChange { it.copy(aiUpscale = v) } }
                // F-73 확장(D-021): 이북·PDF 속 그림·사진만 2배로. 글자에는 적용 안 함. 앱 전체 한 값. 기본 끔.
                SwitchRow("문서 그림 AI 화질 향상", "이북·PDF 속 그림·사진을 선명하게 · 글자·한글·Word 에는 적용 안 함", s.epubAiUpscale) { v -> onChange { it.copy(epubAiUpscale = v) } }
            }
            // F-73: 저장 공간 상한(300MB / 1GB / 2GB, 만화·문서 그림 함께)과 [지우기]
            StorageLimitRow(
                limit = mbLabel(s.upscaleCacheMb.toLong() shl 20), used = upUsed?.let { mbLabel(it) },
                onCycle = {
                    onChange { cur ->
                        val ch = Prefs.UPSCALE_CACHE_CHOICES
                        cur.copy(upscaleCacheMb = ch[(ch.indexOf(cur.upscaleCacheMb) + 1).mod(ch.size)])
                    }
                },
                onClear = {
                    scope.launch {
                        withContext(Dispatchers.IO) { ComicUpscale.clear(context.applicationContext) }
                        upTick++
                        info = "AI 화질 향상으로 처리해 둔 그림을 모두 지웠어요. 다시 볼 때 새로 처리해요."
                    }
                },
            )
        }
        Group("연결") {
            ValueRow("사전", "단어 길게 눌러 찾기", "말글사전") {
                info = if (Links.isInstalled(context, Links.MALGEUL)) "말글사전이 설치되어 있어요. 본문 글자 선택에서 '사전'을 누르면 바로 찾아요."
                else "말글사전이 설치되어 있지 않아요. 말글사전 APK를 설치하세요. 그동안은 다른 사전 앱을 고를 수 있어요."
            }
        }
        Group("정보") {
            ValueRow("백업·내보내기", "서재·책갈피·메모·형광펜·기록을 파일로 저장하거나 가져오기", "") { backupOpen = true }
            ValueRow("형광펜·메모 모두 내보내기", ".txt 또는 .md 로 저장하거나 공유", "", onExportAll)
            SwitchRow("한 번만 보기 기록 지우기", "앱 닫으면 지워요", s.clearOnceOnExit) { v -> onChange { it.copy(clearOnceOnExit = v) }; if (v) onClearOnce() }
            ValueRow("오픈소스 라이선스", "AGPL-3.0 · 사용한 라이브러리", "", onLicenses)
            ValueRow("버전", "", AppInfo.VERSION_LABEL) {}
        }
        Box(Modifier.height(24.dp))
    }
    info?.let {
        AlertDialog(onDismissRequest = { info = null }, text = { Text(it) }, confirmButton = { TextButton(onClick = { info = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("확인") } })
    }
    if (warmTime) WarmTimeDialog(s.warmFrom, s.warmTo, onSave = { f, t -> warmTime = false; onChange { it.copy(warmFrom = f, warmTo = t, warmAuto = true) } }, onDismiss = { warmTime = false })
    if (tapEditor) TapEditorDialog(s.tapCustom, onSave = { str -> tapEditor = false; onChange { it.copy(tapLayout = TapLayout.CUSTOM, tapCustom = str, tapGuideShown = false) } }, onDismiss = { tapEditor = false })
    if (infoItems) AlertDialog(
        onDismissRequest = { infoItems = false },
        title = { Text("정보줄 항목") },
        text = {
            Column {
                InfoItem.entries.forEach { item ->
                    SwitchLine(item.label, "", s.infoItems and item.bit != 0) { v -> onChange { it.copy(infoItems = if (v) it.infoItems or item.bit else it.infoItems and item.bit.inv()) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = { infoItems = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") } },
    )
    if (backupOpen) AlertDialog(
        onDismissRequest = { backupOpen = false },
        title = { Text("백업·내보내기") },
        text = {
            Column {
                Text("서재 정보·읽은 위치·책갈피·메모·형광펜·독서 기록·설정을 백업 파일(ASH백업_날짜.ashbak) 하나로 저장해요. 책 파일은 담지 않아요. " +
                    "가져올 때는 지금 기록과 합치거나 모두 바꿀 수 있어요.", fontSize = 14.sp, lineHeight = 20.sp)
                Button(
                    onClick = { backupOpen = false; exportBackup() }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp),
                ) { Text("파일로 내보내기") }
                OutlinedButton(
                    onClick = { backupOpen = false; onBackupImport() }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp),
                ) { Text("백업 가져오기") }
            }
        },
        confirmButton = { TextButton(onClick = { backupOpen = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") } },
    )
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
            if (sub.isNotEmpty()) Text(sub, fontSize = 14.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (value.isNotEmpty()) Text(value, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
}

/** F-73 저장 공간 상한 줄: 줄을 누르면 상한을 바꾸고, 오른쪽 [지우기](48dp)로 처리해 둔 그림을 지운다. */
@Composable
private fun StorageLimitRow(limit: String, used: String?, onCycle: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = onCycle).heightIn(min = 56.dp).padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("저장 공간 상한", fontSize = 15.sp)
                Text("AI 화질 향상으로 처리한 그림(만화·문서)" + (used?.let { " · 지금 $it" } ?: ""), fontSize = 14.sp, lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(limit, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
        }
        TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp).padding(end = 8.dp)) { Text("지우기", fontSize = 15.sp) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun SwitchRow(name: String, sub: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onToggle(!on) }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp)
            if (sub.isNotEmpty()) Text(sub, fontSize = 14.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = on, onCheckedChange = onToggle, modifier = Modifier.padding(start = 8.dp))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
}

/** S-45 다음 권 확인창. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextVolumeSheet(done: Book, next: Book, onLibrary: () -> Unit, onOpenNext: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
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
fun TapGuide(rtl: Boolean, grid: List<TapAction> = emptyList(), onChange: (() -> Unit)? = null, onOk: () -> Unit) {
    val prevLeft = !rtl
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onOk)) {
        if (grid.size != 9) Row(Modifier.fillMaxSize()) {
            Zone(if (prevLeft) "이전 쪽" else "다음 쪽", Modifier.weight(0.3f), Color(0x552E4A6B))
            Zone("메뉴", Modifier.weight(0.4f), Color(0x338C5A2B))
            Zone(if (prevLeft) "다음 쪽" else "이전 쪽", Modifier.weight(0.3f), Color(0x552E4A6B))
        } else Column(Modifier.fillMaxSize()) {
            for (row in 0 until 3) Row(Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0 until 3) {
                    val a = grid[row * 3 + col]
                    Zone(
                        when (a) { TapAction.PREV -> "이전 쪽"; TapAction.NEXT -> "다음 쪽"; TapAction.MENU -> "메뉴"; TapAction.NONE -> "" },
                        Modifier.weight(1f), zoneColor(a),
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
private fun mbLabel(bytes: Long): String {
    val mb = bytes / (1L shl 20)
    return when {
        mb < 1024 -> "${mb}MB"
        mb % 1024 == 0L -> "${mb / 1024}GB"
        else -> "%.1fGB".format(java.util.Locale.ROOT, mb / 1024.0)
    }
}
