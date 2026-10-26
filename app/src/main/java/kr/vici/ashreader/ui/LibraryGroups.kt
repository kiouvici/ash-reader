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

/**
 * 묶음 안 정렬(작업 05·07 ⋯ 보기·정렬). 묶음마다 기억한다([ShelfViews]). 순서(ordinal)를 저장하므로 끝에만 더한다.
 * [MANUAL] 직접 정렬 = [order](책 id 순서)대로, 순서에 없는 책은 뒤에 최근 순.
 */
enum class GroupSort(val label: String) {
    RECENT("최근 읽은 순"), TITLE("제목"), ADDED("추가한 날"), AUTHOR("지은이"), MANUAL("직접 정렬");

    fun apply(list: List<Book>, order: List<String> = emptyList()): List<Book> = when (this) {
        RECENT -> list.sortedByDescending { if (it.lastOpenedAt > 0) it.lastOpenedAt else it.addedAt }
        TITLE -> list.sortedWith(SeriesGroups.order) // 숫자는 1, 2, 10 순서
        ADDED -> list.sortedByDescending { it.addedAt }
        AUTHOR -> list.sortedWith(compareBy<Book> { it.author.isBlank() }.thenComparator { a, b -> byAuthor.compare(a.author, b.author) }.then(SeriesGroups.order))
        MANUAL -> {
            val pos = order.withIndex().associate { (i, id) -> id to i }
            val (known, rest) = list.partition { it.id in pos }
            known.sortedBy { pos[it.id] } + RECENT.apply(rest)
        }
    }

    companion object {
        /** ⋯ 메뉴에 보이는 순서(목업). */
        val MENU = listOf(RECENT, TITLE, AUTHOR, ADDED, MANUAL)
        fun of(i: Int) = entries.getOrElse(i) { RECENT }
        private val byAuthor: Collator by lazy { Collator.getInstance(Locale.KOREAN) }
    }
}

/** 모음 하나의 보기(작업 07): 정렬 + 목록 보기인가. */
data class ShelfView(val sort: GroupSort, val list: Boolean)

/** 모음마다 보기·정렬 기억(설정 문자열 "열쇠=정렬:목록;…"). 안드로이드 없이 시험한다. */
object ShelfViews {
    fun parse(s: String): Map<String, ShelfView> = s.split(';').mapNotNull { e ->
        val k = e.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val v = e.substringAfter('=')
        k to ShelfView(GroupSort.of(v.substringBefore(':').toIntOrNull() ?: 0), v.substringAfter(':', "0") == "1")
    }.toMap()

    fun format(m: Map<String, ShelfView>): String =
        m.entries.joinToString(";") { (k, v) -> "${k.replace(';', ' ').replace('=', ' ')}=${v.sort.ordinal}:${if (v.list) 1 else 0}" }

    fun of(s: String, key: String, defaultList: Boolean): ShelfView = parse(s)[key] ?: ShelfView(GroupSort.RECENT, defaultList)

    fun put(s: String, key: String, v: ShelfView): String = format(parse(s) + (key to v))
}

/**
 * ≡ 모음(작업 07 LibraryMenus ①): 기본 모음 8개 + 사용자가 만든 모음. 순서·숨김은 설정 문자열(쉼표, 숨긴 것은 '-' 앞붙임).
 * 사용자 모음 열쇠 = "c-" + id.
 */
object ShelfColls {
    const val ALL = "all"; const val FORMAT = "format"; const val FOLDER = "folder"; const val SERIES = "series"
    const val READING = "reading"; const val DONE = "done"; const val RECENT_FILES = "recentFiles"; const val HIDDEN = "hidden"
    val BUILT_IN = listOf(ALL, FORMAT, FOLDER, SERIES, READING, DONE, RECENT_FILES, HIDDEN)
    val TITLES = mapOf(
        ALL to "전체", FORMAT to "형식", FOLDER to "폴더", SERIES to "시리즈",
        READING to "읽는 중", DONE to "다 읽음", RECENT_FILES to "최근 받은 파일", HIDDEN to "숨긴 책",
    )
    /** '최근 받은 파일' = 30일 안에 서재에 들어온 책. */
    const val RECENT_DAYS = 30L

    fun userKey(id: String) = "c-$id"
    fun isUser(key: String) = key.startsWith("c-")

