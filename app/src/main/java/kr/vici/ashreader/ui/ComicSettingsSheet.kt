// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kr.vici.ashreader.data.ComicBookPrefs
import kr.vici.ashreader.data.PanelMode
import kr.vici.ashreader.doc.AiCapability
import kr.vici.ashreader.doc.ComicUpscale

private enum class ComicTab(val label: String) { TURN("넘김"), VIEW("보기"), QUALITY("화질") }

/**
 * S-33 만화 설정(바텀시트, 탭 3개: 넘김 / 보기 / 화질). 이 책에만 기억하고, 아래 [모든 만화 기본값으로]로 기본값을 바꾼다.
 * 바꾸는 즉시 읽기 화면에 반영된다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicSettingsSheet(
    cs: ComicSession,
    globalPanel: PanelMode,
    globalAi: Boolean,
    upscale: ComicUpscale?,
    upscaleLimitMb: Int,
    onDismiss: () -> Unit,
    /** 태블릿 가로: 바텀시트 대신 오른쪽 측면 시트. */
    side: Boolean = false,
) {
    var tab by remember { mutableStateOf(ComicTab.TURN) }
    val p = cs.prefs
    val cs3 = MaterialTheme.colorScheme
    var savedNote by remember { mutableStateOf(false) }
    LaunchedEffect(savedNote) { if (savedNote) { delay(2000); savedNote = false } }
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text("만화 설정", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = if (side) 16.dp else 0.dp))
            Text("이 책에만 적용돼요.", fontSize = 12.sp, color = cs3.onSurfaceVariant)
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ComicTab.entries.forEach { t -> Pill(t.label, t == tab) { tab = t } }
            }
            when (tab) {
                ComicTab.TURN -> TurnTab(cs, p, globalPanel)
                ComicTab.VIEW -> ViewTab(cs, p)
                ComicTab.QUALITY -> QualityTab(cs, p, globalAi, upscale, upscaleLimitMb)
            }
            HorizontalDivider(Modifier.padding(top = 16.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (savedNote) "앞으로 여는 만화에 이 설정을 써요." else "", fontSize = 12.sp, color = cs3.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { cs.saveAsDefaults(); savedNote = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("모든 만화 기본값으로") }
            }
        }
    }
    if (side) {
        // 태블릿 가로(S-33): 오른쪽 측면 시트 360dp — 펼침 화면을 가리지 않아 바꾸는 즉시 보인다
        BackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color(0x33000000)).clickable(onClick = onDismiss).semantics { contentDescription = "만화 설정 닫기" })
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(360.dp).clickable(enabled = true, onClick = {}),
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp), tonalElevation = 2.dp, shadowElevation = 8.dp,
            ) {
                Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Close, "닫기") }
                    }
                    content()
                }
            }
        }
    } else ModalBottomSheet(onDismissRequest = onDismiss) { content() }
}

@Composable
private fun Label(t: String, sub: String? = null) {
    Column(Modifier.padding(top = 14.dp)) {
        Text(t, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        if (sub != null) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Choices(options: List<String>, selected: Int, enabled: Boolean = true, onPick: (Int) -> Unit) {
    Row(
        Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()).alpha(if (enabled) 1f else 0.38f),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, o -> Pill(o, i == selected) { if (enabled) onPick(i) } }
    }
}

@Composable
private fun SwitchLine(t: String, on: Boolean, enabled: Boolean = true, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 6.dp).alpha(if (enabled) 1f else 0.38f), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = { if (enabled) onChange(it) }, enabled = enabled)
    }
}

@Composable
private fun SliderLine(t: String, value: Float, range: ClosedFloatingPointRange<Float>, text: String, enabled: Boolean = true, steps: Int = 0, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).alpha(if (enabled) 1f else 0.38f), verticalAlignment = Alignment.CenterVertically) {
        Text(t, fontSize = 14.sp, modifier = Modifier.width(64.dp))
        Slider(
            value = value, onValueChange = { if (enabled) onChange(it) }, valueRange = range, steps = steps, enabled = enabled,
            modifier = Modifier.weight(1f).semantics { contentDescription = "$t $text" },
        )
        Text(text, fontSize = 12.sp, modifier = Modifier.width(48.dp).padding(start = 8.dp))
    }
}

private fun pct(v: Float) = (if (v > 0f) "+" else "") + (v * 100).toInt()

