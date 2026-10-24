// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import java.io.File
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kr.vici.ashreader.data.MetaRules
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 작업 06 자동 매칭 정확도 측정(손으로 돌림, 네트워크 필요): 실제 출판된 책 30권의 흔한 파일 이름으로
 * 앱과 같은 규칙([MetaRules])을 카카오 책 검색(local.properties 의 kakao.restKey 가 있을 때) + 구글 북스에 돌려,
 * 자동 적용된 것 중 틀린 비율을 잰다(목표 5% 이하). 키는 읽기만 하고 출력하지 않는다.
 * 개인 문서 이름은 보내지 않는다. 결과는 app/build/meta-accuracy.csv.
 *
 *     ASH_META_PROBE=1 gradlew :app:testDebugUnitTest --tests kr.vici.ashreader.MetaAccuracyProbe
 *
 * CI 에서는 건너뛴다(환경변수 없음). 앱 코드의 네트워크는 net/BookInfoNet 한 곳 — 이 시험은 그 규칙만 쓰고 연결은 따로 한다.
 */
class MetaAccuracyProbe {
    /** 파일 이름 → (정답 제목 핵심, 정답 지은이 핵심). */
    private val cases = listOf(
        "데미안 - 헤르만 헤세.epub" to ("데미안" to "헤세"),
        "어린왕자(생텍쥐페리)_완역본.epub" to ("어린 왕자" to "생텍쥐페리"),
        "1984 - George Orwell.epub" to ("1984" to "Orwell|오웰"),
        "Animal Farm (George Orwell) [EN].epub" to ("Animal Farm" to "Orwell|오웰"),
        "채식주의자_한강.epub" to ("채식주의자" to "한강"),
        "소년이 온다 - 한강 [창비].epub" to ("소년이 온다" to "한강"),
        "아몬드 손원평.epub" to ("아몬드" to "손원평"),
        "82년생 김지영.epub" to ("82년생 김지영" to "조남주"),
        "The Great Gatsby - F. Scott Fitzgerald.epub" to ("Great Gatsby" to "Fitzgerald|피츠제럴드"),
        "Pride and Prejudice by Jane Austen.epub" to ("Pride and Prejudice" to "Austen|오스틴"),
        "[대원] 슬램덩크 03권 (완) 1200px.cbz" to ("슬램덩크" to "이노우에"),
        "원피스_vol.012.cbz" to ("원피스" to "오다"),
        "나루토 1권.cbz" to ("나루토" to "키시모토"),
        "진격의 거인 05권 [학산].cbz" to ("진격의 거인" to "이사야마"),
        "드래곤볼 완전판 01.cbz" to ("드래곤볼" to "토리야마"),
        "명탐정 코난 10권.cbz" to ("명탐정 코난" to "아오야마"),
        "귀멸의 칼날 1권 (scan).cbz" to ("귀멸의 칼날" to "고토게"),
        "One Piece v01 (Digital).cbz" to ("One Piece" to "Oda|오다"),
        "Naruto Vol. 3.cbz" to ("Naruto" to "Kishimoto|키시모토"),
        "Berserk 01 [HQ].cbz" to ("Berserk" to "Miura|미우라"),
        "총균쇠_재레드다이아몬드_스캔.pdf" to ("총 균 쇠" to "다이아몬드"),
        "사피엔스 (유발 하라리) 600dpi.pdf" to ("사피엔스" to "하라리"),
        "코스모스 칼세이건 scan.pdf" to ("코스모스" to "세이건"),
        "정의란 무엇인가 - 마이클 샌델.pdf" to ("정의란 무엇인가" to "샌델"),
        "이기적 유전자 (리처드 도킨스) 스캔본.pdf" to ("이기적 유전자" to "도킨스"),
        "The Selfish Gene - Richard Dawkins.pdf" to ("Selfish Gene" to "Dawkins|도킨스"),
        "토지 1권 박경리.pdf" to ("토지" to "박경리"),
        "해리포터와 마법사의 돌 1.epub" to ("해리 포터와 마법사의 돌" to "롤링"),
        "삼국지 1권 이문열.epub" to ("삼국지" to "이문열"),
        "난장이가 쏘아올린 작은 공 조세희.epub" to ("난장이가 쏘아올린 작은 공" to "조세희"),
    )

    /** local.properties 의 kakao.restKey(없으면 ""). 출력하지 않는다. */
    private val kakaoKey: String by lazy {
        val f = listOf(File("../local.properties"), File("local.properties")).firstOrNull { it.isFile } ?: return@lazy ""
        f.readLines().firstOrNull { it.startsWith("kakao.restKey=") }?.substringAfter('=')?.trim().orEmpty()
    }

