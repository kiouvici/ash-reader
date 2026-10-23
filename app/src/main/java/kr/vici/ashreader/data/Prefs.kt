// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 본문 글꼴 (D-012). 파일이 없는 글꼴은 나눔명조로 대체한다(BodyFonts).
 * 리디바탕·마루부리는 OFL 예약 글꼴 이름(RFN) 때문에 앱 화면에서는 'ASH 바탕'·'ASH 부리'로 부른다(원 글꼴 이름은 '기반'으로만 밝힘).
 */
enum class BodyFont(val label: String) { NANUM_MYEONGJO("나눔명조 (기본)"), RIDI_BATANG("ASH 바탕 (리디바탕 기반)"), MARUBURI("ASH 부리 (마루부리 기반)") }

/** 읽기 테마(F-21·F-43). [CUSTOM] = 사용자 색(Settings.customBg·customFg). 순서(ordinal)를 저장하므로 끝에만 더한다. */
enum class ReadTheme(val label: String) { DAY("주간"), SEPIA("세피아"), NIGHT("야간"), CUSTOM("내 테마") }

/** 탭 영역 배치(F-64, 화면 설계서 4.1). */
enum class TapLayout(val label: String) { SIDES("좌우"), L_SHAPE("L자"), ANYWHERE("어디든 다음"), CUSTOM("내 방식") }

/** 하단 정보줄 항목(F-45). [bit] = Settings.infoItems 비트. */
enum class InfoItem(val label: String, val bit: Int) {
    CLOCK("시계", 1), BATTERY("배터리", 2), CHAPTER_LEFT("이번 장 남은 쪽", 4), PROGRESS("전체 진행률", 8), TIME_LEFT("남은 읽기 시간", 16),
}

/** 화면 꺼짐 시간(F-67). */
enum class ScreenTimeout(val label: String) { SYSTEM("시스템 따름"), FIVE_MIN("5분"), ALWAYS("계속 켜짐") }

/** 화면 회전(F-67, S-51 5). */
enum class Rotation(val label: String) { AUTO("자동"), PORTRAIT("세로 고정"), LANDSCAPE("가로 고정") }

/** 칸 단위 보기 (D-007). */
enum class PanelMode(val label: String) { TAP_ZOOM("누르면 확대"), AUTO("자동 순서"), OFF("끔") }

data class Settings(
    val font: BodyFont = BodyFont.NANUM_MYEONGJO,
    val fontSize: Int = 18,
    val lineHeight: Float = 1.8f,
    val theme: ReadTheme = ReadTheme.DAY,
    val curl: Boolean = true,
    val volumeKeys: Boolean = true,
    val panelMode: PanelMode = PanelMode.TAP_ZOOM,
    val aiUpscale: Boolean = false,
    val clearOnceOnExit: Boolean = true,
    val tapGuideShown: Boolean = false,
    /** AI 화질 개선 캐시 상한(MB). 300 / 1024(기본) / 2048 (F-73). */
    val upscaleCacheMb: Int = 1024,
    /** 파일 접근 안내(S-02)를 한 번 거쳤나(허용·거부·나중에 모두). */
    val storageAsked: Boolean = false,
    /** 서점 바로가기 기본 항목(예스24)을 넣었나(F-36). 사용자가 빼면 다시 넣지 않는다. */
    val storeInit: Boolean = false,
    // ── 4B 읽기·듣기 ──
    /** F-41 TXT 정리: 빈 줄 0=그대로 1=하나로 2=모두 없앰 / 끊긴 줄 합치기 / 줄 앞 공백 정리. */
    val txtBlank: Int = 1,
    val txtJoin: Boolean = true,
    val txtTrim: Boolean = true,
    /** F-32 TXT 기본 인코딩(서재 문서에 따로 정한 것이 없을 때). "" = 자동, 아니면 TextEncoding.Kind 이름. */
    val txtDefaultEncoding: String = "",
    /** F-42 따뜻한 색 강도(0~80%), 지정 시각 자동 켜기(분 단위 시각). */
    val warmth: Int = 0,
    val warmAuto: Boolean = false,
    val warmFrom: Int = 21 * 60,
    val warmTo: Int = 7 * 60,
    /** F-43 내 테마 색(ARGB). */
    val customBg: Int = 0xFFF3F1EA.toInt(),
    val customFg: Int = 0xFF2B2B2B.toInt(),
    /** 앱 밝기(창 밝기) 0..1, -1 = 시스템 따름. [dim] = 최저 밝기 아래 '추가 어둡게' 0~60%. */
    val brightness: Float = -1f,
    val dim: Int = 0,
    /** F-16 넘김 효과 없음(켜면 [curl] 보다 먼저). */
    val noTurnEffect: Boolean = false,
    /** F-44 자동 넘김 간격(초). */
    val autoTurnSec: Int = 10,
    /** F-45 정보줄 항목(InfoItem 비트)·위치. 기본: 시계·전체 진행률·이번 장 남은 쪽, 아래. */
    val infoItems: Int = 1 or 4 or 8,
    val infoTop: Boolean = false,
    /** F-64 탭 영역, 내 방식 3×3(p=이전 n=다음 m=메뉴 x=없음), 왼손잡이. */
    val tapLayout: TapLayout = TapLayout.SIDES,
    val tapCustom: String = "pmnpmnpmn",
    val leftHanded: Boolean = false,
    /** F-65 왼쪽 가장자리로 밝기 조절. */
    val edgeBrightness: Boolean = true,
    /** F-67 화면 꺼짐 시간·회전. */
    val screenTimeout: ScreenTimeout = ScreenTimeout.SYSTEM,
    val rotation: Rotation = Rotation.AUTO,
    /** F-21 고정 쪽 문서 야간 반전에서 그림은 반전하지 않기(기본 켬). */
    val nightKeepImages: Boolean = true,
    /** F-13 듣기 속도·높낮이, F-47 잠자기 타이머 기본(분, 0 = 끔, -1 = 장 끝까지). */
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsSleep: Int = 0,
    /** '듣는 동안 볼륨 키는 소리 크기' 1회 안내를 보였나. */
    val ttsVolumeHint: Boolean = false,
    /** F-36 서재 위 '내 서점 앱' 줄 보이기. */
    val showStores: Boolean = true,
)

