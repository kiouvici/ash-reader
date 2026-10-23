// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.convert

import java.io.File

/**
 * CHM → 합성 EPUB (기술 설계서 2장: libmspack chmd 로 풀고 HHC(목차)·HTML 을 EPUB 으로).
 * - HTML 은 meta charset → 없으면 [TextEncoding] 으로 판별해 UTF-8 로 바꿔 쓴다(한국어 CHM 은 보통 CP949).
 * - 스크립트(<script>)는 지운다(N-05 ③). 그림·CSS 는 그대로.
 * - 차례(spine) = HHC 순서, 그다음 HHC 에 없는 나머지 HTML(이름 순). 목차(NCX) = HHC 계층.
 */
object ChmEpub {
    private val HTML = Regex(".*\\.(html?|xhtml)$", RegexOption.IGNORE_CASE)

    fun convert(src: File, work: File, outEpub: File, title: String): ConvertResult {
        work.deleteRecursively()
        val root = File(work, "OEBPS").apply { mkdirs() }
        try {
            val n = try { NativeConvert.chmExtractAll(src.absolutePath, root.absolutePath, MAX_EXTRACT_BYTES) } catch (e: UnsatisfiedLinkError) {
                return ConvertResult.Failed("이 기기에서는 CHM 변환 엔진을 쓸 수 없어요.")
            }
            when {
                n == -2 -> return ConvertResult.Failed("CHM 안 파일이 너무 커요(500MB 넘음).")
                n < 0 -> return ConvertResult.Failed("CHM 파일을 풀지 못했어요. 손상되었을 수 있어요.")
            }
            val r = build(root, title)
            if (r.spine.isEmpty()) return ConvertResult.Failed("CHM 안에 읽을 HTML 쪽이 없어요.")
            EpubWriter.zip(work, "OEBPS/content.opf", outEpub)
            return ConvertResult.Ok(outEpub)
        } finally {
            work.deleteRecursively()
        }
    }

    class Built(val spine: List<String>, val toc: List<Hhc.Entry>)

