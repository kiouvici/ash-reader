// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import kr.vici.ashreader.doc.PageFrame
import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kr.vici.ashreader.data.InfoItem
import kr.vici.ashreader.data.ReadTheme
import kr.vici.ashreader.data.ScreenTimeout
import kr.vici.ashreader.data.Settings
import kr.vici.ashreader.data.TapLayout
import kr.vici.ashreader.tts.Listen

// ───────────────────────────── 밝기·색온도 계산(F-42, 1.3) ─────────────────────────────

/** 밝기 눈금 하나로 창 밝기와 '추가 어둡게'를 함께 다룬다: 0.01..1 = 창 밝기, 0 아래(-0.6까지) = 최저 밝기 + 검은 막. */
object LightMath {
    const val MIN = 0.01f

    fun level(brightness: Float, dim: Int): Float = if (dim > 0) -dim / 100f else brightness.coerceAtLeast(MIN)

    /** 눈금 → (창 밝기, 추가 어둡게 %). */
    fun split(level: Float): Pair<Float, Int> =
        if (level >= MIN) level.coerceAtMost(1f) to 0 else MIN to Math.round(-level * 100f).coerceIn(0, 60)

    /** 지금 시각(분)이 [from]~[to] 안인가(밤을 넘기는 구간 포함). */
    fun inWindow(now: Int, from: Int, to: Int): Boolean = when {
        from == to -> true
        from < to -> now in from until to
        else -> now >= from || now < to
    }

    fun warmOn(s: Settings, nowMin: Int): Boolean = s.warmth > 0 && (!s.warmAuto || inWindow(nowMin, s.warmFrom, s.warmTo))

    fun nowMinutes(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    fun hm(min: Int) = "%02d:%02d".format(Locale.ROOT, min / 60, min % 60)
}

/** 본문 위 따뜻한 색 막(F-42)과 추가 어둡게 막. 누름은 받지 않는다. */
@Composable
fun LightOverlay(s: Settings, dimNow: Int) {
    val now by produceState(LightMath.nowMinutes(), s.warmAuto) {
        while (true) { value = LightMath.nowMinutes(); delay(60_000) }
    }
    val warm = if (LightMath.warmOn(s, now)) s.warmth else 0
    if (warm > 0) Box(Modifier.fillMaxSize().background(Color(0xFFFF8F1F).copy(alpha = warm / 100f * 0.55f)))
    if (dimNow > 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimNow / 100f)))
}

// ───────────────────────────── F-45 하단 정보줄 ─────────────────────────────

