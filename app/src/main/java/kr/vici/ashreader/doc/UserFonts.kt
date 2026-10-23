// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.doc

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * F-20 내 글꼴. 사용자가 고른 TTF·OTF(·TTC) 파일을 앱 안 글꼴 폴더(files/userfonts)로 복사해 이북류 본문에 쓴다.
 * 원본 파일은 건드리지 않는다. 글꼴 파일 이름이 곧 설정값(Settings.userFont)이다.
 */
object UserFonts {
    /** 글꼴 하나 상한(한글 글꼴은 10~30MB 가 흔함). */
    private const val MAX_BYTES = 64L shl 20

    fun dir(context: Context): File = File(context.filesDir, "userfonts")

    fun list(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.isFile && it.length() > 0 && isFontName(it.name) }?.sortedBy { it.name.lowercase() } ?: emptyList()

    /** 설정값 [name] 의 글꼴 파일. 없으면 null(내장 글꼴로). */
    fun file(context: Context, name: String): File? {
        if (name.isEmpty() || name.contains('/') || name.contains("..")) return null
        return File(dir(context), name).takeIf { it.isFile && it.length() > 0 }
    }

    /** 화면에 보일 이름(확장자 뺌). */
    fun label(name: String): String = name.substringBeforeLast('.')

    private fun isFontName(n: String) = n.lowercase().let { it.endsWith(".ttf") || it.endsWith(".otf") || it.endsWith(".ttc") }

    /** 글꼴 파일 머리(sfnt 0x00010000 · 'OTTO' · 'true' · 'ttcf'). */
    fun looksLikeFont(head: ByteArray): Boolean {
        if (head.size < 4) return false
        val m = String(head, 0, 4, Charsets.ISO_8859_1)
        return m == "OTTO" || m == "true" || m == "ttcf" || (head[0] == 0.toByte() && head[1] == 1.toByte() && head[2] == 0.toByte() && head[3] == 0.toByte())
    }

    class Imported(val name: String)
    class ImportFailed(msg: String) : Exception(msg)

    /** [uri] 의 글꼴을 복사한다. 같은 이름이 있으면 덮어쓴다. 실패하면 [ImportFailed](안내 문구). 백그라운드에서 부른다. */
    fun import(context: Context, uri: Uri): Imported {
        val cr = context.contentResolver
        var display: String? = null
        var size = -1L
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    display = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        if (size > MAX_BYTES) throw ImportFailed("글꼴 파일이 너무 커요(64MB 넘음).")
        val raw = (display ?: uri.lastPathSegment ?: "font").substringAfterLast('/')
        val ext = raw.substringAfterLast('.', "").lowercase().takeIf { it == "ttf" || it == "otf" || it == "ttc" }
        val base = raw.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} _.()\\-]"), "_").trim().take(60).ifEmpty { "font" }
        val d = dir(context).apply { mkdirs() }
        val tmp = File(d, ".import.tmp")
        try {
            val input = cr.openInputStream(uri) ?: throw ImportFailed("파일을 열 수 없어요.")
            input.use { i ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw ImportFailed("글꼴 파일이 너무 커요(64MB 넘음).")
                        o.write(buf, 0, n)
                    }
                }
            }
            val head = ByteArray(4)
            val got = tmp.inputStream().use { it.read(head) }
            if (got < 4 || !looksLikeFont(head)) throw ImportFailed("TTF·OTF 글꼴 파일이 아니에요.")
            val kind = ext ?: when (String(head, 0, 4, Charsets.ISO_8859_1)) { "OTTO" -> "otf"; "ttcf" -> "ttc"; else -> "ttf" }
            // 안드로이드가 읽을 수 있는 글꼴인지 한 번 확인(깨진 파일 거르기)
            val ok = runCatching { android.graphics.Typeface.Builder(tmp).build() != null }.getOrDefault(false)
            if (!ok) throw ImportFailed("글꼴을 읽을 수 없어요. 손상된 파일일 수 있어요.")
            val out = File(d, "$base.$kind")
            out.delete()
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true) }
            return Imported(out.name)
        } catch (e: ImportFailed) {
            throw e
        } catch (e: Exception) {
            throw ImportFailed("글꼴을 가져오지 못했어요.")
        } finally {
            tmp.delete()
        }
    }

    fun delete(context: Context, name: String): Boolean = file(context, name)?.delete() ?: false
}
