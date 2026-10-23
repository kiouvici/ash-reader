// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.e2e

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import kr.vici.ashreader.AshApp
import kr.vici.ashreader.MainActivity
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.ScanFolder
import kr.vici.ashreader.data.Settings
import kr.vici.ashreader.data.StorageAccess
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * 7D 종단(E2E) UI 시험 공통. 실제 앱(MainActivity)을 띄우고 UiAutomator 로 진짜 터치를 넣는다(넘김 화면은 Android View 라
 * 접근성 트리로 찾고 화면 좌표로 누른다). Compose 규칙은 설정 화면처럼 스크롤 목록을 정확히 다룰 때 쓴다.
 *
 * 시험 파일은 CI(.github/scripts/e2e-run.sh)가 미리 넣는다:
 *  - /sdcard/Download/ash-e2e/        공개 시험 파일 + 합성 파일(서재가 모음)
 *  - /sdcard/Documents/ash-e2e-view/  인텐트(ACTION_VIEW) 시험용(서재가 모으지 않음 → 한 번만 보기)
 *
 * 단계마다 화면을 찍어 앱 내부 폴더(files/e2e-shots/<시험 클래스>/)에 남긴다 → CI 가 run-as 로 꺼내 산출물로 올린다.
 * 화면 글자는 '이북'→'eBook' 이름 바꾸기(7A)와 무관하게 맞도록 정규식([EBOOK])으로 찾는다.
 */
abstract class E2eTest {
    /**
     * Compose 시험 규칙이 걸려 있으면 그 시험 동안 만들어진 모든 Compose 화면의 프레임이 시험 시계로 돌아,
     * Compose API(waitForIdle 등)를 부를 때만 화면이 바뀐다. UiAutomator 만 쓰는 시험에서는 화면이 멈춘 것처럼 보이므로
     * 규칙은 Compose API 를 쓰는 시험(설정)에만 걸고, 그 시험은 [pump] 로 프레임을 돌린다.
     */
    protected open fun pump() {}

    @get:Rule val failShot = object : TestWatcher() {
        override fun failed(e: Throwable?, d: Description) {
            runCatching { shot("FAIL-" + d.methodName) }
            Log.e(TAG, "실패: ${d.className}#${d.methodName}", e)
        }
    }

    protected val inst: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    protected val ctx: Context get() = inst.targetContext
    protected val app: AshApp get() = ctx.applicationContext as AshApp
    protected val device: UiDevice by lazy { UiDevice.getInstance(inst) }

    /** 대부분의 시험: 안내는 이미 거쳤고(파일 접근 허용됨) 설정은 기본값. */
    @Before open fun setUp() {
        device.wakeUp()
        shell("input keyevent 82") // 잠금 화면 풀기(없으면 무시됨)
        resetPrefs()
        ensureLibrary()
    }

    /** 다음 시험은 launchApp(CLEAR_TASK)으로 새로 시작한다. 여기서 홈으로 가면 실패 화면(FAIL-*)이 홈 화면으로 찍혀서 두지 않는다. */
    @After open fun tearDown() {}

    // ───────────── 상태 준비 ─────────────

    protected fun resetPrefs(storageAsked: Boolean = true, tapGuideShown: Boolean = true) = runBlocking {
        app.prefs.update { cur -> Settings(storageAsked = storageAsked, tapGuideShown = tapGuideShown, storeInit = cur.storeInit, ttsVolumeHint = true) }
    }

    /** Download 를 모으는 폴더로 두고 한 번 모은다(시험 파일이 서재에 들어오게). 읽은 위치·보기 방식은 처음으로. */
    protected fun ensureLibrary() = runBlocking {
        val dao = app.db.books()
        dao.upsertScanFolder(ScanFolder(StorageAccess.downloadDir().path, true, "Download"))
        runCatching { app.collector.scan() }.onFailure { Log.w(TAG, "모으기 실패", it) }
        for (b in dao.allBooks()) {
            dao.restart(b.id)
            dao.setViewMode(b.id, Book.VIEW_ORIGINAL)
        }
    }

    protected fun shell(cmd: String): String = runCatching { device.executeShellCommand(cmd) }.getOrDefault("")

