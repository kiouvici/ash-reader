// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.SeriesGroups
import kr.vici.ashreader.data.Bookmark
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.LibraryRepo
import kr.vici.ashreader.data.IntentInbox
import kr.vici.ashreader.data.Highlight
import kr.vici.ashreader.data.Note
import kr.vici.ashreader.data.ReadingSession
import kr.vici.ashreader.data.Settings
import kr.vici.ashreader.doc.ComicUpscale
import kr.vici.ashreader.ui.AppSettingsScreen
import kr.vici.ashreader.ui.AshTheme
import kr.vici.ashreader.ui.ExportSheet
import kr.vici.ashreader.ui.LibraryFoldersScreen
import kr.vici.ashreader.ui.BackupImportScreen
import kr.vici.ashreader.ui.StorageIntroScreen
import kr.vici.ashreader.ui.StoreEditScreen
import kr.vici.ashreader.ui.Icons
import kr.vici.ashreader.ui.LibraryScreen
import kr.vici.ashreader.ui.LicensesScreen
import kr.vici.ashreader.ui.NextVolumeSheet
import kr.vici.ashreader.ui.ReaderScreen
import kr.vici.ashreader.ui.ReaderTarget
import kr.vici.ashreader.ui.SeriesScreen
import kr.vici.ashreader.ui.StatsScreen
import kr.vici.ashreader.ui.VolumeKeys
import kr.vici.ashreader.ui.statsSince
import kr.vici.ashreader.ui.ZipPickSheet

internal sealed interface Screen {
    data object Library : Screen
    data object Stats : Screen
    data object Settings : Screen
    data object Licenses : Screen
    data class Series(val key: String) : Screen
    data class Reader(val target: ReaderTarget, val once: LibraryRepo.Opened?) : Screen
    /** S-02 파일 접근 권한 안내 */
    data object StorageIntro : Screen
    /** S-17 서재 폴더(설정 > 서재). [intro] = 첫 실행 안내(S-02)에서 옴 */
    data class Folders(val intro: Boolean) : Screen
    /** S-53 백업 가져오기(폴더에서 백업 파일 고르기) */
    data object BackupImport : Screen
    /** S-15 서점 바로가기 편집 */
    data object StoreEdit : Screen
}

/**
 * 화면 이동 상태(필수 2). 화면 회전·프로세스 재시작에도 읽던 책·한 번만 보기 문서로 돌아오게 SavedStateHandle 에 둔다.
 * 암호(PDF·Word)는 회전 때 다시 묻지 않도록 이 객체 메모리에만 두고 번들에는 넣지 않는다(저장 금지, F-34).
 */
internal class NavModel(private val h: SavedStateHandle) : ViewModel() {
    private val screenState = mutableStateOf(decode(h.get<Bundle>(K_SCREEN)) ?: Screen.Library)
    private val tabState = mutableStateOf(decode(h.get<Bundle>(K_TAB))?.takeIf { it is Screen.Library || it is Screen.Stats || it is Screen.Settings } ?: Screen.Library)

    var screen: Screen
        get() = screenState.value
        set(v) { screenState.value = v; h[K_SCREEN] = encode(v) }

    var tab: Screen
        get() = tabState.value
        set(v) { tabState.value = v; h[K_TAB] = encode(v) }

    val passwords: MutableMap<String, String> = HashMap()

    /** 다른 앱에서 받은 문서(I-714). 처리를 끝낼 때까지 SavedStateHandle 에 남는다. */
    val inbox = IntentInbox(
        h.get<ArrayList<Uri>>(K_PENDING)?.let { IntentInbox.Item(h.get<Long>(K_PENDING_SEQ) ?: 0L, it.toList()) },
        h.get<Long>(K_DONE) ?: 0L,
    ) { item, done ->
        h[K_PENDING] = item?.let { ArrayList(it.uris) }
        h[K_PENDING_SEQ] = item?.seq ?: 0L
        h[K_DONE] = done
    }

    /** 화면 회전·재생성과 상관없이 보여 줄 안내(가져오기 결과 등). */
    val notices = Channel<String>(Channel.BUFFERED)