    /** 설정 [s] + 사용자 모음 열쇠 [users] → (열쇠, 보임) 순서 목록. 설정에 없는 것은 기본 순서로 뒤에, 없어진 사용자 모음은 뺀다. */
    fun arrange(s: String, users: List<String>): List<Pair<String, Boolean>> {
        val all = BUILT_IN + users
        val saved = s.split(',').filter { it.isNotBlank() }.map { if (it.startsWith("-")) it.drop(1) to false else it to true }
            .filter { it.first in all }.distinctBy { it.first }
        val seen = saved.map { it.first }.toSet()
        return saved + all.filter { it !in seen }.map { it to true }
    }

    fun format(list: List<Pair<String, Boolean>>): String = list.joinToString(",") { (k, on) -> if (on) k else "-$k" }
}

/** 격자 한 칸(작업 07 LibraryAll): 책 한 권 또는 시리즈 쌓기("24권의 책"). */
sealed class ShelfItem {
    abstract val key: String
    data class One(val book: Book) : ShelfItem() { override val key get() = book.id }
    data class Stack(val group: LibGroup) : ShelfItem() { override val key get() = "stack-" + group.id }
}

object ShelfItems {
    /** '전체' 격자: 시리즈 권들은 처음 나오는 자리에 쌓기 하나로. [series] = 시리즈 묶음(2권 이상). */
    fun stacked(sorted: List<Book>, series: List<LibGroup>): List<ShelfItem> {
        val groupOf = HashMap<String, LibGroup>()
        for (g in series) for (b in g.books) groupOf[b.id] = g
        val done = HashSet<String>()
        val out = ArrayList<ShelfItem>(sorted.size)
        for (b in sorted) {
            val g = groupOf[b.id]
            if (g == null) out += ShelfItem.One(b)
            else if (done.add(g.id)) out += ShelfItem.Stack(g)
        }
        return out
    }

    /** 기본 모음 책(작업 07 ≡): 읽는 중 / 다 읽음 / 최근 받은 파일(30일). [now] 밀리초. */
    fun builtIn(key: String, books: List<Book>, now: Long): List<Book> = when (key) {
        ShelfColls.READING -> books.filter { it.status == Book.READING && it.lastOpenedAt > 0 }
        ShelfColls.DONE -> books.filter { it.status == Book.DONE }
        ShelfColls.RECENT_FILES -> books.filter { now - it.addedAt <= ShelfColls.RECENT_DAYS * 86_400_000L }.sortedByDescending { it.addedAt }
        else -> books
    }

    /** "어제"·"3일 전"·"지난주"·"2025-03-01"(목록 보기 상태 줄). */
    fun ago(t: Long, now: Long): String {
        if (t <= 0) return ""
        val d = (now - t) / 86_400_000L
        return when {
            d < 1 -> "오늘"
            d < 2 -> "어제"
            d < 7 -> "${d}일 전"
            d < 14 -> "지난주"
            d < 31 -> "${d / 7}주 전"
            else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA).format(java.util.Date(t))
        }
    }
}

/** 표지 아래 상태 칸(작업 05): 0 = 읽는 중(파랑), 1 = 그 밖. [fresh] = 아직 열지 않은 책(작업 07 '새 책' 알약). */
data class BookState(val text: String, val reading: Boolean, val fresh: Boolean = false)

/** 책 상태 글자: 열 수 없음 / 다 읽음 / '42%' / 오늘·어제 받음 / 새 책. [now] = 지금 시각(밀리초). */
fun bookState(b: Book, unavailable: Boolean, now: Long = System.currentTimeMillis()): BookState {
    if (unavailable) return BookState("열 수 없음", false)
    if (b.status == Book.DONE) return BookState("다 읽음", false)
    if (b.lastOpenedAt > 0) {
        val pct = if (b.pageCount > 0) ((b.lastPage + 1) * 100 / b.pageCount).coerceIn(0, 99) else 0
        return BookState("$pct%", true)
    }
    val days = dayOf(now) - dayOf(b.addedAt)
    return BookState(when (days) { 0L -> "오늘 받음"; 1L -> "어제 받음"; else -> "새 책" }, false, fresh = true)
}

private fun dayOf(t: Long): Long {
    val tz = java.util.TimeZone.getDefault()
    return (t + tz.getOffset(t)) / 86_400_000L
}