/**
 * 정보줄(S-20): 시계·배터리·이번 장 남은 쪽·전체 진행률·남은 읽기 시간. 항목·위치는 설정.
 * [chapterLeft] = 이번 장 남은 쪽(모르면 null), [msPerPage] = 최근 읽기 속도(모르면 null → 남은 시간 숨김).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderInfoLine(
    page: Int, count: Int, chapterLeft: Int?, msPerPage: Long?, s: Settings, c: ReadingColors, modifier: Modifier,
    /** 태블릿 두 쪽 펼침에서 쪽마다 정보줄(F-45, S-20): (왼쪽 쪽, 오른쪽 쪽). 빈 자리는 null. null = 한 줄. */
    halves: Pair<Int?, Int?>? = null,
    /**
     * eBook 보기 쪽 가장자리 몫(I-711). 있으면 정보줄을 그 몫이 비워 둔 띠에 정확히 놓는다(7G) — 막대 값을 따로 읽어
     * 띄우면 기기(작업 표시줄·두 쪽 펼침 등)에 따라 비워 둔 자리와 어긋나 마지막 줄과 겹칠 수 있었다.
     */
    frame: PageFrame? = null,
) {
    val context = LocalContext.current
    val tick by produceState(0L) { while (true) { delay(30_000); value = SystemClock.elapsedRealtime() } }
    val clock = remember(tick) { SimpleDateFormat("HH:mm", Locale.KOREA).format(Date()) }
    val bat = remember(tick) { battery(context) }
    val on = { item: InfoItem -> s.infoItems and item.bit != 0 }
    val left = ArrayList<String>()
    if (on(InfoItem.CLOCK)) left += clock
    if (on(InfoItem.BATTERY) && bat in 0..100) left += "배터리 $bat%"
    val right = ArrayList<String>()
    if (on(InfoItem.CHAPTER_LEFT) && chapterLeft != null) right += if (chapterLeft <= 0) "이 장 마지막 쪽" else "이 장 ${chapterLeft}쪽 남음"
    if (on(InfoItem.PROGRESS) && count > 0) right += "${(page + 1) * 100 / count}% · ${page + 1}/${count}쪽"
    if (on(InfoItem.TIME_LEFT) && msPerPage != null && count > 0) right += "약 " + durationLabel((count - page - 1).coerceAtLeast(0) * msPerPage)
    // I-711: eBook 보기가 비워 둔 자리(PageFrame)와 같은 곳에 놓이게 막대가 숨어도 잡히는 값으로 띄운다
    val scale = LocalDensity.current.fontScale
    val pad = if (frame != null) {
        // 7G: 쪽 배치가 비운 띠(frame) 안에 고정 — 띠 = 막대 몫 바깥쪽 + 정보줄 높이(infoLineDp) + 틈(INFO_GAP_DP)
        val band = PageFrame.infoLineDp(scale)
        val bar = ((if (s.infoTop) frame.top else frame.bottom) - band - PageFrame.INFO_GAP_DP).coerceAtLeast(0f)
        Modifier.padding(start = frame.left.dp, end = frame.right.dp)
            .padding(top = if (s.infoTop) bar.dp else 0.dp, bottom = if (s.infoTop) 0.dp else bar.dp)
            .height(band.dp)
    } else Modifier.windowInsetsPadding(
        if (s.infoTop) WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top)
        else WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout).only(WindowInsetsSides.Bottom),
    ).padding(vertical = 6.dp)
    if (halves != null) {
        // 쪽마다: 각 쪽 가운데에 그 쪽 번호, 왼쪽 쪽 바깥에 시계·배터리, 오른쪽 쪽 바깥에 진행 정보
        val (lp, rp) = halves
        val desc = (left + listOfNotNull(lp?.let { "왼쪽 ${it + 1}쪽" }, rp?.let { "오른쪽 ${it + 1}쪽" }) + right).joinToString(", ")
        Row(modifier.fillMaxWidth().then(pad).semantics { contentDescription = desc }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                Text(left.joinToString(" · "), fontSize = 12.sp, lineHeight = PageFrame.INFO_LINE_SP.sp, color = c.subC, maxLines = 1, modifier = Modifier.align(Alignment.CenterStart))
                if (lp != null) Text("${lp + 1}", fontSize = 12.sp, lineHeight = PageFrame.INFO_LINE_SP.sp, color = c.subC, modifier = Modifier.align(Alignment.Center))
            }
            Box(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                if (rp != null) Text("${rp + 1}", fontSize = 12.sp, lineHeight = PageFrame.INFO_LINE_SP.sp, color = c.subC, modifier = Modifier.align(Alignment.Center))
                Text(right.joinToString("  "), fontSize = 12.sp, lineHeight = PageFrame.INFO_LINE_SP.sp, color = c.subC, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
        return
    }
    if (left.isEmpty() && right.isEmpty()) return
    Row(modifier.fillMaxWidth().then(pad).padding(horizontal = 20.dp).semantics { contentDescription = (left + right).joinToString(", ") }, verticalAlignment = Alignment.CenterVertically) {
        Text(left.joinToString(" · "), fontSize = 12.sp, lineHeight = PageFrame.INFO_LINE_SP.sp, color = c.subC, maxLines = 1, modifier = Modifier.weight(1f))
        Text(right.joinToString("  "), fontSize = 12.sp, lineHeight = PageFrame.INFO_LINE_SP.sp, color = c.subC, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun battery(context: Context): Int =
    (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

fun durationLabel(ms: Long): String {
    val m = (ms / 60_000L).coerceAtLeast(1)
    return if (m >= 60) "${m / 60}시간" + (if (m % 60 >= 10) " ${m % 60}분" else "") else "${m}분"
}

// ───────────────────────────── S-25 넘김 방식 ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TurnSheet(
    s: Settings, onChange: ((Settings) -> Settings) -> Unit, autoOn: Boolean, onAuto: (Boolean) -> Unit,
    onGuide: () -> Unit, onDismiss: () -> Unit,
    /** 스크롤 보기(F-22)를 고를 수 있는 문서인가(만화는 웹툰 보기로 대신). */
    scrollAvailable: Boolean = true,
    /** 지금 보기가 위아래 연속(스크롤 보기·웹툰)이면 자동 넘김 대신 자동 스크롤 속도. */
    continuous: Boolean = scrollAvailable && s.scrollView,
) {
    val cs = MaterialTheme.colorScheme
    var editor by remember { mutableStateOf(false) }
    val scroll = continuous
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetBars()
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            SheetLabel("넘김 효과")
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp).alpha(if (scroll) 0.38f else 1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill("종이책", !s.noTurnEffect && s.curl) { if (!scroll) onChange { it.copy(curl = true, noTurnEffect = false) } }
                Pill("슬라이드", !s.noTurnEffect && !s.curl) { if (!scroll) onChange { it.copy(curl = false, noTurnEffect = false) } }
                Pill("없음", s.noTurnEffect) { if (!scroll) onChange { it.copy(noTurnEffect = true) } }
            }
            if (scrollAvailable) {
                SheetLabel("보기 방식")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("쪽 넘김", !s.scrollView) { onChange { it.copy(scrollView = false) } }
                    Pill("스크롤", s.scrollView) { onChange { it.copy(scrollView = true) } }
                }
                if (s.scrollView) Text("스크롤 보기에서는 효과를 쓰지 않아요. 쪽은 빈칸 없이 이어서 보여요.", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
            if (scroll) {
                SwitchLine("자동 스크롤", if (autoOn) "켜짐 · 화면을 누르면 멈춰요" else "${s.autoScrollSpeed}단계 속도로 내려가요", autoOn, onAuto)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("속도", fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp))
                    var v by remember(s.autoScrollSpeed) { mutableFloatStateOf(s.autoScrollSpeed.toFloat()) }
                    AppleSlider(
                        value = v, onValueChange = { v = it }, valueRange = 1f..10f, steps = 8,
                        onValueChangeFinished = { val n = Math.round(v); onChange { it.copy(autoScrollSpeed = n) } },
                        modifier = Modifier.weight(1f).semantics { contentDescription = "자동 스크롤 속도" },
                    )
                    Text("${Math.round(v)}단계", fontSize = 14.sp, modifier = Modifier.widthIn(min = 56.dp).padding(start = 8.dp), textAlign = TextAlign.End)
                }
            } else {
                SwitchLine("자동 넘김", if (autoOn) "켜짐 · 화면을 누르면 멈춰요" else "${s.autoTurnSec}초마다 다음 쪽", autoOn, onAuto)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("간격", fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp))
                    var v by remember(s.autoTurnSec) { mutableFloatStateOf(s.autoTurnSec.toFloat()) }
                    AppleSlider(
                        value = v, onValueChange = { v = it }, valueRange = 3f..60f, steps = 56,
                        onValueChangeFinished = { val n = Math.round(v); onChange { it.copy(autoTurnSec = n) } },
                        modifier = Modifier.weight(1f).semantics { contentDescription = "자동 넘김 간격" },
                    )
                    Text("${Math.round(v)}초", fontSize = 14.sp, modifier = Modifier.widthIn(min = 48.dp).padding(start = 8.dp), textAlign = TextAlign.End)
                }
            }
            SheetLabel("화면 누르는 곳")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TapLayout.entries.forEach { t ->
                    Pill(if (t == TapLayout.CUSTOM) "내 방식 ▸" else t.label, s.tapLayout == t) {
                        if (t == TapLayout.CUSTOM) editor = true
                        else onChange { it.copy(tapLayout = t, tapGuideShown = false) }
                    }
                }
            }
            SwitchLine("왼손잡이(좌우 바꾸기)", "", s.leftHanded) { v -> onChange { it.copy(leftHanded = v, tapGuideShown = false) } }
            SwitchLine("볼륨 키로 넘기기", "듣는 동안에는 소리 크기 조절", s.volumeKeys) { v -> onChange { it.copy(volumeKeys = v) } }
            TextButton(onClick = onGuide, modifier = Modifier.heightIn(min = 48.dp)) { Text("누르는 곳 다시 보기", color = cs.primary) }
        }
    }
    if (editor) TapEditorDialog(s.tapCustom, onSave = { str -> editor = false; onChange { it.copy(tapLayout = TapLayout.CUSTOM, tapCustom = str, tapGuideShown = false) } }, onDismiss = { editor = false })
}

