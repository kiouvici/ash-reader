// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable

// 애플(iOS)식 단추·막대(대표님 2026-10-25 01:10: 애플스럽지 않은 것은 애플스럽게). 머티리얼 기본 모양 대신 앱 전체가 이것을 쓴다.

/** 애플식 큰 제목 크기(서재·모음·형식·설정·독서 기록 모두 같게). */
const val LARGE_TITLE = 34f

/** iOS 파랑(막대 지난 쪽·고른 것). */
val AppleBlue = Color(0xFF0A84FF)

/** iOS 초록(켜짐). */
val AppleGreen = Color(0xFF34C759)

/**
 * iOS 스위치: 51×31 둥근 막대 + 흰 동그라미(그림자), 켜면 초록·꺼지면 옅은 회색. 미끄러지듯 움직인다.
 * [onCheckedChange] 가 null 이면 누름은 바깥 줄(toggleable)이 받는다.
 */
@Composable
fun AppleSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true, dark: Boolean = LocalAshDark.current) {
    val off = if (dark) Color(0xFF39393D) else Color(0xFFE9E9EA)
    val track by animateColorAsState(if (checked) AppleGreen else off, label = "track")
    val x by animateDpAsState(if (checked) 22.dp else 2.dp, spring(dampingRatio = 0.75f, stiffness = 600f), label = "thumb")
    Box(
        modifier.size(51.dp, 31.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(15.5.dp)).background(track)
            .then(if (onCheckedChange != null) Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange) else Modifier),
    ) {
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = x).size(27.dp)
                .shadow(3.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.25f))
                .background(Color.White, CircleShape),
        )
    }
}


/**
 * iOS 막대: 4dp 가는 줄(지난 쪽 = [active]) + 흰 동그라미 손잡이(그림자). [steps] 가 있으면 눈금마다 딱딱 걸린다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppleSlider(
    value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>, active: Color = AppleBlue, inactive: Color = Color(0x33787880),
    modifier: Modifier = Modifier, onValueChangeFinished: (() -> Unit)? = null, steps: Int = 0, enabled: Boolean = true,
) {
    Slider(
        value = value, onValueChange = onValueChange, modifier = modifier.alpha(if (enabled) 1f else 0.4f), enabled = enabled,
        onValueChangeFinished = onValueChangeFinished, steps = steps, valueRange = valueRange,
        thumb = {
            Box(
                Modifier.size(26.dp).shadow(3.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.28f))
                    .background(Color.White, CircleShape).border(0.5.dp, Color.Black.copy(alpha = 0.05f), CircleShape),
            )
        },
        track = { st ->
            val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
            val f = ((st.value - valueRange.start) / span).coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(inactive, cornerRadius = r)
                drawRoundRect(active, size = Size(size.width * f, size.height), cornerRadius = r)
            }
        },
    )
}

/**
 * iOS 구분 단추(Segmented Control): 옅은 회색 바탕 안에 고른 칸만 흰 바탕(그림자). 탭 고르기에 쓴다.
 */
@Composable
fun AppleSegmented(labels: List<String>, selected: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier, dark: Boolean = LocalAshDark.current) {
    val track = if (dark) Color(0xFF2C2C2E) else Color(0x1F767680)
    val on = if (dark) Color(0xFF636366) else Color.White
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(track).padding(2.dp),
    ) {
        labels.forEachIndexed { i, l ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).height(40.dp)
                    .then(if (sel) Modifier.shadow(2.dp, RoundedCornerShape(8.dp), ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.15f)) else Modifier)
                    .clip(RoundedCornerShape(8.dp)).background(if (sel) on else Color.Transparent)
                    .selectable(sel, role = Role.Tab) { onPick(i) },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Text(
                    l, fontSize = 14.sp, fontWeight = if (sel) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                    color = if (dark) Color.White else Color.Black, maxLines = 1,
                )
            }
        }
    }
}