    // ───────────── 앱 띄우기·화면 이동 ─────────────

    protected fun launchApp(clear: Boolean = true) {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (clear) i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(i)
        check(device.wait(Until.hasObject(By.pkg(PKG).depth(0)), 45_000)) { "앱이 뜨지 않음" }
        pump()
        device.waitForIdle(3_000)
    }

    /** 아래 탭('서재'·'독서 기록'·'설정')이 보이는 화면인가. */
    protected fun onTabs(): Boolean = device.hasObject(By.text("독서 기록"))

    protected fun goTab(name: String) {
        repeat(6) {
            if (onTabs()) {
                // '서재'는 제목과 탭 이름이 같다 → 가장 아래(탭 막대)에 있는 것을 누른다
                val c = device.findObjects(By.text(name)).maxByOrNull { it.visibleBounds.centerY() }
                if (c != null) { c.click(); device.waitForIdle(2_000); return }
            }
            device.pressBack()
            SystemClock.sleep(600)
            if (device.currentPackageName != PKG) launchApp(clear = false)
        }
        fail("'$name' 탭으로 갈 수 없음")
    }

    /** 서재에서 제목으로 찾아 연다. [badge] 가 있으면 같은 제목 중 그 형식(예: "PDF")만. */
    protected fun openFromLibrary(title: String, badge: String? = null) {
        goTab("서재")
        obj(By.desc("검색")).click()
        val field = obj(By.clazz("android.widget.EditText"))
        field.text = title
        SystemClock.sleep(500)
        val p = Pattern.compile(Pattern.quote(title) + ", " + (badge?.let { Pattern.quote(it) } ?: "[^,]+") + ", .*")
        val tile = find(By.desc(p), 20_000)
            ?: run { shot("no-tile-$title"); fail("서재에 '$title' 이(가) 없음"); throw IllegalStateException() }
        tile.click()
    }

    // ───────────── 읽기 화면 ─────────────

    sealed interface Opened {
        data class Page(val desc: String) : Opened
        data class Error(val msg: String) : Opened
        data object Password : Opened
        data object ZipPick : Opened
    }

