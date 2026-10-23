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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.Bookmark
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.LibraryRepo
import kr.vici.ashreader.data.Highlight
import kr.vici.ashreader.data.Note
import kr.vici.ashreader.data.ReadingSession
import kr.vici.ashreader.data.ScanFolder
import kr.vici.ashreader.data.StorageAccess
import kr.vici.ashreader.data.Settings
import kr.vici.ashreader.doc.ComicUpscale
import kr.vici.ashreader.ui.AppSettingsScreen
import kr.vici.ashreader.ui.AshTheme
import kr.vici.ashreader.ui.ExportSheet
import kr.vici.ashreader.ui.FoldersScreen
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
import kr.vici.ashreader.ui.weekStart
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
    /** S-03·설정 모으는 폴더. [intro] = 첫 안내에서 옴 */
    data class Folders(val intro: Boolean) : Screen
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

    companion object {
        private const val K_SCREEN = "ash.screen"
        private const val K_TAB = "ash.tab"

        fun encode(sc: Screen): Bundle = Bundle().apply {
            when (sc) {
                Screen.Library -> putString("k", "library")
                Screen.Stats -> putString("k", "stats")
                Screen.Settings -> putString("k", "settings")
                Screen.Licenses -> putString("k", "licenses")
                Screen.StorageIntro -> putString("k", "storage")
                Screen.StoreEdit -> putString("k", "stores")
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
    /** 외부에서 받은 문서(VIEW·SEND). */
    private val incoming = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            incoming.value = uriOf(intent)
            // 필수 5: 앱 시작 때 쓸기 — 서재에 없는 문서의 변환 캐시 + (설정이 켜져 있으면) 한 번만 보기 사본.
            // 회전·복원(savedInstanceState 있음)일 때는 열린 한 번만 보기 문서를 지우지 않도록 건너뛴다.
            val app = ash
            app.appScope.launch { runCatching { app.library.sweep(app.prefs.settings.first().clearOnceOnExit) } }
        }
        setContent { AshTheme { AshRoot(incoming) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        uriOf(intent)?.let { incoming.value = it }
    }

    private fun uriOf(i: Intent?): Uri? = when (i?.action) {
        Intent.ACTION_VIEW -> i.data
        Intent.ACTION_SEND -> streamOf(i)
        else -> null
    }?.takeIf { safeIncoming(it) }

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
private fun AshRoot(incoming: MutableStateFlow<Uri?>) {
    val context = LocalContext.current
    val app = context.ash
    val dao = app.db.books()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val settings by app.prefs.settings.collectAsState(initial = Settings())
    val books by remember { dao.recent() }.collectAsState(initial = emptyList())
    // 필수 2: 화면 상태는 ViewModel(SavedStateHandle)에 — 회전해도 읽기 화면·한 번만 보기 문서가 유지된다
    val nav: NavModel = viewModel { NavModel(createSavedStateHandle()) }
    var nextVol by remember { mutableStateOf<Pair<Book, Book>?>(null) }
    val pending by incoming.collectAsState()

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
    val stores by remember { dao.storeApps() }.collectAsState(initial = emptyList())
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val st = app.prefs.settings.first()
        runCatching { app.stores.ensureDefaults(app.prefs, st.storeInit) }
        if (!st.storageAsked && nav.screen == Screen.Library) nav.screen = Screen.StorageIntro else scan(force = true)
        ready = true
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (ready && nav.screen !is Screen.StorageIntro) scan() }

    /** 파일 접근 허용됨(S-02) → Download 를 모으는 폴더로 넣고 모은다. */
    fun storageGranted() {
        scope.launch {
            dao.upsertScanFolder(ScanFolder(StorageAccess.downloadDir().path, true, "Download"))
            app.prefs.update { it.copy(storageAsked = true) }
            nav.screen = Screen.Library
            scan(force = true)
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
    LaunchedEffect(pending) {
        val uri = pending ?: return@LaunchedEffect
        incoming.value = null
        runCatching { app.library.openOnce(uri) }.onSuccess { o ->
            if (o.format == DocFormat.ZIP) {
                // 서재에 같은 압축 파일이 있으면 그 기록으로(안 문서의 읽은 위치가 이어짐)
                val inLib = dao.get(o.id)?.takeIf { it.hidden == 0 }
                openZip(o.file, o.id, o.title, once = inLib == null)
                return@onSuccess
            }
            val existing = dao.get(o.id)?.takeIf { it.hidden == 0 }
            // 서재에 같은 문서가 있으면 그 기록으로 이어 읽는다(F-06). 파일은 방금 받은 사본으로 연다(서재 쪽 파일이 없어도 열리게).
            nav.screen = if (existing != null) Screen.Reader(
                ReaderTarget(existing.id, existing.title, o.file.absolutePath, DocFormat.of(existing.format), existing.lastPage, false, existing.viewMode, existing.pageCount, existing.textEncoding), null,
            )
            else Screen.Reader(ReaderTarget(o.id, o.title, o.file.absolutePath, o.format, 0, once = true), o)
        }.onFailure { snack.showSnackbar("파일을 열 수 없어요. ${it.message.orEmpty()}") }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            var ok = 0
            for (u in uris) runCatching { app.library.import(u) }.onSuccess { ok++ }
            if (uris.isNotEmpty()) snack.showSnackbar(if (ok == uris.size) "${ok}권을 서재에 넣었어요." else "${uris.size - ok}개 파일은 넣지 못했어요.")
        }
    }

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
                        if (o != null) app.appScope.launch { runCatching { app.library.closeOnce(o, clearCopy) } }
                    }
                }
            }
            val bookmarks by remember(bookId) { dao.bookmarks(bookId) }.collectAsState(initial = emptyList<Bookmark>())
            val notes by remember(bookId) { dao.notes(bookId) }.collectAsState(initial = emptyList<Note>())
            val highlights by remember(bookId) { dao.highlights(bookId) }.collectAsState(initial = emptyList<Highlight>())
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
                        val b = dao.get(bookId)?.takeIf { it.seriesKey.isNotBlank() } ?: return@launch
                        dao.volume(b.seriesKey, b.volume + 1)?.let { nextVol = b to it }
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
                        books = books, onOpen = ::open, onAdd = { picker.launch(arrayOf("*/*")) },
                        onSeries = { nav.screen = Screen.Series(it) },
                        onRemove = { b -> scope.launch { app.library.remove(b) } },
                        stores = stores, scanning = scanning,
                        onRefresh = { scan(force = true) },
                        onFolders = { nav.screen = Screen.Folders(intro = false) },
                        onStoreEdit = { nav.screen = Screen.StoreEdit },
                        onExport = { b -> exportFor = b.id },
                        showStores = settings.showStores,
                    )
                    Screen.Stats -> {
                        val since = remember { weekStart() }
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
                    )
                    is Screen.Folders -> {
                        val back = { nav.screen = if (sc.intro) Screen.Library else nav.tab }
                        BackHandler { back() }
                        FoldersScreen(
                            intro = sc.intro, onBack = back,
                            onAccess = { nav.screen = Screen.StorageIntro },
                            onScan = { scan(force = true) },
                        )
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
                        val vols by remember(sc.key) { dao.series(sc.key) }.collectAsState(initial = emptyList())
                        SeriesScreen(sc.key, vols, onBack = { nav.screen = Screen.Library }, onOpen = ::open)
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
