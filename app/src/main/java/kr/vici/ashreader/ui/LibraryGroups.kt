// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import java.text.Collator
import java.util.Locale
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.SeriesGroups

/**
 * 서재 묶음 하나(작업 05, 애플 북스 방식): 형식·폴더·시리즈 탭의 카드 한 장.
 * [books] = 하위 폴더까지 모든 책, [kids] = 하위 폴더(폴더별만), [seriesKey] = 시리즈 묶음 키(시리즈만).
 */
class LibGroup(
    val id: String,
    val name: String,
    val books: List<Book>,
    val kids: List<LibGroup> = emptyList(),
    val seriesKey: String? = null,
)

/** 묶음 만들기. 안드로이드 API 없이 JVM 에서 시험한다. */
object LibGroups {
    /** 서재 폴더가 아닌 곳(다른 앱에서 'ASH Reader로 열기'로 넣은 사본). */
    const val IMPORTED = "가져온 파일"

    private val collator: Collator by lazy { Collator.getInstance(Locale.KOREAN) }
    private val byName = Comparator<LibGroup> { a, b -> collator.compare(a.name, b.name) }

    /** 형식별: 형식 묶음 순서(이북·텍스트·PDF·만화·한글·Word·압축). */
    fun formats(books: List<Book>): List<LibGroup> =
        books.groupBy { DocFormat.of(it.format).group }.toSortedMap(compareBy { it.ordinal })
            .map { (g, l) -> LibGroup("f-${g.name}", g.title, l) }

    /** 시리즈: [SeriesGroups] 묶음(자동 2권 이상 + 직접 묶음), 이름순. */
    fun series(books: List<Book>): List<LibGroup> =
        SeriesGroups.groups(books).map { (k, v) -> LibGroup("s-$k", SeriesGroups.nameOf(k), v, seriesKey = k) }.sortedWith(byName)

    /**
     * 폴더별: 맨 위 단계 = 서재 폴더([roots] = 서재 폴더 이름), 안쪽 = 하위 폴더.
     * 책의 [Book.folder] 는 "서재 폴더 이름/하위/하위" 이고 서재 폴더 이름에도 '/' 가 들 수 있어(예: "Download/소설")
     * 가장 긴 이름부터 맞춘다. 어느 서재 폴더에도 맞지 않으면 첫 칸을 맨 위로, 비어 있으면 [IMPORTED].
     */
    fun folders(books: List<Book>, roots: List<String>): List<LibGroup> {
        val sorted = roots.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }
        val byRoot = LinkedHashMap<String, MutableList<Pair<List<String>, Book>>>()
        for (b in books) {
            val f = b.folder.trim('/')
            val root = when {
                f.isEmpty() -> IMPORTED
                else -> sorted.firstOrNull { f == it || f.startsWith("$it/") } ?: f.substringBefore('/')
            }
            val rest = if (f.isEmpty()) emptyList() else f.removePrefix(root).split('/').filter { it.isNotEmpty() }
            byRoot.getOrPut(root) { ArrayList() } += rest to b
        }
        return byRoot.map { (r, l) -> build("d-$r", r, l) }.sortedWith(byName)
    }

    private fun build(id: String, name: String, items: List<Pair<List<String>, Book>>): LibGroup {
        val kids = items.filter { it.first.isNotEmpty() }.groupBy { it.first[0] }
            .map { (n, l) -> build("$id/$n", n, l.map { it.first.drop(1) to it.second }) }
            .sortedWith(byName)
        return LibGroup(id, name, items.map { it.second }, kids)
    }

    /** [list] 와 그 하위에서 [id] 묶음. */
    fun find(list: List<LibGroup>, id: String): LibGroup? {
        for (g in list) {
            if (g.id == id) return g
            find(g.kids, id)?.let { return it }
        }
        return null
    }

    /** 카드·묶음 화면 부제: "폴더 2개  64권" / 묶음 화면 "폴더 2개, 책 64권". */
    fun cardSub(g: LibGroup): String = (if (g.kids.isNotEmpty()) "폴더 ${g.kids.size}개  " else "") + "${g.books.size}권"
    fun detailSub(g: LibGroup): String = (if (g.kids.isNotEmpty()) "폴더 ${g.kids.size}개, " else "") + "책 ${g.books.size}권"
}

/** 묶음 안 정렬(작업 05). 저장하지 않는다(화면을 떠나면 처음 값). */
enum class GroupSort(val label: String) {
    RECENT("최근 읽은 순"), TITLE("이름"), ADDED("추가한 날");

    fun apply(list: List<Book>): List<Book> = when (this) {
        RECENT -> list.sortedByDescending { if (it.lastOpenedAt > 0) it.lastOpenedAt else it.addedAt }
        TITLE -> list.sortedWith(SeriesGroups.order) // 숫자는 1, 2, 10 순서
        ADDED -> list.sortedByDescending { it.addedAt }
    }
}

/** 표지 아래 상태 칸(작업 05): 0 = 읽는 중(파랑), 1 = 그 밖. */
data class BookState(val text: String, val reading: Boolean)

/** 책 상태 글자: 열 수 없음 / 다 읽음 / '42%' / 오늘·어제 받음 / 새 책. [now] = 지금 시각(밀리초). */
fun bookState(b: Book, unavailable: Boolean, now: Long = System.currentTimeMillis()): BookState {
    if (unavailable) return BookState("열 수 없음", false)
    if (b.status == Book.DONE) return BookState("다 읽음", false)
    if (b.lastOpenedAt > 0) {
        val pct = if (b.pageCount > 0) ((b.lastPage + 1) * 100 / b.pageCount).coerceIn(0, 99) else 0
        return BookState("$pct%", true)
    }
    val days = dayOf(now) - dayOf(b.addedAt)
    return BookState(when (days) { 0L -> "오늘 받음"; 1L -> "어제 받음"; else -> "새 책" }, false)
}

private fun dayOf(t: Long): Long {
    val tz = java.util.TimeZone.getDefault()
    return (t + tz.getOffset(t)) / 86_400_000L
}
