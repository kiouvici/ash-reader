// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.vici.ashreader.data.AutoScroll
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.ComicBookPrefs
import kr.vici.ashreader.data.DocViewPrefs
import kr.vici.ashreader.data.DocViewStore
import kr.vici.ashreader.doc.MarginCroppable
import kr.vici.ashreader.doc.OpenFailed
import kr.vici.ashreader.data.Bookmark
import kr.vici.ashreader.data.locatorFrac
import kr.vici.ashreader.data.locatorPage
import kr.vici.ashreader.data.Highlight
import kr.vici.ashreader.doc.TextGeo
import kr.vici.ashreader.doc.TextPage
import kr.vici.ashreader.doc.PageAnchor
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.FormatGroup
import kr.vici.ashreader.data.Note
import kr.vici.ashreader.data.Rotation
import kr.vici.ashreader.data.ScreenTimeout
import kr.vici.ashreader.data.Settings
import kr.vici.ashreader.doc.DjvuReaderDoc
import kr.vici.ashreader.engine.mupdf.MupdfDoc
import kr.vici.ashreader.tts.Listen
import kr.vici.ashreader.tts.ListenSource
import kr.vici.ashreader.tts.Sentence
import kr.vici.ashreader.tts.Sentences
import kr.vici.ashreader.doc.ComicReaderDoc
import kr.vici.ashreader.doc.ComicUpscale
import kr.vici.ashreader.doc.EpubUpscaleHook
import kr.vici.ashreader.doc.DocOpener
import kr.vici.ashreader.doc.DrmProtected
import kr.vici.ashreader.engine.convert.TextEncoding
import kr.vici.ashreader.doc.HwpReaderDoc
import kr.vici.ashreader.doc.MupdfReaderDoc
import kr.vici.ashreader.doc.PasswordNeeded
import kr.vici.ashreader.doc.ReaderDoc
import kr.vici.ashreader.pagecurl.CurlConfig
import kr.vici.ashreader.pagecurl.PageCurlView
import kr.vici.ashreader.pagecurl.PageSource
import kr.vici.ashreader.pagecurl.SpreadMode
import kr.vici.ashreader.pagecurl.TurnDirection
import kr.vici.ashreader.pagecurl.TurnEffect

/** 읽기 화면에 넘기는 문서. [once] = 외부에서 연 '한 번만 보기'(S-40). */
data class ReaderTarget(
    val id: String,
    val title: String,
    val path: String,
    val format: DocFormat,
    val startPage: Int,
    val once: Boolean,
    /** 한글 문서 보기 방식([Book.VIEW_ORIGINAL]/[Book.VIEW_EBOOK]). 지난번 방식으로 다시 연다. */
    val viewMode: Int = Book.VIEW_ORIGINAL,
    /** [startPage]를 저장할 때의 쪽 수. 지금 쪽 수와 다르면(글꼴·크기·보기 방식 변경) 비율로 옮긴다. */
    val startCount: Int = 0,
    /** TXT 인코딩 수동 지정(F-32): "" = 자동, 아니면 TextEncoding.Kind 이름. */
    val textEncoding: String = "",
)

/** 볼륨 키 → 읽기 화면 (MainActivity 가 연결). 반환 true = 키를 소비. */
object VolumeKeys {
    @Volatile var handler: ((next: Boolean) -> Boolean)? = null
}

private sealed interface Load {
    data object Loading : Load
    data class Ok(val doc: ReaderDoc) : Load
    /** [drm] = 서점 보호가 걸린 책(S-43 서점 보호 안내). */
    data class Err(val msg: String, val drm: Boolean = false) : Load
    /** 암호 걸린 문서. [wrong] = 방금 넣은 암호가 틀림. */
    data class Locked(val wrong: Boolean) : Load
}

/** 닫힌 문서에 렌더 요청이 와도 죽지 않게 감싼다. */
/** [marks] = 쪽마다 칠할 형광펜·검색 표시(없으면 글자 위치를 읽지 않는다). */
private class GuardedSource(
    private val doc: ReaderDoc,
    private val colors: ReadingColors,
    /** 쪽 모양이 정해진 문서(만화·PDF·한글 원본 등) — 넘길 때 종이만 움직이게 종이 비율을 알려 준다. */
    private val fixed: Boolean = false,
    private val marks: (page: Int, tp: () -> TextPage?) -> List<Mark> = { _, _ -> emptyList() },
) : PageSource {
    override val pageCount: Int = doc.pageCount
    override val paperOnly: Boolean get() = fixed
    override fun render(index: Int, width: Int, height: Int): Bitmap = synchronized(guard) {
        if (closed.contains(doc)) Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(colors.bg) }
        else runCatching {
            val bmp = doc.render(index, width, height, colors)
            var tp: TextPage? = null
            val ms = runCatching { marks(index) { doc.pageText(index).also { tp = it } } }.getOrDefault(emptyList())
            tp?.let { t -> if (ms.isNotEmpty()) runCatching { paintMarks(bmp, t, ms) } }
            bmp
        }.getOrElse {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(colors.bg) }
        }
    }

    companion object {
        val guard = Any()
        val closed: MutableSet<ReaderDoc> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())
        fun close(doc: ReaderDoc) {
            synchronized(guard) { closed += doc; runCatching { doc.close() } }
        }
    }
}

/** 닫혔을 수 있는 문서에 대한 엔진 호출(백그라운드). 닫혔거나 실패하면 [fallback]. */
private fun <T> guarded(doc: ReaderDoc, fallback: T, f: () -> T): T = synchronized(GuardedSource.guard) {
    if (GuardedSource.closed.contains(doc)) fallback else runCatching(f).getOrDefault(fallback)
}

/** 듣기(F-13)에 읽을 글을 주는 열린 문서. 글자 위치가 있으면 문장 강조, 없으면(줄 글자만) 소리만. */
private class DocListenSource(private val doc: ReaderDoc, override val bookId: String, override val title: String) : ListenSource {
    override val pageCount: Int = doc.pageCount
    @Volatile private var tocPages: List<Int>? = null

    /** 쪽 글과 글자 위치를 아는지. */
    private fun textOf(page: Int): Pair<String, Boolean>? {
        if (page !in 0 until pageCount) return null
        val tp = doc.pageText(page)
        return if (tp != null && tp.text.isNotBlank()) tp.text to true else doc.pageLines(page).joinToString("\n") to false
    }

    /** 쪽 경계에 걸친 문장은 끊지 않고 다음 쪽 머리까지 한 문장으로 읽는다(앞 쪽이 가져간 머리는 뺀다). */
    override fun sentences(page: Int): List<Sentence> = guarded(doc, emptyList()) {
        val (text, located) = textOf(page) ?: return@guarded emptyList()
        if (text.isBlank()) return@guarded emptyList()
        val prev = runCatching { textOf(page - 1) }.getOrNull()
        val next = runCatching { textOf(page + 1) }.getOrNull()
        val l = Sentences.splitAcross(page, text, prev?.first, next?.first)
        if (located && next?.second != false) l
        else l.map { it.copy(start = if (located) it.start else -1, end = if (located) it.end else -1, start2 = -1, end2 = -1) }
    }

    override fun chapterEnd(page: Int): Int {
        val t = tocPages ?: guarded(doc, emptyList<Int>()) { doc.toc().map { it.page }.filter { it >= 0 }.distinct().sorted() }.also { tocPages = it }
        return t.firstOrNull { it > page } ?: pageCount
    }
}

/** 듣기를 쓸 수 없는 문서면 그 까닭(S-27). null = 들을 수 있음. */
/** 다 쓴 문서를 화면 스레드 밖에서 닫는다(엔진 닫기가 느릴 수 있음). */
private fun closeDoc(d: ReaderDoc) { Thread { GuardedSource.close(d) }.start() }

private fun cannotListen(doc: ReaderDoc, fmt: DocFormat): String? = when {
    doc is MupdfReaderDoc || doc is DjvuReaderDoc -> null
    fmt.group == FormatGroup.HWP -> "원본 보기에서는 들을 수 없어요. ‘eBook 보기’로 바꾸면 들을 수 있어요."
    fmt.group == FormatGroup.COMIC || doc is ComicReaderDoc -> "만화는 그림이라 들을 수 없어요."
    else -> "이 문서는 들을 수 없어요."
}

/** 확대 덧화면 요청: 자리 [index] 를 [zoom] 배로, 덧화면 좌표 ([fx], [fy]) 를 중심으로. */
private data class ZoomReq(val index: Int, val fx: Float, val fy: Float, val zoom: Float)

/** 두 번 탭을 가리기 위해 미룬 한 번 탭. */
private class TapHold { var job: Job? = null; var at = 0L; var x = 0f; var y = 0f }

/** 각주 팝업 상태(S-29). [anchor] = 링크 위치(화면 높이 비율), [below] = 카드를 링크 아래에. */
private data class FootState(val text: String, val page: Int, val anchor: Float, val below: Boolean)

/**
 * 왼쪽 가장자리 밝기(F-65): 왼쪽 32dp, 세로 가운데 200dp 띠에서 세로 이동이 가로 이동의 2배 이상이면 밝기.
 * 부모에서 먼저(Initial) 보고, 밝기로 정해지면 손가락을 가져가 아래 쪽 넘김 화면에는 취소가 간다.
 */
private fun Modifier.edgeBrightness(enabled: Boolean, edge: Dp, band: Dp, current: () -> Float, onLive: (Float?) -> Unit, onDone: (Float) -> Unit): Modifier =
    if (!enabled) this else this.pointerInput(Unit) {
        val edgePx = edge.toPx(); val halfBand = band.toPx() / 2f
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.x > edgePx || abs(down.position.y - size.height / 2f) > halfBand) return@awaitEachGesture
            var decided = false
            val start = current()
            var lv = start
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) break
                val dx = ch.position.x - down.position.x
                val dy = ch.position.y - down.position.y
                if (!decided) {
                    if (abs(dy) < slop && abs(dx) < slop) continue
                    if (abs(dy) >= 2f * abs(dx)) decided = true else return@awaitEachGesture
                }
                ch.consume()
                lv = (start - dy / (size.height * 0.5f) * 1.6f).coerceIn(-0.6f, 1f)
                onLive(lv)
            }
            if (decided) { onDone(lv); onLive(null) }
        }
    }

