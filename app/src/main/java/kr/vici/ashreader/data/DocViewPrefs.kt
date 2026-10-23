// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.data

import android.content.Context

/**
 * 고정 쪽 문서(PDF·XPS·DjVu·한글·Word 원본) 보기 값 — 책마다 기억한다.
 * F-23 맞춤([fit]: [ComicBookPrefs.FIT_SCREEN] 쪽 · FIT_WIDTH 폭 · FIT_HEIGHT 높이), F-24 여백 자르기([crop]).
 * DB 를 바꾸지 않도록 SharedPreferences 에 둔다. 한 번만 보기 문서는 저장하지 않는다.
 */
data class DocViewPrefs(val fit: Int = ComicBookPrefs.FIT_SCREEN, val crop: Boolean = false)

class DocViewStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("doc_view", Context.MODE_PRIVATE)

    fun load(bookId: String): DocViewPrefs {
        val v = sp.getString("b:$bookId", null) ?: return DocViewPrefs()
        val parts = v.split(',')
        val fit = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it == ComicBookPrefs.FIT_SCREEN || it == ComicBookPrefs.FIT_WIDTH || it == ComicBookPrefs.FIT_HEIGHT }
        return DocViewPrefs(fit ?: ComicBookPrefs.FIT_SCREEN, parts.getOrNull(1) == "1")
    }

    fun save(bookId: String, p: DocViewPrefs) {
        if (p == DocViewPrefs()) sp.edit().remove("b:$bookId").apply()
        else sp.edit().putString("b:$bookId", "${p.fit},${if (p.crop) 1 else 0}").apply()
    }
}
