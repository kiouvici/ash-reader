// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.vici.ashreader.data.BodyFont
import kr.vici.ashreader.data.Prefs
import kr.vici.ashreader.data.ReadTheme
import kr.vici.ashreader.data.Settings
import kr.vici.ashreader.engine.convert.TextEncoding

private enum class AaTab(val label: String) { TEXT("글자"), THEME("테마"), PARA("문단") }

/** TXT 인코딩 고르기(F-32). [chosen] = 사용자가 고른 것(null = 자동), [detected] = 지금 연 인코딩. */
class TxtEncodingUi(val chosen: TextEncoding.Kind?, val detected: TextEncoding.Kind?, val onPick: (TextEncoding.Kind?) -> Unit)

/** S-24 보기 설정('Aa'). 바꾼 값은 시트를 닫을 때가 아니라 바로 저장된다(문서는 닫힌 뒤 다시 배치). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewSettingsSheet(s: Settings, reflowable: Boolean, onChange: ((Settings) -> Settings) -> Unit, onDismiss: () -> Unit, txt: TxtEncodingUi? = null) {
    var tab by remember { mutableStateOf(AaTab.TEXT) }
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            // 미리보기
            val c = s.theme.colors()
            Text(
                "그는 창밖을 오래 바라보았다. 비는 그칠 기색이 없었고, 골목 끝 가로등은 젖은 길 위에 노란 빛을 길게 늘어뜨리고 있었다.",
                fontFamily = previewFamily(s.font), fontSize = s.fontSize.sp, lineHeight = (s.fontSize * s.lineHeight).sp,
                color = c.fgC, textAlign = TextAlign.Justify,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bgC).border(1.dp, cs.outlineVariant, RoundedCornerShape(12.dp)).padding(16.dp),
            )
            if (!reflowable) Text("이 문서는 쪽 모양이 고정돼 있어 글꼴·크기는 바뀌지 않아요. 테마만 적용돼요.", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AaTab.entries.forEach { t -> Pill(t.label, t == tab) { tab = t } }
            }
            when (tab) {
                AaTab.TEXT -> {
                    Text("글꼴", fontSize = 13.sp, color = cs.onSurfaceVariant)
                    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BodyFont.entries.forEach { f ->
                            val sel = f == s.font
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                    .border(if (sel) 2.dp else 1.dp, if (sel) cs.primary else cs.outlineVariant, RoundedCornerShape(12.dp))
                                    .clickable { onChange { it.copy(font = f) } }
                                    .heightIn(min = 72.dp).padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("가나다", fontFamily = previewFamily(f), fontSize = 20.sp)
                                Text(f.label, fontSize = 12.sp, textAlign = TextAlign.Center)
                            }
                        }
                    }
                    Text("한자는 기기 글꼴로 표시돼요", fontSize = 12.sp, color = cs.onSurfaceVariant)
                    Stepper("글자 크기", "${s.fontSize}", small = "가", big = "가",
                        onMinus = { onChange { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(Prefs.MIN_SIZE)) } },
                        onPlus = { onChange { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(Prefs.MAX_SIZE)) } })
                    if (txt != null) {
                        Text("글자 인코딩 (TXT)", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
                        Text("글자가 깨져 보이면 바꿔 보세요. 이 책에만 적용돼요.", fontSize = 12.sp, color = cs.onSurfaceVariant)
                        Row(Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val auto = "자동" + (if (txt.chosen == null && txt.detected != null) " (${txt.detected.label})" else "")
                            Pill(auto, txt.chosen == null) { txt.onPick(null) }
                            TextEncoding.Kind.entries.forEach { k -> Pill(k.label, txt.chosen == k) { txt.onPick(k) } }
                        }
                    }
                }
                AaTab.THEME -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadTheme.entries.forEach { t ->
                        val c2 = t.colors(); val sel = t == s.theme
                        Box(
                            Modifier.weight(1f).heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).background(c2.bgC)
                                .border(if (sel) 2.dp else 1.dp, if (sel) cs.primary else cs.outline, RoundedCornerShape(12.dp))
                                .clickable { onChange { it.copy(theme = t) } },
                            contentAlignment = Alignment.Center,
                        ) { Text(t.label, color = c2.fgC, fontSize = 14.sp) }
                    }
                }
                AaTab.PARA -> Stepper("줄 간격", "%.1f".format(s.lineHeight), small = "−", big = "+",
                    onMinus = { onChange { it.copy(lineHeight = ((it.lineHeight - 0.1f) * 10).let { v -> Math.round(v) / 10f }.coerceAtLeast(1.2f)) } },
                    onPlus = { onChange { it.copy(lineHeight = ((it.lineHeight + 0.1f) * 10).let { v -> Math.round(v) / 10f }.coerceAtMost(2.4f)) } })
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, small: String, big: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = onMinus, modifier = Modifier.size(48.dp)) { Text(small, fontSize = 14.sp) }
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.size(56.dp, 24.dp))
        IconButton(onClick = onPlus, modifier = Modifier.size(48.dp)) { Text(big, fontSize = 22.sp) }
    }
}

/** 미리보기 글꼴(앱에 넣은 본문 글꼴). */
private fun previewFamily(f: BodyFont): FontFamily = when (f) {
    BodyFont.NANUM_MYEONGJO -> Myeongjo
    BodyFont.RIDI_BATANG -> RidiBatang
    BodyFont.MARUBURI -> MaruBuri
}