/** S-20·S-21 읽기 화면 (+ S-40 한 번만 보기, 태블릿 두 쪽 펼침). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    target: ReaderTarget,
    s: Settings,
    onSettings: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
    onAddToLibrary: () -> Unit,
    onProgress: (page: Int, count: Int) -> Unit,
    onFinished: () -> Unit,
    onSession: (startedAt: Long, durationMs: Long, pages: Int) -> Unit,
    bookmarks: List<Bookmark>,
    notes: List<Note>,
    /** [existingId] = 이 쪽에 이미 있는 책갈피(있으면 지운다). */
    onToggleBookmark: (page: Int, frac: Float, existingId: Long?) -> Unit,
    onAddNote: (page: Int, text: String, frac: Float) -> Unit,
    highlights: List<Highlight> = emptyList(),
    onAddHighlight: (page: Int, start: Int, end: Int, text: String, color: Int, note: String, frac: Float) -> Unit = { _, _, _, _, _, _, _ -> },
    onRemoveHighlight: (id: Long) -> Unit = {},
    /** 한글 보기 방식이 바뀜(사용자 전환·자동 전환) → 책에 저장. */
    onViewMode: (mode: Int) -> Unit = {},
    /** 문서 id → 암호. 회전해도 다시 묻지 않게 화면 바깥(ViewModel 메모리)에 둔다. 디스크·번들에는 저장하지 않는다. */
    passwords: MutableMap<String, String> = remember { HashMap() },
    /** TXT 인코딩을 바꿈(F-32) → 책에 저장. "" = 자동. */
    onTextEncoding: (String) -> Unit = {},
    /** 서점 보호 책 안내에서 '내 서점 앱 열기'(S-43). */
    onOpenStore: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val colors = s.readingColors()
    // 서재 검색에서 책을 열면 글자판이 읽기 화면에 남아 메뉴·누르는 자리를 가렸다(CI D04) — 책을 열 때 닫는다
    run {
        val act = LocalContext.current as? Activity
        LaunchedEffect(target.id) {
            val w = act?.window ?: return@LaunchedEffect
            (act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(w.decorView.windowToken, 0)
        }
    }
    // 대표님 01:10 전수 검사: 어두운 종이(집중·내 테마)에서 아래 내비게이션 줄이 밝은 띠로 남고 위 상태 표시줄 글자가 묻혔다.
    // 읽는 동안에는 위아래 줄을 종이 색에 맞춘다(투명 + 밝은/어두운 아이콘), 나가면 앱 테마로 되돌린다
    run {
        val paperDark = colors.bgC.luminance() < 0.4f
        val act = LocalContext.current as? Activity
        DisposableEffect(act, paperDark) {
            val w = act?.window
            if (w != null) {
                @Suppress("DEPRECATION")
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (android.os.Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
            }
            // 아이콘 색은 AshTheme 한 곳에서 정한다(앱 테마가 다시 그려져도 종이 색 유지)
            readerBarsDark = paperDark
            onDispose { readerBarsDark = null }
        }
    }
    var menu by remember { mutableStateOf(false) }
    // 작업 07 E: 떠 있는 메뉴 쌓기 · 테마 및 설정 · 사용자화 · 안내선(켜짐, 띠 자리 = 화면 높이 비율)
    var stackOpen by remember { mutableStateOf(false) }
    var stackClosedAt by remember { mutableStateOf(0L) }
    // 넘김 화면에서 시작한 두 손가락 확대를 확대 덧화면으로 넘기는 통로(손을 떼면 닫힘)
    var pinchFeed by remember { mutableStateOf<kotlinx.coroutines.channels.ReceiveChannel<PinchStep>?>(null) }
    var themeOpen by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    var guideOn by rememberSaveable(target.id) { mutableStateOf(false) }
    var guideY by rememberSaveable(target.id) { mutableFloatStateOf(0.4f) }
    // 필수 2: 회전·복원에도 읽던 쪽 유지
    var page by rememberSaveable(target.id) { mutableIntStateOf(target.startPage) }
    var showAa by remember { mutableStateOf(false) }
    // 작업 04: 이북·PDF 문서 그림 AI 전후 비교
    var docCompareOpen by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var memoOpen by remember { mutableStateOf(false) }
    var turnReq by remember { mutableIntStateOf(0) }
    var turnFwd by remember { mutableStateOf(true) }
    var curlView by remember { mutableStateOf<PageCurlView?>(null) }
    var pagesTurned by remember { mutableIntStateOf(0) }
    var spreadNow by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf<Selection?>(null) }
    var selMemo by remember { mutableStateOf<Selection?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    /** 글자 선택 '검색'으로 연 검색어(7B). */
    var searchSeed by remember { mutableStateOf("") }
    var hit by remember { mutableStateOf<Hit?>(null) }
    /** 작업 07 E4: 찾은 곳 모두(‹ › 이동). */
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var jumpReq by remember { mutableStateOf<Int?>(null) }
    var tocTab by remember { mutableIntStateOf(0) }
    var openDoc by remember { mutableStateOf<ReaderDoc?>(null) }
    var exportOpen by remember { mutableStateOf(false) }
    var showTurn by remember { mutableStateOf(false) }
    var showLight by remember { mutableStateOf(false) }
    var showTune by remember { mutableStateOf(false) }
    /** 밝기를 끄는 동안의 값(가장자리·슬라이더). null = 설정값. */
    var liveLight by remember { mutableStateOf<Float?>(null) }
    /** F-59 만화 쪽 메뉴를 연 원본 쪽. */
    var comicMenuPage by remember { mutableStateOf<Int?>(null) }
    /** 고정 쪽·만화 확대 덧화면(F-55·F-23). */
    var zoomAt by remember { mutableStateOf<ZoomReq?>(null) }
    /** 스크롤 보기에서 듣기가 읽는 쪽을 보이게. */
    var revealReq by remember { mutableStateOf<Int?>(null) }
    /** 지금 보기가 위아래 연속(스크롤 보기·웹툰)인가 / 만화인가 — 넘김 시트에 알린다. */
    var continuousNow by remember { mutableStateOf(false) }
    var comicNow by remember { mutableStateOf(false) }
    val tapState = remember { TapHold() }
    /** F-44 자동 넘김. 화면을 누르면 멈춤([autoPaused]). */
    var autoOn by rememberSaveable(target.id) { mutableStateOf(false) }
    var autoPaused by remember { mutableStateOf(false) }
    var foot by remember { mutableStateOf<FootState?>(null) }
    var ttsDialog by remember { mutableStateOf<Listen.Check?>(null) }
    var notifAsk by remember { mutableStateOf(false) }
    var notifAsked by rememberSaveable { mutableStateOf(false) }
    var listenSrc by remember { mutableStateOf<ListenSource?>(null) }
    /** 화면 꺼짐 5분(F-67): 누르거나 넘길 때마다 다시 센다. */
    var touchTick by remember { mutableIntStateOf(0) }
    /** F-45 남은 읽기 시간: 최근 쪽 머문 시간(중앙값). */
    val dwell = remember(target.id) { ArrayList<Long>() }
    var enterAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var msPerPage by remember { mutableStateOf<Long?>(null) }
    val listen by Listen.state.collectAsState()
    val listening = listen.active && listen.bookId == target.id
    val activity = context as? Activity
    // F-13: 읽기 화면을 떠나면(회전 제외) 듣기를 끝내고 알림을 내린다 — 닫힌 문서를 알림에서 재생하지 않게
    DisposableEffect(target.id) {
        onDispose { if (activity?.isChangingConfigurations != true) Listen.leave(target.id) }
    }
    val fmt = target.format
    /** TXT 인코딩(F-32): "" = 자동. 바꾸면 문서를 다시 연다. */
    var txtEnc by rememberSaveable(target.id) { mutableStateOf(target.textEncoding) }
    // 한글 문서 보기 방식(F-28): false = 원본(쪽 그림), true = 이북식(다시 배치 — 글꼴·크기·테마·글자 선택·검색·목차)
    val isHwp = fmt.group == FormatGroup.HWP
    // Word(DOCX·DOC·RTF)도 원본(쪽 모양, F-30)/이북식 두 보기. 보기 상태는 hwpEbook 을 같이 쓴다.
    val isWord = fmt.group == FormatGroup.WORD
    val dualView = isHwp || isWord
    var hwpEbook by rememberSaveable(target.id) { mutableStateOf(target.viewMode == Book.VIEW_EBOOK) }
    /** F-28 원본 → 이북식 자동 전환을 이미 했나(한 번만). */
    var hwpAuto by rememberSaveable(target.id) { mutableStateOf(false) }
    /** 그림만 든 PDF 를 만화 보기로 연 상태. */
    var pdfComic by rememberSaveable(target.id) { mutableStateOf(fmt == DocFormat.PDF && target.viewMode == Book.VIEW_COMIC) }
    /** 그림만 든 PDF 로 판정됨('만화로 보기' 제안). null = 아직 모름. */
    var imageOnly by rememberSaveable(target.id) { mutableStateOf<Boolean?>(null) }
    var comicOfferClosed by rememberSaveable(target.id) { mutableStateOf(false) }
    /** 첫 문서를 연 뒤 할 일(저장된 쪽 수 비교·F-28 자동 전환 검사)을 한 번만 하기 위한 표시. */
    var firstLoadDone by rememberSaveable(target.id) { mutableStateOf(false) }
    /** [page] 를 정할 때의 쪽 수. 회전 등으로 다시 배치돼 쪽 수가 바뀌면 비율로 옮긴다. */
    var pageCountSeen by rememberSaveable(target.id) { mutableIntStateOf(0) }
    /**
     * 작업 11: 읽던 자리 표시(읽던 쪽의 첫 글자 — [PageAnchor]). 다시 나눠 쪽 수가 바뀌면 비율로 짐작한 쪽 근처에서 이 글자가 든 쪽으로 간다.
     * [heldPage] = 다시 나눈 뒤 옮겨 온 쪽 — 그 쪽에서는 자리 표시를 새로 잡지 않는다(가로의 큰 쪽 첫 글자로 바꾸면 세로로 돌아올 때마다 앞으로 밀림).
     */
    var anchor by rememberSaveable(target.id) { mutableStateOf<String?>(null) }
    var heldPage by rememberSaveable(target.id) { mutableIntStateOf(-1) }
    /** [anchor] 가 가리키는 쪽(지금 배치 기준). 지금 쪽과 다르면(넘기자마자 앱이 꺼져 새로 못 잡음) 그 자리 표시는 쓰지 않는다(CI process-death 4 → 3). */
    var anchorPage by rememberSaveable(target.id) { mutableIntStateOf(-1) }
    /**
     * 다시 나눈 뒤 옮기는 중: -2 = 아님, -1 = 자리 찾는 중, 0 이상 = 그 쪽으로 옮기는 중. 이동 중에는 쪽 화면이 스스로 알리는 쪽(옛 번호·끝 쪽 맞춤 등)으로
     * 자리 표시를 덮지 않는다(CI 태블릿: 가로 7쪽 → 세로 4쪽 → 가로 3쪽).
     */
    var relocateTo by remember(target.id) { mutableIntStateOf(-2) }
    val reflow = if (isWord) hwpEbook else fmt.reflowable || (isHwp && hwpEbook)
    /** F-22 스크롤 보기(만화는 웹툰 보기로 대신). */
    val scrollView = s.scrollView && fmt.group != FormatGroup.COMIC && !pdfComic
    /** 보기 방식을 바꾼 뒤 새 문서에서 이어 볼 위치(읽은 비율). */
    var pendingFrac by remember { mutableStateOf<Float?>(null) }
    /** 암호 걸린 Word·PDF 의 암호. 메모리에만 두고 저장하지 않는다. */
    var password by remember(target.id) { mutableStateOf(passwords[target.id]) }
    var pwTry by remember(target.id) { mutableIntStateOf(0) }
    /** 고정 쪽 문서 맞춤(F-23)·여백 자르기(F-24) — 책마다. 한 번만 보기는 저장하지 않는다. */
    val viewStore = remember { DocViewStore(context) }
    var fixedView by rememberSaveable(target.id, stateSaver = DocViewSaver) { mutableStateOf(if (target.once) DocViewPrefs(crop = s.cropDefault) else viewStore.load(target.id, s.cropDefault)) }
    fun setFixedView(v: DocViewPrefs) { fixedView = v; if (!target.once) viewStore.save(target.id, v) }

    fun stub(msg: String) { scope.launch { snack.showSnackbar(msg) } }

    BackHandler(enabled = menu || sel != null || hit != null || foot != null || guideOn) {
        when { foot != null -> foot = null; sel != null -> sel = null; stackOpen -> stackOpen = false; menu -> menu = false; guideOn -> guideOn = false; else -> hit = null }
    }

    // ── 화면·창 (F-42 밝기, F-65, F-67) ──
    val effLevel: Float? = liveLight ?: if (s.brightness < 0f && s.dim == 0) null else LightMath.level(s.brightness, s.dim)
    val dimNow = effLevel?.let { LightMath.split(it).second } ?: 0
    LaunchedEffect(effLevel) {
        val w = activity?.window ?: return@LaunchedEffect
        val lp = w.attributes
        lp.screenBrightness = if (effLevel == null) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else LightMath.split(effLevel).first
        w.attributes = lp
    }
    val keepOn = s.screenTimeout == ScreenTimeout.ALWAYS || (autoOn && !autoPaused)
    LaunchedEffect(s.screenTimeout, touchTick, keepOn) {
        val w = activity?.window ?: return@LaunchedEffect
        when {
            keepOn -> w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            s.screenTimeout == ScreenTimeout.FIVE_MIN -> {
                w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                delay(5 * 60_000L)
                w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            else -> w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(s.rotation) {
        activity?.requestedOrientation = when (s.rotation) {
            Rotation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            Rotation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            Rotation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }
    DisposableEffect(listening) {
        if (listening) activity?.volumeControlStream = AudioManager.STREAM_MUSIC
        onDispose { activity?.volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE }
    }
    DisposableEffect(Unit) {
        onDispose {
            val w = activity?.window
            if (w != null) {
                w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val lp = w.attributes; lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE; w.attributes = lp
            }
            if (activity?.isChangingConfigurations != true) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                // 읽기 화면을 떠나면 듣기도 끝(화면 꺼짐·다른 앱으로 가는 것은 계속)
                if (Listen.state.value.bookId == target.id) Listen.stop()
            }
        }
    }
    LaunchedEffect(page) {
        touchTick++
        val now = SystemClock.elapsedRealtime()
        val d = now - enterAt
        enterAt = now
        if (d in 2_000L..600_000L && !listening && !autoOn) {
            dwell += d
            if (dwell.size > 30) dwell.removeAt(0)
            if (dwell.size >= 3) msPerPage = dwell.sorted()[dwell.size / 2]
        }
    }
    // 듣기 안내(끝까지 읽음·잠자기 타이머)
    LaunchedEffect(listen.message) {
        val m = listen.message ?: return@LaunchedEffect
        Listen.consumeMessage()
        snack.showSnackbar(m)
    }
    LaunchedEffect(listening) {
        if (listening && !s.ttsVolumeHint) {
            onSettings { it.copy(ttsVolumeHint = true) }
            snack.showSnackbar("듣는 동안 볼륨 키는 소리 크기를 조절해요")
        }
    }

    fun startListen() {
        val src = listenSrc ?: return
        menu = false
        Listen.start(context, src, page, s.ttsRate, s.ttsPitch, s.ttsSleep)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startListen() }
    fun onListen() {
        val d = openDoc ?: return
        cannotListen(d, fmt)?.let { stub(it); return }
        if (listening) { menu = false; if (!listen.playing) Listen.resumeAt(page); return }
        Listen.prepare(context) { check ->
            scope.launch {
                when {
                    check != Listen.Check.OK -> ttsDialog = check
                    Build.VERSION.SDK_INT >= 33 && !notifAsked &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> notifAsk = true
                    else -> startListen()
                }
            }
        }
    }

    // 독서 기록(S-50)
    val sessionPages by rememberUpdatedState(pagesTurned)
    DisposableEffect(target.id) {
        val start = System.currentTimeMillis()
        onDispose { onSession(start, System.currentTimeMillis() - start, sessionPages) }
    }

    val curLevel by rememberUpdatedState(effLevel ?: activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f)
    // 지금 보이는 문서의 책 경로(같은 책을 다시 나누는지 알기)
    var loadedPath by remember { mutableStateOf<String?>(null) }
    var loadedShape by remember { mutableStateOf<List<Any?>?>(null) }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(colors.bgC).edgeBrightness(
            enabled = s.edgeBrightness, edge = 32.dp, band = 200.dp, current = { curLevel },
            onLive = { liveLight = it },
            onDone = { lv -> val (b, d) = LightMath.split(lv); onSettings { it.copy(brightness = b, dim = d) } },
        ),
    ) {
        val wDp = maxWidth.value
        val hDp = maxHeight.value
        val spread = wDp > hDp && minOf(wDp, hDp) >= 600f
        // F-22 스크롤 보기는 한 줄(칸)로: 이북류는 너무 넓지 않게 760dp 까지
        val pageWdp = if (scrollView) (if (reflow) minOf(wDp, 760f) else wDp) else if (spread) wDp / 2f else wDp
        val txtKey = if (fmt == DocFormat.TXT) listOf(s.txtBlank, s.txtJoin, s.txtTrim, s.txtDefaultEncoding, s.txtTocMask, s.txtTocCustom) else emptyList<Any>()
        val styleKey = listOf(s.userFont, s.paraSpace, s.marginH, s.marginV, s.align, s.breakChar, s.hyphen, s.indent, s.dialogIndent, s.theme == kr.vici.ashreader.data.ReadTheme.BOLD)
        // I-711 eBook 보기(쪽 넘김): 상태 표시줄·카메라 구멍·정보줄·내비게이션 줄 몫을 모든 쪽에서 비운다
        val pageFrame = rememberPageFrame(s)
        val frame = if (reflow && !scrollView) pageFrame else null
        val layoutKey = if (reflow) listOf(s.font, s.fontSize, s.lineHeight, pageWdp.toInt(), hDp.toInt(), hwpEbook, txtEnc, frame) + styleKey + txtKey
            else listOf(spread, hwpEbook, pdfComic, fixedView.crop)
        // 화면 크기·보기 방식(이게 바뀌면 예전처럼 '여는 중'을 거쳐 쪽 위치를 다시 맞춘다 — 회전·앱 다시 만들기, CI land-b)
        val shapeKey = listOf(pageWdp.toInt(), hDp.toInt(), spread, frame, hwpEbook, pdfComic, fixedView.crop)

        val load by produceState<Load>(Load.Loading, target.path, layoutKey, pwTry) {
            // 대표님 01:00: 글자 크기·줄 간격 등을 바꿔 다시 나눌 때 '여는 중…'으로 번쩍이지 않게(그리고 열린 시트가 닫히지 않게),
            // 같은 책·같은 화면 크기면 새 배치가 다 될 때까지 지금 문서를 그대로 보여 주고, 새 문서로 바꾼 뒤에 옛 문서를 닫는다
            val prev = (value as? Load.Ok)?.doc
            val relayout = prev != null && loadedPath == target.path && loadedShape == shapeKey
            loadedPath = target.path
            loadedShape = shapeKey
            if (!relayout) { value = Load.Loading; prev?.let { closeDoc(it) } }
            val pw = password
            val crop = fixedView.crop && !reflow && !pdfComic
            fun openNow(): ReaderDoc = DocOpener.open(
                context, File(target.path), fmt, pageWdp, hDp, s, hwpEbook = dualView && hwpEbook, password = pw, docId = target.id, comic = pdfComic,
                textEncoding = TextEncoding.Kind.of(txtEnc.ifEmpty { s.txtDefaultEncoding }), frame = frame,
            ).also { d -> if (crop) (d as? MarginCroppable)?.cropMargins = true }
            val r: Result<ReaderDoc> = if (isHwp && !hwpEbook && !hwpAuto) {
                // F-28: 한글 원본 보기가 파싱에 실패하거나, 10초 안에 첫 쪽을 못 내거나, 첫 쪽이 비어 보이면(처음 열 때)
                // 이북식으로 바꾸고 알린다. 자동 전환은 이 책을 여는 동안 한 번만(사용자가 원본으로 되돌리면 그대로 둔다).
                val h = openHwpOriginal({ openNow() }, checkBlank = !firstLoadDone)
                val parseFailed = h is HwpOpen.Done && h.result.exceptionOrNull().let { e ->
                    e is OpenFailed && e.message.orEmpty().startsWith("한글 문서를 열 수 없어요")
                }
                if (h is HwpOpen.Done && !parseFailed) h.result
                else {
                    hwpAuto = true
                    hwpEbook = true
                    onViewMode(Book.VIEW_EBOOK)
                    stub(
                        when (h) {
                            HwpOpen.TimedOut -> "원본 보기가 10초 넘게 걸려 eBook 보기로 열었어요."
                            HwpOpen.Blank -> "원본 보기가 비어 보여 eBook 보기로 열었어요."
                            else -> "원본 보기로 열지 못해 eBook 보기로 열었어요."
                        },
                    )
                    return@produceState
                }
            } else withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { runCatching { openNow() } }
            // 여는 사이에 또 바뀌었으면(막대를 연달아 움직임) 이 문서는 버린다 — 다음 차례가 지금 보이는 문서를 이어 보여 준다
            if (!coroutineContext[kotlinx.coroutines.Job]!!.isActive) { r.getOrNull()?.let { closeDoc(it) }; return@produceState }
            value = r.fold({ Load.Ok(it) }, { e ->
                when (e) {
                    is PasswordNeeded -> Load.Locked(e.wrong)
                    is DrmProtected -> Load.Err(e.message.orEmpty(), drm = true)
                    is OutOfMemoryError -> Load.Err("문서가 너무 커서 열 수 없어요.")
                    else -> Load.Err(e.message ?: "문서를 열 수 없어요.")
                }
            })
            if (relayout) prev?.let { closeDoc(it) }
            // 보이는 문서는 다음 차례(다시 나누기·다른 책) 또는 화면을 떠날 때 닫는다(아래 DisposableEffect)
        }
        val shownLoad by rememberUpdatedState(load)
        DisposableEffect(Unit) { onDispose { (shownLoad as? Load.Ok)?.doc?.let { closeDoc(it) } } }

        when (val l = load) {
            Load.Loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = colors.accentC)
                Text("여는 중…", color = colors.subC, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
            }
            is Load.Err -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (l.drm) Text("서점 보호가 걸린 책이에요", color = colors.fgC, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                Text(if (l.drm) "구매한 서점의 앱에서 읽어 주세요." else l.msg, color = colors.fgC, fontSize = 16.sp)
                if (l.drm) Button(onClick = onOpenStore, modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) { Text("내 서점 앱 열기") }
                if (fmt == DocFormat.TXT && txtEnc.isNotEmpty()) {
                    Button(onClick = { txtEnc = ""; onTextEncoding("") }, modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) { Text("인코딩 자동으로") }
                }
                if (dualView && hwpEbook) {
                    Button(onClick = { hwpEbook = false }, modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) { Text("원본 보기로") }
                }
                if (isWord && !hwpEbook) {
                    Button(onClick = { hwpEbook = true; onViewMode(Book.VIEW_EBOOK) }, modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) { Text("eBook 보기로") }
                }
                Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) { Text("서재로") }
            }
            is Load.Locked -> PasswordDialog(wrong = l.wrong, pdf = fmt == DocFormat.PDF, onSubmit = { password = it; passwords[target.id] = it; pwTry++ }, onCancel = onBack)
            is Load.Ok -> {
                val doc = l.doc
                // 듣기(F-13): 이 문서를 읽을 글 공급자로. 다시 배치·회전으로 문서가 바뀌면 듣기는 멈췄다가 새 문서로 잇는다.
                val src = remember(doc) { DocListenSource(doc, target.id, target.title) }
                LaunchedEffect(src) { listenSrc = src; Listen.attach(src) }
                DisposableEffect(src) { onDispose { Listen.detach(src, keep = true) } }
                // F-46 각주: 보이는 쪽의 문서 안 링크를 미리 읽어 둔다(누를 때 화면 스레드에서 엔진을 부르지 않게)
                var pageLinks by remember(doc) { mutableStateOf<Map<Int, List<MupdfDoc.LinkBox>>>(emptyMap()) }
                LaunchedEffect(doc, page, spreadNow) {
                    if (doc is MupdfReaderDoc && reflow) {
                        val ps = if (spreadNow) listOf(page, page + 1) else listOf(page)
                        pageLinks = withContext(Dispatchers.IO) { ps.filter { it in 0 until doc.pageCount }.associateWith { p -> guarded(doc, emptyList()) { doc.internalLinks(p) } } }
                    }
                }
                LaunchedEffect(doc) {
                    openDoc = doc; sel = null; hit = null
                    val first = !firstLoadDone
                    firstLoadDone = true
                    val n = doc.pageCount
                    val pf = pendingFrac
                    if (pf != null) {
                        pendingFrac = null
                        if (n > 0) jumpReq = (pf * n).toInt().coerceIn(0, n - 1)
                    } else if (first && target.startCount > 0 && n > 0 && n != target.startCount) {
                        // 저장할 때와 쪽 수가 다름(글꼴·크기·보기 방식) → 읽은 비율로 옮긴다
                        jumpReq = locatorPage(target.startPage, locatorFrac(target.startPage, target.startCount), n)
                    } else if (!first && pageCountSeen > 0 && n > 0 && n != pageCountSeen) {
                        // 회전·글꼴 변경으로 다시 배치돼 쪽 수가 바뀜 → 읽던 비율로 짐작한 뒤, 읽던 쪽 첫 글자가 든 쪽으로(작업 11)
                        val oldPage = page; val oldN = pageCountSeen
                        val est = locatorPage(page, locatorFrac(page, pageCountSeen), n)
                        // 자리 표시가 지금 쪽 것일 때만(옛 쪽 것이면 비율로만)
                        val a = anchor.takeIf { anchorPage == page }
                        heldPage = page // 옮기기 전 옛 쪽 번호로 새 문서의 글을 읽어 자리 표시를 덮지 않게
                        relocateTo = -1
                        pageCountSeen = n
                        // 자리 표시가 있으면 찾은 뒤 한 번만 옮긴다(짐작한 쪽으로 먼저 가면 그 쪽 글로 자리 표시가 덮일 수 있음)
                        val radius = maxOf(8, n / 15)
                        val found = if (a != null && reflow) withContext(Dispatchers.IO) {
                            PageAnchor.find(a, est, n, radius) { i -> guarded(doc, null) { doc.pageText(i)?.text } }
                        } else null
                        // 진단 로그(작업 11, 숫자만): 옛 쪽·옛 쪽 수 → 새 쪽 수, 짐작·찾은 쪽, 자리 표시 쪽·길이, 후보(쪽@위치)
                        if (reflow) {
                            val cands = if (a != null) withContext(Dispatchers.IO) {
                                PageAnchor.matches(a, est, n, radius) { i -> guarded(doc, null) { doc.pageText(i)?.text } }
                            }.joinToString(" ") { "${it.first}@${it.second}" } else "-"
                            android.util.Log.i("AshAnchor", "relayout old=$oldPage/$oldN new n=$n est=$est found=$found anchorPage=$anchorPage anchorLen=${a?.length ?: 0} spread=$spreadNow cands=[$cands]")
                        }
                        heldPage = found ?: est
                        if (found != null) anchorPage = found
                        relocateTo = found ?: est
                        jumpReq = found ?: est
                    }
                    if (n > 0) pageCountSeen = n
                    // 그림만 든 PDF 인지 한 번 본다('만화로 보기' 제안)
                    if (doc is MupdfReaderDoc && fmt == DocFormat.PDF && imageOnly == null) {
                        imageOnly = withContext(Dispatchers.IO) { doc.isImageOnly() }
                    }
                    // F-28 원본 첫 쪽 검사·10초 초과는 문서를 여는 곳(openHwpOriginal)에서 한다
                    if (first && doc is MupdfReaderDoc && doc.tocFallback) {
                        snack.showSnackbar("내 제목 모양(정규식)이 너무 오래 걸려 기본 모양으로만 목차를 만들었어요.")
                    }
                }
                // 만화 설정(S-33, 책마다): 방향·펼침·웹툰·그림 보기·AI 화질. 쪽 수(펼침 배치)가 정해지므로 count 보다 먼저.
                val comic = rememberComicSession(doc, target.id, target.once, spread, wDp > hDp)
                // F-50·7G 두 쪽 펼침의 첫 쪽 단독(표지): 만화는 책마다 설정('첫 쪽 단독'), 그 밖에는 첫 쪽이 실제로
                // 쪽 전체 그림(표지·스캔 쪽)일 때만. 글로 시작하는 책은 첫 펼침부터 두 쪽(빈 왼쪽 쪽이 생기지 않게).
                val coverImage by produceState<Boolean?>(null, doc) {
                    value = withContext(Dispatchers.IO) { guarded(doc, false) { doc.coverIsImage() } }
                }
                val coverAlone = comic?.prefs?.coverAlone ?: (coverImage == true)
                val pxW = constraints.maxWidth.toFloat()
                val pxH = constraints.maxHeight.toFloat()
                // 화면 폭이 바뀌면(회전·창 크기) 쪽 칸이 달라지므로 글자 선택을 닫는다(7B).
                // 높이는 보지 않는다 — 메뉴를 닫으며 시스템 막대가 숨어 높이만 바뀌어도 방금 고른 글자가 닫히지 않게.
                LaunchedEffect(pxW) { sel = null }
                val count = doc.pageCount
                // 자리 표시용 쪽 글(이 배치에서 한 번 읽은 쪽은 다시 읽지 않는다)
                val anchorTexts = remember(doc) { java.util.concurrent.ConcurrentHashMap<Int, String>() }
                if (reflow) LaunchedEffect(page, count, spreadNow) { android.util.Log.i("AshAnchor", "page=$page count=$count spread=$spreadNow held=$heldPage relocate=$relocateTo anchorPage=$anchorPage") }
                // 작업 11: 읽던 쪽이 바뀌면(넘김·이동) 그 쪽 첫 글자를 자리 표시로. 다시 나눈 뒤 옮겨 온 쪽([heldPage])은 그대로 둔다.
                if (reflow) LaunchedEffect(doc, page, relocateTo) {
                    val to = relocateTo
                    if (to == -1) return@LaunchedEffect // 자리 찾는 중
                    if (to >= 0) {
                        // 옮기는 중: 도착하면(또는 1.5초 안에 다른 쪽으로 자리 잡으면 — 두 쪽 펼침 맞춤) 그 쪽을 '옮겨 온 쪽'으로
                        if (page != to) delay(1_500)
                        android.util.Log.i("AshAnchor", "arrive to=$to page=$page anchorPage=$anchorPage")
                        if (anchorPage == to) anchorPage = page
                        heldPage = page
                        relocateTo = -2
                        return@LaunchedEffect
                    }
                    if (page == heldPage) return@LaunchedEffect
                    // 두 쪽 펼침: 옮겨 온 뒤 화면이 펼침 왼쪽 쪽으로 번호를 맞추면(±1) 같은 자리로 본다(CI 태블릿 세로 4 → 3)
                    if (spreadNow && heldPage >= 0 && kotlin.math.abs(page - heldPage) <= 1) {
                        android.util.Log.i("AshAnchor", "spread-adjust held=$heldPage page=$page anchorPage=$anchorPage")
                        if (anchorPage == heldPage) anchorPage = page
                        heldPage = page
                        return@LaunchedEffect
                    }
                    heldPage = -1
                    delay(300)
                    val p = page
                    withContext(Dispatchers.IO) {
                        PageAnchor.capture(p, count, radius = 8) { i -> anchorTexts.getOrPut(i) { guarded(doc, null) { doc.pageText(i)?.text }.orEmpty() } }
                    }.let { a2 ->
                        android.util.Log.i("AshAnchor", "capture page=$p count=$count len=${a2?.length ?: 0} held=$heldPage spread=$spreadNow")
                        if (a2 != null) { anchor = a2; anchorPage = p }
                    }
                }
                LaunchedEffect(page, count) {
                    if (count > 0) {
                        delay(400)
                        onProgress(page.coerceIn(0, count - 1), count)
                        if (page >= count - 1) onFinished()
                    }
                }
                val webtoon = comic?.webtoon == true
                val aiOn = comic?.aiOn(s.aiUpscale) == true
                // AI 화질 개선(F-73): 만화 + 설정 켬일 때만. 원본을 먼저 보여 주고 2배 결과로 바꿔 낀다.
                val upscale = remember(doc, aiOn) { if (aiOn && doc is ComicReaderDoc) ComicUpscale(context, doc) else null }
                DisposableEffect(upscale) {
                    val cd = doc as? ComicReaderDoc
                    cd?.upscale = upscale
                    onDispose {
                        if (cd != null && cd.upscale === upscale) cd.upscale = null
                        upscale?.close()
                    }
                }
                val comicDoc = doc as? ComicReaderDoc
                // F-60 쪽 미리보기(슬라이더)·만화 쪽 목록 그리드
                val thumbs = remember(doc) { ThumbCache() }
                var gridOpen by remember(doc) { mutableStateOf(false) }
                val tocForPreview by produceState<List<kr.vici.ashreader.doc.TocEntry>?>(null, doc, reflow) {
                    value = if (reflow) withContext(Dispatchers.IO) { guarded(doc, emptyList()) { doc.toc() } } else emptyList()
                }
                // F-54 맞춤 방식(화면 외): 쪽마다 한 자리로 보고 쪽 안을 밀어 본다
                val fitMode = comic != null && !webtoon && comic.prefs.fit != ComicBookPrefs.FIT_SCREEN
                val scrollMode = scrollView && comicDoc == null
                val continuous = scrollMode || webtoon
                SideEffect { continuousNow = continuous; comicNow = comicDoc != null }
                if (comic != null) ComicSessionEffects(
                    comic, autoSpread = spread, landscape = wDp > hDp, plainPages = webtoon || fitMode, upscale = upscale,
                    onRelayout = { f -> page = f(page) }, onNotice = { stub(it) },
                )
                // F-73 전후 비교(S-33 [화질]): 도구 막대 자리를 차지하는 비교 화면. 끝내면 보던 쪽으로.
                var compareOpen by remember(doc) { mutableStateOf(false) }
                if (compareOpen && comic != null) AiCompareScreen(
                    comic.doc, upscale, comic.doc.sourceOf(page),
                    onExit = { src -> compareOpen = false; if (src != comic.doc.sourceOf(page)) jumpReq = comic.doc.indexOf(src) },
                    onMode = { m -> if (m == 0) comic.update { it.copy(ai = 0) }; compareOpen = false },
                )
                // F-58 미리 풀기 상한: 기기 RAM 의 1/8
                LaunchedEffect(comicDoc) {
                    val cd = comicDoc ?: return@LaunchedEffect
                    val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                    val mi = android.app.ActivityManager.MemoryInfo()
                    am?.getMemoryInfo(mi)
                    if (mi.totalMem > 0) cd.aheadBudget = (mi.totalMem / 8).coerceIn(64L shl 20, 768L shl 20)
                }
                // F-44 자동 넘김(쪽 넘김 보기): 쪽이 바뀔 때마다 다시 센다. 스크롤 보기·웹툰은 자동 스크롤(아래).
                LaunchedEffect(autoOn, autoPaused, page, s.autoTurnSec, menu, continuous) {
                    if (!autoOn || autoPaused || menu || continuous) return@LaunchedEffect
                    delay(s.autoTurnSec * 1000L)
                    if (page >= doc.pageCount - 1) { autoOn = false; stub("마지막 쪽이라 자동 넘김을 껐어요.") } else { turnFwd = true; turnReq++ }
                }
                val autoDp = if (continuous && autoOn && !autoPaused && !menu) AutoScroll.dpPerSec(s.autoScrollSpeed) else 0
                val onAutoEnd = { autoOn = false; autoPaused = false; stub("끝까지 내려서 자동 스크롤을 껐어요.") }
                // 읽는 문장이 다른 쪽으로 가면 따라 넘긴다. 쪽 경계에 걸친 문장은 다음 쪽 몫을 읽기 시작할 때 넘긴다.
                // 바로 다음 쪽(펼침)이면 지금 넘김 효과로 넘기고, 멀면 건너뛴다.
                LaunchedEffect(listen.cur, listen.onNext, listening) {
                    val c = listen.cur ?: return@LaunchedEffect
                    if (!listening) return@LaunchedEffect
                    val cp = if (listen.onNext && c.spans) c.page + 1 else c.page
                    if (cp !in 0 until doc.pageCount) return@LaunchedEffect
                    if (scrollMode) { revealReq = cp; return@LaunchedEffect }
                    val visible = if (!spreadNow) cp == page else if (page == 0 && coverAlone) cp == 0 else cp == page || cp == page + 1
                    if (visible) return@LaunchedEffect
                    val nextFirst = if (!spreadNow) page + 1 else if (page == 0 && coverAlone) 1 else page + 2
                    if (cp == nextFirst && curlView != null && comicDoc == null && zoomAt == null) { turnFwd = true; turnReq++ } else jumpReq = cp
                }
                val tapOf = { x: Float, y: Float -> TapZones.action(x, y, s.tapLayout, s.tapCustom, s.leftHanded, doc.rtl) }
                val comicMenuOf = { sourcePage: Int -> menu = false; comicMenuPage = sourcePage }
                if (comic != null && webtoon) {
                    ComicWebtoonReader(
                        cs = comic, startPage = comic.doc.sourceOf(page), colors = colors,
                        menuVisible = menu, onToggleMenu = { menu = !menu },
                        onPage = { if (it != page) { page = it; pagesTurned++ } },
                        turnRequest = turnReq, turnForward = turnFwd,
                        jumpRequest = jumpReq, onJumpHandled = { jumpReq = null },
                        upscale = upscale, onLongPress = comicMenuOf,
                        autoDpPerSec = autoDp, onAutoEnd = onAutoEnd,
                    )
                } else if (fitMode && comicDoc != null) {
                    // F-54 맞춤 방식(폭·높이·원본) + F-55 두 번 탭 2배 + F-58 미리 풀기
                    val readySet = upscale?.ready?.collectAsState()?.value
                    val extState = comicDoc.extract?.collectAsState()?.value
                    LaunchedEffect(upscale, page, pxW, pxH) { upscale?.request(comicDoc.sourceOf(page), pxW.toInt(), pxH.toInt()) }
                    ZoomPager(
                        count = count, startIndex = page, fit = comic?.prefs?.fit ?: ComicBookPrefs.FIT_SCREEN, rtl = doc.rtl, colors = colors,
                        load = { i, w -> (comicDoc.takeAheadFree(i, w) ?: comicDoc.renderFree(comicDoc.sourceOf(i), w))?.let { ZoomImage(it.bmp, it.nativeW) } },
                        prefetch = { i, fwd, w -> comicDoc.prefetchFree(i, fwd, w) },
                        tapAction = tapOf, menuVisible = menu, onToggleMenu = { menu = !menu },
                        onPage = { if (it != page) { page = it; pagesTurned++ } },
                        onLongPress = { i -> comicMenuOf(comicDoc.sourceOf(i)) },
                        turnRequest = turnReq, turnForward = turnFwd,
                        jumpRequest = jumpReq, onJumpHandled = { jumpReq = null },
                        reloadKey = listOf(comicDoc.look, comic?.prefs?.fit, readySet?.contains(comicDoc.sourceOf(page)), extState?.done?.let { comicDoc.ready(comicDoc.sourceOf(page)) }),
                    )
                    ComicExtractOverlay(comicDoc, listOf(comicDoc.sourceOf(page)), colors, menuVisible = menu) {}
                    if (upscale != null && !menu) UpscaleChip(upscale, comicDoc.sourceOf(page), Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 8.dp, end = 12.dp)) { showAa = true }
                } else {
                    val near = if (reflow || isWord) 3 else 0 // Word 원본↔eBook은 쪽 수가 달라 근처 쪽까지 찾는다
                    val speaking = if (listening) listen.cur?.takeIf { it.start >= 0 } else null
                    val source = remember(doc, colors, highlights, hit, speaking, comic?.layoutVersion) {
                        val hl = highlights; val h = hit; val sp = speaking
                        val spColor = mix(colors.accent, colors.bg, 0.45f)
                        GuardedSource(doc, colors, fixed = !reflow) { i, tpOf ->
                            // 쪽 수가 바뀌어도(이북식 글꼴·크기) 위치 비율로 근처 쪽을 찾은 뒤 글자로 다시 맞춘다
                            val cand = hl.filter { kotlin.math.abs(locatorPage(it.page, it.frac, count) - i) <= near }
                            val spHere = sp != null && (sp.page == i || (sp.start2 >= 0 && sp.page + 1 == i))
                            val tp = if (cand.isEmpty() && h?.page != i && !spHere) null else tpOf()
                            if (tp == null) emptyList<Mark>()
                            else {
                                cand.mapNotNull { x ->
                                    val r = TextGeo.resolve(tp.text, x.startIdx, x.endIdx, x.text) ?: return@mapNotNull null
                                    Mark(r.first, r.last + 1, HighlightColors[x.color.coerceIn(0, 3)])
                                } + listOfNotNull(
                                    h?.takeIf { it.page == i }?.let { searchMark(it) },
                                    // 듣기: 읽는 문장 옅은 주색 바탕(S-27). 쪽 경계에 걸친 문장은 다음 쪽 머리도 칠한다.
                                    sp?.takeIf { it.page == i && it.end <= tp.text.length }?.let { Mark(it.start, it.end, spColor) },
                                    sp?.takeIf { it.start2 >= 0 && it.page + 1 == i && it.end2 <= tp.text.length }?.let { Mark(it.start2, it.end2, spColor) },
                                )
                            }
                        }
                    }
                    val rtl = doc.rtl
                    val snapPx = with(LocalDensity.current) { 24.dp.toPx() }
                    /** 고른 칸·누른 곳(px)에서 낱말을 골라 글자 선택을 연다(S-28). */
                    fun selectAt(slot: PageSlot, xPx: Float, yPx: Float) {
                        scope.launch {
                            val tp = withContext(Dispatchers.IO) { if (slot.page in 0 until count) guarded(doc, null) { doc.pageText(slot.page) } else null }
                            if (tp == null || tp.text.isBlank()) { stub(noTextMsg(fmt)); return@launch }
                            val b = slot.content(tp)
                            // 7G: 줄 사이·빈칸을 길게 눌러도 빈칸 하나를 고르지 않는다 — 24dp(세로는 한 줄 높이) 안의 가까운 낱말로 붙고,
                            // 없으면 아무것도 고르지 않는다
                            val tol = snapPx
                            val i = TextGeo.wordCharNear(
                                tp, (xPx - b.l) / (b.r - b.l), (yPx - b.t) / (b.b - b.t),
                                rx = tol / (b.r - b.l).coerceAtLeast(1f), ry = tol / (b.b - b.t).coerceAtLeast(1f),
                            )
                            if (i < 0) return@launch
                            val w = TextGeo.wordAt(tp.text, i)
                            if (TextGeo.blank(tp.text, w.first, w.last + 1)) return@launch
                            sel = Selection(slot, tp, w.first, w.last + 1)
                        }
                    }
                    // 스크롤 보기 자리 잡기용 쪽 비율(고정 쪽은 지금 쪽 것을 한 번 재 둔다 — 엔진 호출은 백그라운드)
                    val defAspect by produceState<Float?>(if (reflow) hDp / pageWdp.coerceAtLeast(1f) else null, doc, scrollMode) {
                        if (value == null && scrollMode) value = withContext(Dispatchers.IO) {
                            guarded(doc, null) { doc.pageAspect(page.coerceIn(0, (count - 1).coerceAtLeast(0))) } ?: 1.414f
                        }
                    }
                    val da = defAspect
                    // F-23 고정 쪽 문서 폭·높이 맞춤(쪽 넘김 보기): 맞춤 배율로 그린 쪽을 밀어 본다(만화 F-54 와 같은 화면)
                    val fitFixed = !reflow && comicDoc == null && !scrollMode && fixedView.fit != ComicBookPrefs.FIT_SCREEN
                    if (fitFixed) {
                        ZoomPager(
                            count = count, startIndex = page, fit = fixedView.fit, rtl = rtl, colors = colors,
                            load = { i, w ->
                                val a = (guarded(doc, null) { doc.pageAspect(i) } ?: 1.414f).coerceIn(0.2f, 5f)
                                // 화소 상한 약 9MP
                                val rw = minOf(w, kotlin.math.sqrt(9_000_000.0 / a).toInt()).coerceAtLeast(1)
                                val rh = (rw * a).toInt().coerceAtLeast(1)
                                ZoomImage(source.render(i, rw, rh), rw)
                            },
                            tapAction = tapOf, menuVisible = menu, onToggleMenu = { menu = !menu },
                            onPage = { if (it != page) { page = it; pagesTurned++; sel = null } },
                            onLongPressAt = { slot, x, y -> menu = false; selectAt(slot, x, y) }, // 7B 폭·높이 맞춤에서도 글자 선택
                            turnRequest = turnReq, turnForward = turnFwd,
                            jumpRequest = jumpReq, onJumpHandled = { jumpReq = null },
                            reloadKey = source,
                        )
                    } else if (scrollMode && da == null) {
                        CircularProgressIndicator(color = colors.accentC, modifier = Modifier.align(Alignment.Center))
                    } else if (scrollMode && da != null) {
                        // F-22 스크롤 보기(+ F-44 자동 스크롤). EPUB 그림 AI(D-021)는 새로 그리는 쪽부터 바뀐다.
                        EpubUpscaleHook(doc, s.epubAiUpscale, null) {}
                        ScrollReader(
                            source = source,
                            aspectOf = { i -> if (reflow) da else guarded(doc, null) { doc.pageAspect(i) } },
                            defaultAspect = da,
                            columnMaxDp = if (reflow) pageWdp else wDp,
                            zoomable = !reflow,
                            colors = colors, startPage = page,
                            onPinchFont = if (reflow && s.pinchFont) ({ scale -> onSettings { cur -> cur.copy(fontSize = Math.round(cur.fontSize * scale).coerceIn(kr.vici.ashreader.data.Prefs.MIN_SIZE, kr.vici.ashreader.data.Prefs.MAX_SIZE)) } }) else null,
                            onPage = { if (it != page) { page = it; pagesTurned++ } },
                            onTap = { _, _ ->
                                touchTick++
                                when {
                                    sel != null -> sel = null
                                    menu -> menu = false
                                    foot != null -> foot = null
                                    autoOn && !autoPaused -> autoPaused = true
                                    // 대표님 2026-10-26: 문서 스크롤 보기는 위·아래 탭으로 넘기지 않고 밀어서만 넘긴다. 탭은 메뉴.
                                    else -> menu = true
                                }
                            },
                            onLongPress = { slot, x, y -> menu = false; selectAt(slot, x, y) },
                            onUserScroll = { if (sel != null) sel = null; touchTick++ },
                            turnRequest = turnReq, turnForward = turnFwd,
                            jumpRequest = jumpReq, onJumpHandled = { jumpReq = null },
                            revealRequest = revealReq,
                            autoDpPerSec = autoDp, onAutoEnd = onAutoEnd,
                        )
                    } else {
                    LaunchedEffect(jumpReq, source) {
                        val j = jumpReq ?: return@LaunchedEffect
                        page = j.coerceIn(0, (count - 1).coerceAtLeast(0))
                        curlView?.let { it.tag = source; it.setPageSource(source, page) }
                        jumpReq = null
                    }
                    val cfg = CurlConfig(
                        direction = if (doc.rtl) TurnDirection.RTL else TurnDirection.LTR,
                        effect = when { s.noTurnEffect -> TurnEffect.NONE; s.curl -> TurnEffect.CURL; else -> TurnEffect.SLIDE },
                        spread = comic?.spreadMode() ?: SpreadMode.AUTO,
                        coverAlone = coverAlone,
                        backColor = colors.bg,
                        shadowStrength = colors.shadow,
                    )
                    // 고정 쪽·만화: 두 번 탭 = 누른 곳 2배(F-55), 두 손가락 = 확대(F-23) → 확대 덧화면(ZoomPager)
                    val zoomable = !reflow
                    fun openZoom(xf: Float, yf: Float, k: Float) {
                        val slot = slotAt(xf * pxW, pxW, pxH, page, spreadNow, rtl, coverAlone)
                        if (slot.page !in 0 until count) return
                        menu = false; sel = null
                        zoomAt = ZoomReq(slot.page, (xf * pxW - slot.left) / slot.width * pxW, yf * pxH, k)
                    }
                    fun handleTap(v: PageCurlView, x: Float, y: Float) {
                        when {
                            sel != null -> sel = null
                            menu -> menu = false
                            foot != null -> foot = null
                            autoOn && !autoPaused -> autoPaused = true
                            else -> {
                                // F-46: 문서 안 링크(각주)를 누르면 팝업, 읽던 쪽은 그대로
                                val slot = slotAt(x * pxW, pxW, pxH, page, spreadNow, rtl, coverAlone)
                                val nx = (x * pxW - slot.left) / slot.width
                                val ny = (y * pxH - slot.top) / slot.height
                                val pad = 0.015f
                                val link = pageLinks[slot.page]?.firstOrNull { nx >= it.l - pad && nx <= it.r + pad && ny >= it.t - pad && ny <= it.b + pad }
                                val md = doc as? MupdfReaderDoc
                                if (link != null && md != null) {
                                    scope.launch {
                                        val fn = withContext(Dispatchers.IO) { guarded(doc, null) { md.footnote(link.uri) } }
                                        if (fn == null) { stub("링크가 가리키는 곳을 찾지 못했어요.") }
                                        else { foot = FootState(fn.text, fn.page, if (y < 0.6f) link.b else link.t, below = y < 0.6f) }
                                    }
                                } else when (tapOf(x, y)) {
                                    TapAction.PREV -> { v.turn(next = false) }
                                    TapAction.NEXT -> { v.turn(next = true) }
                                    TapAction.MENU -> { menu = true }
                                    TapAction.NONE -> {}
                                }
                            }
                        }
                    }
                    AndroidView(
                        factory = { ctx ->
                            PageCurlView(ctx).also { v ->
                                curlView = v
                                v.onPageChanged = { first, sp -> spreadNow = sp; if (first != page) { page = first; pagesTurned++; sel = null } }
                            }
                        },
                        update = { v ->
                            v.onLongPress = { xf, yf ->
                                menu = false
                                val slot = slotAt(xf * pxW, pxW, pxH, page, spreadNow, rtl, coverAlone)
                                if (comicDoc != null) {
                                    // F-59 만화 쪽 메뉴(표지로 지정·공유·저장)
                                    if (slot.page in 0 until count) comicMenuOf(comicDoc.sourceOf(slot.page))
                                } else selectAt(slot, xf * pxW, yf * pxH)
                            }
                            v.onTap = { x, y ->
                                touchTick++
                                val t = tapState
                                val plain = sel == null && !menu && foot == null && !(autoOn && !autoPaused)
                                if (zoomable && plain) {
                                    // 두 번 탭을 가리려고 한 번 탭은 잠깐 미룬다(고정 쪽·만화만)
                                    val now = SystemClock.uptimeMillis()
                                    val pj = t.job
                                    if (pj != null && pj.isActive && now - t.at < 300L && abs(x - t.x) < 0.08f && abs(y - t.y) < 0.08f) {
                                        pj.cancel(); t.job = null
                                        openZoom(x, y, ViewDefaults.doubleTap)
                                    } else {
                                        t.at = now; t.x = x; t.y = y
                                        t.job = scope.launch { delay(250L); handleTap(v, x, y) }
                                    }
                                } else handleTap(v, x, y)
                            }
                            v.setConfig(cfg)
                            if (v.tag !== source) { v.tag = source; v.setPageSource(source, page) }
                            // 접근성(TalkBack·UiAutomator): Compose semantics 설명은 AndroidView 안의 넘김 화면 노드에 실리지 않아
                            // (특히 Android 8) 읽기 화면이 '이름 없는 화면'이 된다 → View 자체에도 지금 쪽을 단다
                            v.contentDescription = "${target.title} ${page + 1}쪽"
                        },
                        onRelease = { it.release() },
                        modifier = Modifier.fillMaxSize()
                            .then(
                                if (!zoomable) {
                                    // 두 손가락 벌리기·오므리기 = 글자 크게·작게(설정 '두 손가락으로 글자 크기', 작업 06). 넘김 화면에는 취소가 간다
                                    if (!s.pinchFont) Modifier else Modifier.pointerInput(Unit) {
                                        awaitEachGesture {
                                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                            var scale = 1f
                                            var pinching = false
                                            while (true) {
                                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                                if (ev.changes.none { it.pressed }) break
                                                if (ev.changes.count { it.pressed } >= 2) {
                                                    if (!pinching) tapState.job?.cancel()
                                                    pinching = true
                                                    scale *= ev.calculateZoom()
                                                }
                                                if (pinching) ev.changes.forEach { it.consume() }
                                            }
                                            if (pinching && (scale > 1.08f || scale < 0.93f)) onSettings { cur ->
                                                cur.copy(fontSize = Math.round(cur.fontSize * scale).coerceIn(kr.vici.ashreader.data.Prefs.MIN_SIZE, kr.vici.ashreader.data.Prefs.MAX_SIZE))
                                            }
                                        }
                                    }
                                } else Modifier.pointerInput(page, spreadNow) {
                                    // 두 손가락을 대면 확대 덧화면을 1배로 열고, 손을 뗄 때까지 손가락 움직임을 그대로 넘긴다
                                    // (대표님 01:55: 예전에는 1.5배로 툭 커진 뒤에야 자연스럽게 커졌다). 넘김 화면에는 취소가 간다
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        var feed: kotlinx.coroutines.channels.Channel<PinchStep>? = null
                                        while (true) {
                                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                                            val down = ev.changes.filter { it.pressed }
                                            if (down.isEmpty()) break
                                            val f = feed
                                            if (f == null && down.size >= 2) {
                                                val cx = down.map { it.position.x }.average().toFloat()
                                                val cy = down.map { it.position.y }.average().toFloat()
                                                ev.changes.forEach { it.consume() }
                                                tapState.job?.cancel()
                                                val ch = kotlinx.coroutines.channels.Channel<PinchStep>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                                                feed = ch; pinchFeed = ch
                                                openZoom(cx / size.width, cy / size.height, 1f)
                                            } else if (f != null) {
                                                if (down.size >= 2) f.trySend(PinchStep(ev.calculateCentroid(useCurrent = true), ev.calculatePan(), ev.calculateZoom()))
                                                ev.changes.forEach { it.consume() }
                                            }
                                        }
                                        feed?.let { it.trySend(PinchStep(Offset.Zero, Offset.Zero, 1f, end = true)); it.close() }
                                    }
                                },
                            )
                            .semantics { contentDescription = "${target.title} ${page + 1}쪽" },
                    )
                    // 넘김 화면을 처음 그릴 때의 요청 번호는 이미 처리된 것(스크롤 보기 등에서 돌아와도 넘기지 않게)
                    val curlTurn0 = remember(doc) { turnReq }
                    LaunchedEffect(turnReq) { if (turnReq > curlTurn0 && zoomAt == null) curlView?.turn(turnFwd) }
                    zoomAt?.let { z ->
                        ZoomPager(
                            count = count, startIndex = z.index, fit = ComicBookPrefs.FIT_SCREEN, rtl = rtl, colors = colors,
                            load = { i, w ->
                                if (comicDoc != null) comicDoc.renderFree(comicDoc.sourceOf(i), w)?.let { ZoomImage(it.bmp, it.nativeW) }
                                else {
                                    // 고정 쪽: 화면 비율로 그린 쪽(형광펜·검색 표시 포함), 화소 상한 약 9MP
                                    val k = kotlin.math.sqrt(9_000_000.0 / (pxW.toDouble() * pxH)).toFloat().coerceAtMost(w / pxW)
                                    val rw = (pxW * k).toInt().coerceAtLeast(1); val rh = (pxH * k).toInt().coerceAtLeast(1)
                                    ZoomImage(source.render(i, rw, rh), rw)
                                }
                            },
                            tapAction = tapOf, menuVisible = false,
                            onToggleMenu = { zoomAt = null; pinchFeed = null; jumpReq = page; menu = true },
                            onPage = { if (it != page) { page = it; pagesTurned++ } },
                            onLongPress = if (comicDoc != null) ({ i: Int -> comicMenuOf(comicDoc.sourceOf(i)) }) else null,
                            // 7B 확대 덧화면에서도 글자 선택(확대·이동한 칸으로 좌표를 맞춘다)
                            onLongPressAt = if (comicDoc == null) ({ slot: PageSlot, x: Float, y: Float -> selectAt(slot, x, y) }) else null,
                            turnRequest = turnReq, turnForward = turnFwd,
                            reloadKey = source,
                            initialZoom = z.zoom, initialFocus = Offset(z.fx, z.fy),
                            onExit = { zoomAt = null; pinchFeed = null; jumpReq = page },
                            livePinch = pinchFeed,
                        )
                        BackHandler { if (sel != null) sel = null else { zoomAt = null; pinchFeed = null; jumpReq = page } }
                    }
                    if (doc is ComicReaderDoc) {
                        // solid CBR 풀기 진행·쪽 준비 중(S-41). 보이는 쪽이 풀리면 다시 그린다.
                        val visSrc = (if (spreadNow) listOf(page, page + 1) else listOf(page)).filter { it < count }.map { doc.sourceOf(it) }
                        ComicExtractOverlay(doc, visSrc, colors, menuVisible = menu) { curlView?.let { v -> v.tag = source; v.setPageSource(source, page) } }
                    }
                    // F-73 확장(D-021·작업 03): 문서(이북·PDF) 그림 AI 화질 향상 — 처리된 그림이 생기면 지금 쪽을 다시 그린다. 넘김 중에는 쉰다.
                    EpubUpscaleHook(doc, s.epubAiUpscale, curlView) { curlView?.let { v -> v.tag = source; v.setPageSource(source, page) } }
                    // 작업 04: 이북·PDF 도 그림을 바꾼 쪽이면 'AI 화질' 표시, 누르면 보기 시트(맨 위 AI 칸)
                    if (doc is MupdfReaderDoc && !menu) DocAiChip(doc, page, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 8.dp, end = 12.dp)) { showAa = true }
                    if (upscale != null && doc is ComicReaderDoc) {
                        val readySet by upscale.ready.collectAsState()
                        LaunchedEffect(upscale, page, pxW, pxH, comic?.layoutVersion) { upscale.request(doc.sourceOf(page), pxW.toInt(), pxH.toInt()) }
                        // 보이는 쪽의 2배 결과가 나왔는데 넘김 화면이 원본으로 그렸으면 다시 그린다
                        LaunchedEffect(readySet, page, source) {
                            val vis = (if (spreadNow) listOf(page, page + 1) else listOf(page)).map { doc.sourceOf(it) }
                            if (vis.any { it in readySet && !doc.shownUpscaled(it) }) {
                                delay(300)
                                curlView?.let { v -> v.tag = source; v.setPageSource(source, page) }
                            }
                        }
                        if (!menu) UpscaleChip(upscale, doc.sourceOf(page), Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 8.dp, end = 12.dp)) { showAa = true }
                    }
                    }
                }
                comicMenuPage?.let { mp ->
                    if (comicDoc != null) ComicPageMenu(comicDoc, mp, target.title, target.id, target.path, target.once, onDismiss = { comicMenuPage = null }, onMessage = { stub(it) })
                }

                // 작업 07 B3: 서재 목록 보기의 '첫 문단 3줄' — 처음 열 때 앞 쪽 글을 기기 안에만 저장(만화·한 번만 보기 제외)
                LaunchedEffect(doc) {
                    if (target.once || comicDoc != null || kr.vici.ashreader.doc.FirstPara.load(context, target.id) != null) return@LaunchedEffect
                    kotlinx.coroutines.delay(3_000)
                    val text = withContext(Dispatchers.IO) {
                        (0 until minOf(count, 4)).asSequence().map { i -> guarded(doc, null) { doc.pageText(i)?.text }.orEmpty().trim() }
                            .firstOrNull { it.length >= 60 }
                    }
                    if (text != null) withContext(Dispatchers.IO) { kr.vici.ashreader.doc.FirstPara.save(context, target.id, text) }
                }

                // 볼륨 키 (화면 설계서 4.5): 메뉴·시트가 열려 있으면 시스템 음량
                // 듣는 동안은 소리 크기(4.5). 자동 넘김 중 볼륨 키 = 넘김 + 타이머 다시 시작(page 가 바뀌며 다시 셈)
                val volOn = s.volumeKeys && !menu && !showAa && !showToc && !showTurn && !showLight && sel == null && !searchOpen && !listening && comicMenuPage == null
                DisposableEffect(volOn) {
                    val h: (Boolean) -> Boolean = { next -> touchTick++; turnFwd = next; turnReq++; true }
                    VolumeKeys.handler = if (volOn) h else null
                    onDispose { VolumeKeys.handler = null }
                }

                // 정보줄(F-45, 메뉴 숨김일 때). 듣기 바가 아래에 있으면 아래 정보줄은 숨긴다.
                val tocPages by produceState<List<Int>?>(null, doc) {
                    value = withContext(Dispatchers.IO) { guarded(doc, emptyList<Int>()) { doc.toc().map { it.page }.filter { it >= 0 }.distinct().sorted() } }
                }
                val chapterLeft = tocPages?.takeIf { it.isNotEmpty() }?.let { t -> (t.firstOrNull { it > page } ?: count) - page - 1 }
                if (!menu && !(listening && !s.infoTop)) {
                    // 태블릿 두 쪽 펼침에서 '쪽마다' 설정이면 각 쪽 아래에 그 쪽 번호(만화는 원본 쪽 번호)
                    val perPage = s.infoPerPage && spreadNow && !scrollView && !webtoon && !fitMode && zoomAt == null
                    val halves = if (!perPage) null else {
                        fun num(i: Int): Int? = if (i !in 0 until count) null else comicDoc?.sourceOf(i) ?: i
                        val coverFirst = page == 0 && coverAlone
                        val a: Int? = if (coverFirst) null else num(page)
                        val b: Int? = if (coverFirst) num(0) else num(page + 1)
                        if (doc.rtl) b to a else a to b
                    }
                    ReaderInfoLine(
                        page, count, chapterLeft, msPerPage, s, colors, Modifier.align(if (s.infoTop) Alignment.TopCenter else Alignment.BottomCenter), halves = halves,
                        // 7G: 쪽 넘김 eBook 보기(가로·세로·두 쪽 펼침)는 쪽 배치가 비운 띠에 정확히 놓는다
                        frame = frame?.takeIf { !scrollView && !webtoon && !fitMode && zoomAt == null },
                        glass = scrollView || webtoon,
                    )
                }
                // 자동 넘김 멈춤 띠
                if (autoOn && autoPaused && !menu) Row(
                    Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 36.dp)
                        .background(colors.barC, RoundedCornerShape(24.dp)).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (continuous) "자동 스크롤 멈춤" else "자동 넘김 멈춤", fontSize = 14.sp, color = colors.fgC)
                    TextButton(onClick = { autoPaused = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("다시 시작", color = colors.accentC, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { autoOn = false; autoPaused = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("끄기", color = colors.fgC) }
                }

                // S-40 한 번만 보기 띠
                fun toggleHwpView() {
                    pendingFrac = if (count > 0) (page + 0.5f) / count else 0f
                    menu = false; sel = null; hit = null
                    hwpEbook = !hwpEbook
                    onViewMode(if (hwpEbook) Book.VIEW_EBOOK else Book.VIEW_ORIGINAL)
                }
                // 그림만 든 PDF ↔ 만화 보기(AI 화질 등)
                fun togglePdfComic() {
                    menu = false; sel = null; hit = null
                    comicOfferClosed = true
                    pdfComic = !pdfComic
                    onViewMode(if (pdfComic) Book.VIEW_COMIC else Book.VIEW_ORIGINAL)
                }
                val pdfToggle = fmt == DocFormat.PDF && (pdfComic || imageOnly == true)
                // 그림으로 된 PDF 의 '만화로 보기' 띠는 없앴다(대표님 2026-10-25 00:01) — 전환은 테마 및 설정 맨 위 'PDF | 만화'
                val curBookmark = bookmarks.firstOrNull { locatorPage(it.page, it.frac, count) == page }
                if (target.once) OnceBar(fmt, page, count, colors, ebook = hwpEbook, onAdd = onAddToLibrary, onEbookView = { toggleHwpView() },
                    modifier = Modifier.align(if (menu) Alignment.Center else Alignment.BottomCenter).padding(bottom = if (menu) 0.dp else 28.dp))

                // 작업 07 E: 안내선(메뉴와 상관없이 켜 둔 동안 보임, 스크롤 보기에서도)
                if (guideOn) GuideLine(colors, s.fontSize * s.lineHeight, guideY, onMove = { guideY = it }, onOff = { guideOn = false })

                LaunchedEffect(menu) { if (!menu) stackOpen = false }
                if (menu) {
                    val portraitNow = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
                    val canSearch = doc is MupdfReaderDoc || doc is DjvuReaderDoc || doc is HwpReaderDoc
                    // 작업 07 E: 예전 위·아래 막대 → 바깥 틀(챕터 남은 쪽·✕ / 쪽수·메뉴) + 떠 있는 메뉴 쌓기
                    ReaderChrome(
                        colors, page, count, chapterLeft, stackOpen,
                        onClose = onBack,
                        // 메뉴 밖을 눌러 닫힌 바로 그 누름이 ⋯ 이면 다시 열지 않는다(떠 있는 메뉴가 따로 창이라 누름이 둘 다에 간다)
                        onMenu = { if (System.currentTimeMillis() - stackClosedAt > 400) stackOpen = !stackOpen },
                        onSeek = { p -> jumpReq = p },
                        preview = { i, f ->
                            SliderPreview(
                                i, f, colors, fixedPage = !reflow, cache = thumbs,
                                aspect = 1.414f,
                                thumb = { idx, w ->
                                    withContext(Dispatchers.IO) {
                                        val a = guarded(doc, null) { doc.pageAspect(idx) } ?: 1.414f
                                        guarded(doc, null) { doc.render(idx, w, (w * a).toInt().coerceIn(1, w * 4), colors) }
                                    }
                                },
                                chapter = { idx -> tocForPreview?.let { chapterOf(it, idx) } },
                                notReady = { idx -> comicDoc != null && !comicDoc.ready(comicDoc.sourceOf(idx)) },
                            )
                        },
                    )
                    // 떠 있는 메뉴는 쪽 넘김 화면(SurfaceView) 위에 뜨도록 따로 창(Popup)에. 뒤는 흐리지 않고 원본 그대로, 바탕만 하얗게(대표님 2026-10-26)
                    val density = LocalDensity.current
                    val navB = WindowInsets.navigationBars.getBottom(density)
                    // 그림자 자리(MenuShadowRoom): 창이 메뉴 크기뿐이면 그림자가 창 끝에서 잘려 둘레에 네모 띠가 보인다(태블릿 TB375FC).
                    // 창을 사방으로 넓힌 만큼 위치를 되밀어 메뉴 자리는 그대로 둔다
                    val stackDismiss = { stackOpen = false; stackClosedAt = System.currentTimeMillis() }
                    if (stackOpen) androidx.compose.ui.window.Popup(
                        alignment = Alignment.BottomEnd,
                        offset = with(density) { IntOffset(-14.dp.roundToPx() + MenuShadowRoom.roundToPx(), -(76.dp.roundToPx() + navB) + MenuShadowRoom.roundToPx()) },
                        onDismissRequest = stackDismiss,
                        properties = androidx.compose.ui.window.PopupProperties(focusable = true, clippingEnabled = false),
                    ) {
                    ShadowRoomBox(stackDismiss) {
                    ReaderMenuStack(
                        colors, percent = if (count > 0) (page + 1) * 100 / count else 0, bookmarked = curBookmark != null, guideOn = guideOn,
                        modifier = Modifier,
                        onToc = { stackOpen = false; showToc = true },
                        onSearch = { stackOpen = false; if (canSearch) { menu = false; searchOpen = true } else stub(noTextMsg(fmt)) },
                        onTheme = { stackOpen = false; themeOpen = true },
                        onShare = {
                            stackOpen = false
                            val p = page
                            sharePageImage(context, p, render = {
                                val a = guarded(doc, null) { doc.pageAspect(p) } ?: 1.414f
                                guarded(doc, null) { doc.render(p, 1080, (1080 * a).toInt().coerceIn(1, 4320), colors) }
                            }) { stub(it) }
                        },
                        onGuide = { stackOpen = false; guideOn = !guideOn; menu = false },
                        onBookmark = { onToggleBookmark(page, locatorFrac(page, count), curBookmark?.id) },
                    )
                    }
                    }
                    // 사용자화: 떠 있는 메뉴에 없는 예전 기능을 빠짐없이
                    if (customOpen) CustomizeSheet(colors, buildList {
                        if (comic == null) add(CustomItem(Icons.Toc, "글꼴·줄 간격·여백", if (reflow) "글꼴·크기·문단·내 글꼴" else "쪽 맞춤·여백 자르기·테마") { customOpen = false; showAa = true })
                        else add(CustomItem(Icons.Grid, "만화 보기", "넘김 방향·두 쪽·맞춤·선명하게") { customOpen = false; showAa = true })
                        add(CustomItem(Icons.Turn, "넘김 효과·자동 넘김", "넘김 방식·누르는 곳·볼륨 버튼") { customOpen = false; showTurn = true })
                        add(CustomItem(Icons.Sun, "밝기·따뜻한 색", "추가 어둡게·따뜻한 색 시간") { customOpen = false; showLight = true })
                        if (comicDoc == null) add(CustomItem(Icons.Speaker, "듣기", "읽어 주기") { customOpen = false; menu = false; onListen() })
                        else add(CustomItem(Icons.Grid, "쪽 목록", "모든 쪽 그림") { customOpen = false; menu = false; gridOpen = true })
                        add(CustomItem(Icons.Pen, "이 쪽에 메모", "${page + 1}쪽") { customOpen = false; memoOpen = true })
                        val locked = s.rotation != Rotation.AUTO
                        add(CustomItem(if (locked) Icons.RotateLock else Icons.Rotate, if (locked) "회전 잠금 풀기" else "회전 잠그기", null) {
                            customOpen = false
                            val r = if (locked) Rotation.AUTO else if (portraitNow) Rotation.PORTRAIT else Rotation.LANDSCAPE
                            onSettings { it.copy(rotation = r) }
                            stub(if (r == Rotation.AUTO) "화면 회전을 풀었어요." else "화면 회전을 잠갔어요.")
                        })
                        // 원본·eBook / PDF·만화 전환은 테마 및 설정 맨 위에만(대표님 2026-10-25 00:02)
                    }) { customOpen = false }
                }

                // F-60 만화 쪽 목록 그리드(전체 화면). 원본 쪽 기준, 고르면 그 쪽이 처음 나오는 자리로
                if (gridOpen && comicDoc != null) {
                    val bmSrc = remember(bookmarks, count) { bookmarks.map { comicDoc.sourceOf(locatorPage(it.page, it.frac, count)) }.toSet() }
                    ComicPageGrid(
                        count = comicDoc.sourceCount, current = comicDoc.sourceOf(page), bookmarked = bmSrc, rtl = doc.rtl, colors = colors, cache = thumbs,
                        thumb = { p, w -> withContext(Dispatchers.IO) { guarded(doc, null) { comicDoc.pageBitmap(p, w) } } },
                        ready = { p -> comicDoc.ready(p) },
                        onPick = { p -> gridOpen = false; jumpReq = comicDoc.indexOf(p) },
                        onClose = { gridOpen = false },
                    )
                }

                hit?.let { h ->
                    // 작업 07 E4: 찾은 곳 ‹ › 이동
                    val at = hits.indexOf(h)
                    fun goHit(k: Int) { hits.getOrNull(k)?.let { n -> hit = n; jumpReq = n.page } }
                    if (!menu && !searchOpen) Row(
                        Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)
                            .background(colors.barC, RoundedCornerShape(24.dp)).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (hits.size > 1) IconButton(onClick = { goHit(at - 1) }, enabled = at > 0, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Back, "앞에 찾은 곳", tint = colors.fgC.copy(alpha = if (at > 0) 1f else 0.3f))
                        }
                        TextButton(onClick = { searchOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (hits.size > 1 && at >= 0) "${at + 1} / ${hits.size}" else "검색 결과로", color = colors.accentC)
                        }
                        if (hits.size > 1) IconButton(onClick = { goHit(at + 1) }, enabled = at < hits.size - 1, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Chevron, "다음 찾은 곳", tint = colors.fgC.copy(alpha = if (at < hits.size - 1) 1f else 0.3f))
                        }
                        IconButton(onClick = { hit = null; hits = emptyList() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Close, "검색 표시 지우기", tint = colors.fgC) }
                    }
                }

                sel?.let { cur ->
                    SelectionLayer(
                        sel = cur, c = colors,
                        onChange = { sel = it },
                        onDismiss = { sel = null },
                        onCopy = { if (cur.text.isNotBlank()) { copyText(context, cur.text); stub("복사했어요.") }; sel = null },
                        onDict = { if (cur.text.isNotBlank()) lookUp(context, cur.text, s.dictPkg) { stub(it) }; sel = null },
                        onHighlight = { color ->
                            // 7G: 빈칸만 고른 형광펜은 만들지 않는다
                            if (cur.text.isBlank()) Unit
                            else if (target.once) stub("서재에 추가하면 형광펜을 남길 수 있어요.")
                            else onAddHighlight(cur.slot.page, cur.start, cur.end, cur.text, color, "", selFrac(cur, count))
                            sel = null
                        },
                        onMemo = { if (cur.text.isBlank()) Unit else if (target.once) stub("서재에 추가하면 메모를 남길 수 있어요.") else selMemo = cur; sel = null },
                        onSearch = { searchSeed = cur.text.take(60); sel = null; menu = false; searchOpen = true },
                    )
                }

                if (searchOpen) SearchPanel(
                    doc, colors, onClose = { searchOpen = false; searchSeed = "" }, initialQuery = searchSeed,
                    chapterOf = { p -> tocForPreview?.let { chapterOf(it, p) } },
                ) { h, all ->
                    searchOpen = false; hit = h; hits = all; jumpReq = h.page
                }

                if (listening && !menu) ListenBar(listen, s.ttsRate, colors,
                    onPlay = { if (listen.playing) Listen.pause() else Listen.resumeAt(page) },
                    onTune = { showTune = true },
                    onSleep = { m -> Listen.setSleep(m); onSettings { it.copy(ttsSleep = m) } },
                    modifier = Modifier.align(Alignment.BottomCenter))

                foot?.let { f ->
                    FootnoteCard(f.text, f.below, f.anchor, colors,
                        onGo = { foot = null; jumpReq = f.page },
                        onDismiss = { foot = null })
                }

                // 만화 설정(S-33): 태블릿 가로는 화면 안 측면 시트(Box)라 쪽 화면들보다 '뒤'에 그리면 가려져 안 보였다(7D E2E).
                // 쪽 화면·메뉴 다음에 그린다(휴대폰 바텀시트는 따로 창이라 상관없음).
                // 작업 07 E: 테마 및 설정(가/가·쪽 넘김/스크롤·어둡게·밝기·테마 6개·사용자화).
                // AI 화질 향상은 작업 04 자리 그대로 맨 위(이북·PDF = 앱 전체 한 값, 만화 = 책마다)
                if (themeOpen) {
                    val aiKind = (doc as? MupdfReaderDoc)?.takeIf { it.aiImages }?.let { if (reflow) AiKind.EBOOK else AiKind.PDF }
                    ThemeSheet(
                        s, colors, onSettings, onDismiss = { themeOpen = false }, reflow = reflow,
                        scrollAvailable = !comicNow && fmt.group != FormatGroup.COMIC && !pdfComic,
                        level = liveLight ?: LightMath.level(s.brightness.takeIf { it >= 0f } ?: (activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f), s.dim),
                        onLive = { liveLight = it },
                        ai = when {
                            // 대표님 00:40: 한 줄(켜기·끄기 옆에 [전후 비교]) — 시트 세로를 줄인다
                            comic != null -> ({ bg, fg -> ComicAiCard(comic, s.aiUpscale, upscale, s.upscaleCacheMb, { themeOpen = false; menu = false; compareOpen = true }, Modifier.padding(top = 6.dp), compact = true, compactBg = bg, compactFg = fg) })
                            aiKind != null -> ({ bg, fg -> AiQualityCard(aiKind, s.epubAiUpscale, onToggle = { v -> onSettings { it.copy(epubAiUpscale = v) } }, onCompare = { themeOpen = false; menu = false; docCompareOpen = true }, modifier = Modifier.padding(top = 6.dp), limitMb = s.upscaleCacheMb, compact = true, compactBg = bg, compactFg = fg) })
                            else -> null
                        },
                        onCustomize = { themeOpen = false; customOpen = true },
                        // 대표님 23:57: 원본·eBook(그림 PDF는 PDF·만화) 전환을 맨 위에
                        viewSwitch = when {
                            dualView && !target.once -> ViewSwitch("원본", "eBook", hwpEbook) { want -> if (want != hwpEbook) { themeOpen = false; toggleHwpView() } }
                            pdfToggle -> ViewSwitch("PDF", "만화", pdfComic) { want -> if (want != pdfComic) { themeOpen = false; togglePdfComic() } }
                            else -> null
                        },
                    )
                }
                if (comic != null && showAa) ComicSettingsSheet(
                    comic, s.aiUpscale, upscale, s.upscaleCacheMb, onDismiss = { showAa = false }, side = spread,
                    onCompare = { showAa = false; menu = false; compareOpen = true },
                )
                if (!s.tapGuideShown && !target.once) TapGuide(
                    rtl = doc.rtl,
                    grid = TapZones.grid(s.tapLayout, s.tapCustom, s.leftHanded, doc.rtl),
                    edge = if (s.tapLayout == kr.vici.ashreader.data.TapLayout.CUSTOM) 1f / 3f else TapZones.EDGE,
                    onChange = { onSettings { it.copy(tapGuideShown = true) }; showTurn = true },
                ) { onSettings { it.copy(tapGuideShown = true) } }
            }
        }
        // F-42 따뜻한 색·추가 어둡게(본문 위, 누름은 받지 않음)
        LightOverlay(s, dimNow)
        // F-65 가장자리 밝기: 시스템 뒤로 가기 제스처에서 뺄 띠(왼쪽 32dp × 세로 가운데 200dp)
        if (s.edgeBrightness) Box(Modifier.align(Alignment.CenterStart).size(32.dp, 200.dp).systemGestureExclusion())
        liveLight?.let { lv ->
            Text(
                if (lv < LightMath.MIN) "추가 어둡게 ${LightMath.split(lv).second}%" else "밝기 ${Math.round(lv * 100)}%",
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp))
    }

    val txtUi = if (fmt == DocFormat.TXT) TxtEncodingUi(TextEncoding.Kind.of(txtEnc), (openDoc as? MupdfReaderDoc)?.textKind) { k ->
        txtEnc = k?.name.orEmpty()
        if (!target.once) onTextEncoding(txtEnc)
    } else null
    val fixedUi = openDoc.let { od ->
        if (reflow || od == null) null
        else FixedViewUi(
            fixedView.fit, fixedView.crop, cropAvailable = od is MarginCroppable,
            onFit = { v -> setFixedView(fixedView.copy(fit = v)) },
            onCrop = { v -> setFixedView(fixedView.copy(crop = v)) },
        )
    }
    if (showAa && openDoc !is ComicReaderDoc) ViewSettingsSheet(
        s, reflow, onSettings, onDismiss = { showAa = false }, txt = txtUi, fixed = fixedUi,
    )
    (openDoc as? MupdfReaderDoc)?.takeIf { docCompareOpen }?.let { md ->
        DocAiCompareScreen(
            md, s.readingColors(), page,
            onExit = { p -> docCompareOpen = false; if (p != page) jumpReq = p },
            onMode = { m -> if (m == 0) onSettings { it.copy(epubAiUpscale = false) }; docCompareOpen = false },
        )
    }
    if (showTurn) TurnSheet(
        s, onSettings, autoOn = autoOn,
        onAuto = { v -> autoOn = v; autoPaused = false; if (v) { showTurn = false; menu = false } },
        onGuide = { showTurn = false; menu = false; onSettings { it.copy(tapGuideShown = false) } },
        onDismiss = { showTurn = false },
        scrollAvailable = !comicNow && fmt.group != FormatGroup.COMIC && !pdfComic,
        continuous = continuousNow || (s.scrollView && !comicNow && fmt.group != FormatGroup.COMIC && !pdfComic),
    )
    if (showLight) {
        val od = openDoc
        val fixedDoc = !reflow && od !is ComicReaderDoc
        // '그림은 반전 안 함'은 글자 있는 PDF·HWP 원본 등에서만(스캔 PDF·DjVu·만화는 숨김, S-26)
        val keepSwitch = fixedDoc && od !is DjvuReaderDoc && !(fmt == DocFormat.PDF && imageOnly != false)
        LightSheet(s, onSettings, fixed = !reflow, keepSwitch = keepSwitch, onLive = { liveLight = it }, onDismiss = { showLight = false })
    }
    if (showTune) ListenTuneSheet(s, onSettings, onOpenEngine = { openTtsSettings(context) }, onDismiss = { showTune = false })
    ttsDialog?.let { c ->
        AlertDialog(
            onDismissRequest = { ttsDialog = null },
            title = { Text(if (c == Listen.Check.NO_ENGINE) "읽어 주기 엔진이 없어요" else "한국어 음성이 없어요") },
            text = {
                Text(
                    if (c == Listen.Check.NO_ENGINE) "기기에 음성 읽기(TTS) 엔진이 없어요. 설정에서 엔진을 고르거나 받을 수 있어요."
                    else "한국어 읽어 주기 음성이 없어요. 설정에서 받을 수 있어요.",
                )
            },
            confirmButton = { TextButton(onClick = { ttsDialog = null; openTtsSettings(context) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("설정 열기") } },
            dismissButton = { TextButton(onClick = { ttsDialog = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") } },
        )
    }
    if (notifAsk) AlertDialog(
        onDismissRequest = { notifAsk = false; notifAsked = true; startListen() },
        text = { Text("화면을 꺼도 계속 들으려면 알림을 허용해 주세요.") },
        confirmButton = {
            TextButton(onClick = {
                notifAsk = false; notifAsked = true
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else startListen()
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("계속") }
        },
        dismissButton = { TextButton(onClick = { notifAsk = false; notifAsked = true; startListen() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("나중에") } },
    )
    if (exportOpen) ExportSheet(bookId = if (target.once) null else target.id) { exportOpen = false }
    val toc by produceState<List<kr.vici.ashreader.doc.TocEntry>?>(null, openDoc, showToc) {
        val d = openDoc
        value = if (d == null) emptyList() else withContext(Dispatchers.IO) { d.toc() }
    }
    val docCount = openDoc?.pageCount ?: 0
    if (showToc) ModalBottomSheet(onDismissRequest = { showToc = false }) {
        SheetBars()
        LazyColumn(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    listOf("목차", "책갈피·메모·형광펜").forEachIndexed { i, label ->
                        Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable { tocTab = i }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(label, fontSize = 15.sp, fontWeight = if (tocTab == i) FontWeight.Bold else FontWeight.Normal, color = if (tocTab == i) colors.accentC else Color.Unspecified)
                            if (tocTab == i) Box(Modifier.padding(top = 4.dp).size(width = 32.dp, height = 2.dp).background(colors.accentC))
                        }
                    }
                }
            }
            if (tocTab == 0) {
                item { TocList(toc, page) { p -> showToc = false; jumpReq = p } }

            } else {
                if (!target.once && (notes.isNotEmpty() || highlights.isNotEmpty())) item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showToc = false; exportOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("내보내기", color = colors.accentC) }
                    }
                }
                if (bookmarks.isEmpty() && notes.isEmpty() && highlights.isEmpty()) item {
                    Text("책갈피·메모·형광펜이 아직 없어요. 글자를 길게 눌러 형광펜을 칠해 보세요.", fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
                }
                if (isHwp && !hwpEbook && highlights.isNotEmpty()) item {
                    Text("형광펜은 eBook 보기에서 칠해져 보여요. 쪽은 원본 보기에 맞춰 대략 옮겼어요.", fontSize = 13.sp, color = colors.accentC, modifier = Modifier.padding(vertical = 8.dp))
                }
                items(bookmarks, key = { "b${it.id}" }) { b ->
                    val bp = locatorPage(b.page, b.frac, docCount)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { showToc = false; jumpReq = bp },
                        verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Bookmark, null, Modifier.size(20.dp)); Text("  ${bp + 1}쪽", fontSize = 15.sp) }
                }
                items(notes, key = { "n${it.id}" }) { n ->
                    val np = locatorPage(n.page, n.frac, docCount)
                    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { showToc = false; jumpReq = np }.padding(vertical = 6.dp)) {
                        Text("${np + 1}쪽 · 메모", fontSize = 12.sp)
                        Text(n.text, fontSize = 15.sp)
                    }
                    HorizontalDivider()
                }
                items(highlights, key = { "h${it.id}" }) { h ->
                    val hp = locatorPage(h.page, h.frac, docCount)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { showToc = false; jumpReq = hp }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(width = 6.dp, height = 36.dp).background(Color(HighlightColors[h.color.coerceIn(0, 3)])))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text("${hp + 1}쪽 · 형광펜", fontSize = 12.sp)
                            Text(h.text, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (h.note.isNotBlank()) Text("메모: ${h.note}", fontSize = 13.sp, color = colors.accentC)
                        }
                        IconButton(onClick = { onRemoveHighlight(h.id) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Close, "형광펜 지우기") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    if (memoOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { memoOpen = false },
            title = { Text("${page + 1}쪽 메모") },
            text = { OutlinedTextField(text, { text = it }, minLines = 3) },
            confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onAddNote(page, text.trim(), locatorFrac(page, docCount)); memoOpen = false }) { Text("저장") } },
            dismissButton = { TextButton(onClick = { memoOpen = false }) { Text("취소") } },
        )
    }
    selMemo?.let { sm ->
        var text by remember(sm) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { selMemo = null },
            title = { Text("${sm.slot.page + 1}쪽 메모") },
            text = {
                Column {
                    Text("“${sm.text.take(80)}”", fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp))
                    OutlinedTextField(text, { text = it }, minLines = 3)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onAddHighlight(sm.slot.page, sm.start, sm.end, sm.text, 0, text.trim(), selFrac(sm, docCount)); selMemo = null
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { selMemo = null }) { Text("취소") } },
        )
    }
}

