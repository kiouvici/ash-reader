// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.doc

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 서재 목록 보기의 '첫 문단 3줄'(작업 07 LibraryMenus ③). 책을 열어 첫 글이 보일 때 앞 몇백 자를 앱 저장소에 둔다
 * (서재에서 책마다 파일을 열지 않게). 기기 안에만 두고 어디에도 보내지 않는다.
 */
object FirstPara {
    private const val MAX = 300
    private val mem = ConcurrentHashMap<String, String>()

    private fun dir(context: Context) = File(context.filesDir, "first-para")

    /** 저장된 첫 문단(없으면 null). 메모리에 한 번 읽어 둔다. */
    fun load(context: Context, id: String): String? {
        mem[id]?.let { return it.ifEmpty { null } }
        val f = File(dir(context), "$id.txt")
        val t = if (f.isFile) runCatching { f.readText() }.getOrDefault("") else ""
        mem[id] = t
        return t.ifEmpty { null }
    }

    /** 첫 글 저장: 줄바꿈·빈칸을 한 칸으로, 앞 [MAX]자. 이미 있으면 그대로. */
    fun save(context: Context, id: String, text: String) {
        if (!load(context, id).isNullOrEmpty()) return
        val t = text.replace(Regex("\\s+"), " ").trim().take(MAX)
        if (t.length < 20) return
        runCatching { dir(context).mkdirs(); File(dir(context), "$id.txt").writeText(t) }
        mem[id] = t
    }

    fun delete(context: Context, id: String) { mem.remove(id); File(dir(context), "$id.txt").delete() }
}
