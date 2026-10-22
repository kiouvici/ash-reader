// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import kr.vici.ashreader.data.AshDb
import kr.vici.ashreader.data.LibraryRepo
import kr.vici.ashreader.data.Prefs

class AshApp : Application() {
    val db: AshDb by lazy { AshDb.get(this) }
    val prefs: Prefs by lazy { Prefs(this) }
    val library: LibraryRepo by lazy { LibraryRepo(this, db) }
}

val Context.ash: AshApp get() = applicationContext as AshApp

/** 다른 앱 연결 (F-36 서점 바로가기, F-66 사전). 인터넷 없이 인텐트만 쓴다. */
object Links {
    /** 예스24 eBook 앱(실제 APK 로 확인, 시작 화면 com.yes24.ebook.fourth.MainActivity). 매니페스트 <queries> 와 같이 바꿀 것. */
    const val YES24 = "com.yes24.ebook.fourth"
    const val MALGEUL = "kr.vici.malgeul"

    fun isInstalled(context: Context, pkg: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg) != null

    /** 설치돼 있으면 열고 true. 아니면 false(화면이 안내창을 띄운다). */
    fun launch(context: Context, pkg: String): Boolean {
        val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** Play 스토어 앱 상세(스토어 앱이 없으면 false). market:// 은 인터넷 권한이 필요 없다. */
    fun openStore(context: Context, pkg: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) { false }

    /**
     * 단어 찾기(F-66): 말글사전으로 ACTION_PROCESS_TEXT. 없으면 다른 사전 앱 고르기 창.
     * 반환: 말글사전으로 보냈으면 true, 다른 앱 선택창(또는 실패)이면 false → 화면이 S-55 안내.
     */
    fun lookUp(context: Context, word: String): Boolean {
        val base = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, word)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val mine = Intent(base).setPackage(MALGEUL)
        if (mine.resolveActivity(context.packageManager) != null) {
            context.startActivity(mine); return true
        }
        try { context.startActivity(Intent.createChooser(base, "사전 앱 고르기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) {}
        return false
    }
}