/** 읽어 주기 엔진·음성 받기 설정(S-27). */
private fun openTtsSettings(context: android.content.Context) {
    for (i in Listen.settingsIntents()) {
        try { context.startActivity(i); return } catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
    }
}

@Composable
private fun TopBar(
    title: String, c: ReadingColors, bookmarked: Boolean, onBack: () -> Unit, onBookmark: () -> Unit, onSearch: () -> Unit,
    modifier: Modifier, viewToggle: String? = null, viewToggleDesc: String = "", onViewToggle: () -> Unit = {},
    rotationLocked: Boolean = false, onRotate: () -> Unit = {},
) {
    Column(modifier.fillMaxWidth().background(c.barC).statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.Back, "뒤로", tint = c.fgC) }
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.fgC, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            // 한글 문서: 원본 ↔ 이북식, 그림 PDF: PDF ↔ 만화 전환 (라벨 = 바꿀 대상)
            if (viewToggle != null) TextButton(onClick = onViewToggle, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = viewToggleDesc }) {
                Text(viewToggle, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.accentC)
            }
            // F-67 회전 잠금
            IconButton(onClick = onRotate, modifier = Modifier.size(48.dp)) {
                Icon(if (rotationLocked) Icons.RotateLock else Icons.Rotate, if (rotationLocked) "회전 잠금 풀기" else "회전 잠그기", tint = if (rotationLocked) c.accentC else c.fgC)
            }
            IconButton(onClick = onBookmark, modifier = Modifier.size(48.dp)) { Icon(Icons.Bookmark, if (bookmarked) "책갈피 빼기" else "책갈피", tint = if (bookmarked) Color(0xFF8C5A2B) else c.fgC) }
            IconButton(onClick = onSearch, modifier = Modifier.size(48.dp)) { Icon(Icons.Search, "검색", tint = c.fgC) }
        }
        HorizontalDivider(color = c.lineC)
    }
}

