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

enum class ReadTheme(val label: String) { DAY("주간"), SEPIA("세피아"), NIGHT("야간") }

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
        }
    }

    companion object {
        const val MIN_SIZE = 12
        const val MAX_SIZE = 32
        /** AI 화질 개선 캐시 상한 고르기(MB). */
        val UPSCALE_CACHE_CHOICES = listOf(300, 1024, 2048)
    }
}
