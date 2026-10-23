// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 목업과 같은 선 아이콘(24 격자, 선 굵기 2). 아이콘 라이브러리 의존성을 늘리지 않으려고 직접 정의.
 * TODO(디자인): Material Symbols Rounded 로 바꿀지 결정(화면 설계서 1.2).
 */
object Icons {
    private fun line(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) b.addPath(
            pathData = addPathNodes(p), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        return b.build()
    }

    private const val CIRCLE7 = "M18 11a7 7 0 1 1 -14 0a7 7 0 1 1 14 0z"

    val Search = line("search", CIRCLE7, "M16.5 16.5L21 21")
    val Grid = line("grid", "M3 3h7v7H3z", "M14 3h7v7h-7z", "M3 14h7v7H3z", "M14 14h7v7h-7z")
    val Back = line("back", "M15 5l-7 7 7 7")
    val Bookmark = line("bookmark", "M6 3h12v18l-6-4-6 4z")
    val Toc = line("toc", "M4 6h16M4 12h16M4 18h10")
    val Moon = line("moon", "M20 14A8 8 0 1 1 10 4a6 6 0 0 0 10 10z")
    val Speaker = line("listen", "M4 9v6h4l5 4V5L8 9z", "M16 9a4 4 0 0 1 0 6")
    val Highlight = line("highlight", "M4 4h12l4 4v12H4z", "M8 12h8M8 16h5")
    val Pen = line("memo", "M4 20h4L20 8l-4-4L4 16z")
    val Library = line("library", "M4 4h6v16H4z", "M14 4l6 2-4 14-6-2z")
    val Chart = line("stats", "M4 20V10M10 20V4M16 20v-8M22 20H2")
    val Gear = line("settings", "M15 12a3 3 0 1 1 -6 0a3 3 0 1 1 6 0z", "M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M5 19l2-2M17 7l2-2")
    val Close = line("close", "M6 6l12 12M18 6L6 18")
    val Plus = line("plus", "M12 5v14M5 12h14")
    val Minus = line("minus", "M5 12h14")
    val Chevron = line("chevron", "M9 5l7 7-7 7")
    val Star = line("star", "M12 3l2.8 5.7 6.2.9-4.5 4.4 1.1 6.2L12 17.3 6.4 20.2l1.1-6.2L3 9.6l6.2-.9z")
    val More = line("more", "M12 5h.01M12 12h.01M12 19h.01")
    val Up = line("up", "M6 15l6-6 6 6")
    val Down = line("down", "M6 9l6 6 6-6")
    val Folder = line("folder", "M3 6h6l2 2h10v11H3z")
    val Refresh = line("refresh", "M20 12a8 8 0 1 1 -2.3 -5.7", "M20 4v5h-5")
    val Rotate = line("rotate", "M4 12a8 8 0 0 1 13.7-5.6", "M18 3v4h-4", "M20 12a8 8 0 0 1 -13.7 5.6", "M6 21v-4h4")
    val RotateLock = line("rotate-lock", "M4 12a8 8 0 0 1 13.7-5.6", "M18 3v4h-4", "M9 13h6v5H9z", "M10.5 13v-1.5a1.5 1.5 0 0 1 3 0V13")
    val Sun = line("sun", "M16 12a4 4 0 1 1 -8 0a4 4 0 1 1 8 0z", "M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4")
    val Turn = line("turn", "M4 7h13l-3-3", "M20 17H7l3 3")
    val Play = line("play", "M7 4l13 8-13 8z")
    val Pause = line("pause", "M8 4v16M16 4v16")
    val Prev = line("prev", "M18 5l-9 7 9 7z", "M6 5v14")
    val Next = line("next", "M6 5l9 7-9 7z", "M18 5v14")
    val Timer = line("timer", "M19 13a7 7 0 1 1 -14 0a7 7 0 1 1 14 0z", "M12 13V9", "M10 2h4")
    val Share = line("share", "M12 3v12", "M7 8l5-5 5 5", "M5 14v6h14v-6")
    val Save = line("save", "M12 4v11", "M7 10l5 5 5-5", "M5 19h14")
    val Scroll = line("scroll", "M7 3h10v18H7z", "M10 8h4M10 12h4M10 16h4")
}
