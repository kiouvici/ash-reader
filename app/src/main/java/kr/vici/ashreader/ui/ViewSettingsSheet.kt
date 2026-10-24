// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.vici.ashreader.data.BodyFont
import kr.vici.ashreader.data.ComicBookPrefs
import kr.vici.ashreader.data.Prefs
import kr.vici.ashreader.data.ReadTheme
import kr.vici.ashreader.data.Settings
import kr.vici.ashreader.doc.TxtToc
import kr.vici.ashreader.doc.UserFonts
import kr.vici.ashreader.engine.convert.TextEncoding

private enum class AaTab(val label: String) { TEXT("글자"), THEME("테마"), PARA("문단") }

/** TXT 인코딩 고르기(F-32). [chosen] = 사용자가 고른 것(null = 자동), [detected] = 지금 연 인코딩. */
class TxtEncodingUi(val chosen: TextEncoding.Kind?, val detected: TextEncoding.Kind?, val onPick: (TextEncoding.Kind?) -> Unit)

/**
 * 고정 쪽 문서 보기(책마다): F-23 맞춤(쪽·폭·높이), F-24 여백 자르기([cropAvailable] 일 때).
 */
class FixedViewUi(val fit: Int, val crop: Boolean, val cropAvailable: Boolean, val onFit: (Int) -> Unit, val onCrop: (Boolean) -> Unit)

