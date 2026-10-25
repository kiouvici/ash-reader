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
import kr.vici.ashreader.data.Settings

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

/**
 * 앱 화면 테마(작업 06): [mode] 0 = 시스템, 1 = 밝게, 2 = 어둡게. 읽기 화면 종이 색(ReadTheme)과는 따로다.
 * ('배경화면 색 따르기'는 대표님 결정으로 뺌. 어둡게의 화면별 색은 다크 모드 목업을 받은 뒤 맞춘다.)
 */
@Composable
fun AshTheme(mode: Int = 0, content: @Composable () -> Unit) {
    val dark = when (mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
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
    /** 고정 쪽 문서 색 변환에서 그림은 원래 색으로 둔다(야간 '그림은 반전하지 않기', F-21). 이북류는 늘 그림을 그대로 둔다(M-05). */
    val keepImages: Boolean = false,
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
    ReadTheme.CUSTOM -> customColors(0xFFF3F1EA.toInt(), 0xFF2B2B2B.toInt())
}

/** 설정을 반영한 읽기 색: 내 테마(F-43)와 야간 '그림은 반전하지 않기'(F-21). */
fun Settings.readingColors(): ReadingColors {
    val base = if (theme == ReadTheme.CUSTOM) customColors(customBg, customFg) else theme.colors()
    return base.copy(keepImages = nightKeepImages && (theme == ReadTheme.NIGHT || theme == ReadTheme.CUSTOM))
}

/** 내 테마(F-43): 바탕·글자색에서 보조색·막대·선·강조색을 만든다. */
fun customColors(bg: Int, fg: Int): ReadingColors {
    val dark = luminance(bg) < 0.35
    return ReadingColors(
        bg or 0xFF000000.toInt(), fg or 0xFF000000.toInt(), mix(fg, bg, 0.3f), mix(bg, fg, 0.06f), mix(bg, fg, 0.16f),
        if (dark) 0xFF9EC5F5.toInt() else 0xFF2E4A6B.toInt(), if (dark) 0xFF0E2238.toInt() else 0xFFFFFFFF.toInt(),
        if (dark) 0.5f else 1f,
    )
}

/** 두 색 섞기: [t]=0 → [a], 1 → [b]. */
fun mix(a: Int, b: Int, t: Float): Int {
    fun ch(c: Int, s: Int) = (c shr s) and 0xFF
    fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
}

/** 상대 휘도(WCAG). */
fun luminance(c: Int): Double {
    fun lin(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
    return 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
}

/** 대비(WCAG, 1~21). 내 테마 저장 때 4.5 미만이면 경고. */
fun contrast(a: Int, b: Int): Double {
    val la = luminance(a); val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
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