    /**
     * 여러 파일 가져오기(공유 SEND_MULTIPLE·파일 고르기). 화면이 회전돼도 멈추지 않게 ViewModel 범위에서 돈다.
     * 한 번에 [MAX_IMPORT]개까지만 넣고, 넘는 몫은 알린다.
     */
    fun importAll(lib: LibraryRepo, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val take = uris.take(MAX_IMPORT)
        viewModelScope.launch {
            var ok = 0
            for (u in take) runCatching { lib.import(u) }.onSuccess { ok++ }
            val msg = buildString {
                append(if (ok == take.size) "${ok}권을 서재에 넣었어요." else "${ok}권을 넣고, ${take.size - ok}개 파일은 넣지 못했어요.")
                if (uris.size > take.size) append(" 한 번에 ${MAX_IMPORT}개까지만 넣어요(${uris.size - take.size}개 제외).")
            }
            notices.send(msg)
        }
    }

    companion object {
        private const val K_SCREEN = "ash.screen"
        private const val K_TAB = "ash.tab"
        private const val K_PENDING = "ash.pending"
        private const val K_PENDING_SEQ = "ash.pendingSeq"
        private const val K_DONE = "ash.pendingDone"
        const val MAX_IMPORT = 50

        fun encode(sc: Screen): Bundle = Bundle().apply {
            when (sc) {
                Screen.Library -> putString("k", "library")
                Screen.Stats -> putString("k", "stats")
                Screen.Settings -> putString("k", "settings")
                Screen.Licenses -> putString("k", "licenses")
                Screen.StorageIntro -> putString("k", "storage")
                Screen.StoreEdit -> putString("k", "stores")
                Screen.BackupImport -> putString("k", "backupImport")
                is Screen.Folders -> { putString("k", "folders"); putBoolean("intro", sc.intro) }
                is Screen.Series -> { putString("k", "series"); putString("key", sc.key) }
                is Screen.Reader -> {
                    putString("k", "reader")
                    val t = sc.target
                    putString("id", t.id); putString("title", t.title); putString("path", t.path); putString("format", t.format.name)
                    putInt("startPage", t.startPage); putBoolean("once", t.once); putInt("viewMode", t.viewMode); putInt("startCount", t.startCount)
                    putString("enc", t.textEncoding)
                    sc.once?.let { o ->
                        putString("oPath", o.file.path); putString("oTitle", o.title); putString("oFormat", o.format.name); putString("oId", o.id)
                    }
                }
            }
        }

        fun decode(b: Bundle?): Screen? = when (b?.getString("k")) {
            "library" -> Screen.Library
            "stats" -> Screen.Stats
            "settings" -> Screen.Settings
            "licenses" -> Screen.Licenses
            "storage" -> Screen.StorageIntro
            "stores" -> Screen.StoreEdit
            "backupImport" -> Screen.BackupImport
            "folders" -> Screen.Folders(b.getBoolean("intro"))
            "series" -> b.getString("key")?.let { Screen.Series(it) }
            "reader" -> {
                val id = b.getString("id"); val path = b.getString("path")
                if (id.isNullOrEmpty() || path.isNullOrEmpty()) null
                else Screen.Reader(
                    ReaderTarget(
                        id, b.getString("title").orEmpty(), path, DocFormat.of(b.getString("format").orEmpty()),
                        b.getInt("startPage"), b.getBoolean("once"), b.getInt("viewMode"), b.getInt("startCount"),
                        b.getString("enc").orEmpty(),
                    ),
                    b.getString("oPath")?.let { op ->
                        LibraryRepo.Opened(File(op), b.getString("oTitle").orEmpty(), DocFormat.of(b.getString("oFormat").orEmpty()), b.getString("oId").orEmpty())
                    },
                )
            }
            else -> null
        }
    }
}

