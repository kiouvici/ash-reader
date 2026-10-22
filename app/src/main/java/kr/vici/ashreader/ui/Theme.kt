// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kr.vici.ashreader.R
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.FormatGroup
import kr.vici.ashreader.data.ReadTheme

/** 'ASH 잉크' 고정 팔레트 (화면 설계서 1.2). */
object Ash {
    val Ink = Color(0xFF2E4A6B)
    val InkDark = Color(0xFFA8C5EA)
    val Brown = Color(0xFF8C5A2B)
    val Gold = Color(0xFFF0C674)
    val Paper = Color(0xFFFBF9F5)
    val Text = Color(0xFF1F1F1F)
    val Sub = Color(0xFF5F6368)
    val Line = Color(0xFFE5E0D6)
    val Outline = Color(0xFFC9C4BA)
    val Chip = Color(0xFFF0EDE6)
    val ChipOn = Color(0xFFE8EEF5)
    val Cover = Color(0xFFF4ECD8)
}

private val Light = lightColorScheme(
    primary = Ash.Ink, onPrimary = Color.White, secondary = Ash.Brown, onSecondary = Color.White,
    background = Ash.Paper, surface = Ash.Paper, onBackground = Ash.Text, onSurface = Ash.Text,
    onSurfaceVariant = Ash.Sub, outline = Ash.Outline, outlineVariant = Ash.Line,
    surfaceVariant = Ash.Chip, secondaryContainer = Ash.ChipOn, onSecondaryContainer = Ash.Ink,
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerLow = Ash.Paper,
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Ash.InkDark, onPrimary = Color(0xFF0E2238), secondary = Ash.Gold, onSecondary = Color(0xFF2A1C00),
    background = Color(0xFF121417), surface = Color(0xFF121417), onBackground = Color(0xFFE3E3E3), onSurface = Color(0xFFE3E3E3),
    onSurfaceVariant = Color(0xFFA0A4A8), outline = Color(0xFF5A5F66), outlineVariant = Color(0xFF2E3034),
    surfaceVariant = Color(0xFF22252A), secondaryContainer = Color(0xFF233448), onSecondaryContainer = Ash.InkDark,
    surfaceContainer = Color(0xFF1C1D20), surfaceContainerHigh = Color(0xFF24262A), surfaceContainerLow = Color(0xFF16181B),
    error = Color(0xFFF2B8B5),
)

@Composable
fun AshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** 나눔명조(번들) — 서재 제목·Aa 미리보기에 쓴다. */
val Myeongjo = FontFamily(
    Font(R.font.nanum_myeongjo_regular, FontWeight.Normal),
    Font(R.font.nanum_myeongjo_bold, FontWeight.Bold),
)

/** 리디바탕(OFL 1.1, © RIDI & Sandoll) — Aa 미리보기. */
val RidiBatang = FontFamily(Font(R.font.ridibatang, FontWeight.Normal))

/** 마루부리(OFL 1.1, © NAVER Corp.) — Aa 미리보기. */
val MaruBuri = FontFamily(
    Font(R.font.maruburi_regular, FontWeight.Normal),
    Font(R.font.maruburi_bold, FontWeight.Bold),
)

/** 읽기 테마 색 (화면 설계서 1.3, Reader·Curl 목업). ARGB Int 는 엔진 비트맵용. */
data class ReadingColors(
    val bg: Int, val fg: Int, val sub: Int, val bar: Int, val line: Int, val accent: Int, val onAccent: Int,
    val shadow: Float,
) {
    val bgC get() = Color(bg)
    val fgC get() = Color(fg)
    val subC get() = Color(sub)
    val barC get() = Color(bar)
    val lineC get() = Color(line)
    val accentC get() = Color(accent)
    val onAccentC get() = Color(onAccent)
}

fun ReadTheme.colors(): ReadingColors = when (this) {
    ReadTheme.DAY -> ReadingColors(0xFFFFFFFF.toInt(), 0xFF1F1F1F.toInt(), 0xFF5F6368.toInt(), 0xFFFBF9F5.toInt(), 0xFFE5E0D6.toInt(), 0xFF2E4A6B.toInt(), 0xFFFFFFFF.toInt(), 1f)
    ReadTheme.SEPIA -> ReadingColors(0xFFF4ECD8.toInt(), 0xFF4A3A2C.toInt(), 0xFF6E5A45.toInt(), 0xFFEDE2C8.toInt(), 0xFFD8C8A6.toInt(), 0xFF8C5A2B.toInt(), 0xFFFFFFFF.toInt(), 1f)
    ReadTheme.NIGHT -> ReadingColors(0xFF121212.toInt(), 0xFFCFCFCF.toInt(), 0xFFBFC3C7.toInt(), 0xFF1C1D20.toInt(), 0xFF2E3034.toInt(), 0xFF9EC5F5.toInt(), 0xFF0E2238.toInt(), 0.5f)
}

/** 형식 배지 바탕색(밝은 화면). */
fun DocFormat.badgeColor(): Color = when (group) {
    FormatGroup.EBOOK -> Color(0xFF1D6B57)
    FormatGroup.TEXT -> Color(0xFF5B5B5B)
    FormatGroup.PDF -> Color(0xFFB23A48)
    FormatGroup.COMIC -> Color(0xFF7A3E9D)
    FormatGroup.HWP -> Color(0xFF0F6E7A)
    FormatGroup.WORD -> Color(0xFF1F5FA8)
    FormatGroup.ZIP -> Color(0xFF8A5A00)
}

/** 표지 대용 바탕(표지 추출 전, 서재 목업 색). */
fun DocFormat.coverColor(): Color = when (group) {
    FormatGroup.EBOOK -> Color(0xFFDCEBE5)
    FormatGroup.TEXT -> Color(0xFFECECEC)
    FormatGroup.PDF -> Color(0xFFF1E6E7)
    FormatGroup.COMIC -> Color(0xFF3B3B48)
    FormatGroup.HWP -> Color(0xFFE8EEF1)
    FormatGroup.WORD -> Color(0xFFE6EDF6)
    FormatGroup.ZIP -> Color(0xFFF3E9D2)
}
