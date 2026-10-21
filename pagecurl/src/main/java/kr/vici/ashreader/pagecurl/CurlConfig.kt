// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.pagecurl

import android.graphics.Bitmap
import android.graphics.Color

/** 넘김 방향 (F-48). RTL = 오→왼(만화). */
enum class TurnDirection { LTR, RTL }

/** 넘김 효과 (F-15·F-16). */
enum class TurnEffect { CURL, SLIDE, NONE }

/** 두 쪽 펼침 (F-18). AUTO = 태블릿(짧은 변 600dp 이상) 가로에서만 펼침. */
enum class SpreadMode { AUTO, SINGLE, SPREAD }

data class CurlConfig(
    val direction: TurnDirection = TurnDirection.LTR,
    val effect: TurnEffect = TurnEffect.CURL,
    val spread: SpreadMode = SpreadMode.AUTO,
    /** F-50: 두 쪽 펼침에서 첫 쪽(표지)을 오른쪽에 단독으로 둔다. false = 짝을 한 쪽 민 배치(0·1쪽이 첫 펼침). */
    val coverAlone: Boolean = true,
    /** 말린 뒷면에 섞는 테마 바탕색 (M-03). 쪽이 아직 준비 안 됐을 때 빈 종이 색으로도 쓴다. */
    val backColor: Int = Color.WHITE,
    /** 한 쪽 보기에서 뒷면(앞 쪽이 비친 모습)에 바탕색을 섞는 비율. 기술 설계서 4.2: 70%. */
    val backMix: Float = 0.7f,
    /** 그림자 세기 배율. 야간 테마는 0.5 (화면 설계서 1.3). */
    val shadowStrength: Float = 1f,
    /** 한 번 넘기는 애니메이션 길이(처음부터 끝까지). 손을 뗀 뒤에는 남은 거리에 비례. */
    val turnDurationMs: Long = 450,
)

/** 쪽 비트맵 공급자. 형식과 무관하게 비트맵만 받는다(F-17). */
interface PageSource {
    val pageCount: Int

    /**
     * 백그라운드 스레드에서 불린다. [width]×[height] 크기(한 쪽 크기)의 비트맵을 돌려준다.
     * 돌려준 비트맵은 GL 텍스처로 올린 뒤 더 쓰지 않는다(재활용은 공급자 몫).
     * 강조(형광펜·검색·TTS)는 이 비트맵에 구워서 준다(M-03).
     */
    fun render(index: Int, width: Int, height: Int): Bitmap
}