    /** 문서가 열릴 때까지(쪽이 보이거나, 오류 안내·암호 창이 뜰 때까지) 기다린다. */
    protected fun waitOpened(timeoutMs: Long = 60_000): Opened {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            assertAlive()
            if (!device.hasObject(By.text("여는 중…"))) {
                device.findObject(By.desc(PAGE))?.let { return Opened.Page(it.contentDescription ?: "") }
                // 정보줄("2% · 1/40쪽")만 보이는 경우
                device.findObject(By.text(INFO))?.let { return Opened.Page(it.text ?: "") }
                if (device.hasObject(By.text("암호가 걸린 문서"))) return Opened.Password
                if (device.hasObject(By.textStartsWith("압축 파일 안에 읽을 수 있는 파일이"))) return Opened.ZipPick
                // 오류 화면: 가운데 안내 + [서재로] 단추
                if (device.hasObject(By.text("서재로")) && !onTabs()) {
                    val msg = device.findObjects(By.clazz("android.widget.TextView")).mapNotNull { it.text }.filter { it != "서재로" }.joinToString(" / ")
                    return Opened.Error(msg)
                }
            }
            SystemClock.sleep(300)
        }
        shot("open-timeout")
        fail("문서가 ${timeoutMs / 1000}초 안에 열리지 않음")
        throw IllegalStateException()
    }

    protected fun waitPage(timeoutMs: Long = 60_000): String {
        val o = waitOpened(timeoutMs)
        if (o !is Opened.Page) { shot("not-page"); fail("쪽이 보이지 않음: $o") }
        return (o as Opened.Page).desc
    }

    /** 지금 보이는 쪽 번호(1부터). 쪽 화면의 설명("제목 N쪽" 또는 "N쪽")에서 읽는다. */
    protected fun currentPage(): Int? {
        device.findObjects(By.desc(PAGE)).firstOrNull()?.contentDescription?.let { d ->
            return Regex("(\\d+)쪽$").find(d)?.groupValues?.get(1)?.toIntOrNull()
        }
        // 정보줄 "N/M쪽"·메뉴 "N / M쪽"
        val t = device.findObject(By.text(INFO))?.text ?: return null
        return Regex("(\\d+) ?/ ?\\d+쪽").find(t)?.groupValues?.get(1)?.toIntOrNull()
    }

    protected fun waitPageChange(from: Int?, forward: Boolean, timeoutMs: Long = 8_000): Int? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            val p = currentPage()
            if (p != null && from != null && (if (forward) p > from else p < from)) return p
            SystemClock.sleep(200)
        }
        return currentPage()
    }

    /** 화면 비율 좌표로 누르기. */
    protected fun tap(fx: Float, fy: Float) {
        device.click((device.displayWidth * fx).toInt(), (device.displayHeight * fy).toInt())
        SystemClock.sleep(700) // 고정 쪽·만화는 두 번 탭을 가리려고 한 번 탭을 250ms 미룬다
    }

    protected fun swipe(fx0: Float, fy0: Float, fx1: Float, fy1: Float, steps: Int = 20) {
        val w = device.displayWidth; val h = device.displayHeight
        device.swipe((w * fx0).toInt(), (h * fy0).toInt(), (w * fx1).toInt(), (h * fy1).toInt(), steps)
        SystemClock.sleep(900)
    }

    protected fun longPress(fx: Float, fy: Float) {
        val x = (device.displayWidth * fx).toInt(); val y = (device.displayHeight * fy).toInt()
        device.swipe(x, y, x + 1, y, 180) // 약 1초 누르고 있기
        SystemClock.sleep(800)
    }

    /** 읽기 메뉴(위·아래 막대)를 연다. */
    protected fun openMenu() {
        if (device.hasObject(By.desc("쪽 이동"))) return
        // 가운데(쪽 넘김 보기) → 맨 위(칸 단위 보기는 가운데가 칸 확대, 메뉴는 맨 위) 순으로
        for (y in listOf(0.5f, 0.07f, 0.5f, 0.07f)) {
            tap(0.5f, y)
            if (has(By.desc("쪽 이동"), 2_500)) return
        }
        shot("menu-not-open")
        fail("읽기 메뉴가 열리지 않음")
    }

    protected fun closeMenu() {
        if (device.hasObject(By.desc("쪽 이동"))) { device.pressBack(); SystemClock.sleep(500) }
    }

    /** 처음 여는 책의 탭 안내가 있으면 닫는다. */
    protected fun dismissTapGuide() {
        device.findObject(By.text("알겠어요"))?.let { it.click(); SystemClock.sleep(500) }
    }

    // ───────────── 찾기 도우미 ─────────────

    protected fun find(sel: BySelector, timeoutMs: Long): UiObject2? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            pump()
            device.findObject(sel)?.let { return it }
            if (SystemClock.uptimeMillis() > end) return null
            SystemClock.sleep(250)
        }
    }

    protected fun obj(sel: BySelector, timeoutMs: Long = 10_000): UiObject2 =
        find(sel, timeoutMs) ?: run {
            shot("missing")
            fail("화면에서 찾지 못함: $sel")
            throw IllegalStateException()
        }

    protected fun has(sel: BySelector, timeoutMs: Long = 5_000): Boolean = find(sel, timeoutMs) != null

    protected fun clickText(t: String, timeoutMs: Long = 10_000) = obj(By.text(t), timeoutMs).click().also { SystemClock.sleep(500) }

    /** 스크롤해 가며 찾는다(Compose 스크롤 목록도 접근성 스크롤로 움직인다). */
    protected fun scrollFind(sel: BySelector, maxSwipes: Int = 12): UiObject2? {
        device.findObject(sel)?.let { return it }
        val scrollable = device.findObject(By.scrollable(true))
        repeat(maxSwipes) {
            if (scrollable != null) runCatching { scrollable.scroll(Direction.DOWN, 0.6f) } else swipe(0.5f, 0.75f, 0.5f, 0.35f, 30)
            SystemClock.sleep(300)
            device.findObject(sel)?.let { return it }
        }
        return null
    }

    /** 글자 [label] 과 같은 줄에 있는 스위치(줄 전체가 눌리지 않고 스위치만 눌리는 곳용). */
    protected fun switchNear(label: String): UiObject2? {
        val l = scrollFind(By.text(label)) ?: return null
        val y = l.visibleBounds.centerY()
        return device.findObjects(By.checkable(true)).minByOrNull { kotlin.math.abs(it.visibleBounds.centerY() - y) }
    }

    // ───────────── 기록 ─────────────

    /** 화면을 찍어 남긴다(CI 산출물). */
    protected fun shot(name: String) {
        val dir = File(ctx.filesDir, "e2e-shots/" + javaClass.simpleName).apply { mkdirs() }
        val shotNo = SHOTS.merge(javaClass.simpleName, 1) { a, b -> a + b }!!
        val safe = name.replace(Regex("[^0-9A-Za-z가-힣._-]+"), "_").take(60)
        val f = File(dir, "%03d-%s.png".format(shotNo, safe))
        Log.i(TAG, "단계 ${javaClass.simpleName} #$shotNo $name")
        runCatching { device.takeScreenshot(f) }
    }

    /** 앱이 살아 있고(죽거나 ANR 창이 아니고) 앞에 떠 있는지. 시스템 화면(고르기 등)은 [allowOther] 로 허용. */
    protected fun assertAlive(allowOther: Boolean = false) {
        if (device.hasObject(By.textContains("isn't responding")) || device.hasObject(By.textContains("응답하지 않"))) {
            shot("ANR"); fail("ANR 창이 떴음")
        }
        if (device.hasObject(By.textContains("keeps stopping")) || device.hasObject(By.textContains("has stopped")) || device.hasObject(By.textContains("계속 중지"))) {
            shot("crash-dialog"); fail("앱이 멈춤(오류 창)")
        }
        if (!allowOther && device.currentPackageName != PKG) {
            // 잠깐 다른 창(키보드·권한)일 수 있다 → 조금 기다린 뒤 다시 본다
            SystemClock.sleep(1_000)
            if (device.currentPackageName != PKG) { shot("left-app"); fail("앱이 앞에 없음: ${device.currentPackageName}") }
        }
    }

    // ───────────── 인텐트 ─────────────

    /** 파일을 미디어 저장소에 등록해 content:// 주소를 얻는다(다른 앱이 '열기'로 넘기는 주소와 같은 꼴). */
    protected fun contentUriOf(path: String, mime: String): Uri {
        val latch = CountDownLatch(1)
        var out: Uri? = null
        MediaScannerConnection.scanFile(ctx, arrayOf(path), arrayOf(mime)) { _, uri -> out = uri; latch.countDown() }
        latch.await(30, TimeUnit.SECONDS)
        return out ?: error("미디어 등록 실패: $path")
    }

    protected fun sendView(uri: Uri, mime: String) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .setClassName(PKG, "$PKG.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(i)
        SystemClock.sleep(800)
    }

    protected fun visible(r: Rect) = r.width() > 0 && r.height() > 0

    companion object {
        const val TAG = "AshE2E"
        /** 클래스별 화면 번호(시험 메서드마다 인스턴스가 새로 생겨도 이어서 센다). */
        private val SHOTS = java.util.concurrent.ConcurrentHashMap<String, Int>()
        const val PKG = "kr.vici.ashreader"
        /** 쪽 화면 설명: "제목 N쪽"(넘김 화면)·"N쪽"(칸·스크롤·확대)·"웹툰 보기 N쪽". */
        val PAGE: Pattern = Pattern.compile("(.* )?\\d+쪽")
        /** 정보줄·메뉴의 쪽 표시: "2% · 1/40쪽", "1 / 40쪽". */
        val INFO: Pattern = Pattern.compile(".*\\d+ ?/ ?\\d+쪽.*")
        /** '이북식' → 'eBook' 이름 바꾸기(7A) 전후 모두. */
        val EBOOK: Pattern = Pattern.compile(".*(이북|eBook|ebook|EBOOK|전자책).*보기.*")
        val ORIGINAL: Pattern = Pattern.compile("원본.*보기.*")
        val API = Build.VERSION.SDK_INT
        const val DL = "/sdcard/Download/ash-e2e"
        const val VIEW_DIR = "/sdcard/Documents/ash-e2e-view"
    }
}