/** F-64 내 방식 편집기: 3×3 칸을 눌러 이전 → 다음 → 메뉴 → 없음 순으로 바꾼다. */
@Composable
fun TapEditorDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var cur by remember { mutableStateOf(initial) }
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("내 방식으로 누르는 곳") },
        text = {
            Column {
                Text("칸을 누를 때마다 이전 → 다음 → 메뉴 → 없음 순으로 바뀌어요.", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
                for (row in 0 until 3) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    for (col in 0 until 3) {
                        val i = row * 3 + col
                        val a = TapAction.of(cur[i])
                        Box(
                            Modifier.weight(1f).heightIn(min = 64.dp).clip(RoundedCornerShape(10.dp)).background(zoneColor(a))
                                .clickable { cur = TapZones.cycle(cur, i) }
                                .semantics { contentDescription = "${row + 1}행 ${col + 1}열 ${a.label}" },
                            contentAlignment = Alignment.Center,
                        ) { Text(a.label, color = if (a == TapAction.NONE) cs.onSurface else Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                    }
                }
                if (!TapZones.hasMenu(cur)) Text("메뉴 칸이 하나는 있어야 메뉴를 열 수 있어요.", fontSize = 13.sp, color = cs.error, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(cur) }, enabled = TapZones.hasMenu(cur), modifier = Modifier.heightIn(min = 48.dp)) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } },
    )
}