    private fun kakao(text: String): List<MetaRules.Candidate> {
        if (kakaoKey.isBlank()) return emptyList()
        val c = URL("https://dapi.kakao.com/v3/search/book?target=title&size=10&query=${URLEncoder.encode(text, "UTF-8")}").openConnection() as HttpsURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        c.setRequestProperty("Authorization", "KakaoAK $kakaoKey")
        val body = try { c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) } } finally { c.disconnect() }
        fun str(chunk: String, key: String) = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(chunk)?.groupValues?.get(1).orEmpty()
        return body.substringAfter("\"documents\"").split(Regex("\\{\\s*\"authors\"")).drop(1).map { chunk ->
            val authors = Regex("^\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(chunk)?.groupValues?.get(1)
                ?.let { a -> Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(a).joinToString(", ") { it.groupValues[1] } }.orEmpty()
            MetaRules.Candidate(str(chunk, "title"), authors, str(chunk, "publisher"), str(chunk, "datetime").take(4), "", "", "카카오")
        }
    }

    private fun google(text: String): List<MetaRules.Candidate> {        val c = URL("https://www.googleapis.com/books/v1/volumes?q=intitle:${URLEncoder.encode(text, "UTF-8")}&maxResults=10&printType=books").openConnection() as HttpsURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        val body = try { c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) } } finally { c.disconnect() }
        // 단위 시험은 안드로이드 org.json 이 빈 껍데기라 간단히 정규식으로(volumeInfo 마다 title·subtitle·authors·publisher·publishedDate)
        fun str(chunk: String, key: String) = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(chunk)?.groupValues?.get(1).orEmpty()
        return body.split("\"volumeInfo\"").drop(1).map { chunk ->
            val authors = Regex("\"authors\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(chunk)?.groupValues?.get(1)
                ?.let { a -> Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(a).joinToString(", ") { it.groupValues[1] } }.orEmpty()
            MetaRules.Candidate(
                listOf(str(chunk, "title"), str(chunk, "subtitle")).filter { it.isNotBlank() }.joinToString(": "),
                authors, str(chunk, "publisher"), str(chunk, "publishedDate").take(4), "", "", "구글 북스",
            )
        }
    }
    /** 맞는 책인가: 제목 핵심이 들어 있고(띄어쓰기 무시), 지은이가 있으면 지은이 핵심도. */
    private fun right(c: MetaRules.Candidate, title: String, author: String): Boolean {
        val t = MetaRules.norm(c.title); val want = MetaRules.norm(title)
        val titleOk = t.contains(want) || MetaRules.similarity(c.title, title) >= 85
        val authorOk = c.author.isBlank() || author.split('|').any { MetaRules.norm(c.author).contains(MetaRules.norm(it)) }
        return titleOk && authorOk
    }

    @Test
    fun measure() {
        assumeTrue("ASH_META_PROBE=1 일 때만(네트워크)", System.getenv("ASH_META_PROBE") == "1")
        val rows = ArrayList<String>()
        rows += "파일 이름,검색어,권수,후보 수,1위 제목,1위 지은이,일치도,권수 맞음,자동 적용,맞는 책"
        var auto = 0; var wrongAuto = 0; var review = 0; var none = 0
        for ((file, want) in cases) {
            val q = MetaRules.query(file.substringBeforeLast('.'))
            val text = if (q.volume > 0) "${q.title} ${q.volume}" else q.title
            val ranked = MetaRules.rank(q, runCatching { kakao(text) }.getOrDefault(emptyList()) + runCatching { google(text) }.getOrDefault(emptyList()))
            val best = ranked.firstOrNull()
            val ok = best != null && right(best.first, want.first, want.second)
            when {
                best == null -> none++
                best.second.auto -> { auto++; if (!ok) wrongAuto++ }
                else -> review++
            }
            fun esc(s: String) = "\"" + s.replace("\"", "'") + "\""
            rows += listOf(esc(file), esc(q.title), q.volume.toString(), ranked.size.toString(), esc(best?.first?.title.orEmpty()), esc(best?.first?.author.orEmpty()),
                (best?.second?.score ?: 0).toString(), (best?.second?.volumeOk ?: false).toString(), (best?.second?.auto ?: false).toString(), ok.toString()).joinToString(",")
            Thread.sleep(400) // 구글 북스에 무리 주지 않게
        }
        val summary = "전체 ${cases.size} · 자동 적용 $auto(틀림 $wrongAuto = ${if (auto == 0) 0 else wrongAuto * 100 / auto}%) · 확인 필요 $review · 후보 없음 $none"
        rows += summary
        File("build/meta-accuracy.csv").apply { parentFile?.mkdirs(); writeText("﻿" + rows.joinToString("\n")) }
        println(summary)
    }
}
