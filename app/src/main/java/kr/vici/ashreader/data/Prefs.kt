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

/** 본문 글꼴 (D-012). 파일이 없는 글꼴은 나눔명조로 대체한다(BodyFonts). */
enum class BodyFont(val label: String) { NANUM_MYEONGJO("나눔명조 (기본)"), RIDI_BATANG("리디바탕"), MARUBURI("마루부리") }

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
    }

    val settings: Flow<Settings> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
            Settings(
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
            )
        }

    suspend fun update(f: (Settings) -> Settings, current: Settings) {
        val s = f(current)
        context.dataStore.edit { p ->
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
        }
    }

    companion object {
        const val MIN_SIZE = 12
        const val MAX_SIZE = 32
    }
}
