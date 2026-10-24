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
    /** AI 화질 향상(작업 04, Material auto_awesome 모양을 선으로): 큰 별 + 작은 별 둘. */
    val Sparkle = line(
        "auto_awesome",
        "M10 4l1.6 4.4L16 10l-4.4 1.6L10 16l-1.6-4.4L4 10l4.4-1.6z",
        "M18 3l.7 1.8L20.5 5.5l-1.8.7L18 8l-.7-1.8-1.8-.7 1.8-.7z",
        "M18 15l.7 1.8 1.8.7-1.8.7L18 20l-.7-1.8-1.8-.7 1.8-.7z",
    )
    val Scroll = line("scroll", "M7 3h10v18H7z", "M10 8h4M10 12h4M10 16h4")

    // ── 작업 07 서재 메뉴(LibraryMenus·MoreMenu 목업 아이콘) ──
    val Menu = line("collections", "M4 6h16M4 10h16M4 14h16M4 18h11")
    val Dots = line("more-h", "M5 12h.01M12 12h.01M19 12h.01")
    val Info = line("info", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M12 11v6M12 7.5v.01")
    val FolderOpen = line("folder2", "M3 7.5A1.5 1.5 0 0 1 4.5 6h4.3l2 2.2h8.7A1.5 1.5 0 0 1 21 9.7v8.8a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18.5z")
    val Books = line("all", "M4 4.5h4v15H4z", "M10 4.5h4v15h-4z", "M15.6 5.6l3.8-1 3.6 13.9-3.8 1z")
    val FileDoc = line("format", "M6.5 3h7.5l4 4v14H6V3.5z", "M14 3v4h4")
    val Shelf = line("series", "M3.5 20h17", "M5 20V7.5h3.2V20", "M9.8 20V5h3.6v15", "M14.6 19.6l2.7-11.4 3.1.7-2.7 11.4")
    val Done = line("done", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M8 12.3l2.7 2.7L16.2 9.5")
    val Hide = line("hide", "M3 3l18 18", "M10.6 10.6a2 2 0 0 0 2.8 2.8", "M9.9 5.1A9.8 9.8 0 0 1 12 5c6 0 9.5 7 9.5 7a17 17 0 0 1-2.2 3.1", "M6.6 6.6A17 17 0 0 0 2.5 12s3.5 7 9.5 7a9.7 9.7 0 0 0 5.4-1.6")
    val Show = line("show", "M2.5 12s3.5-7 9.5-7 9.5 7 9.5 7-3.5 7-9.5 7-9.5-7-9.5-7z", "M15 12a3 3 0 1 1 -6 0a3 3 0 1 1 6 0z")
    val Clock = line("recent-files", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M12 7.5V12l3 2")
    val Group = line("group", "M4 6h10M4 12h10M4 18h7", "M18 15v6M15 18h6")
    val Picture = line("cover", "M4 5h16v14H4z", "M4 15l4-4 4 4 3-3 5 5")
    val Restart = line("restart", "M4 12a8 8 0 1 0 2.3-5.7", "M4 4v4h4")
    val Trash = line("remove", "M4 7h16", "M10 11v6M14 11v6", "M5 7l1 13h12l1-13", "M9 7V4h6v3")
    val ListView = line("list", "M8 6h12M8 12h12M8 18h12", "M4 6v.01M4 12v.01M4 18v.01")
    val Select = line("select", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M8 12.3l2.7 2.7L16.2 9.5")
    val Guide = line("guide", "M3 9h18M3 15h18")
    val Book = line("page-turn", "M6 3h12v18H6z")
    /** 쪽 넘김 — 책 모양(왼쪽 책등) 안에 좌우 화살표(대표님 23:55·23:58·23:59). */
    val ArrowsLR = line("arrows-lr", "M5 3h12.5a1.5 1.5 0 0 1 1.5 1.5v15a1.5 1.5 0 0 1-1.5 1.5H5z", "M8 3v18", "M10.5 12h6", "M12.5 9.8l-2 2.2 2 2.2", "M14.5 9.8l2 2.2-2 2.2")
    /** 스크롤 — 책 모양 안에 위아래 화살표. */
    val ArrowsUD = line("arrows-ud", "M5 3h12.5a1.5 1.5 0 0 1 1.5 1.5v15a1.5 1.5 0 0 1-1.5 1.5H5z", "M8 3v18", "M13.5 8v8", "M11.3 10l2.2-2 2.2 2", "M11.3 14l2.2 2 2.2-2")
}
