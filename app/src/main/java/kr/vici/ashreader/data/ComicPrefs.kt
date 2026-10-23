// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.data

import android.content.Context
import org.json.JSONObject

/**
 * 만화 설정(S-33) — 책마다 기억한다(F-48·F-57). 처음 여는 책은 '모든 만화 기본값'을 따른다.
 * DB 를 바꾸지 않도록 SharedPreferences(JSON)에 둔다. 한 번만 보기 문서는 저장하지 않는다.
 */
data class ComicBookPrefs(
    /** 읽는 방향(F-48): [DIR_AUTO] 파일 정보대로(F-49), [DIR_LTR], [DIR_RTL], [DIR_TTB] 위→아래(웹툰, F-53). */
    val dir: Int = DIR_AUTO,
    /** 두 쪽 보기: 0 자동 · 1 한 쪽 · 2 두 쪽. */
    val spread: Int = 0,
    /** 첫 쪽 단독(F-50). */
    val coverAlone: Boolean = true,
    /** 짝 한 쪽 밀기(F-50): 두 쪽 보기에서 짝을 한 쪽 민다(펼침 그림 짝이 어긋난 책). */
    val shift: Boolean = false,
    /** 가로로 긴 쪽(F-51, 한 쪽 모드): 0 반으로 나눔 · 1 회전 · 2 그대로. */
    val wide: Int = 0,
    /** 칸 단위 보기(F-72): -1 앱 설정 따름 · 0 끔 · 1 눌러서 확대 · 2 자동 순서. */
    val panel: Int = -1,
    /** 웹툰 그림 사이 간격(F-53): 0 없음 · 1 작게. */
    val gap: Int = 0,
    /** 웹툰 좌우 여백(화면 폭의 %, 0~15). */
    val margin: Int = 0,
    /** 테두리 자동 자르기(F-52)와 민감도(0 약 · 1 보통 · 2 강). */
    val crop: Boolean = true,
    val cropSens: Int = 1,
    /** 밝기·대비(-1~1), 감마(0.5~2), 흑백, 색 반전(F-57). */
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val gamma: Float = 1f,
    val gray: Boolean = false,
    val invert: Boolean = false,
    /** 확대 방식(F-56): true 고품질 · false 빠름. 선명하게 0 끔 ~ 3 강. */
    val hq: Boolean = true,
    val sharpen: Int = 1,
    /** AI 화질 향상(F-73): -1 앱 설정 따름 · 0 끔 · 1 켬. */
    val ai: Int = -1,
    /** 웹툰 보기 제안(S-35)을 이 책에서 이미 물었나. */
    val webtoonAsked: Boolean = false,
    /** 맞춤 방식(F-54): [FIT_SCREEN] 화면 · [FIT_WIDTH] 폭 · [FIT_HEIGHT] 높이 · [FIT_ORIGINAL] 원본. */
    val fit: Int = FIT_SCREEN,
) {
    val webtoon: Boolean get() = dir == DIR_TTB

    fun toJson(): JSONObject = JSONObject()
        .put("dir", dir).put("spread", spread).put("cover", coverAlone).put("shift", shift).put("wide", wide).put("panel", panel)
        .put("gap", gap).put("margin", margin).put("crop", crop).put("cropSens", cropSens)
        .put("bri", brightness.toDouble()).put("con", contrast.toDouble()).put("gam", gamma.toDouble())
        .put("gray", gray).put("inv", invert).put("hq", hq).put("sharp", sharpen).put("ai", ai).put("asked", webtoonAsked).put("fit", fit)

    companion object {
        const val DIR_AUTO = 0
        const val DIR_LTR = 1
        const val DIR_RTL = 2
        const val DIR_TTB = 3
        const val FIT_SCREEN = 0
        const val FIT_WIDTH = 1
        const val FIT_HEIGHT = 2
        const val FIT_ORIGINAL = 3

        fun fromJson(o: JSONObject, base: ComicBookPrefs = ComicBookPrefs()) = ComicBookPrefs(
            dir = o.optInt("dir", base.dir).coerceIn(0, 3),
            spread = o.optInt("spread", base.spread).coerceIn(0, 2),
            coverAlone = o.optBoolean("cover", base.coverAlone),
            shift = o.optBoolean("shift", false),
            wide = o.optInt("wide", base.wide).coerceIn(0, 2),
            panel = o.optInt("panel", base.panel).coerceIn(-1, 2),
            gap = o.optInt("gap", base.gap).coerceIn(0, 1),
            margin = o.optInt("margin", base.margin).coerceIn(0, 15),
            crop = o.optBoolean("crop", base.crop),
            cropSens = o.optInt("cropSens", base.cropSens).coerceIn(0, 2),
            brightness = o.optDouble("bri", base.brightness.toDouble()).toFloat().coerceIn(-1f, 1f),
            contrast = o.optDouble("con", base.contrast.toDouble()).toFloat().coerceIn(-1f, 1f),
            gamma = o.optDouble("gam", base.gamma.toDouble()).toFloat().coerceIn(0.5f, 2f),
            gray = o.optBoolean("gray", base.gray),
            invert = o.optBoolean("inv", base.invert),
            hq = o.optBoolean("hq", base.hq),
            sharpen = o.optInt("sharp", base.sharpen).coerceIn(0, 3),
            ai = o.optInt("ai", base.ai).coerceIn(-1, 1),
            webtoonAsked = o.optBoolean("asked", base.webtoonAsked),
            fit = o.optInt("fit", base.fit).coerceIn(FIT_SCREEN, FIT_ORIGINAL),
        )
    }
}