@Composable
private fun BottomMenu(
    page: Int, count: Int, c: ReadingColors, modifier: Modifier,
    onSeek: (Int) -> Unit, onToc: () -> Unit, onAa: () -> Unit, onTurn: () -> Unit, onListen: () -> Unit, onLight: () -> Unit, onMemo: () -> Unit,
    /** 단추 아래 이름 보이기(설정 '메뉴 버튼 이름 보이기', 작업 06). */
    labels: Boolean = true,
    /** F-60 슬라이더를 끄는 동안 띄울 미리보기(쪽, 0..1 위치). */
    preview: (@Composable (Int, Float) -> Unit)? = null,
    /** F-60 만화 쪽 목록 그리드 열기(만화일 때만, '듣기' 자리). */
    onGrid: (() -> Unit)? = null,
) {
    var drag by remember(page) { mutableFloatStateOf(page.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
    if (dragging && preview != null && count > 1) Box(Modifier.padding(bottom = 8.dp)) { preview(drag.toInt().coerceIn(0, count - 1), drag / (count - 1)) }
    Column(Modifier.fillMaxWidth().background(c.barC).navigationBarsPadding()) {
        HorizontalDivider(color = c.lineC)
        Row(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("1", fontSize = 12.sp, color = c.subC)
            AppleSlider(
                value = drag, onValueChange = { drag = it; dragging = true }, onValueChangeFinished = { dragging = false; onSeek(drag.toInt()) },
                valueRange = 0f..(count - 1).coerceAtLeast(1).toFloat(),
                modifier = Modifier.weight(1f).semantics { contentDescription = "쪽 이동" },
            )
            Text("${drag.toInt() + 1} / ${count}쪽", fontSize = 12.sp, color = c.subC)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 8.dp)) {
            MenuButton("목차", c, labels, Modifier.weight(1f), onToc) { Icon(Icons.Toc, null, Modifier.size(20.dp), tint = c.fgC) }
            MenuButton("보기", c, labels, Modifier.weight(1f), onAa) { Text("Aa", fontFamily = Myeongjo, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.fgC) }
            MenuButton("넘김", c, labels, Modifier.weight(1f), onTurn) { Icon(Icons.Turn, null, Modifier.size(20.dp), tint = c.fgC) }
            if (onGrid != null) MenuButton("쪽 목록", c, labels, Modifier.weight(1f), onGrid) { Icon(Icons.Grid, null, Modifier.size(20.dp), tint = c.fgC) }
            else MenuButton("듣기", c, labels, Modifier.weight(1f), onListen) { Icon(Icons.Speaker, null, Modifier.size(20.dp), tint = c.fgC) }
            MenuButton("밝기", c, labels, Modifier.weight(1f), onLight) { Icon(Icons.Sun, null, Modifier.size(20.dp), tint = c.fgC) }
            MenuButton("메모", c, labels, Modifier.weight(1f), onMemo) { Icon(Icons.Pen, null, Modifier.size(20.dp), tint = c.fgC) }
        }
    }
    }
}