@Composable
private fun TurnTab(cs: ComicSession, p: ComicBookPrefs, globalPanel: PanelMode) {
    val web = p.webtoon
    val dirSel = when (p.dir) {
        ComicBookPrefs.DIR_AUTO -> if (cs.doc.fileRtl) 1 else 0
        ComicBookPrefs.DIR_LTR -> 0
        ComicBookPrefs.DIR_RTL -> 1
        else -> 2
    }
    Label("읽는 방향", if (p.dir == ComicBookPrefs.DIR_AUTO && cs.doc.fileRtl) "자동: 오→왼" else null)
    Choices(listOf("왼→오", "오→왼", "위→아래 · 웹툰"), dirSel) { i ->
        cs.update { it.copy(dir = when (i) { 0 -> ComicBookPrefs.DIR_LTR; 1 -> ComicBookPrefs.DIR_RTL; else -> ComicBookPrefs.DIR_TTB }) }
    }
    if (web) Text("웹툰 보기에서는 두 쪽 보기·펼침·칸 단위 보기를 쓰지 않아요", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    Label("두 쪽 보기")
    Choices(listOf("자동", "한 쪽", "두 쪽"), p.spread, enabled = !web) { i -> cs.update { it.copy(spread = i) } }
    SwitchLine("첫 쪽 단독", p.coverAlone, enabled = !web && p.spread != 1, sub = "두 쪽 보기에서 표지를 혼자 보여요") { v -> cs.update { it.copy(coverAlone = v) } }
    // F-50: 펼침 그림의 짝이 어긋난 책 — 짝을 한 쪽 밀거나 되돌린다(두 쪽 보기일 때)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { cs.update { it.copy(shift = true) } }, enabled = !web && p.spread != 1 && !p.shift, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("짝 한 쪽 밀기") }
        OutlinedButton(onClick = { cs.update { it.copy(shift = false) } }, enabled = !web && p.spread != 1 && p.shift, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("되돌리기") }
    }
    Label("가로로 긴 쪽", "한 쪽 보기·세로 화면일 때. 두 쪽 보기에서는 펼침 하나로 크게 보여요")
    Choices(listOf("반으로 나눔", "회전", "그대로"), p.wide, enabled = !web) { i -> cs.update { it.copy(wide = i) } }

    Label("칸 단위 보기")
    val pm = cs.panelMode(globalPanel)
    SwitchLine("칸 단위 보기", pm != PanelMode.OFF, enabled = !web) { v ->
        cs.update { it.copy(panel = if (v) (if (globalPanel == PanelMode.AUTO) 2 else 1) else 0) }
    }
    Choices(listOf("눌러서 확대", "자동 순서"), if (pm == PanelMode.AUTO) 1 else 0, enabled = !web && pm != PanelMode.OFF) { i ->
        cs.update { it.copy(panel = i + 1) }
    }

    Label("웹툰 보기", if (web) null else "읽는 방향을 '위→아래'로 하면 쓸 수 있어요")
    Text("그림 사이 간격", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp).alpha(if (web) 1f else 0.38f))
    Choices(listOf("없음", "작게"), p.gap, enabled = web) { i -> cs.update { it.copy(gap = i) } }
    SliderLine("좌우 여백", p.margin.toFloat(), 0f..15f, "${p.margin}%", enabled = web, steps = 14) { v -> cs.update { it.copy(margin = v.toInt()) } }
}