fun zoneColor(a: TapAction): Color = when (a) {
    TapAction.PREV, TapAction.NEXT -> Color(0xC72E4A6B)
    TapAction.MENU -> Color(0xB88C5A2B)
    TapAction.NONE -> Color(0x33808080)
}

// ───────────────────────────── S-26 밝기·테마 빠른 설정 ─────────────────────────────

/**
 * [fixed] = 고정 쪽 문서(PDF·DjVu·HWP 원본) — 테마 대신 야간 반전 스위치와 '그림은 반전 안 함'.
 * [keepSwitch] = '그림은 반전 안 함'을 보일 형식(글자 있는 PDF·HWP 원본 등). 스캔·만화는 숨긴다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightSheet(
    s: Settings, onChange: ((Settings) -> Settings) -> Unit, fixed: Boolean, keepSwitch: Boolean,
    onLive: (Float?) -> Unit, onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var customOpen by remember { mutableStateOf(false) }
    var timeOpen by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { onLive(null); onDismiss() }) {
        SheetBars()
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            if (!fixed) {
                SheetLabel("테마")
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadTheme.entries.forEach { t ->
                        val c2 = if (t == ReadTheme.CUSTOM) customColors(s.customBg, s.customFg) else t.colors()
                        val sel = t == s.theme
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                .border(if (sel) 2.dp else 1.dp, if (sel) cs.primary else cs.outline, RoundedCornerShape(12.dp))
                                .clickable { if (t == ReadTheme.CUSTOM && s.theme == ReadTheme.CUSTOM) customOpen = true else onChange { it.copy(theme = t) } }
                                .heightIn(min = 64.dp).padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(c2.bgC).border(1.dp, cs.outline, CircleShape), contentAlignment = Alignment.Center) {
                                Text("가", color = c2.fgC, fontSize = 13.sp)
                            }
                            Text(if (t == ReadTheme.CUSTOM) "내 테마" else t.label, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                TextButton(onClick = { customOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("내 테마 색 정하기 ▸", color = cs.primary) }
            } else {
                SwitchLine("야간(색 반전)", "", s.theme == ReadTheme.NIGHT) { v -> onChange { it.copy(theme = if (v) ReadTheme.NIGHT else ReadTheme.DAY) } }
                if (keepSwitch) SwitchLine("그림은 반전 안 함", "사진·그림은 원래 색으로 보여요", s.nightKeepImages) { v -> onChange { it.copy(nightKeepImages = v) } }
            }
            SheetLabel("밝기")
            val system = s.brightness < 0f && s.dim == 0
            SwitchLine("기기 밝기 따르기", "끄면 이 앱에서만 밝기를 바꿔요(기기 설정은 그대로)", system) { v ->
                onChange { it.copy(brightness = if (v) -1f else 0.5f, dim = 0) }
            }
            if (!system) {
                var lv by remember(s.brightness, s.dim) { mutableFloatStateOf(LightMath.level(s.brightness, s.dim)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Sun, null, Modifier.size(16.dp))
                    AppleSlider(
                        value = lv, valueRange = -0.6f..1f,
                        onValueChange = { lv = it; onLive(it) },
                        onValueChangeFinished = { val (b, d) = LightMath.split(lv); onLive(null); onChange { it.copy(brightness = b, dim = d) } },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "밝기" },
                    )
                    Icon(Icons.Sun, null, Modifier.size(24.dp))
                }
                Text(if (lv < LightMath.MIN) "추가 어둡게 ${LightMath.split(lv).second}%" else "밝기 ${Math.round(lv * 100)}%", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
            SheetLabel("따뜻한 색")
            Row(verticalAlignment = Alignment.CenterVertically) {
                var w by remember(s.warmth) { mutableFloatStateOf(s.warmth.toFloat()) }
                AppleSlider(
                    value = w, valueRange = 0f..80f, onValueChange = { w = it },
                    onValueChangeFinished = { val n = Math.round(w); onChange { it.copy(warmth = n) } },
                    modifier = Modifier.weight(1f).semantics { contentDescription = "따뜻한 색 강도" },
                )
                Text("${Math.round(w)}%", fontSize = 14.sp, modifier = Modifier.widthIn(min = 48.dp).padding(start = 8.dp), textAlign = TextAlign.End)
            }
            SwitchLine(
                "시간 정해 켜기", if (s.warmAuto) "${LightMath.hm(s.warmFrom)} ~ ${LightMath.hm(s.warmTo)}에만 켜요" else "끄면 늘 켜져요(강도 0%면 꺼짐)", s.warmAuto,
            ) { v -> onChange { it.copy(warmAuto = v) }; if (v) timeOpen = true }
            if (s.warmAuto) TextButton(onClick = { timeOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("시간 정하기 ▸", color = cs.primary) }
            SheetLabel("화면 꺼짐")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScreenTimeout.entries.forEach { t -> Pill(t.label, s.screenTimeout == t) { onChange { it.copy(screenTimeout = t) } } }
            }
        }
    }
    if (customOpen) CustomThemeDialog(s.customBg, s.customFg, onSave = { bg, fg -> customOpen = false; onChange { it.copy(customBg = bg, customFg = fg, theme = ReadTheme.CUSTOM) } }, onDismiss = { customOpen = false })
    if (timeOpen) WarmTimeDialog(s.warmFrom, s.warmTo, onSave = { f, t -> timeOpen = false; onChange { it.copy(warmFrom = f, warmTo = t, warmAuto = true) } }, onDismiss = { timeOpen = false })
}

private val BG_SWATCHES = listOf(
    0xFFFFFFFF, 0xFFF3F1EA, 0xFFF4ECD8, 0xFFE8F0E3, 0xFFE6EEF6, 0xFFF6E7E9,
    0xFFDAD6CC, 0xFF3A3A3A, 0xFF232A33, 0xFF1E2B22, 0xFF2B2420, 0xFF000000,
).map { it.toInt() }
private val FG_SWATCHES = listOf(
    0xFF000000, 0xFF1F1F1F, 0xFF2B2B2B, 0xFF4A3A2C, 0xFF1F3A2A, 0xFF1C2E4A,
    0xFF5F6368, 0xFFCFCFCF, 0xFFE8E2D0, 0xFFB8D8B0, 0xFFF0C674, 0xFFFFFFFF,
).map { it.toInt() }

/** F-43 내 테마: 바탕·글자색을 견본에서 골라 저장. 대비 4.5:1 미만이면 경고(저장은 허용). */
@Composable
fun CustomThemeDialog(bg0: Int, fg0: Int, onSave: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    var bg by remember { mutableIntStateOf(bg0) }
    var fg by remember { mutableIntStateOf(fg0) }
    val cs = MaterialTheme.colorScheme
    val ratio = contrast(bg, fg)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("내 테마") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "그날 밤은 유난히 비가 많이 왔다. 창문을 두드리는 빗소리에 잠을 이루지 못했다.",
                    color = Color(fg), fontSize = 16.sp, lineHeight = 26.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(bg)).border(1.dp, cs.outlineVariant, RoundedCornerShape(12.dp)).padding(14.dp),
                )
                Text("대비 ${"%.1f".format(Locale.ROOT, ratio)}:1", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                if (ratio < 4.5) Text("글자가 잘 안 보일 수 있어요", fontSize = 13.sp, color = cs.error)
                SheetLabel("바탕색")
                Swatches(BG_SWATCHES, bg) { bg = it }
                SheetLabel("글자색")
                Swatches(FG_SWATCHES, fg) { fg = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(bg, fg) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } },
    )
}

