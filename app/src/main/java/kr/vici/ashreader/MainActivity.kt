// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.Bookmark
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.LibraryRepo
import kr.vici.ashreader.data.Note
import kr.vici.ashreader.data.ReadingSession
import kr.vici.ashreader.data.Settings
import kr.vici.ashreader.ui.AppSettingsScreen
import kr.vici.ashreader.ui.AshTheme
import kr.vici.ashreader.ui.Icons
import kr.vici.ashreader.ui.LibraryScreen
import kr.vici.ashreader.ui.NextVolumeSheet
import kr.vici.ashreader.ui.ReaderScreen
import kr.vici.ashreader.ui.ReaderTarget
import kr.vici.ashreader.ui.SeriesScreen
import kr.vici.ashreader.ui.StatsScreen
import kr.vici.ashreader.ui.VolumeKeys
import kr.vici.ashreader.ui.weekStart

private sealed interface Screen {
    data object Library : Screen
    data object Stats : Screen
    data object Settings : Screen
    data class Series(val key: String) : Screen
    data class Reader(val target: ReaderTarget, val book: Book?, val once: LibraryRepo.Opened?) : Screen
}

class MainActivity : ComponentActivity() {
    /** 외부에서 받은 문서(VIEW·SEND). */
    private val incoming = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) incoming.value = uriOf(intent)
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
            // '한 번만 보기 기록 지우기'(기본 켬): 앱을 닫으면 캐시 사본 삭제
            val app = ash
            Thread { runCatching { app.library.clearOnce() } }.start()
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
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
    var tab by remember { mutableStateOf<Screen>(Screen.Library) }
    var nextVol by remember { mutableStateOf<Pair<Book, Book>?>(null) }
    val pending by incoming.collectAsState()

    fun change(f: (Settings) -> Settings) { scope.launch { app.prefs.update(f, settings) } }

    fun open(b: Book) {
        val f = DocFormat.of(b.format)
        screen = Screen.Reader(ReaderTarget(b.id, b.title, b.path, f, b.lastPage, once = false), b, null)
    }

    // 외부 인텐트 → 한 번만 보기 (서재에 이미 있으면 그 책으로 연다)
    LaunchedEffect(pending) {
        val uri = pending ?: return@LaunchedEffect
        incoming.value = null
        runCatching { app.library.openOnce(uri) }.onSuccess { o ->
            val existing = dao.get(o.id)
            screen = if (existing != null) Screen.Reader(ReaderTarget(existing.id, existing.title, existing.path, DocFormat.of(existing.format), existing.lastPage, false), existing, null)
            else Screen.Reader(ReaderTarget(o.id, o.title, o.file.absolutePath, o.format, 0, once = true), null, o)
        }.onFailure { snack.showSnackbar("파일을 열 수 없어요. ${it.message.orEmpty()}") }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            var ok = 0
            for (u in uris) runCatching { app.library.import(u) }.onSuccess { ok++ }
            if (uris.isNotEmpty()) snack.showSnackbar(if (ok == uris.size) "${ok}권을 서재에 넣었어요." else "${uris.size - ok}개 파일은 넣지 못했어요.")
        }
    }

    when (val sc = screen) {
        is Screen.Reader -> {
            BackHandler { screen = tab }
            val bookId = sc.target.id
            val bookmarks by remember(bookId) { dao.bookmarks(bookId) }.collectAsState(initial = emptyList<Bookmark>())
            val notes by remember(bookId) { dao.notes(bookId) }.collectAsState(initial = emptyList<Note>())
            ReaderScreen(
                target = sc.target, s = settings, onSettings = ::change,
                onBack = { screen = tab },
                onAddToLibrary = {
                    sc.once?.let { o ->
                        scope.launch {
                            val b = app.library.addOpened(o)
                            screen = Screen.Reader(sc.target.copy(path = b.path, once = false), b, null)
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
                    sc.book?.takeIf { it.seriesKey.isNotBlank() }?.let { b ->
                        scope.launch { dao.volume(b.seriesKey, b.volume + 1)?.let { nextVol = b to it } }
                    }
                },
                onSession = { start, dur, pages ->
                    if (!sc.target.once && dur > 5_000) scope.launch { dao.addSession(ReadingSession(bookId = bookId, startedAt = start, durationMs = dur, pagesTurned = pages)) }
                },
                bookmarks = bookmarks, notes = notes,
                onToggleBookmark = { p ->
                    scope.launch {
                        if (sc.target.once) { snack.showSnackbar("서재에 추가하면 책갈피를 남길 수 있어요."); return@launch }
                        if (bookmarks.any { it.page == p }) dao.removeBookmark(bookId, p) else dao.addBookmark(Bookmark(bookId = bookId, page = p))
                    }
                },
                onAddNote = { p, text -> scope.launch { dao.addNote(Note(bookId = bookId, page = p, text = text)) } },
            )
            nextVol?.let { (done, next) ->
                NextVolumeSheet(done, next,
                    onLibrary = { nextVol = null; screen = Screen.Library },
                    onOpenNext = { nextVol = null; open(next) },
                    onDismiss = { nextVol = null })
            }
        }
        else -> Scaffold(
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                NavigationBar {
                    NavItem("서재", Icons.Library, tab == Screen.Library) { tab = Screen.Library; screen = Screen.Library }
                    NavItem("독서 기록", Icons.Chart, tab == Screen.Stats) { tab = Screen.Stats; screen = Screen.Stats }
                    NavItem("설정", Icons.Gear, tab == Screen.Settings) { tab = Screen.Settings; screen = Screen.Settings }
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (sc) {
                    Screen.Library -> LibraryScreen(
                        books = books, onOpen = ::open, onAdd = { picker.launch(arrayOf("*/*")) },
                        onSeries = { screen = Screen.Series(it) },
                        onRemove = { b -> scope.launch { app.library.remove(b) } },
                    )
                    Screen.Stats -> {
                        val since = remember { weekStart() }
                        val week by remember { dao.sessionsSince(since) }.collectAsState(initial = emptyList())
                        val days by remember { dao.sessionDays() }.collectAsState(initial = emptyList())
                        val times by remember { dao.timeByBook() }.collectAsState(initial = emptyList())
                        StatsScreen(week, streakOf(days), books, times.associate { it.bookId to it.total })
                    }
                    Screen.Settings -> AppSettingsScreen(settings, ::change) { scope.launch { app.library.clearOnce() } }
                    is Screen.Series -> {
                        BackHandler { screen = Screen.Library }
                        val vols by remember(sc.key) { dao.series(sc.key) }.collectAsState(initial = emptyList())
                        SeriesScreen(sc.key, vols, onBack = { screen = Screen.Library }, onOpen = ::open)
                    }
                    is Screen.Reader -> Unit
                }
            }
        }
    }
}

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
