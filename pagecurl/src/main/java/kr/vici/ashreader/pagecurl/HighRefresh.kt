// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.pagecurl

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.Display
import android.view.View

/**
 * 넘김 중 최고 주사율 요청 (S26U 실측: 120Hz 설정에서도 TextureView 만 갱신되면 약 60fps 로 그려짐).
 *
 * 원인(추정): 가변 주사율 기기는 화면에 '움직이는 내용'이 있다는 투표가 없으면 60Hz 이하로 내린다.
 * TextureView 는 자기 Surface 층이 없어 Surface.setFrameRate 를 쓸 수 없고, 손가락 입력이 없는
 * 자동 넘김에서는 터치 부스트도 없다. 그래서 넘기는 동안만 두 가지로 높은 주사율을 요청한다.
 *  ① 창 preferredDisplayModeId = 같은 해상도의 최고 주사율 모드 (API 23+)
 *  ② View.setRequestedFrameRate(REQUESTED_FRAME_RATE_CATEGORY_HIGH) (API 35+, ARR 기기)
 * 넘김이 끝나면 원래 값으로 되돌린다(전력 절약).
 */
internal object HighRefresh {
    private val saved = HashMap<Int, Int>() // window 해시 → 원래 preferredDisplayModeId

    fun maxRate(view: View): Float? {
        val d = view.display ?: return null
        return bestMode(d)?.refreshRate
    }

    private fun bestMode(d: Display): Display.Mode? {
        val cur = d.mode
        return d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate }
    }

    fun request(view: View, on: Boolean) {
        if (Build.VERSION.SDK_INT >= 35) {
            runCatching {
                view.requestedFrameRate = if (on) View.REQUESTED_FRAME_RATE_CATEGORY_HIGH else View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
            }
        }
        val activity = view.context.findActivity() ?: return
        val window = activity.window ?: return
        val key = System.identityHashCode(window)
        val lp = window.attributes
        if (on) {
            val best = view.display?.let { bestMode(it) } ?: return
            if (!saved.containsKey(key)) saved[key] = lp.preferredDisplayModeId
            if (lp.preferredDisplayModeId != best.modeId) {
                lp.preferredDisplayModeId = best.modeId
                window.attributes = lp
            }
        } else {
            val orig = saved.remove(key) ?: return
            if (lp.preferredDisplayModeId != orig) {
                lp.preferredDisplayModeId = orig
                window.attributes = lp
            }
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
