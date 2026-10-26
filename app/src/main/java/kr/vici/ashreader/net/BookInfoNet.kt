// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kr.vici.ashreader.BuildConfig
import kr.vici.ashreader.data.MetaRules
import kr.vici.ashreader.data.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * 책 정보 찾기의 네트워크(작업 06, D-024). **앱에서 인터넷을 쓰는 곳은 여기 한 곳뿐이다**(:app:checkNetworkConfined 가 검사).
 * - 보내는 것: 정리한 제목과 권수([MetaRules.Query])뿐. 책 내용·폴더 경로·파일 전체 이름은 보내지 않는다.
 * - HTTPS 만(network_security_config 로 평문 금지, 여기서도 https 주소만 연다).
 * - 출처: 국립중앙도서관 서지(ISBN) API · 카카오 책 검색(키가 있을 때) + 구글 북스(키 없이). (알라딘은 신규 신청 중단으로 쓰지 않음)
 * - 찾은 결과는 기기에 30일 저장해 같은 검색어는 다시 묻지 않는다(카카오 쿼터).
 *   키는 빌드 때 BuildConfig 로 들어온다(저장소에 없음). 키가 없으면 구글 북스만.
 */
object BookInfoNet {
    private const val TIMEOUT_MS = 8_000
    private const val MAX_JSON = 2 shl 20
    private const val MAX_IMAGE = 6 shl 20

    /**
     * 지금 인터넷으로 찾아도 되나. 설정 '인터넷에서 책 정보 찾기'가 꺼져 있으면 언제나 false.
     * [auto] = 자동으로 찾을 때: '와이파이에서만'(기본)은 와이파이·유선, '와이파이 + 모바일'은 아무 연결, '직접 찾을 때만'은 안 함.
     */
    fun allowed(context: Context, s: Settings, auto: Boolean): Boolean {
        if (!s.metaNet) return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (!auto) return true
        return when (s.metaAuto) {
            0 -> caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            1 -> true
            else -> false
        }
    }

    /** 세 곳에 함께 물어 후보를 모은다(실패한 곳은 빼고). 일치도 정렬은 부르는 쪽([MetaRules.rank]). */
    suspend fun search(context: Context, q: MetaRules.Query): List<MetaRules.Candidate> = withContext(Dispatchers.IO) {
        val text = if (q.volume > 0) "${q.title} ${q.volume}" else q.title
        cached(context, text)?.let { return@withContext it }
        var failed = false
        val list = coroutineScope {
            fun go(block: () -> List<MetaRules.Candidate>) = async { runCatching(block).getOrElse { failed = true; emptyList() } }
            val nl = if (BuildConfig.NL_KEY.isBlank()) null else go { nl(text) }
            val kakao = if (BuildConfig.KAKAO_KEY.isBlank()) null else go { kakao(text) }
            val google = go { google(text) }
            nl?.await().orEmpty() + kakao?.await().orEmpty() + google.await()
        }
        // 연결이 끊겨 실패한 검색은 저장하지 않는다(다음에 다시)
        if (!failed || list.isNotEmpty()) store(context, text, list)
        list
    }

    // ── 기기 저장(같은 검색어는 30일 동안 다시 묻지 않음) ──
    private const val CACHE_DAYS = 30L

    // 2026-10-26: 국립중앙도서관 키를 넣고 저자 칸을 정리하며 v2 로 — 키 없이 받아 둔 옛 결과(국립중앙도서관 빠짐)는 버린다
    private const val CACHE_DIR = "meta-cache-2"
    private val OLD_CACHE_DIRS = listOf("meta-cache")

    private fun cacheFile(context: Context, text: String): java.io.File {
        OLD_CACHE_DIRS.forEach { java.io.File(context.filesDir, it).takeIf { d -> d.exists() }?.deleteRecursively() }
        val h = java.security.MessageDigest.getInstance("SHA-1").digest(MetaRules.norm(text).toByteArray()).joinToString("") { "%02x".format(it) }
        return java.io.File(java.io.File(context.filesDir, CACHE_DIR), "$h.json")
    }

    private fun cached(context: Context, text: String): List<MetaRules.Candidate>? {
        val f = cacheFile(context, text)
        if (!f.isFile || System.currentTimeMillis() - f.lastModified() > CACHE_DAYS * 86_400_000L) return null
        return runCatching {
            JSONArray(f.readText()).objs().map {
                MetaRules.Candidate(it.optString("t"), it.optString("a"), it.optString("p"), it.optString("y"), it.optString("i"), it.optString("c"), it.optString("s"))
            }
        }.getOrNull()
    }

    private fun store(context: Context, text: String, list: List<MetaRules.Candidate>) = runCatching {
        val f = cacheFile(context, text)
        f.parentFile?.mkdirs()
        f.writeText(JSONArray().apply {
            list.forEach { put(JSONObject().put("t", it.title).put("a", it.author).put("p", it.publisher).put("y", it.year).put("i", it.isbn).put("c", it.coverUrl).put("s", it.source)) }
        }.toString())
    }

