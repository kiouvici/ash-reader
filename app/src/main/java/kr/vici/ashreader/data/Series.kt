// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.data

import java.io.File
import kr.vici.ashreader.engine.comic.ComicFormat

/**
 * 시리즈 묶기(F-62)·다음 권(F-61). 안드로이드 API 없이 JVM 에서 시험한다.
 * - 자동: 같은 폴더 안에서 파일명의 숫자·권·화 표기를 뺀 나머지([Book.seriesKey])가 같은 문서가 2개 이상이면 한 묶음.
 * - 직접 묶음([Book.SERIES_MANUAL]): 폴더와 상관없이 같은 이름([Book.seriesKey])이면 한 묶음(1권만이어도).
 * - 묶기 풀기·시리즈에서 빼기([Book.SERIES_EXCLUDED]): 자동 묶기에서 뺀다.
 * 묶음 안 순서는 파일명 숫자 순서(1, 2, 10).
 */
object SeriesGroups {
    private const val SEP = '\u001F'

    /** 문서가 있는 폴더(같은 폴더 판정). 폴더 수집 = 실제 상위 경로, SAF = 문서 주소의 상위, 앱 안 사본 = 가져온 폴더 이름. */
    fun folderKey(b: Book): String = when (b.origin) {
        Book.ORIGIN_FILE -> File(b.path).parent.orEmpty()
        Book.ORIGIN_SAF -> b.sourceUri.replace("%2F", "/").replace("%2f", "/").substringBeforeLast('/', b.folder)
        else -> b.folder
    }

    /** 묶음 키. 묶이지 않으면 null. */
    fun keyOf(b: Book): String? = when (b.seriesManual) {
        Book.SERIES_MANUAL -> if (b.seriesKey.isBlank()) null else "m$SEP${b.seriesKey}"
        Book.SERIES_EXCLUDED -> null
        else -> if (b.seriesKey.isBlank()) null else "a$SEP${folderKey(b)}$SEP${b.seriesKey}"
    }

    /** 화면에 보일 묶음 이름. */
    fun nameOf(key: String): String = key.substringAfterLast(SEP)

    fun isManual(key: String): Boolean = key.startsWith("m$SEP")

    /** 묶음 안 순서: 파일명 숫자 순서(1, 2, 10), 같으면 권 번호. */
    val order: Comparator<Book> = compareBy(ComicFormat.naturalOrder) { b: Book -> b.title }.thenBy { it.volume }

    /** 묶음들(키 → 권 목록, 순서대로). 자동 묶음은 2권 이상만. */
    fun groups(books: List<Book>): Map<String, List<Book>> {
        val out = LinkedHashMap<String, MutableList<Book>>()
        for (b in books) { val k = keyOf(b) ?: continue; out.getOrPut(k) { ArrayList() } += b }
        return out.filter { (k, v) -> isManual(k) || v.size >= 2 }.mapValues { (_, v) -> v.sortedWith(order) }
    }

    /** [key] 묶음의 권들(순서대로). */
    fun volumes(key: String, books: List<Book>): List<Book> = groups(books)[key].orEmpty()

    /**
     * 다음 권(F-61): [cur] 가 든 묶음에서 파일명 숫자 순서로 다음 문서.
     * 묶음이 없으면(같은 폴더에 한 권뿐 등) 다른 폴더까지 같은 [Book.seriesKey] 에서 권 번호가 하나 큰 문서.
     */
    fun next(cur: Book, books: List<Book>): Book? {
        val k = keyOf(cur)
        if (k != null) {
            val vols = groups(books)[k]
            if (vols != null) {
                val i = vols.indexOfFirst { it.id == cur.id }
                if (i >= 0) return vols.getOrNull(i + 1)
            }
        }
        if (cur.seriesKey.isBlank() || cur.seriesManual != Book.SERIES_AUTO || cur.volume <= 0) return null
        return books.filter { it.id != cur.id && it.seriesManual == Book.SERIES_AUTO && it.seriesKey == cur.seriesKey && it.volume == cur.volume + 1 }
            .minWithOrNull(order)
    }

    /** 권 이름: "3권", 번호가 없으면 제목. */
    fun volumeLabel(b: Book): String = if (b.volume > 0) "${b.volume}권" else b.title
}