@Composable
private fun Swatches(list: List<Int>, sel: Int, onPick: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    for (row in list.chunked(6)) Row(Modifier.padding(vertical = 2.dp)) {
        row.forEach { c ->
            Box(Modifier.size(48.dp).clickable { onPick(c) }.semantics { contentDescription = "색 #" + Integer.toHexString(c and 0xFFFFFF).padStart(6, '0') }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(Color(c)).border(if (c == sel) 3.dp else 1.dp, if (c == sel) cs.primary else cs.outline, CircleShape))
            }
        }
    }
}

/** 따뜻한 색 자동 켜기 시각(시 단위 ± 30분). */
@Composable
fun WarmTimeDialog(from0: Int, to0: Int, onSave: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    var from by remember { mutableIntStateOf(from0) }
    var to by remember { mutableIntStateOf(to0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("따뜻한 색 켜는 시간") },
        text = {
            Column {
                TimeStepper("켜기", from) { from = it }
                TimeStepper("끄기", to) { to = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(from, to) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } },
    )
}

@Composable
private fun TimeStepper(label: String, v: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange((v - 30 + 1440) % 1440) }, modifier = Modifier.size(48.dp).semantics { contentDescription = "$label 30분 앞으로" }) { Icon(Icons.Minus, null) }
        Text(LightMath.hm(v), fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.widthIn(min = 64.dp), textAlign = TextAlign.Center)
        IconButton(onClick = { onChange((v + 30) % 1440) }, modifier = Modifier.size(48.dp).semantics { contentDescription = "$label 30분 뒤로" }) { Icon(Icons.Plus, null) }
    }
}