    /** 저장해 둔 검색 결과 지우기(설정 › 책 정보 › 인터넷에서 받은 정보 모두 지우기). */
    fun clearCache(context: Context) { (OLD_CACHE_DIRS + CACHE_DIR).forEach { java.io.File(context.filesDir, it).deleteRecursively() } }

    /** 표지 그림 받기(https 만, 6MB 까지). 실패하면 null. */
    suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) return@withContext null
        runCatching { get(url, MAX_IMAGE) }.getOrNull()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** https GET → 바이트([limit] 넘으면 실패). */
    private fun get(url: String, limit: Int): ByteArray {
        require(url.startsWith("https://")) { "https 만" }
        val c = URL(url).openConnection() as HttpsURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "ASH-Reader/1.0 (Android)")
            c.setRequestProperty("Accept", "application/json, image/*")
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val out = ByteArrayOutputStream()
            c.inputStream.use { i ->
                val buf = ByteArray(16 shl 10)
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > limit) error("너무 큼")
                }
            }
            return out.toByteArray()
        } finally { c.disconnect() }
    }

    private fun json(url: String) = JSONObject(String(get(url, MAX_JSON), Charsets.UTF_8))

    private fun https(u: String?): String = u.orEmpty().trim().let { if (it.startsWith("http://")) "https://" + it.removePrefix("http://") else it }
        .takeIf { it.startsWith("https://") }.orEmpty()

    private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    /** 구글 북스(키 없이): 제목 검색. */
    private fun google(text: String): List<MetaRules.Candidate> =
        json("https://www.googleapis.com/books/v1/volumes?q=intitle:${enc(text)}&maxResults=10&printType=books").optJSONArray("items").objs().map { it ->
            val v = it.optJSONObject("volumeInfo") ?: JSONObject()
            val ids = v.optJSONArray("industryIdentifiers").objs()
            val isbn = ids.firstOrNull { it.optString("type") == "ISBN_13" }?.optString("identifier") ?: ids.firstOrNull { it.optString("type") == "ISBN_10" }?.optString("identifier")
            val authors = v.optJSONArray("authors")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val title = listOf(v.optString("title"), v.optString("subtitle")).filter { it.isNotBlank() }.joinToString(": ")
            MetaRules.Candidate(
                title = title, author = authors.joinToString(", "), publisher = v.optString("publisher"), year = v.optString("publishedDate").take(4),
                isbn = MetaRules.isbn(isbn), coverUrl = https(v.optJSONObject("imageLinks")?.optString("thumbnail")), source = "구글 북스",
            )
        }

    /** 카카오 책 검색(REST API 키, 헤더 Authorization: KakaoAK). */
    private fun kakao(text: String): List<MetaRules.Candidate> {
        val c = URL("https://dapi.kakao.com/v3/search/book?target=title&size=10&query=${enc(text)}").openConnection() as HttpsURLConnection
        val body = try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("Authorization", "KakaoAK ${BuildConfig.KAKAO_KEY}")
            c.setRequestProperty("User-Agent", "ASH-Reader/1.0 (Android)")
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            c.inputStream.use { i -> String(i.readBytes().also { if (it.size > MAX_JSON) error("너무 큼") }, Charsets.UTF_8) }
        } finally { c.disconnect() }
        return JSONObject(body).optJSONArray("documents").objs().map {
            val authors = it.optJSONArray("authors")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            // isbn = "ISBN10 ISBN13"(빈칸으로 둘) → 13자리 먼저
            val isbn = it.optString("isbn").split(' ').map { MetaRules.isbn(it) }.filter { it.isNotEmpty() }.maxByOrNull { it.length }.orEmpty()
            MetaRules.Candidate(
                title = it.optString("title"), author = authors.joinToString(", "), publisher = it.optString("publisher"), year = it.optString("datetime").take(4),
                isbn = isbn, coverUrl = https(it.optString("thumbnail")), source = "카카오",
            )
        }
    }
    /** 국립중앙도서관 서지정보(ISBN) API. */
    private fun nl(text: String): List<MetaRules.Candidate> =
        json("https://www.nl.go.kr/seoji/SearchApi.do?cert_key=${enc(BuildConfig.NL_KEY)}&result_style=json&page_no=1&page_size=10&title=${enc(text)}")
            .optJSONArray("docs").objs().map {
                MetaRules.Candidate(
                    title = it.optString("TITLE"), author = MetaRules.nlAuthor(it.optString("AUTHOR")), publisher = it.optString("PUBLISHER"), year = it.optString("PUBLISH_PREDATE").take(4),
                    isbn = MetaRules.isbn(it.optString("EA_ISBN")), coverUrl = https(it.optString("TITLE_URL")), source = "국립중앙도서관",
                )
            }
}