/** S-24 보기 설정('Aa'). 바꾼 값은 시트를 닫을 때가 아니라 바로 저장된다(문서는 닫힌 뒤 다시 배치). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewSettingsSheet(
    s: Settings, reflowable: Boolean, onChange: ((Settings) -> Settings) -> Unit, onDismiss: () -> Unit,
    txt: TxtEncodingUi? = null, fixed: FixedViewUi? = null,
    /** 이북·PDF 처럼 문서 그림 AI 화질 향상 대상인가(작업 03: 읽는 중에 켜고 끈다. 값은 설정과 같다). */
    aiImages: Boolean = false,
) {
    var tab by remember { mutableStateOf(AaTab.TEXT) }
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    /** 내 글꼴 목록(F-20). 더하거나 지우면 다시 읽는다. */
    var fontsVer by remember { mutableIntStateOf(0) }
    val userFonts = remember(fontsVer) { UserFonts.list(context) }
    var fontMsg by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { UserFonts.import(context, uri) } }
            r.onSuccess { f -> fontsVer++; fontMsg = "‘${UserFonts.label(f.name)}’ 글꼴을 넣었어요."; onChange { it.copy(userFont = f.name) } }
                .onFailure { e -> fontMsg = (e as? UserFonts.ImportFailed)?.message ?: "글꼴을 가져오지 못했어요." }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            // 미리보기
            val c = s.readingColors()
            val userFile = if (s.userFont.isNotEmpty()) UserFonts.file(context, s.userFont) else null
            val indentEm = when (s.indent) { 2 -> 2f; 3 -> 0f; else -> 1f }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bgC).border(1.dp, cs.outlineVariant, RoundedCornerShape(12.dp))
                    .padding(horizontal = (8 + s.marginH).dp, vertical = 12.dp),
            ) {
                val ta = if (s.align == 1) TextAlign.Start else TextAlign.Justify
                val fam = userFile?.let { f -> remember(f.absolutePath) { runCatching { FontFamily(Font(f)) }.getOrNull() } } ?: previewFamily(s.font)
                Text(
                    "그는 창밖을 오래 바라보았다. 비는 그칠 기색이 없었고, 골목 끝 가로등은 젖은 길 위에 노란 빛을 길게 늘어뜨리고 있었다.",
                    fontFamily = fam, fontSize = s.fontSize.sp, lineHeight = (s.fontSize * s.lineHeight).sp,
                    color = c.fgC, textAlign = ta, style = MaterialTheme.typography.bodyLarge.copy(textIndent = TextIndent(firstLine = indentEm.em)),
                )
                Text(
                    "“비가 오래 오네.”",
                    fontFamily = fam, fontSize = s.fontSize.sp, lineHeight = (s.fontSize * s.lineHeight).sp,
                    color = c.fgC, textAlign = ta,
                    style = MaterialTheme.typography.bodyLarge.copy(textIndent = TextIndent(firstLine = (if (s.dialogIndent == 1) 0f else indentEm).em)),
                    modifier = Modifier.padding(top = (s.paraSpace * s.fontSize / 10).dp),
                )
            }
            if (!reflowable) Text("이 문서는 쪽 모양이 고정돼 있어 글꼴·크기는 바뀌지 않아요. 테마와 쪽 맞춤만 적용돼요.", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            if (fixed != null) {
                Label("쪽 맞춤 (이 책)", top = 12)
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ComicBookPrefs.FIT_SCREEN to "쪽 맞춤", ComicBookPrefs.FIT_WIDTH to "폭 맞춤", ComicBookPrefs.FIT_HEIGHT to "높이 맞춤").forEach { (v, l) ->
                        Pill(l, fixed.fit == v) { fixed.onFit(v) }
                    }
                }
                if (fixed.fit != ComicBookPrefs.FIT_SCREEN) Hint("폭·높이 맞춤에서는 화면을 밀어 쪽 안을 보고, 끝에 닿으면 다음 쪽으로 가요. 글자 선택은 쪽 맞춤에서 돼요.")
                if (fixed.cropAvailable) SwitchLine("여백 자르기 (이 책)", "쪽 둘레 흰 여백을 잘라 글자를 크게 보여요", fixed.crop) { v -> fixed.onCrop(v) }
            }
            // 작업 03: 서재 설정까지 나가지 않고 읽는 중에 켜고 끈다(이 기기에서 쓸 수 없다고 판정됐으면 숨김)
            if (aiImages && kr.vici.ashreader.doc.AiCapability.cached(context)?.verdict.let { it == null || it == kr.vici.ashreader.doc.AiCapability.Verdict.OK }) {
                SwitchLine("그림 AI 화질 향상", "그림·사진을 선명하게 · 글자에는 적용 안 함 · 모든 이북·PDF", s.epubAiUpscale) { v -> onChange { it.copy(epubAiUpscale = v) } }
            }
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AaTab.entries.forEach { t -> Pill(t.label, t == tab) { tab = t } }
            }
            when (tab) {
                AaTab.TEXT -> {
                    Label("글꼴")
                    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BodyFont.entries.forEach { f ->
                            val sel = f == s.font && userFile == null
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                    .border(if (sel) 2.dp else 1.dp, if (sel) cs.primary else cs.outlineVariant, RoundedCornerShape(12.dp))
                                    .clickable { onChange { it.copy(font = f, userFont = "") } }
                                    .heightIn(min = 72.dp).padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("가나다", fontFamily = previewFamily(f), fontSize = 20.sp)
                                Text(f.label, fontSize = 12.sp, textAlign = TextAlign.Center)
                            }
                        }
                    }
                    // F-20 내 글꼴
                    Label("내 글꼴", top = 8)
                    Row(Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        userFonts.forEach { f ->
                            val sel = s.userFont == f.name && userFile != null
                            Row(
                                Modifier.clip(RoundedCornerShape(20.dp))
                                    .border(if (sel) 2.dp else 1.dp, if (sel) cs.primary else cs.outline, RoundedCornerShape(20.dp))
                                    .heightIn(min = 48.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.heightIn(min = 48.dp).widthIn(max = 180.dp).clickable { onChange { it.copy(userFont = f.name) } }.padding(start = 14.dp, end = 4.dp), contentAlignment = Alignment.Center) {
                                    Text(UserFonts.label(f.name), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal)
                                }
                                IconButton(onClick = { deleting = f.name }, modifier = Modifier.size(48.dp)) { Icon(Icons.Close, "${UserFonts.label(f.name)} 글꼴 지우기", Modifier.size(18.dp)) }
                            }
                        }
                        Pill("+ 글꼴 추가", false) {
                            fontMsg = null
                            pickFont.launch(arrayOf("font/ttf", "font/otf", "font/sfnt", "font/collection", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/octet-stream"))
                        }
                    }
                    Hint(fontMsg ?: "TTF·OTF 파일을 넣으면 이북류 본문에 써요. 한글이 없는 글꼴이면 한글은 나눔명조로 나와요.")
                    deleting?.let { name ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("‘${UserFonts.label(name)}’ 글꼴을 지울까요?", fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Pill("지우기", false) {
                                scope.launch {
                                    withContext(Dispatchers.IO) { UserFonts.delete(context, name) }
                                    if (s.userFont == name) onChange { it.copy(userFont = "") }
                                    deleting = null; fontsVer++
                                }
                            }
                            Box(Modifier.padding(start = 8.dp)) { Pill("취소", false) { deleting = null } }
                        }
                    }
                    Hint("한자는 기기 글꼴로 표시돼요")
                    Stepper("글자 크기", "${s.fontSize}", small = "가", big = "가",
                        onMinus = { onChange { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(Prefs.MIN_SIZE)) } },
                        onPlus = { onChange { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(Prefs.MAX_SIZE)) } })
                    if (txt != null) {
                        Label("글자 인코딩 (TXT)", top = 16)
                        Hint("글자가 깨져 보이면 바꿔 보세요. 이 책에만 적용돼요.")
                        Row(Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val auto = "자동" + (if (txt.chosen == null && txt.detected != null) " (${txt.detected.label})" else "")
                            Pill(auto, txt.chosen == null) { txt.onPick(null) }
                            TextEncoding.Kind.entries.forEach { k -> Pill(k.label, txt.chosen == k) { txt.onPick(k) } }
                        }
                    }
                }
                AaTab.THEME -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadTheme.entries.forEach { t ->
                        val c2 = if (t == ReadTheme.CUSTOM) customColors(s.customBg, s.customFg) else t.colors(); val sel = t == s.theme
                        Box(
                            Modifier.weight(1f).heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).background(c2.bgC)
                                .border(if (sel) 2.dp else 1.dp, if (sel) cs.primary else cs.outline, RoundedCornerShape(12.dp))
                                .clickable { onChange { it.copy(theme = t) } },
                            contentAlignment = Alignment.Center,
                        ) { Text(t.label, color = c2.fgC, fontSize = 14.sp) }
                    }
                }
                AaTab.PARA -> Column {
                    Stepper("줄 간격", "%.1f".format(s.lineHeight), small = "−", big = "+",
                        onMinus = { onChange { it.copy(lineHeight = ((it.lineHeight - 0.1f) * 10).let { v -> Math.round(v) / 10f }.coerceAtLeast(1.2f)) } },
                        onPlus = { onChange { it.copy(lineHeight = ((it.lineHeight + 0.1f) * 10).let { v -> Math.round(v) / 10f }.coerceAtMost(2.4f)) } })
                    // F-19 문단 간격·여백·정렬
                    Stepper("문단 간격", if (s.paraSpace == 0) "없음" else "%.1f".format(s.paraSpace / 10f), small = "−", big = "+",
                        onMinus = { onChange { it.copy(paraSpace = (it.paraSpace - 2).coerceAtLeast(0)) } },
                        onPlus = { onChange { it.copy(paraSpace = (it.paraSpace + 2).coerceAtMost(14)) } })
                    Stepper("좌우 여백", "%.1f".format(s.marginH / 10f), small = "−", big = "+",
                        onMinus = { onChange { it.copy(marginH = (it.marginH - 5).coerceAtLeast(0)) } },
                        onPlus = { onChange { it.copy(marginH = (it.marginH + 5).coerceAtMost(40)) } })
                    Stepper("위아래 여백", "%.1f".format(s.marginV / 10f), small = "−", big = "+",
                        onMinus = { onChange { it.copy(marginV = (it.marginV - 4).coerceAtLeast(0)) } },
                        onPlus = { onChange { it.copy(marginV = (it.marginV + 4).coerceAtMost(40)) } })
                    Hint("여백은 글자 크기 배수예요(1.5 = 글자 한 개 반).")
                    Label("정렬", top = 12)
                    Choice(listOf("양쪽 맞춤", "왼쪽 맞춤"), s.align) { v -> onChange { it.copy(align = v) } }
                    // F-40 들여쓰기
                    Label("문단 첫 줄 들여쓰기", top = 8)
                    Choice(listOf("원본 유지", "1글자", "2글자", "없음"), s.indent) { v -> onChange { it.copy(indent = v) } }
                    Label("대화문(따옴표로 시작하는 문단)", top = 8)
                    Choice(listOf("들여쓰기 같게", "들여쓰기 없음"), s.dialogIndent) { v -> onChange { it.copy(dialogIndent = v) } }
                    // F-37 줄 바꿈, F-39 하이픈
                    Label("줄 바꿈", top = 8)
                    Choice(listOf("띄어쓰기 단위", "글자 단위"), if (s.breakChar) 1 else 0) { v -> onChange { it.copy(breakChar = v == 1) } }
                    Hint(if (s.breakChar) "줄 끝을 꽉 채워요. 낱말 중간에서도 줄이 바뀌어요." else "낱말을 끊지 않아요. 줄보다 긴 낱말(웹 주소 등)만 글자 단위로 끊어요.")
                    SwitchLine("영문 하이픈 넣기", "영어 낱말을 줄 끝에서 하이픈(-)으로 나눠요. 한국어에는 쓰지 않아요", s.hyphen) { v -> onChange { it.copy(hyphen = v) } }
                    // F-41 TXT 정리(TXT 파일일 때만, 원본은 그대로)
                    if (txt != null) {
                        Label("TXT 정리 — 화면에만 적용, 파일은 그대로", top = 16)
                        Text("빈 줄", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("그대로", "하나로", "모두 없앰").forEachIndexed { i, l -> Pill(l, s.txtBlank == i) { onChange { it.copy(txtBlank = i) } } }
                        }
                        SwitchLine("끊긴 줄 합치기", "문장이 끝나지 않은 줄을 다음 줄과 이어요", s.txtJoin) { v -> onChange { it.copy(txtJoin = v) } }
                        SwitchLine("줄 앞 공백 정리", "빈칸·전각 빈칸·탭을 지워요", s.txtTrim) { v -> onChange { it.copy(txtTrim = v) } }
                        // F-33 TXT 목차
                        Label("목차 만들기 — 이런 줄을 장 제목으로", top = 16)
                        Row(Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(TxtToc.CHAPTER to "제1장·제1화·1화", TxtToc.LATIN to "Chapter 1", TxtToc.NAMED to "프롤로그·에필로그·외전", TxtToc.NUMBER to "#1 · <1> · 1.").forEach { (bit, l) ->
                                Pill(l, (s.txtTocMask and bit) != 0) { onChange { it.copy(txtTocMask = it.txtTocMask xor bit) } }
                            }
                        }
                        var custom by remember(s.txtTocCustom) { mutableStateOf(s.txtTocCustom) }
                        val ok = remember(custom) { TxtToc.validCustom(custom) }
                        OutlinedTextField(
                            value = custom, onValueChange = { custom = it.take(200) }, singleLine = true,
                            label = { Text("내 제목 모양(정규식, 예: ^【.+】$)") },
                            isError = !ok,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Hint(if (!ok) "식이 맞지 않거나, 오래 걸릴 수 있는 모양이에요(괄호 반복 안의 반복 등)." else "줄 전체가 아니라 줄 안에서 맞는 곳이 있으면 제목으로 봐요(60자 이하 줄).", Modifier.weight(1f))
                            Pill("적용", false) { if (ok) onChange { it.copy(txtTocCustom = custom.trim()) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(t: String, top: Int = 0) {
    Text(t, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = top.dp))
}

@Composable
private fun Hint(t: String, modifier: Modifier = Modifier) {
    Text(t, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun Choice(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l -> Pill(l, selected == i) { onPick(i) } }
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