class MainActivity : ComponentActivity() {
    /** 화면 상태 + 외부에서 받은 문서(VIEW·SEND) 받은편지함. Activity 가 다시 만들어져도 같은 것(ViewModel). */
    private val nav: NavModel by lazy {
        ViewModelProvider(this, viewModelFactory { initializer { NavModel(createSavedStateHandle()) } })[NavModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            // I-714: 최근 앱 목록에서 다시 띄운 경우(LAUNCHED_FROM_HISTORY)는 예전 '열기' 인텐트가 다시 오므로 무시한다
            if (!fromHistory(intent)) nav.inbox.offer(urisOf(intent))
            // 필수 5: 앱 시작 때 쓸기 — 서재에 없는 문서의 변환 캐시 + (설정이 켜져 있으면) 한 번만 보기 사본.
            // 회전·복원(savedInstanceState 있음)일 때는 열린 한 번만 보기 문서를 지우지 않도록 건너뛴다.
            val app = ash
            app.appScope.launch { runCatching { app.library.sweep(app.prefs.settings.first().clearOnceOnExit) } }
        }
        setContent { AshTheme { AshRoot(nav) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // I-714: 읽기 화면이 열려 있든 서재든, 새로 받은 문서는 늘 그 문서로 연다(같은 파일을 다시 받아도 새로)
        setIntent(intent)
        if (!fromHistory(intent)) nav.inbox.offer(urisOf(intent))
    }

    private fun fromHistory(i: Intent?): Boolean = i != null && (i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0

    /** VIEW·SEND 는 한 개, SEND_MULTIPLE(여러 파일 공유)은 여러 개(F-05). */
    private fun urisOf(i: Intent?): List<Uri> = when (i?.action) {
        Intent.ACTION_VIEW -> listOfNotNull(i.data)
        Intent.ACTION_SEND -> listOfNotNull(streamOf(i))
        Intent.ACTION_SEND_MULTIPLE -> streamsOf(i)
        else -> emptyList()
    }.filter { safeIncoming(it) }

    /**
     * 받은 URI 검사(권장): content:// 만 받고, 이 앱 자신의 provider·앱 전용 폴더를 가리키는 것은 거부한다
     * (다른 앱이 우리 내부 파일을 '열어 달라'고 넘겨 빼내는 것을 막음).
     */
    private fun safeIncoming(u: Uri): Boolean {
        if (u.scheme != "content") return false
        val auth = u.authority.orEmpty()
        if (auth.startsWith(packageName)) return false
        val p = Uri.decode(u.toString())
        val priv = listOfNotNull(dataDir?.path, filesDir.path, cacheDir.path, "/data/data/$packageName", "/data/user/0/$packageName")
        return priv.none { p.contains(it) }
    }

    @Suppress("DEPRECATION")
    private fun streamsOf(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))?.filterNotNull().orEmpty()

    @Suppress("DEPRECATION")
    private fun streamOf(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP) {
            val h = VolumeKeys.handler
            if (h != null) {
                // 길게 눌러도 한 번만 (화면 설계서 4.5)
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) h(code == KeyEvent.KEYCODE_VOLUME_DOWN)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (isFinishing) {
            // 필수 5: 앱을 닫으면 서재에 없는 문서의 변환 캐시는 늘 지우고,
            // '한 번만 보기 기록 지우기'(기본 켬)가 켜져 있을 때만 한 번만 보기 사본도 지운다.
            val app = ash
            app.appScope.launch { runCatching { app.library.sweep(app.prefs.settings.first().clearOnceOnExit) } }
        }
        super.onDestroy()
    }
}

@Composable
private fun AshRoot(nav: NavModel) {
    val context = LocalContext.current
    val app = context.ash
    val dao = app.db.books()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val settings by app.prefs.settings.collectAsState(initial = Settings())
    val books by remember { dao.recent() }.collectAsState(initial = emptyList())
    // 필수 2: 화면 상태는 ViewModel(SavedStateHandle)에 — 회전해도 읽기 화면·한 번만 보기 문서가 유지된다
    var nextVol by remember { mutableStateOf<Pair<Book, Book>?>(null) }
    val pending by nav.inbox.state.collectAsState()

    fun change(f: (Settings) -> Settings) { scope.launch { app.prefs.update(f) } }

    // AI 화질 캐시 상한(F-73): 설정값을 엔진에 넣고 넘치면 줄인다
    // (화면의 settings 는 처음에 기본값이라, 저장된 값이 읽힌 뒤에만 반영하도록 DataStore 를 직접 본다)
    LaunchedEffect(Unit) {
        app.prefs.settings.map { it.upscaleCacheMb }.distinctUntilChanged().collect { mb ->
            val limit = mb.toLong() shl 20
            ComicUpscale.limitBytes = limit
            withContext(Dispatchers.IO) { runCatching { ComicUpscale.trim(app, limit) } }
        }
    }

    // F-01: 폴더 수집. 앱 시작·복귀 때(30초에 한 번까지)와 새로고침·폴더 바꿈 때.
    var lastScan by remember { mutableStateOf(0L) }
    fun scan(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastScan < 30_000) return
        lastScan = now
        scope.launch {
            runCatching { app.collector.scan() }.onSuccess { r -> if (r.added > 0) snack.showSnackbar("새 파일 ${r.added}개를 찾았어요.") }
        }
    }
    val scanning by app.collector.busy.collectAsState()
    // S-17: 찾을 수 없는 서재 폴더 안 책은 지우지 않고 '열 수 없음'으로 보인다
    val badFolders by app.collector.unavailable.collectAsState()
    val allFolders by remember { dao.scanFolders() }.collectAsState(initial = emptyList())
    val unavailableBooks = remember(books, allFolders, badFolders) { app.collector.unavailableBooks(books, allFolders, badFolders) }
    val stores by remember { dao.storeApps() }.collectAsState(initial = emptyList())
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val st = app.prefs.settings.first()
        runCatching { app.stores.ensureDefaults(app.prefs, st.storeInit) }
        if (!st.storageAsked && nav.screen == Screen.Library) nav.screen = Screen.StorageIntro else scan(force = true)
        ready = true
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (ready && nav.screen !is Screen.StorageIntro) scan() }