@Composable
private fun MenuButton(label: String, c: ReadingColors, labels: Boolean, modifier: Modifier, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        // 이름을 숨겨도 TalkBack 은 이름을 읽는다
        modifier.heightIn(min = 56.dp).clickable(onClickLabel = label, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = label }.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon()
        if (labels) Text(label, fontSize = 12.sp, color = c.fgC)
    }
}

@Composable
private fun OnceBar(fmt: DocFormat, page: Int, count: Int, c: ReadingColors, ebook: Boolean, onAdd: () -> Unit, onEbookView: () -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(horizontal = 16.dp).fillMaxWidth().background(c.barC, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("한 번만 보는 중이에요", fontSize = 14.sp, color = c.fgC, modifier = Modifier.weight(1f))
            TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) { Text("서재에 추가", color = c.accentC, fontWeight = FontWeight.Bold) }
        }
        if (fmt.group == FormatGroup.HWP || fmt.group == FormatGroup.WORD) {
            Text(
                if (ebook) "eBook 보기 — 글자 크기·줄 간격·테마를 바꾸고 글자를 고를 수 있어요"
                else if (fmt.group == FormatGroup.WORD) "원본 보기 — 쪽·표·머리글까지 Word 문서 모양 그대로 표시"
                else "원본 보기 — 표·서식·도장까지 한글 문서 그대로 표시",
                fontSize = 12.sp, color = c.subC,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${page + 1} / ${count}쪽", fontSize = 12.sp, color = c.subC, modifier = Modifier.weight(1f))
                Text(if (ebook) "eBook" else "원본", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.accentC, modifier = Modifier.padding(horizontal = 8.dp))
                TextButton(onClick = onEbookView, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (ebook) "원본으로 보기" else "eBook으로 보기", color = c.fgC) }
            }
        }
    }
}

