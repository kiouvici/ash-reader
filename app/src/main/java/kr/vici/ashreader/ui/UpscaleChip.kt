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