    /**
     * 파일 접근 허용됨(S-02) → 서재 폴더 고르기(S-17). 예전처럼 Download 만 몰래 넣지 않고 사용자가 고른다
     * (Download 는 그 화면의 '바로 추가' 한 번으로).
     */
    fun storageGranted() {
        scope.launch {
            app.prefs.update { it.copy(storageAsked = true) }
            nav.screen = Screen.Folders(intro = true)
        }
    }

    var exportFor by remember { mutableStateOf<String?>(null) }
    var exportAll by remember { mutableStateOf(false) }
    /** S-46 압축 파일 안 고르기: 압축 파일·식별값·한 번만 보기 여부·안의 문서들. */
    var zipPick by remember { mutableStateOf<ZipPick?>(null) }
    var zipEmpty by remember { mutableStateOf(false) }

    /** 압축 안 문서를 연다(서재 기록이 있으면 그 기록으로 이어 읽기). */
    fun openInner(z: ZipPick, item: LibraryRepo.ZipItem) {
        zipPick = null
        scope.launch {
            runCatching { app.library.openInner(z.file, z.zipId, item, z.once) }.onSuccess { (o, b) ->
                nav.screen = if (b != null) Screen.Reader(
                    ReaderTarget(b.id, b.title, o.file.absolutePath, DocFormat.of(b.format), b.lastPage, false, b.viewMode, b.pageCount, b.textEncoding), null,
                ) else Screen.Reader(ReaderTarget(o.id, o.title, o.file.absolutePath, o.format, 0, once = true), o)
            }.onFailure { e -> snack.showSnackbar(e.message ?: "압축 안 문서를 열 수 없어요.") }
        }
    }

    /** 문서가 든 ZIP(S-46): 1개면 바로 열고, 여럿이면 고르기, 없으면 안내. */
    fun openZip(file: File, zipId: String, title: String, once: Boolean) {
        scope.launch {
            runCatching { app.library.zipItems(file) }.onSuccess { items ->
                val z = ZipPick(file, zipId, title, once, items)
                when (items.size) {
                    0 -> zipEmpty = true
                    1 -> openInner(z, items[0])
                    else -> zipPick = z
                }
            }.onFailure { snack.showSnackbar("압축 파일을 열 수 없어요. 손상되었을 수 있어요.") }
        }
    }