/** 화면 x(px)가 가리키는 쪽 칸. 두 쪽 펼침이면 왼쪽/오른쪽 반([coverAlone] = 첫 펼침이 표지 단독). */
private fun slotAt(x: Float, w: Float, h: Float, first: Int, spread: Boolean, rtl: Boolean, coverAlone: Boolean): PageSlot {
    if (!spread) return PageSlot(first, 0f, 0f, w, h)
    val leftHalf = x < w / 2f
    val leftIdx = if (first == 0 && coverAlone) -1 else first // 표지 단독: 첫 펼침 = 빈 왼쪽 + 0쪽
    val idx = if (leftHalf != rtl) leftIdx else leftIdx + 1
    return PageSlot(idx, if (leftHalf) 0f else w / 2f, 0f, w / 2f, h)
}

/** 고른 글자의 보기 방식과 무관한 위치(문서 비율): 쪽 + 쪽 안 글자 위치. */
private fun selFrac(sel: Selection, count: Int): Float =
    locatorFrac(sel.slot.page, count, if (sel.tp.text.isEmpty()) 0.5f else sel.start.toFloat() / sel.tp.text.length)

private fun noTextMsg(fmt: DocFormat): String = when (fmt.group) {
    FormatGroup.COMIC -> "만화는 그림이라 글자를 고를 수 없어요."
    else -> "이 쪽에는 고를 수 있는 글자가 없어요"
}

/** 암호 걸린 Word·PDF 문서 암호 입력(S-42). 틀리면 다시 묻는다. 입력값은 저장하지 않는다. */
@Composable
private fun PasswordDialog(wrong: Boolean, pdf: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember(wrong) { mutableStateOf("") }
    val submit = { if (text.isNotEmpty()) { val t = text; text = ""; onSubmit(t) } }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("암호가 걸린 문서") },
        text = {
            Column {
                Text(
                    if (wrong) "암호가 맞지 않아요. 다시 입력해 주세요." else "이 ${if (pdf) "PDF" else "Word"} 문서를 열려면 암호를 입력하세요. 암호는 저장하지 않아요.",
                    fontSize = 14.sp, color = if (wrong) MaterialTheme.colorScheme.error else Color.Unspecified,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true, label = { Text("암호") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { submit() }, enabled = text.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("열기") } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("서재로") } },
    )
}