// ───────────────────────────── S-27 듣기 바 ─────────────────────────────

/** 잠자기 타이머 고르기(F-47): 분, -1 = 이 장 끝까지, 0 = 끄기. */
val SLEEP_CHOICES = listOf(0 to "끄기", 15 to "15분", 30 to "30분", 60 to "60분", -1 to "이 장 끝까지")

@Composable
fun ListenBar(st: Listen.State, rate: Float, c: ReadingColors, onPlay: () -> Unit, onTune: () -> Unit, onSleep: (Int) -> Unit, modifier: Modifier) {
    var sleepMenu by remember { mutableStateOf(false) }
    val left by produceState(0L, st.sleepAt) {
        while (st.sleepAt > 0L) { value = (st.sleepAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L); delay(15_000) }
        value = 0L
    }
    Column(modifier.fillMaxWidth().background(c.barC).navigationBarsPadding()) {
        HorizontalDivider(color = c.lineC)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Listen.prev() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Prev, "이전 문장", Modifier.size(20.dp), tint = c.fgC) }
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(c.accentC).clickable(onClick = onPlay)
                    .semantics { contentDescription = if (st.playing) "멈춤" else "재생" },
                contentAlignment = Alignment.Center,
            ) { Icon(if (st.playing) Icons.Pause else Icons.Play, null, Modifier.size(22.dp), tint = c.onAccentC) }
            IconButton(onClick = { Listen.next() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Next, "다음 문장", Modifier.size(20.dp), tint = c.fgC) }
            TextButton(onClick = onTune, modifier = Modifier.heightIn(min = 48.dp).weight(1f).semantics { contentDescription = "속도와 높낮이" }) {
                Text("속도 ${"%.1f".format(Locale.ROOT, rate)}× ▾", fontSize = 13.sp, color = c.fgC, maxLines = 1)
            }
            Box {
                TextButton(onClick = { sleepMenu = true }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "잠자기 타이머" }) {
                    Icon(Icons.Timer, null, Modifier.size(18.dp), tint = c.fgC)
                    Text(
                        when { st.sleepChapter -> " 장 끝"; left > 0 -> " ${(left + 59_999) / 60_000}분"; else -> "" },
                        fontSize = 13.sp, color = c.fgC,
                    )
                }
                DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                    SLEEP_CHOICES.forEach { (m, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { sleepMenu = false; onSleep(m) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
            IconButton(onClick = { Listen.stop() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Close, "듣기 끝내기", Modifier.size(20.dp), tint = c.fgC) }
        }
    }
}