/** [ComicBookPrefs] 저장소. 키 "b:<책 id>" = 책마다, "default" = 모든 만화 기본값. */
class ComicPrefsStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("comic_prefs", Context.MODE_PRIVATE)

    fun defaults(): ComicBookPrefs = sp.getString("default", null)?.let { s ->
        runCatching { ComicBookPrefs.fromJson(JSONObject(s)) }.getOrNull()
    } ?: ComicBookPrefs()

    fun load(bookId: String): ComicBookPrefs {
        val base = defaults().copy(webtoonAsked = false)
        val s = sp.getString("b:$bookId", null) ?: return base
        return runCatching { ComicBookPrefs.fromJson(JSONObject(s), base) }.getOrDefault(base)
    }

    fun save(bookId: String, p: ComicBookPrefs) { sp.edit().putString("b:$bookId", p.toJson().toString()).apply() }

    /** [모든 만화 기본값으로] — 이 책의 값을 앞으로 처음 여는 만화의 기본값으로. 책마다 하는 일(물어봄 표시)은 빼고. */
    fun saveDefaults(p: ComicBookPrefs) { sp.edit().putString("default", p.copy(webtoonAsked = false, shift = false).toJson().toString()).apply() }

    fun forget(bookId: String) { sp.edit().remove("b:$bookId").apply() }

    /** 백업(S-53): 저장된 값 전부("default"·"b:<책 id>" → JSON). */
    fun exportAll(): JSONObject {
        val o = JSONObject()
        for ((k, v) in sp.all) if (v is String && (k == "default" || k.startsWith("b:"))) {
            runCatching { o.put(k, JSONObject(v)) }
        }
        return o
    }

    /** 백업 가져오기: 알맞은 키만, 값은 [ComicBookPrefs]로 한 번 걸러서 쓴다. [keepBook] = 이 기기 서재에 있는 책만. */
    fun importAll(o: JSONObject, keepBook: (String) -> Boolean): Int {
        val e = sp.edit()
        var n = 0
        for (k in o.keys()) {
            val ok = k == "default" || (k.startsWith("b:") && k.length in 3..80 && keepBook(k.substring(2)))
            val v = o.optJSONObject(k) ?: continue
            if (!ok) continue
            e.putString(k, ComicBookPrefs.fromJson(v).toJson().toString()); n++
        }
        e.apply()
        return n
    }
}
