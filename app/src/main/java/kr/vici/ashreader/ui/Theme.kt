// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
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

/**
 * 어둡게(작업 07 DarkMode 목업 — 애플 북스 다크): 바탕 #000, 띠 #161618, 카드 #1C1C1E·#2C2C2E,
 * 글자 흰색·보조 #98989F. surfaceContainerLow = 띠, surfaceContainer = 설정 카드·단추, surfaceContainerHigh = 서재 카드.
 */
private val Dark = darkColorScheme(
    primary = Color(0xFF409CFF), onPrimary = Color.Black, secondary = Ash.Gold, onSecondary = Color(0xFF2A1C00),
    background = Color.Black, surface = Color.Black, onBackground = Color.White, onSurface = Color.White,
    onSurfaceVariant = Color(0xFF98989F), outline = Color(0xFF5A5A5E), outlineVariant = Color(0xFF2C2C2E),
    surfaceVariant = Color(0xFF2C2C2E), secondaryContainer = Color(0xFF3A3A3C), onSecondaryContainer = Color.White,
    surfaceContainer = Color(0xFF1C1C1E), surfaceContainerHigh = Color(0xFF2C2C2E), surfaceContainerLow = Color(0xFF161618),
    surfaceContainerHighest = Color(0xFF3A3A3C), surfaceContainerLowest = Color.Black,
    error = Color(0xFFFF453A),
)

/** 지금 앱 화면이 어둡게인가(표지 은은한 빛·유리 메뉴 색 등에 씀). */
val LocalAshDark = staticCompositionLocalOf { false }

/** 감수 1: 12sp 이하 보조 글자 — 밝게 #6C6C70, 어둡게 #98989F. */
val smallSub: Color @Composable get() = if (LocalAshDark.current) Color(0xFF98989F) else Color(0xFF6C6C70)

/**
 * 앱 화면 테마(작업 06·07): [mode] 0 = 시스템, 1 = 밝게, 2 = 어둡게. 읽기 화면 종이 색(ReadTheme)과는 따로다.
 * 상태 표시줄 아이콘도 앱 테마를 따른다(어둡게 → 흰 아이콘).
 */
@Composable
fun AshTheme(mode: Int = 0, content: @Composable () -> Unit) {
    val dark = when (mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).run {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalAshDark provides dark) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
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
    // 작업 07 ReaderNew 목업 테마 타일 색: 고요 #F4EFE6/#3B342B, 종이 #ECE7DD/#2B2B2B, 볼드 흰 바탕 검은 굵은 글자, 평온 #E9EFE9/#23302A, 집중 #000/#E8E8EA
    ReadTheme.SEPIA -> ReadingColors(0xFFF4EFE6.toInt(), 0xFF3B342B.toInt(), 0xFF6E6252.toInt(), 0xFFEDE6DA.toInt(), 0xFFD9CFBE.toInt(), 0xFF8C5A2B.toInt(), 0xFFFFFFFF.toInt(), 1f)
    ReadTheme.NIGHT -> ReadingColors(0xFF000000.toInt(), 0xFFE8E8EA.toInt(), 0xFF98989F.toInt(), 0xFF1C1C1E.toInt(), 0xFF2C2C2E.toInt(), 0xFF9EC5F5.toInt(), 0xFF0E2238.toInt(), 0.5f)
    ReadTheme.CUSTOM -> customColors(0xFFF3F1EA.toInt(), 0xFF2B2B2B.toInt())
    ReadTheme.PAPER -> ReadingColors(0xFFECE7DD.toInt(), 0xFF2B2B2B.toInt(), 0xFF5E5A52.toInt(), 0xFFE4DED2.toInt(), 0xFFD2CBBD.toInt(), 0xFF6B4A2B.toInt(), 0xFFFFFFFF.toInt(), 1f)
    ReadTheme.BOLD -> ReadingColors(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF3A3A3C.toInt(), 0xFFF7F7F8.toInt(), 0xFFE0E0E3.toInt(), 0xFF1E5FB4.toInt(), 0xFFFFFFFF.toInt(), 1f)
    ReadTheme.CALM -> ReadingColors(0xFFE9EFE9.toInt(), 0xFF23302A.toInt(), 0xFF56635C.toInt(), 0xFFE0E8E1.toInt(), 0xFFC9D5CC.toInt(), 0xFF2F6B4F.toInt(), 0xFFFFFFFF.toInt(), 1f)
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
