// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.vici.ashreader.doc.AiCapability

/** 보기 시트 맨 위 AI 칸의 문서 종류(부제가 다르다, 작업 04). */
enum class AiKind(val sub: String) {
    EBOOK("책 속 작은 그림·삽화 · 글자는 그대로"),
    PDF("쪽 속 그림·사진 · 글자는 그대로"),
    COMIC("쪽 전체를 2배 선명하게"),
}

/**
 * 작업 04: 이북 Aa·PDF 보기·만화 설정 시트 맨 위에 두는 같은 모양의 'AI 화질 향상' 칸.
 * 흰 카드(모서리 14dp, 옅은 테두리): 아이콘 · 제목 · 부제 · 스위치, 아래 [전후 비교](꽉 찬 폭, 40dp 알약).
 * 스위치가 꺼져 있으면 [전후 비교]는 흐리게. 이 기기에서 AI 를 못 쓰면 칸 대신 이유 한 줄(S-33).
 * 처음 켤 때는 확인창(S-36, 앱 전체 한 번). [comparing] = 지금 전후 비교 중이면 단추가 "전후 비교 끄기".
 */
@Composable
fun AiQualityCard(
    kind: AiKind,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    onCompare: (() -> Unit)?,
    modifier: Modifier = Modifier,
    comparing: Boolean = false,
    limitMb: Int = 1024,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val cap by produceState(AiCapability.cached(context)) {
        if (value == null) value = AiCapability.measureWithin(context)
    }
    val c = cap
    when {
        c == null -> { Reason("이 기기에서 쓸 수 있는지 확인하는 중이에요… (약 10초)", modifier); return }
        c.verdict == AiCapability.Verdict.LOW_MEMORY -> { Reason("이 기기는 메모리가 부족해서 AI 화질 향상을 쓸 수 없어요.", modifier); return }
        c.verdict == AiCapability.Verdict.TOO_SLOW -> { Reason("이 기기에서는 처리가 너무 느려서 AI 화질 향상을 쓸 수 없어요.", modifier); return }
        else -> {}
    }
    // S-36: 처음 켤 때 확인창. 값 = 확인 뒤 비교도 열지(true)
    var confirm by remember { mutableStateOf<Boolean?>(null) }
    fun turnOn(thenCompare: Boolean) {
        if (!AiConsent.given(context)) { confirm = thenCompare; return }
        onToggle(true)
        if (thenCompare) onCompare?.invoke()
    }
    Surface(
        modifier = modifier.fillMaxWidth().border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp), color = cs.surface,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = on, role = Role.Switch) { v -> if (v) turnOn(false) else onToggle(false) }
                    .semantics { contentDescription = "AI 화질 향상" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Sparkle, null, Modifier.size(24.dp), tint = cs.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("AI 화질 향상", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(kind.sub, fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
                Switch(checked = on, onCheckedChange = null)
            }
            if (!c.gpu) Text("이 기기에서는 쪽마다 시간이 조금 걸려요(약 ${c.secPerPage}초).", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            extra()
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onCompare?.invoke() }, enabled = on && onCompare != null,
                shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
            ) { Text(if (comparing) "전후 비교 끄기" else "전후 비교", fontSize = 14.sp) }
        }
    }
    confirm?.let { thenCompare ->
        val lim = if (limitMb >= 1024) "${limitMb / 1024}GB" else "${limitMb}MB"
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text("AI 화질 향상을 켤까요? 처리하는 동안 배터리를 더 쓰고 기기가 따뜻해질 수 있어요. 처리한 그림은 최대 ${lim}까지 저장해 둡니다.") },
            confirmButton = {
                TextButton(onClick = { AiConsent.give(context); confirm = null; onToggle(true); if (thenCompare) onCompare?.invoke() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("켜기") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } },
        )
    }
}

@Composable
private fun Reason(t: String, modifier: Modifier) {
    Text(t, fontSize = 14.sp, modifier = modifier.fillMaxWidth().padding(vertical = 12.dp))
}

/** S-36 처음 켤 때 확인창을 봤나(앱 전체 한 번). */
internal object AiConsent {
    fun given(c: android.content.Context) = c.getSharedPreferences("comic_prefs", android.content.Context.MODE_PRIVATE).getBoolean("ai_consent", false)
    fun give(c: android.content.Context) { c.getSharedPreferences("comic_prefs", android.content.Context.MODE_PRIVATE).edit().putBoolean("ai_consent", true).apply() }
}