@Composable
private fun ViewTab(cs: ComicSession, p: ComicBookPrefs) {
    Label("맞춤", if (p.webtoon) "웹툰 보기는 폭 맞춤이에요" else "폭·높이·원본은 쪽 안을 밀어 보고, 끝에 닿으면 다음 쪽으로 넘어가요")
    Choices(listOf("화면", "폭", "높이", "원본"), p.fit, enabled = !p.webtoon) { i -> cs.update { it.copy(fit = i) } }
    SwitchLine("테두리 자동 자르기", p.crop, sub = if (p.webtoon) "웹툰은 좌우 흰 여백만 같은 폭으로 잘라요" else "흰·검은 테두리를 잘라 그림을 크게") { v -> cs.update { it.copy(crop = v) } }
    Text("자르기 민감도", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp).alpha(if (p.crop) 1f else 0.38f))
    Choices(listOf("약", "보통", "강"), p.cropSens, enabled = p.crop) { i -> cs.update { it.copy(cropSens = i) } }

    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("밝기·대비·감마", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        TextButton(onClick = { cs.update { it.copy(brightness = 0f, contrast = 0f, gamma = 1f) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("초기화") }
    }
    SliderLine("밝기", p.brightness, -1f..1f, pct(p.brightness)) { v -> cs.update { it.copy(brightness = Math.round(v * 20) / 20f) } }
    SliderLine("대비", p.contrast, -1f..1f, pct(p.contrast)) { v -> cs.update { it.copy(contrast = Math.round(v * 20) / 20f) } }
    SliderLine("감마", p.gamma, 0.5f..2f, "%.2f".format(p.gamma)) { v -> cs.update { it.copy(gamma = Math.round(v * 20) / 20f) } }
    SwitchLine("흑백", p.gray) { v -> cs.update { it.copy(gray = v) } }
    SwitchLine("색 반전", p.invert) { v -> cs.update { it.copy(invert = v) } }

    Label("확대 방식")
    Choices(listOf("빠름", "고품질"), if (p.hq) 1 else 0) { i -> cs.update { it.copy(hq = i == 1) } }
    Label("선명하게")
    Choices(listOf("끔", "약", "중", "강"), p.sharpen) { i -> cs.update { it.copy(sharpen = i) } }
}

@Composable
private fun QualityTab(cs: ComicSession, p: ComicBookPrefs, globalAi: Boolean, upscale: ComicUpscale?, limitMb: Int) {
    val context = LocalContext.current
    val cap by produceState(AiCapability.cached(context)) {
        if (value == null) value = AiCapability.measureWithin(context)
    }
    LaunchedEffect(cap) { cap?.let { cs.aiVerdict = it.verdict } }
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    val c = cap
    when {
        c == null -> Text("이 기기에서 쓸 수 있는지 확인하는 중이에요… (약 10초)", fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
        c.verdict == AiCapability.Verdict.LOW_MEMORY -> Text("이 기기는 메모리가 부족해서 AI 화질 향상을 쓸 수 없어요.", fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
        c.verdict == AiCapability.Verdict.TOO_SLOW -> Text("이 기기에서는 처리가 너무 느려서 AI 화질 향상을 쓸 수 없어요.", fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
        else -> {
            var confirm by remember { mutableStateOf(false) }
            val on = cs.aiOn(globalAi)
            SwitchLine("AI 화질 향상", on, sub = "작은 그림을 2배로 선명하게 키워요.") { v ->
                if (v && !AiConsent.given(context)) confirm = true else cs.update { it.copy(ai = if (v) 1 else 0) }
            }
            if (!c.gpu) Text("이 기기에서는 쪽마다 시간이 조금 걸려요(약 ${c.secPerPage}초).", fontSize = 12.sp, color = sub)
            val paused = upscale?.paused?.collectAsState()?.value
            if (on && paused != null) Text("기기가 뜨겁거나 배터리가 적어서 잠시 멈췄어요. 식거나 충전하면 다시 시작해요.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
            val ready = upscale?.ready?.collectAsState()?.value
            val stats by produceState<Pair<Int, Long>?>(null, ready?.size) {
                value = withContext(Dispatchers.IO) { ComicUpscale.doneCount(context, cs.doc.cacheKey) to ComicUpscale.usedBytes(context) }
            }
            stats?.let { (n, used) ->
                Text("처리한 쪽: $n / ${cs.doc.sourceCount}   저장 공간 ${used shr 20}MB / ${if (limitMb >= 1024) "${limitMb / 1024}GB" else "${limitMb}MB"}", fontSize = 12.sp, color = sub, modifier = Modifier.padding(top = 8.dp))
            }
            if (confirm) AlertDialog(
                onDismissRequest = { confirm = false },
                text = { Text("AI 화질 향상을 켤까요? 처리하는 동안 배터리를 더 쓰고 기기가 따뜻해질 수 있어요. 처리한 그림은 최대 ${if (limitMb >= 1024) "${limitMb / 1024}GB" else "${limitMb}MB"}까지 저장해 둡니다.") },
                confirmButton = {
                    TextButton(onClick = { AiConsent.give(context); confirm = false; cs.update { it.copy(ai = 1) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("켜기") }
                },
                dismissButton = { TextButton(onClick = { confirm = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } },
            )
        }
    }
}

/** S-36 처음 켤 때 확인창을 봤나(앱 전체 한 번). */
private object AiConsent {
    fun given(c: android.content.Context) = c.getSharedPreferences("comic_prefs", android.content.Context.MODE_PRIVATE).getBoolean("ai_consent", false)
    fun give(c: android.content.Context) { c.getSharedPreferences("comic_prefs", android.content.Context.MODE_PRIVATE).edit().putBoolean("ai_consent", true).apply() }
}