class Prefs(private val context: Context) {
    private object K {
        val font = intPreferencesKey("font")
        val size = intPreferencesKey("font_size")
        val lh = floatPreferencesKey("line_height")
        val theme = intPreferencesKey("theme")
        val curl = booleanPreferencesKey("curl")
        val vol = booleanPreferencesKey("volume_keys")
        val panel = intPreferencesKey("panel_mode")
        val ai = booleanPreferencesKey("ai_upscale")
        val once = booleanPreferencesKey("clear_once")
        val tap = booleanPreferencesKey("tap_guide_shown")
        val upCache = intPreferencesKey("upscale_cache_mb")
        val storageAsked = booleanPreferencesKey("storage_asked")
        val storeInit = booleanPreferencesKey("store_init")
        val txtBlank = intPreferencesKey("txt_blank")
        val txtJoin = booleanPreferencesKey("txt_join")
        val txtTrim = booleanPreferencesKey("txt_trim")
        val txtEnc = stringPreferencesKey("txt_default_enc")
        val warmth = intPreferencesKey("warmth")
        val warmAuto = booleanPreferencesKey("warm_auto")
        val warmFrom = intPreferencesKey("warm_from")
        val warmTo = intPreferencesKey("warm_to")
        val customBg = intPreferencesKey("custom_bg")
        val customFg = intPreferencesKey("custom_fg")
        val bright = floatPreferencesKey("brightness")
        val dim = intPreferencesKey("dim")
        val autoTurn = intPreferencesKey("auto_turn_sec")
        val noEffect = booleanPreferencesKey("no_turn_effect")
        val infoItems = intPreferencesKey("info_items")
        val infoTop = booleanPreferencesKey("info_top")
        val tapLayout = intPreferencesKey("tap_layout")
        val tapCustom = stringPreferencesKey("tap_custom")
        val leftHanded = booleanPreferencesKey("left_handed")
        val edgeBright = booleanPreferencesKey("edge_brightness")
        val screenTimeout = intPreferencesKey("screen_timeout")
        val rotation = intPreferencesKey("rotation")
        val nightKeep = booleanPreferencesKey("night_keep_images")
        val ttsRate = floatPreferencesKey("tts_rate")
        val ttsPitch = floatPreferencesKey("tts_pitch")
        val ttsSleep = intPreferencesKey("tts_sleep")
        val ttsHint = booleanPreferencesKey("tts_volume_hint")
        val showStores = booleanPreferencesKey("show_stores")
    }

