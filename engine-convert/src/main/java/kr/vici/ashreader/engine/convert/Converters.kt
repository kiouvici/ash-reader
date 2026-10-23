// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.convert

import java.io.File

/** libash_convert.so JNI 선언 (libmobi + libmspack chmd). */
internal object NativeConvert {
    init { System.loadLibrary("ash_convert") }

    /** 결과 코드는 ash_convert_jni.c 의 R_* 와 같다. */
    @JvmStatic external fun mobiToDir(path: String, dir: String): Int

    /** 푼 파일 수, 또는 -1 열기 실패 / -2 크기 상한 초과 / -3 손상. */
    @JvmStatic external fun chmExtractAll(path: String, dir: String, maxTotal: Long): Int
}

/** 변환 결과. [Ok.epub] 은 MuPDF 로 여는 합성 EPUB. */
sealed interface ConvertResult {
    data class Ok(val epub: File) : ConvertResult
    /** 서점 보호(DRM)가 걸린 책(D-002: 해제하지 않음). */
    data object Drm : ConvertResult
    /** 지원하지 않는 변종(Kindle Print Replica 등). */
    data class Unsupported(val why: String) : ConvertResult
    data class Failed(val why: String) : ConvertResult
}

/** 압축 해제·변환 공통 상한(N-09). */
const val MAX_EXTRACT_BYTES = 500L shl 20

/**
 * MOBI·AZW3(KF8) → 합성 EPUB (기술 설계서 2장: libmobi 가 KF8 skeleton/fragment·kindle:embed 그림 링크를 되살린다).
 * 결과는 [outEpub] 에 쓴다. [work] 는 쓰고 지우는 임시 폴더.
 */
object MobiEpub {
    fun convert(src: File, work: File, outEpub: File): ConvertResult {
        work.deleteRecursively(); work.mkdirs()
        try {
            val rc = try { NativeConvert.mobiToDir(src.absolutePath, work.absolutePath) } catch (e: UnsatisfiedLinkError) {
                return ConvertResult.Failed("이 기기에서는 MOBI·AZW3 변환 엔진을 쓸 수 없어요.")
            }
            return when (rc) {
                0 -> {
                    val opf = File(work, "OEBPS/content.opf")
                    if (!opf.isFile) return ConvertResult.Failed("책 구조(OPF)를 만들지 못했어요.")
                    fixOpf(opf)
                    EpubWriter.zip(work, "OEBPS/content.opf", outEpub)
                    ConvertResult.Ok(outEpub)
                }
                1 -> ConvertResult.Drm
                2 -> ConvertResult.Unsupported("Kindle Print Replica(쪽 고정 PDF형) 책은 열 수 없어요.")
                else -> ConvertResult.Failed("MOBI·AZW3 파일을 읽지 못했어요(코드 $rc). 손상되었을 수 있어요.")
            }
        } finally {
            work.deleteRecursively()
        }
    }

    /** OPF 가 가리키는데 실제로 없는 파일(빈 NCX 등)을 manifest·spine toc 에서 뺀다(MuPDF 가 목차 파일을 못 찾아 실패하지 않게). */
    internal fun fixOpf(opf: File) {
        val dir = opf.parentFile ?: return
        var s = opf.readText(Charsets.UTF_8)
        val missing = Regex("<item\\b[^>]*>").findAll(s).mapNotNull { m ->
            val id = Regex("\\bid=\"([^\"]*)\"").find(m.value)?.groupValues?.get(1) ?: return@mapNotNull null
            val href = Regex("\\bhref=\"([^\"]*)\"").find(m.value)?.groupValues?.get(1) ?: return@mapNotNull null
            if (File(dir, href).isFile) null else id to m.value
        }.toList()
        if (missing.isEmpty()) return
        for ((id, tag) in missing) {
            s = s.replace(tag, "")
            s = s.replace(Regex("\\s+toc=\"" + Regex.escape(id) + "\""), "")
            s = s.replace(Regex("<itemref\\b[^>]*idref=\"" + Regex.escape(id) + "\"[^>]*/>"), "")
        }
        opf.writeText(s, Charsets.UTF_8)
    }
}