    fun openTarget(b: Book) {
        scope.launch {
            runCatching { app.library.localPath(b) }.onSuccess { p ->
                val f = DocFormat.of(b.format)
                if (f == DocFormat.ZIP) { dao.touch(b.id); openZip(File(p), b.id, b.title, once = false); return@onSuccess }
                nav.screen = Screen.Reader(
                    ReaderTarget(b.id, b.title, p, f, b.lastPage, once = false, viewMode = b.viewMode, startCount = b.pageCount, textEncoding = b.textEncoding), null,
                )
            }.onFailure { e -> snack.showSnackbar(e.message ?: "파일을 열 수 없어요.") }
        }
    }

    fun open(b: Book) = openTarget(b)

    // 외부 인텐트 → 한 번만 보기 (서재에 이미 있으면 그 책으로 연다)
    // I-714: 받은편지함은 처리를 끝낸 뒤에만 비운다(복사 중 회전·재생성돼도 다시 처리). 새 것이 오면 이 처리는 취소되고 새 것을 연다.
    LaunchedEffect(pending) {
        val item = pending ?: return@LaunchedEffect
        val all = item.uris
        if (all.size > 1) {
            // 여러 파일 공유(SEND_MULTIPLE): 서재에 넣고 알린다(최대 50개, 회전해도 이어서)
            nav.inbox.done(item.seq)
            nav.screen = nav.tab
            nav.importAll(app.library, all)
            return@LaunchedEffect
        }
        val uri = all[0]
        runCatching { app.library.openOnce(uri) }.onSuccess { o ->
            if (o.format == DocFormat.ZIP) {
                // 서재에 같은 압축 파일이 있으면 그 기록으로(안 문서의 읽은 위치가 이어짐)
                val inLib = dao.get(o.id)?.takeIf { it.hidden == 0 }
                openZip(o.file, o.id, o.title, once = inLib == null)
                return@onSuccess
            }
            val existing = dao.get(o.id)?.takeIf { it.hidden == 0 }
            // 서재에 같은 문서가 있으면 그 기록으로 이어 읽는다(F-06). 서재 쪽 파일을 열 수 있으면 받은 사본은 바로 지우고,
            // 없으면 받은 사본으로 열고 닫을 때 지운다(사본이 캐시에 쌓이지 않게).
            // (SAF 로 모은 문서는 다시 복사하지 않도록 받은 사본으로 연다)
            val libPath = existing?.takeIf { it.origin != Book.ORIGIN_SAF }?.let { e -> runCatching { app.library.localPath(e) }.getOrNull() }
            if (existing != null && libPath != null) app.library.discardOnce(o)
            nav.screen = if (existing != null) Screen.Reader(
                ReaderTarget(existing.id, existing.title, libPath ?: o.file.absolutePath, DocFormat.of(existing.format), existing.lastPage, false, existing.viewMode, existing.pageCount, existing.textEncoding),
                if (libPath == null) o else null,
            )
            else Screen.Reader(ReaderTarget(o.id, o.title, o.file.absolutePath, o.format, 0, once = true), o)
        }.onFailure { e ->
            // 취소(새 문서를 받음·화면 재생성)는 실패가 아니다 — 받은편지함에 남겨 다시 처리한다
            if (e is kotlinx.coroutines.CancellationException) throw e
            scope.launch { snack.showSnackbar("파일을 열 수 없어요. ${e.message.orEmpty()}") }
        }
        nav.inbox.done(item.seq)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        nav.importAll(app.library, uris)
    }
    LaunchedEffect(nav) { for (m in nav.notices) snack.showSnackbar(m) }