/** 듣기 속도·높낮이(F-13). 손을 떼면 저장하고 지금 문장부터 새 값으로 읽는다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenTuneSheet(s: Settings, onChange: ((Settings) -> Settings) -> Unit, onOpenEngine: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetBars()
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            var r by remember(s.ttsRate) { mutableFloatStateOf(s.ttsRate) }
            var p by remember(s.ttsPitch) { mutableFloatStateOf(s.ttsPitch) }
            SheetLabel("속도  ${"%.1f".format(Locale.ROOT, r)}×")
            AppleSlider(
                value = r, valueRange = 0.5f..3f, steps = 24, onValueChange = { r = it },
                onValueChangeFinished = { val v = Math.round(r * 10) / 10f; Listen.setRate(v); onChange { it.copy(ttsRate = v) } },
                modifier = Modifier.semantics { contentDescription = "읽기 속도" },
            )
            SheetLabel("높낮이  ${"%.1f".format(Locale.ROOT, p)}")
            AppleSlider(
                value = p, valueRange = 0.5f..2f, steps = 14, onValueChange = { p = it },
                onValueChangeFinished = { val v = Math.round(p * 10) / 10f; Listen.setPitch(v); onChange { it.copy(ttsPitch = v) } },
                modifier = Modifier.semantics { contentDescription = "목소리 높낮이" },
            )
            TextButton(onClick = { r = 1f; p = 1f; Listen.setRate(1f); Listen.setPitch(1f); onChange { it.copy(ttsRate = 1f, ttsPitch = 1f) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("처음 값으로", color = cs.primary)
            }
            TextButton(onClick = onOpenEngine, modifier = Modifier.heightIn(min = 48.dp)) { Text("읽어 주기 엔진·음성 설정 열기 ▸", color = cs.primary) }
        }
    }
}

// ───────────────────────────── S-29 각주 팝업 ─────────────────────────────

/** 각주 카드(F-46): 링크 아래(공간 없으면 위), 최대 화면 40% 높이, 넘치면 카드 안 스크롤. 바깥 누르면 닫힘. */
@Composable
fun FootnoteCard(text: String, below: Boolean, anchorFrac: Float, c: ReadingColors, onGo: () -> Unit, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().clickable(onClick = onDismiss).semantics { contentDescription = "각주 닫기" }) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val maxH = maxHeight * 0.4f
            val top = if (below) maxHeight * anchorFrac + 12.dp else (maxHeight * anchorFrac - maxH - 12.dp).coerceAtLeast(24.dp)
            Column(
                Modifier.padding(top = top.coerceAtMost(maxHeight - 120.dp).coerceAtLeast(24.dp), start = 16.dp, end = 16.dp).fillMaxWidth()
                    .heightIn(max = maxH).clip(RoundedCornerShape(16.dp)).background(c.barC).border(1.dp, c.lineC, RoundedCornerShape(16.dp))
                    .clickable(enabled = true, onClick = {}).padding(16.dp),
            ) {
                Text("각주", fontSize = 12.sp, color = c.subC)
                Text(
                    text.ifBlank { "각주 내용을 찾지 못했어요." }, fontSize = 16.sp, lineHeight = 25.sp, color = c.fgC,
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
                )
                TextButton(onClick = onGo, modifier = Modifier.heightIn(min = 48.dp).align(Alignment.End)) { Text("원문 위치로 가기", color = c.accentC) }
            }
        }
    }
}

// ───────────────────────────── 작은 공용 ─────────────────────────────

@Composable
fun SheetLabel(t: String) {
    Text(t, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

@Composable
fun SwitchLine(name: String, sub: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onToggle(!on) }.heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp)
            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppleSwitch(checked = on, onCheckedChange = onToggle)
    }
}