    val settings: Flow<Settings> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p -> read(p) }

    private fun read(p: Preferences) = Settings(
        font = BodyFont.entries.getOrElse(p[K.font] ?: 0) { BodyFont.NANUM_MYEONGJO },
        fontSize = (p[K.size] ?: 18).coerceIn(MIN_SIZE, MAX_SIZE),
        lineHeight = (p[K.lh] ?: 1.8f).coerceIn(1.2f, 2.4f),
        theme = ReadTheme.entries.getOrElse(p[K.theme] ?: 0) { ReadTheme.DAY },
        curl = p[K.curl] ?: true,
        volumeKeys = p[K.vol] ?: true,
        panelMode = PanelMode.entries.getOrElse(p[K.panel] ?: 0) { PanelMode.TAP_ZOOM },
        aiUpscale = p[K.ai] ?: false,
        clearOnceOnExit = p[K.once] ?: true,
        tapGuideShown = p[K.tap] ?: false,
        upscaleCacheMb = (p[K.upCache] ?: 1024).let { v -> UPSCALE_CACHE_CHOICES.firstOrNull { it == v } ?: 1024 },
        storageAsked = p[K.storageAsked] ?: false,
        storeInit = p[K.storeInit] ?: false,
        txtBlank = (p[K.txtBlank] ?: 1).coerceIn(0, 2),
        txtJoin = p[K.txtJoin] ?: true,
        txtTrim = p[K.txtTrim] ?: true,
        txtDefaultEncoding = p[K.txtEnc] ?: "",
        warmth = (p[K.warmth] ?: 0).coerceIn(0, 80),
        warmAuto = p[K.warmAuto] ?: false,
        warmFrom = (p[K.warmFrom] ?: 21 * 60).coerceIn(0, 24 * 60 - 1),
        warmTo = (p[K.warmTo] ?: 7 * 60).coerceIn(0, 24 * 60 - 1),
        customBg = p[K.customBg] ?: 0xFFF3F1EA.toInt(),
        customFg = p[K.customFg] ?: 0xFF2B2B2B.toInt(),
        brightness = (p[K.bright] ?: -1f).let { if (it < 0f) -1f else it.coerceIn(0.01f, 1f) },
        dim = (p[K.dim] ?: 0).coerceIn(0, 60),
        autoTurnSec = (p[K.autoTurn] ?: 10).coerceIn(2, 120),
        noTurnEffect = p[K.noEffect] ?: false,
        infoItems = p[K.infoItems] ?: (1 or 4 or 8),
        infoTop = p[K.infoTop] ?: false,
        tapLayout = TapLayout.entries.getOrElse(p[K.tapLayout] ?: 0) { TapLayout.SIDES },
        tapCustom = (p[K.tapCustom] ?: "pmnpmnpmn").takeIf { it.length == 9 } ?: "pmnpmnpmn",
        leftHanded = p[K.leftHanded] ?: false,
        edgeBrightness = p[K.edgeBright] ?: true,
        screenTimeout = ScreenTimeout.entries.getOrElse(p[K.screenTimeout] ?: 0) { ScreenTimeout.SYSTEM },
        rotation = Rotation.entries.getOrElse(p[K.rotation] ?: 0) { Rotation.AUTO },
        nightKeepImages = p[K.nightKeep] ?: true,
        ttsRate = (p[K.ttsRate] ?: 1f).coerceIn(0.5f, 3f),
        ttsPitch = (p[K.ttsPitch] ?: 1f).coerceIn(0.5f, 2f),
        ttsSleep = p[K.ttsSleep] ?: 0,
        ttsVolumeHint = p[K.ttsHint] ?: false,
        showStores = p[K.showStores] ?: true,
    )

    /**
     * 설정 바꾸기. [f] 는 DataStore edit 안에서 '지금 저장된 값'에 적용한다
     * (화면이 들고 있던 옛 값에 적용하면 빠르게 두 번 바꿀 때 앞의 변경이 사라진다).
     */
    suspend fun update(f: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            val s = f(read(p))
            p[K.font] = s.font.ordinal
            p[K.size] = s.fontSize
            p[K.lh] = s.lineHeight
            p[K.theme] = s.theme.ordinal
            p[K.curl] = s.curl
            p[K.vol] = s.volumeKeys
            p[K.panel] = s.panelMode.ordinal
            p[K.ai] = s.aiUpscale
            p[K.once] = s.clearOnceOnExit
            p[K.tap] = s.tapGuideShown
            p[K.upCache] = s.upscaleCacheMb
            p[K.storageAsked] = s.storageAsked
            p[K.storeInit] = s.storeInit
            p[K.txtBlank] = s.txtBlank
            p[K.txtJoin] = s.txtJoin
            p[K.txtTrim] = s.txtTrim
            p[K.txtEnc] = s.txtDefaultEncoding
            p[K.warmth] = s.warmth
            p[K.warmAuto] = s.warmAuto
            p[K.warmFrom] = s.warmFrom
            p[K.warmTo] = s.warmTo
            p[K.customBg] = s.customBg
            p[K.customFg] = s.customFg
            p[K.bright] = s.brightness
            p[K.dim] = s.dim
            p[K.autoTurn] = s.autoTurnSec
            p[K.noEffect] = s.noTurnEffect
            p[K.infoItems] = s.infoItems
            p[K.infoTop] = s.infoTop
            p[K.tapLayout] = s.tapLayout.ordinal
            p[K.tapCustom] = s.tapCustom
            p[K.leftHanded] = s.leftHanded
            p[K.edgeBright] = s.edgeBrightness
            p[K.screenTimeout] = s.screenTimeout.ordinal
            p[K.rotation] = s.rotation.ordinal
            p[K.nightKeep] = s.nightKeepImages
            p[K.ttsRate] = s.ttsRate
            p[K.ttsPitch] = s.ttsPitch
            p[K.ttsSleep] = s.ttsSleep
            p[K.ttsHint] = s.ttsVolumeHint
            p[K.showStores] = s.showStores
        }
    }

    companion object {
        const val MIN_SIZE = 12
        const val MAX_SIZE = 32
        /** AI 화질 개선 캐시 상한 고르기(MB). */
        val UPSCALE_CACHE_CHOICES = listOf(300, 1024, 2048)
    }
}