    /** 푼 폴더 [root](OEBPS)에서 HTML 을 UTF-8 로 바꾸고 content.opf·toc.ncx 를 만든다. JVM 시험용으로 따로 둔다. */
    fun build(root: File, title: String): Built {
        val base = root.canonicalFile
        val files = base.walkTopDown().filter { it.isFile }.map { it.relativeTo(base).invariantSeparatorsPath }.sorted().toList()
        val lower = files.associateBy { it.lowercase() }
        // 목차: .hhc 첫 파일
        val hhcName = files.firstOrNull { it.endsWith(".hhc", ignoreCase = true) }
        val toc = hhcName?.let { h ->
            val bytes = File(base, h).readBytes()
            val dir = h.substringBeforeLast('/', "")
            Hhc.parse(HtmlText.decode(bytes)).mapNotNull { e ->
                val p = resolve(dir, e.local) ?: return@mapNotNull null
                val real = lower[p.substringBefore('#').lowercase()] ?: return@mapNotNull null
                e.copy(local = real + (p.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" }))
            }
        }.orEmpty()
        val htmls = files.filter { HTML.matches(it) }
        val spine = LinkedHashSet<String>()
        toc.forEach { spine += it.local.substringBefore('#') }
        htmls.sorted().forEach { spine += it }
        // HTML → UTF-8
        for (h in htmls) {
            val f = File(base, h)
            runCatching { f.writeText(HtmlText.toUtf8Html(HtmlText.decode(f.readBytes())), Charsets.UTF_8) }
        }
        File(base, "content.opf").writeText(opf(title, files, spine.toList()), Charsets.UTF_8)
        File(base, "toc.ncx").writeText(ncx(title, toc), Charsets.UTF_8)
        return Built(spine.toList(), toc)
    }

    /** HHC 기준 상대 경로 → 압축 안 경로. 바깥(../ 넘침)·외부 주소(http:, ms-its:, mk:)는 null. */
    internal fun resolve(dir: String, local: String): String? {
        var l = local.trim().replace('\\', '/')
        if (l.isEmpty() || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(l) && !l.startsWith("/")) {
            // ms-its:file.chm::/page.htm 형태는 :: 뒤를 쓴다
            val i = l.indexOf("::")
            if (i < 0) return null
            l = l.substring(i + 2)
        }
        l = percentDecode(l)
        val parts = ArrayList<String>()
        val full = if (l.startsWith("/")) l else (if (dir.isEmpty()) "" else "$dir/") + l
        val frag = full.substringAfter('#', "")
        for (p in full.substringBefore('#').split('/')) when (p) {
            "", "." -> Unit
            ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
            else -> parts += p
        }
        if (parts.isEmpty()) return null
        return parts.joinToString("/") + if (frag.isNotEmpty()) "#$frag" else ""
    }

    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) { out.write(v); i += 3; continue }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8)); i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun mediaType(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "htm", "html", "xhtml" -> "application/xhtml+xml"
        "css" -> "text/css"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "ttf", "otf" -> "application/font-sfnt"
        else -> null // 스크립트·기타는 manifest 에 넣지 않는다
    }

    internal fun opf(title: String, files: List<String>, spine: List<String>): String {
        val ids = HashMap<String, String>()
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"uid\">\n")
        sb.append(" <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n  <dc:title>").append(EpubWriter.xmlEscape(title))
            .append("</dc:title>\n  <dc:identifier id=\"uid\">ash-chm</dc:identifier>\n  <dc:language>ko</dc:language>\n </metadata>\n <manifest>\n")
        sb.append("  <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
        var n = 0
        for (f in files) {
            val mt = mediaType(f) ?: continue
            val id = "f" + (n++)
            ids[f] = id
            sb.append("  <item id=\"").append(id).append("\" href=\"").append(EpubWriter.xmlEscape(EpubWriter.hrefEncode(f)))
                .append("\" media-type=\"").append(mt).append("\"/>\n")
        }
        sb.append(" </manifest>\n <spine toc=\"ncx\">\n")
        for (s in spine) ids[s]?.let { sb.append("  <itemref idref=\"").append(it).append("\"/>\n") }
        sb.append(" </spine>\n</package>\n")
        return sb.toString()
    }

    internal fun ncx(title: String, toc: List<Hhc.Entry>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n")
        sb.append(" <head><meta name=\"dtb:uid\" content=\"ash-chm\"/></head>\n <docTitle><text>").append(EpubWriter.xmlEscape(title)).append("</text></docTitle>\n <navMap>\n")
        var order = 1
        val stack = ArrayList<Int>() // 열린 navPoint 의 깊이
        for (e in toc) {
            val d = e.depth.coerceIn(0, (stack.lastOrNull() ?: -1) + 1)
            while (stack.isNotEmpty() && stack.last() >= d) { sb.append(" </navPoint>\n"); stack.removeAt(stack.size - 1) }
            sb.append(" <navPoint id=\"n").append(order).append("\" playOrder=\"").append(order).append("\"><navLabel><text>")
                .append(EpubWriter.xmlEscape(e.name.ifBlank { "·" })).append("</text></navLabel><content src=\"")
                .append(EpubWriter.xmlEscape(EpubWriter.hrefEncode(e.local.substringBefore('#')) + (e.local.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" })))
                .append("\"/>\n")
            stack += d; order++
        }
        while (stack.isNotEmpty()) { sb.append(" </navPoint>\n"); stack.removeAt(stack.size - 1) }
        sb.append(" </navMap>\n</ncx>\n")
        return sb.toString()
    }
}

/** CHM 목차(HHC, sitemap HTML) 읽기. */
object Hhc {
    /** [depth] 0부터. [local] = HHC 에 적힌 경로(아직 정리 전). */
    data class Entry(val name: String, val local: String, val depth: Int)

    private val TOKENS = Regex("<\\s*(/?)\\s*ul\\b[^>]*>|<object\\b[^>]*>(.*?)</object\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val PARAM = Regex("<param\\b[^>]*>", RegexOption.IGNORE_CASE)

    fun parse(html: String): List<Entry> {
        val out = ArrayList<Entry>()
        var depth = -1
        for (m in TOKENS.findAll(html)) {
            val t = m.value
            if (m.groupValues[2].isEmpty() && Regex("^<\\s*/?\\s*ul", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
                if (m.groupValues[1] == "/") depth-- else depth++
                continue
            }
            if (!t.contains("text/sitemap", ignoreCase = true)) continue
            var name = ""; var local = ""
            for (p in PARAM.findAll(m.groupValues[2])) {
                val k = attr(p.value, "name")?.lowercase() ?: continue
                val v = attr(p.value, "value") ?: continue
                if (k == "name" && name.isEmpty()) name = HtmlText.unescape(v)
                if (k == "local" && local.isEmpty()) local = HtmlText.unescape(v)
            }
            if (local.isNotEmpty()) out += Entry(name.trim(), local, depth.coerceAtLeast(0))
        }
        return out
    }

    private fun attr(tag: String, name: String): String? =
        Regex("(?<![\\w-])" + name + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(tag)?.let { m ->
            m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } }
        }
}

/** HTML 바이트 → 글자(문자 집합 판별) → UTF-8 HTML. */
object HtmlText {
    private val META_CHARSET = Regex("<meta\\b[^>]*charset\\s*=\\s*[\"']?([\\w.:-]+)", RegexOption.IGNORE_CASE)
    private val META_ANY_CHARSET = Regex("<meta\\b[^>]*charset[^>]*>", RegexOption.IGNORE_CASE)
    private val SCRIPT = Regex("<script\\b.*?</script\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val HEAD = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)

    /** meta charset 이 있으면 그것(EUC-KR·ks_c_5601 은 CP949 로), 없으면 [TextEncoding.detect]. */
    fun decode(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val declared = META_CHARSET.find(head)?.groupValues?.get(1)?.lowercase()
        val cs = when {
            declared == null -> null
            declared in setOf("euc-kr", "ks_c_5601-1987", "ks_c_5601", "cp949", "ms949", "x-windows-949", "windows-949", "korean") -> TextEncoding.cp949()
            else -> runCatching { java.nio.charset.Charset.forName(declared) }.getOrNull()
        }
        if (cs != null) {
            // 선언이 UTF-8 인데 실제로 UTF-8 이 아니면(흔한 실수) 판별 결과를 쓴다
            if (cs != Charsets.UTF_8 || TextEncoding.utf8Errors(bytes, minOf(bytes.size, TextEncoding.SAMPLE)) == 0) return String(bytes, cs)
        }
        return TextEncoding.decode(bytes, TextEncoding.detect(bytes, minOf(bytes.size, TextEncoding.SAMPLE)))
    }

    /** charset 선언을 UTF-8 로 바꾸고 스크립트를 지운다. */
    fun toUtf8Html(text: String): String {
        var s = SCRIPT.replace(text, "")
        s = META_ANY_CHARSET.replace(s, "")
        val h = HEAD.find(s)
        return if (h != null) s.substring(0, h.range.last + 1) + "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\"/>" + s.substring(h.range.last + 1)
        else "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\"/>$s"
    }

    fun unescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
}
