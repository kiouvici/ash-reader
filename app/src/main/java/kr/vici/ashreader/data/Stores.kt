// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 서재 '내 서점 앱' 바로가기(F-36, S-15). 기본 = 예스24 eBook. 사용자가 기기에 설치된 런처 앱 중에서 골라 더하고 빼고 순서를 바꾼다.
 * 앱 목록은 MAIN/LAUNCHER 조회로만 얻는다(QUERY_ALL_PACKAGES 안 씀, 매니페스트 <queries>). 서점 앱의 책·파일에는 접근하지 않는다.
 */
class StoreRepo(private val context: Context, private val db: AshDb) {
    private val dao get() = db.books()

    data class LauncherApp(val pkg: String, val label: String)

    /** 처음 한 번 기본 항목(예스24)을 넣는다. 이미 넣었으면(사용자가 뺐어도) 다시 넣지 않는다. */
    suspend fun ensureDefaults(prefs: Prefs, already: Boolean) {
        if (already) return
        if (dao.allStoreApps().isEmpty()) dao.upsertStoreApps(listOf(StoreApp(YES24, "예스24 eBook", 0)))
        prefs.update { it.copy(storeInit = true) }
    }

    /** 기기에 설치된 런처 앱(이 앱 제외), 이름 순. */
    suspend fun launcherApps(): List<LauncherApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(i, PackageManager.ResolveInfoFlags.of(0)) else pm.queryIntentActivities(i, 0)
        list.mapNotNull { r ->
            val pkg = r.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            LauncherApp(pkg, r.loadLabel(pm)?.toString().orEmpty().ifBlank { pkg })
        }.distinctBy { it.pkg }.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.KOREAN)) { it.label })
    }

    suspend fun add(pkg: String, label: String) {
        val cur = dao.allStoreApps()
        if (cur.any { it.pkg == pkg }) return
        dao.upsertStoreApps(listOf(StoreApp(pkg, label, (cur.maxOfOrNull { it.ord } ?: -1) + 1)))
    }

    suspend fun remove(pkg: String) = dao.deleteStoreApp(pkg)

    /** [pkg] 를 [delta](-1 위, +1 아래)만큼 옮기고 순서를 0부터 다시 매긴다. */
    suspend fun move(pkg: String, delta: Int) {
        val l = dao.allStoreApps().toMutableList()
        val i = l.indexOfFirst { it.pkg == pkg }
        val j = i + delta
        if (i < 0 || j !in l.indices) return
        val x = l.removeAt(i); l.add(j, x)
        dao.upsertStoreApps(l.mapIndexed { k, a -> a.copy(ord = k) })
    }

    companion object {
        /** 예스24 eBook 앱(실제 APK 로 확인). 매니페스트 <queries> 와 같이 바꿀 것. */
        const val YES24 = "com.yes24.ebook.fourth"

        private val icons = object : LruCache<String, Bitmap>(4 shl 20) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }

        /** 설치된 앱 아이콘(px 크기 정사각). 설치 안 됐으면 null. */
        fun icon(context: Context, pkg: String, px: Int): Bitmap? {
            icons.get("$pkg@$px")?.let { return it }
            val d = runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return null
            val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, px, px)
            d.draw(Canvas(b))
            icons.put("$pkg@$px", b)
            return b
        }
    }
}
