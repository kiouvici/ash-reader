// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.vici.ashreader.doc.ComicUpscale

/**
 * AI 화질 표시(작게): 처리 중 / 적용됨. 해당 없으면 아무것도 그리지 않는다.
 * [onTap] 이 있으면 단추가 된다(만화 설정 [화질] 탭·전후 비교). 누름 영역은 48dp 이상.
 */
@Composable
fun UpscaleChip(up: ComicUpscale, page: Int, modifier: Modifier = Modifier, onTap: (() -> Unit)? = null) {
    val ready by up.ready.collectAsState()
    val busy by up.busy.collectAsState()
    val text = when {
        page in ready -> "AI 화질"
        busy == page -> "AI 화질 처리 중…"
        else -> null
    }
    if (text != null) Box(
        modifier.then(if (onTap != null) Modifier.heightIn(min = 48.dp).clickable(onClickLabel = "AI 화질 설정 열기", onClick = onTap) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = 12.sp, color = Color.White,
            modifier = Modifier.background(Color(0x80000000), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * 작업 04: 이북·PDF 쪽에 AI 로 바꾼 그림이 있으면 같은 'AI 화질' 표시. 누르면 [onTap](보기 시트 — 맨 위 AI 칸).
 * 문서 그림 AI 가 꺼져 있거나 그 쪽에 바꾼 그림이 없으면 아무것도 그리지 않는다.
 */
@Composable
fun DocAiChip(doc: kr.vici.ashreader.doc.MupdfReaderDoc, page: Int, modifier: Modifier = Modifier, onTap: () -> Unit) {
    val up = doc.imageUpscale ?: return
    val version by up.version.collectAsState()
    val n = remember(version, page) { up.itemsOn(page) ?: 0 }
    if (n <= 0) return
    Box(modifier.heightIn(min = 48.dp).clickable(onClickLabel = "AI 화질 설정 열기", onClick = onTap), contentAlignment = Alignment.Center) {
        Text(
            "AI 화질", fontSize = 12.sp, color = Color.White,
            modifier = Modifier.background(Color(0x80000000), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}