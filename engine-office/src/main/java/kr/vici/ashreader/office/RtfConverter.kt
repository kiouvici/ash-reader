// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * RTF → XHTML (본문 글자만). 문단(\par)·줄바꿈(\line)·탭·표 칸(\cell, \row)·유니코드(\uN)·
 * 코드 페이지 바이트(\'hh, \ansicpgN — 한국어 949) 를 옮기고, 글꼴표·스타일·그림·머리글 같은 부가 영역은 건너뛴다.
 * 서식(굵게·크기·색)은 다루지 않는다.
 */
object RtfConverter {
    class Result(val xhtml: String, val paragraphTexts: List<String>)

    /** 내용이 아닌 영역(통째로 건너뜀). */
    private val SKIP = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "headerl", "headerr", "headerf",
        "footer", "footerl", "footerr", "footerf", "listtable", "listoverridetable", "rsidtbl", "generator",
        "xmlnstbl", "themedata", "colorschememapping", "datastore", "latentstyles", "pgdsctbl", "object",
        "fldinst", "filetbl", "revtbl", "bkmkstart", "bkmkend", "footnote", "annotation", "nonshppict", "shp",
    )

    private class State(var skip: Boolean, var uc: Int)

    fun convert(bytes: ByteArray): Result {
        val paras = ArrayList<String>()
        val cur = StringBuilder()
        val pending = ByteArrayOutputStream()
        var charset: Charset = Charsets.ISO_8859_1.let { runCatching { Charset.forName("windows-1252") }.getOrDefault(it) }
        val stack = ArrayList<State>()
        var st = State(skip = false, uc = 1)
        var toSkip = 0 // \uN 뒤 대체 글자 수
        var groupStart = false

        fun flushBytes() {
            if (pending.size() > 0) { cur.append(String(pending.toByteArray(), charset)); pending.reset() }
        }
        fun emit(c: Char) { if (!st.skip) { flushBytes(); cur.append(c) } }
        fun endPara() { flushBytes(); paras += cur.toString().trim().replace(Regex("[ \\t]{2,}"), " "); cur.setLength(0) }

        var i = 0
        val n = bytes.size
        while (i < n) {
            val b = bytes[i].toInt() and 0xFF
            when (b) {
                '{'.code -> { flushBytes(); stack += st; st = State(st.skip, st.uc); groupStart = true; toSkip = 0; i++; continue }
                '}'.code -> { flushBytes(); st = if (stack.isEmpty()) st else stack.removeAt(stack.size - 1); toSkip = 0; i++; groupStart = false; continue }
                '\\'.code -> {
                    i++
                    if (i >= n) break
                    val c = bytes[i].toInt() and 0xFF
                    if (c.toChar().isLetter() && c < 0x80) {
                        val ws = i
                        while (i < n && (bytes[i].toInt() and 0xFF).let { it < 0x80 && it.toChar().isLetter() }) i++
                        val word = String(bytes, ws, i - ws, Charsets.US_ASCII)
                        var param: Int? = null
                        if (i < n && (bytes[i] == '-'.code.toByte() || bytes[i].toInt().toChar().isDigit())) {
                            val ps = i
                            i++
                            while (i < n && bytes[i].toInt().toChar().isDigit()) i++
                            param = String(bytes, ps, i - ps, Charsets.US_ASCII).toIntOrNull()
                        }
                        if (i < n && bytes[i] == ' '.code.toByte()) i++
                        val first = groupStart
                        groupStart = false
                        if (first && word in SKIP) { st.skip = true; continue }
                        when (word) {
                            "ansicpg" -> param?.let { cp -> charset = codepage(cp) ?: charset }
                            "uc" -> st.uc = (param ?: 1).coerceAtLeast(0)
                            "u" -> {
                                if (toSkip > 0) { toSkip--; continue }
                                val v = param ?: 0
                                emit((if (v < 0) v + 65536 else v).toChar())
                                toSkip = st.uc
                            }
                            "par", "row" -> if (!st.skip) endPara()
                            "sect", "page" -> if (!st.skip) endPara()
                            "line" -> emit('\n')
                            "tab", "cell" -> emit('\t')
                            "emdash" -> emit('—'); "endash" -> emit('–'); "bullet" -> emit('•')
                            "lquote" -> emit('‘'); "rquote" -> emit('’'); "ldblquote" -> emit('“'); "rdblquote" -> emit('”')
                            "emspace", "enspace", "qmspace" -> emit(' ')
                            "bin" -> i += (param ?: 0).coerceAtLeast(0) // 이진 자료 건너뜀
                        }
                        continue
                    }
                    groupStart = false
                    when (c.toChar()) {
                        '\'' -> {
                            val end = minOf(n, i + 3)
                            val hex = String(bytes, i + 1, end - i - 1, Charsets.US_ASCII)
                            i = end
                            val v = hex.toIntOrNull(16) ?: continue
                            if (toSkip > 0) { toSkip--; continue }
                            if (!st.skip) pending.write(v)
                        }
                        '*' -> { st.skip = true; i++ }
                        '~' -> { emit(' '); i++ }
                        '_' -> { emit('-'); i++ }
                        '-' -> i++
                        '\r', '\n' -> { if (!st.skip) endPara(); i++ }
                        else -> { emit(c.toChar()); i++ } // \{ \} \\
                    }
                    continue
                }
                '\r'.code, '\n'.code -> { i++; continue }
                else -> {
                    groupStart = false
                    if (toSkip > 0) { toSkip--; i++; continue }
                    if (!st.skip) {
                        if (b < 0x80) { flushBytes(); cur.append(b.toChar()) } else pending.write(b)
                    }
                    i++
                }
            }
        }
        endPara()
        while (paras.isNotEmpty() && paras.last().isEmpty()) paras.removeAt(paras.size - 1)

        val sb = StringBuilder()
        paras.forEachIndexed { k, p ->
            sb.append("<p id=\"p").append(k).append("\">")
            if (p.isEmpty()) sb.append("<br/>")
            else p.split('\n').forEachIndexed { j, line -> if (j > 0) sb.append("<br/>"); sb.append(DocxConverter.esc(line)) }
            sb.append("</p>\n")
        }
        val html = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"ko\">\n<head>\n<meta charset=\"UTF-8\"/>\n<title>document</title>\n</head>\n<body>\n" +
            sb + "</body>\n</html>\n"
        return Result(html, paras.filter { it.isNotEmpty() })
    }

    private fun codepage(cp: Int): Charset? = when (cp) {
        949 -> runCatching { Charset.forName("x-windows-949") }.getOrNull() ?: runCatching { Charset.forName("MS949") }.getOrNull()
        65001 -> Charsets.UTF_8
        else -> runCatching { Charset.forName("windows-$cp") }.getOrNull() ?: runCatching { Charset.forName("cp$cp") }.getOrNull()
    }
}
