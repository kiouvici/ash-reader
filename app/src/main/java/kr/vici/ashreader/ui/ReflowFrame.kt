// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import kr.vici.ashreader.data.Settings
import kr.vici.ashreader.data.infoLineItems
import kr.vici.ashreader.doc.PageFrame

/**
 * eBook 보기 쪽 가장자리 몫(I-711): 상태 표시줄·카메라 구멍(위), 정보줄 + 내비게이션 줄(아래), 옆 구멍·옆 내비게이션 줄.
 * 막대가 숨어도 잡히는 값을 쓰므로 메뉴를 열고 닫아도 쪽 배치가 바뀌지 않는다. 가로·세로·두 쪽 펼침 모두 같은 규칙.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberPageFrame(s: Settings): PageFrame {
    val d = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val status = WindowInsets.statusBarsIgnoringVisibility
    val nav = WindowInsets.navigationBarsIgnoringVisibility
    val cut = WindowInsets.displayCutout
    fun dp(px: Int) = px / d.density
    val st = dp(status.getTop(d)); val ct = dp(cut.getTop(d)); val cb = dp(cut.getBottom(d))
    val cl = dp(cut.getLeft(d, dir)); val cr = dp(cut.getRight(d, dir))
    val nb = dp(nav.getBottom(d)); val nl = dp(nav.getLeft(d, dir)); val nr = dp(nav.getRight(d, dir))
    val infoShown = s.infoLineItems != 0 || s.infoPerPage
    val scale = d.fontScale
    // 1dp 단위로 반올림해 키로 쓴다(소수 흔들림으로 문서를 다시 배치하지 않게)
    return remember(st.toInt(), ct.toInt(), cb.toInt(), cl.toInt(), cr.toInt(), nb.toInt(), nl.toInt(), nr.toInt(), infoShown, s.infoTop, scale) {
        val f = PageFrame.of(st, ct, cb, cl, cr, nb, nl, nr, infoShown, s.infoTop, scale)
        PageFrame(kotlin.math.ceil(f.top), kotlin.math.ceil(f.bottom), kotlin.math.ceil(f.left), kotlin.math.ceil(f.right))
    }
}