    when (val sc = nav.screen) {
        is Screen.Reader -> {
            BackHandler { nav.screen = nav.tab }
            val bookId = sc.target.id
            // 필수 5: 한 번만 보기 문서를 닫으면(회전은 제외) 변환 캐시·사본 정리. 암호도 메모리에서 지운다.
            val activity = context as? Activity
            val clearCopy by rememberUpdatedState(settings.clearOnceOnExit)
            DisposableEffect(bookId, sc.once) {
                val o = sc.once
                onDispose {
                    if (activity?.isChangingConfigurations != true) {
                        nav.passwords.remove(bookId)
                        // 서재에 있는 문서를 받은 사본으로 연 경우(once=false)는 사본을 늘 지운다
                        val drop = clearCopy || !sc.target.once
                        if (o != null) app.appScope.launch { runCatching { app.library.closeOnce(o, drop) } }
                    }
                }
            }
            val bookmarks by remember(bookId) { dao.bookmarks(bookId) }.collectAsState(initial = emptyList<Bookmark>())
            val notes by remember(bookId) { dao.notes(bookId) }.collectAsState(initial = emptyList<Note>())
            val highlights by remember(bookId) { dao.highlights(bookId) }.collectAsState(initial = emptyList<Highlight>())
            // I-714: 다른 문서로 바뀌면 읽기 화면 상태를 통째로 새로(앞 문서의 상태가 남아 앞 문서가 다시 보이지 않게)
            key(sc.target.id) {
                ReaderScreen(
                    target = sc.target, s = settings, onSettings = ::change,
                    onBack = { nav.screen = nav.tab },
                    onAddToLibrary = {
                        sc.once?.let { o ->
                            scope.launch {
                                val b = app.library.addOpened(o)
                                nav.screen = Screen.Reader(sc.target.copy(path = b.path, once = false), null)
                                snack.showSnackbar("서재에 넣었어요.")
                            }
                        }
                    },
                    onProgress = { p, n ->
                        if (!sc.target.once) scope.launch {
                            val st = if (p >= n - 1) Book.DONE else Book.READING
                            dao.progress(bookId, p, n, st)
                        }
                    },
                    onFinished = {
                        if (!sc.target.once) scope.launch {
                            // F-61: 같은 폴더(같은 묶음)의 파일명 숫자 순서로 다음 권, 없으면 같은 시리즈의 다음 권 번호
                            val b = dao.get(bookId) ?: return@launch
                            SeriesGroups.next(b, dao.visibleBooks())?.let { nextVol = b to it }
                        }
                    },
                    onSession = { start, dur, pages ->
                        if (!sc.target.once && dur > 5_000) scope.launch { dao.addSession(ReadingSession(bookId = bookId, startedAt = start, durationMs = dur, pagesTurned = pages)) }
                    },
                    bookmarks = bookmarks, notes = notes,
                    onToggleBookmark = { p, frac, existingId ->
                        scope.launch {
                            if (sc.target.once) { snack.showSnackbar("서재에 추가하면 책갈피를 남길 수 있어요."); return@launch }
                            if (existingId != null) dao.removeBookmarkById(existingId) else dao.addBookmark(Bookmark(bookId = bookId, page = p, frac = frac))
                        }
                    },
                    onAddNote = { p, text, frac -> scope.launch { dao.addNote(Note(bookId = bookId, page = p, text = text, frac = frac)) } },
                    highlights = highlights,
                    onAddHighlight = { p, st, en, text, color, note, frac ->
                        scope.launch {
                            dao.addHighlight(Highlight(bookId = bookId, page = p, startIdx = st, endIdx = en, text = text, color = color, note = note, frac = frac))
                            snack.showSnackbar(if (note.isBlank()) "형광펜을 칠했어요." else "메모를 남겼어요.")
                        }
                    },
                    onRemoveHighlight = { id -> scope.launch { dao.removeHighlight(id) } },
                    onViewMode = { mode -> if (!sc.target.once) scope.launch { dao.setViewMode(bookId, mode) } },
                    passwords = nav.passwords,
                    onTextEncoding = { enc -> if (!sc.target.once) scope.launch { dao.setEncoding(bookId, enc) } },
                    onOpenStore = {
                        val first = stores.firstOrNull { Links.isInstalled(context, it.pkg) }
                        if (first == null || !Links.launch(context, first.pkg)) nav.screen = Screen.StoreEdit
                    },
                )
            }
            // 앱 안내(형광펜·메모를 남겼어요, 서재에 넣었어요 등)는 서재 탭 Scaffold 에만 걸려 있어 읽는 동안 보이지 않고
            // 서재로 돌아간 뒤에야 떴다(7D E2E) → 읽기 화면 위에도 띄운다. 빈 Box 라 누름은 받지 않는다.
            Box(Modifier.fillMaxSize()) {
                SnackbarHost(snack, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 140.dp))
            }
            nextVol?.let { (done, next) ->
                NextVolumeSheet(done, next,
                    onLibrary = { nextVol = null; nav.screen = Screen.Library },
                    onOpenNext = { nextVol = null; open(next) },
                    onDismiss = { nextVol = null })
            }
        }
        Screen.StorageIntro -> {
            BackHandler { scope.launch { app.prefs.update { it.copy(storageAsked = true) } }; nav.screen = nav.tab }
            StorageIntroScreen(
                onGranted = { storageGranted() },
                onFolders = {
                    scope.launch { app.prefs.update { it.copy(storageAsked = true) } }
                    nav.screen = Screen.Folders(intro = true)
                },
            )
        }
        else -> Scaffold(
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                NavigationBar {
                    NavItem("서재", Icons.Library, nav.tab == Screen.Library) { nav.tab = Screen.Library; nav.screen = Screen.Library }
                    NavItem("독서 기록", Icons.Chart, nav.tab == Screen.Stats) { nav.tab = Screen.Stats; nav.screen = Screen.Stats }
                    NavItem("설정", Icons.Gear, nav.tab == Screen.Settings) { nav.tab = Screen.Settings; nav.screen = Screen.Settings }
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (sc) {
                    Screen.Library -> LibraryScreen(
                        books = books, unavailable = unavailableBooks, onOpen = ::open, onAdd = { picker.launch(arrayOf("*/*")) },
                        onSeries = { nav.screen = Screen.Series(it) },
                        onRemove = { b -> scope.launch { app.library.remove(b) } },
                        stores = stores, scanning = scanning,
                        onRefresh = { scan(force = true) },
                        onFolders = { nav.screen = Screen.Folders(intro = false) },
                        onStoreEdit = { nav.screen = Screen.StoreEdit },
                        onExport = { b -> exportFor = b.id },
                        showStores = settings.showStores,
                        sort = settings.libSort, sortDesc = settings.libSortDesc,
                        onSort = { o, d -> change { it.copy(libSort = o, libSortDesc = d) } },
                    )
                    Screen.Stats -> {
                        val since = remember { statsSince() }
                        val week by remember { dao.sessionsSince(since) }.collectAsState(initial = emptyList())
                        val days by remember { dao.sessionDays() }.collectAsState(initial = emptyList())
                        val times by remember { dao.timeByBook() }.collectAsState(initial = emptyList())
                        StatsScreen(week, streakOf(days), books, times.associate { it.bookId to it.total })
                    }
                    Screen.Settings -> AppSettingsScreen(
                        settings, ::change, onClearOnce = { scope.launch { app.library.clearOnce() } }, onLicenses = { nav.screen = Screen.Licenses },
                        onStorage = { nav.screen = Screen.StorageIntro },
                        onFolders = { nav.screen = Screen.Folders(intro = false) },
                        onStoreEdit = { nav.screen = Screen.StoreEdit },
                        onExportAll = { exportAll = true },
                        onBackupImport = { nav.screen = Screen.BackupImport },
                    )
                    is Screen.Folders -> {
                        val back = { nav.screen = if (sc.intro) Screen.Library else nav.tab }
                        BackHandler { back() }
                        LibraryFoldersScreen(intro = sc.intro, onBack = back, onScan = { scan(force = true) })
                    }
                    Screen.BackupImport -> {
                        BackHandler { nav.screen = Screen.Settings }
                        BackupImportScreen(onBack = { nav.screen = Screen.Settings }, onDone = { msg -> nav.screen = Screen.Settings; scope.launch { snack.showSnackbar(msg) } })
                    }
                    Screen.StoreEdit -> {
                        BackHandler { nav.screen = nav.tab }
                        StoreEditScreen(onBack = { nav.screen = nav.tab })
                    }
                    Screen.StorageIntro -> Unit
                    Screen.Licenses -> {
                        BackHandler { nav.screen = Screen.Settings }
                        LicensesScreen(onBack = { nav.screen = Screen.Settings })
                    }
                    is Screen.Series -> {
                        BackHandler { nav.screen = Screen.Library }
                        val vols = remember(sc.key, books) { SeriesGroups.volumes(sc.key, books) }
                        SeriesScreen(
                            SeriesGroups.nameOf(sc.key), vols, all = books, onBack = { nav.screen = Screen.Library }, onOpen = ::open,
                            onUngroup = {
                                // F-62 묶기 풀기: 묶음만 풀고 파일은 그대로. 스낵바 [되돌리기]
                                val before = vols.map { Triple(it.id, it.seriesKey, it.seriesManual) }
                                nav.screen = Screen.Library
                                scope.launch {
                                    before.forEach { (id, key, _) -> dao.setSeries(id, key, Book.SERIES_EXCLUDED) }
                                    val r = snack.showSnackbar("'${SeriesGroups.nameOf(sc.key)}' 묶음을 풀었어요. 파일은 그대로예요.", actionLabel = "되돌리기", withDismissAction = true)
                                    if (r == SnackbarResult.ActionPerformed) before.forEach { (id, key, mode) -> dao.setSeries(id, key, mode) }
                                }
                            },
                            onRename = { name ->
                                if (vols.isNotEmpty()) scope.launch {
                                    vols.forEach { dao.setSeries(it.id, name, Book.SERIES_MANUAL) }
                                    nav.screen = Screen.Series(SeriesGroups.keyOf(vols.first().copy(seriesKey = name, seriesManual = Book.SERIES_MANUAL))!!)
                                }
                            },
                            onAdd = { picked ->
                                scope.launch {
                                    val name = SeriesGroups.nameOf(sc.key)
                                    // 직접 묶기: 지금 권들도 직접 묶음으로 바꿔 폴더가 달라도 한 묶음이 되게
                                    (vols + picked).forEach { dao.setSeries(it.id, name, Book.SERIES_MANUAL) }
                                    nav.screen = Screen.Series(SeriesGroups.keyOf(Book(id = "", title = "", format = "", path = "", seriesKey = name, seriesManual = Book.SERIES_MANUAL))!!)
                                }
                            },
                            isTaken = { n ->
                                val k = SeriesGroups.keyOf(Book(id = "", title = "", format = "", path = "", seriesKey = n, seriesManual = Book.SERIES_MANUAL))
                                k != null && k != sc.key && books.any { SeriesGroups.keyOf(it) == k }
                            },
                        )
                    }
                    is Screen.Reader -> Unit
                }
            }
            exportFor?.let { id -> ExportSheet(bookId = id) { exportFor = null } }
            if (exportAll) ExportSheet(bookId = null) { exportAll = false }
        }
    }
    zipPick?.let { z -> ZipPickSheet(z.title, z.items, onPick = { openInner(z, it) }, onDismiss = { zipPick = null }) }
    if (zipEmpty) androidx.compose.material3.AlertDialog(
        onDismissRequest = { zipEmpty = false },
        text = { Text("이 압축 파일 안에는 읽을 수 있는 파일이 없어요.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { zipEmpty = false }) { Text("확인") } },
    )
}

/** S-46 고르기 상태. */
internal data class ZipPick(val file: File, val zipId: String, val title: String, val once: Boolean, val items: List<LibraryRepo.ZipItem>)

/** 오늘(또는 어제)부터 거꾸로 이어진 날 수. [days] = epoch 일(UTC) 내림차순. */
private fun streakOf(days: List<Long>): Int {
    if (days.isEmpty()) return 0
    val today = System.currentTimeMillis() / 86_400_000L
    var expect = if (days.first() == today) today else if (days.first() == today - 1) today - 1 else return 0
    var n = 0
    for (d in days) { if (d == expect) { n++; expect-- } else if (d < expect) break }
    return n
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(selected = selected, onClick = onClick, icon = { Icon(icon, contentDescription = null) }, label = { Text(label) })
}
