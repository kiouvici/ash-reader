// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.doc

import android.content.Context
import java.io.File
import java.util.UUID
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.Settings
import kr.vici.ashreader.engine.mupdf.PagedComposer
import kr.vici.ashreader.office.AgileDecryptor
import kr.vici.ashreader.office.DocExtractor
import kr.vici.ashreader.office.DocPaged
import kr.vici.ashreader.office.DocxPaged
import kr.vici.ashreader.office.FormatSniffer
import kr.vici.ashreader.office.PagedWord
import kr.vici.ashreader.office.RtfPaged
import kr.vici.ashreader.office.SniffedFormat

/**
 * Word 원본(구조 보존) 보기(F-30). DOCX·DOC·RTF → 쪽 묶음(:engine-office) → Word 쪽 크기 PDF(:engine-mupdf PagedComposer) → 기존 PDF 경로로 연다.
 * 결과 PDF 는 cache/office/<docId>/original-v[VERSION].pdf 에 두고 다시 열 때 쓴다(변환 캐시 규칙 — 서재에서 지우면 같이 지움).
 * 암호 DOCX 는 풀린 내용이 디스크에 남지 않도록 닫을 때 지우는 임시 폴더에 만든다(F-34).
 */
object WordOriginal {
    /** 변환 규칙이 바뀌면 올린다(옛 캐시를 다시 만들게). */
    private const val VERSION = 2 // 2: 다단·과부/고아 줄·표 조건부 서식·EMF/WMF·목차·DOC 각주/탭

    fun open(context: Context, file: File, format: DocFormat, pageWdp: Float, pageHdp: Float, s: Settings, password: String?, key: String): ReaderDoc {
        val raw = file.readBytes()
        val sniffed = FormatSniffer.sniff(raw)
        var temp: File? = null
        val bytes: ByteArray = when {
            sniffed == SniffedFormat.ENCRYPTED_OOXML -> {
                if (password.isNullOrEmpty()) throw PasswordNeeded(wrong = false)
                val r = try { AgileDecryptor.decrypt(raw, password) } catch (e: Exception) {
                    throw OpenFailed("암호가 걸린 Word 문서를 풀 수 없어요. 파일이 손상되었을 수 있어요.")
                }
                when (r) {
                    is AgileDecryptor.Result.Ok -> r.zip
                    is AgileDecryptor.Result.WrongPassword -> throw PasswordNeeded(wrong = true)
                    is AgileDecryptor.Result.Unsupported -> throw OpenFailed("이 암호 방식은 아직 열 수 없어요. (${r.reason})")
                }.also { temp = File(context.cacheDir, "office-enc/" + UUID.randomUUID()) }
            }
            sniffed == SniffedFormat.DOC && DocExtractor.isEncrypted(raw) ->
                throw OpenFailed("암호가 걸린 DOC(Word 97-2003) 문서는 아직 열 수 없어요. Word에서 암호를 풀거나 DOCX로 저장한 뒤 열어 주세요.")
            else -> raw
        }
        val dir = (temp ?: File(context.cacheDir, "office/$key")).apply { mkdirs() }
        val pdf = File(dir, "original-v$VERSION.pdf")
        // 목차(제목 문단 → 쪽): 합성할 때 함께 쓴다. 한 줄 = 단계 \t 쪽 \t 제목
        val tocFile = File(dir, "original-v$VERSION.toc")
        // 옛 규칙으로 만든 결과는 지운다
        dir.listFiles()?.forEach { f -> if (f.name.startsWith("original-v") && !f.name.startsWith("original-v$VERSION.")) f.delete() }
        try {
            if (!pdf.isFile || pdf.length() == 0L) {
                val work = File(dir, "paged-work").apply { deleteRecursively(); mkdirs() }
                try {
                    val paged: PagedWord = when {
                        sniffed == SniffedFormat.RTF -> RtfPaged.convert(bytes)
                        bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> DocxPaged().convert(bytes)
                        else -> DocPaged.convert(bytes)
                    }
                    // EMF·WMF 그림은 Canvas 로 PNG 로 그려 넣는다(못 그리면 회색 상자)
                    paged.writeTo(work) { m, w, h -> MetafileRaster.png(m, w, h) }
                    val r = PagedComposer.compose(work, fontCss(context), pdf)
                    runCatching { tocFile.writeText(r.toc.joinToString("\n") { "${it.level}\t${it.page}\t${it.title.replace('\n', ' ')}" }) }
                } finally {
                    work.deleteRecursively()
                }
            }
        } catch (e: PasswordNeeded) {
            temp?.deleteRecursively(); throw e
        } catch (e: OutOfMemoryError) {
            temp?.deleteRecursively(); pdf.delete()
            throw OpenFailed("Word 문서가 너무 커서 원본 보기를 만들 수 없어요. eBook 보기로 읽어 주세요.")
        } catch (e: Exception) {
            temp?.deleteRecursively(); pdf.delete()
            throw OpenFailed("Word 원본 보기를 만들지 못했어요. eBook 보기로 읽어 주세요.")
        }
        val tmp = temp
        val cleanup: (() -> Unit)? = if (tmp != null) { { tmp.deleteRecursively() } } else null
        val toc = runCatching {
            tocFile.readLines().mapNotNull { line ->
                val t = line.split('\t', limit = 3)
                if (t.size < 3) null else TocEntry(t[2], t[0].toIntOrNull() ?: 0, t[1].toIntOrNull() ?: -1)
            }
        }.getOrDefault(emptyList())
        return try {
            MupdfReaderDoc(pdf, format, pageWdp, pageHdp, s, context, reflow = false, onClose = cleanup).also { if (toc.isNotEmpty()) it.tocOverride = toc }
        } catch (e: Exception) {
            tmp?.deleteRecursively(); throw e
        }
    }

    /** 합성기 사용자 CSS: 번들 글꼴(명조·고딕·기호용 리디바탕) 경로. */
    private fun fontCss(context: Context): String {
        val d = BundledFonts.dir(context)
        fun f(n: String) = File(d, n).takeIf { it.isFile }?.absolutePath
        val mj = f("NanumMyeongjo-Regular.ttf") ?: throw OpenFailed("글꼴을 찾을 수 없어요.")
        return PagedWord.fontCss(mj, f("NanumMyeongjo-Bold.ttf"), f("NanumGothic-Regular.ttf") ?: mj, null, f("RIDIBatang.otf"))
    }
}
