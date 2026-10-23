// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.BatteryManager
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.Bookmark
import kr.vici.ashreader.data.locatorFrac
import kr.vici.ashreader.data.locatorPage
import kr.vici.ashreader.data.Highlight
import kr.vici.ashreader.doc.TextGeo
import kr.vici.ashreader.doc.TextPage
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.FormatGroup
import kr.vici.ashreader.data.Note
import kr.vici.ashreader.data.PanelMode
import kr.vici.ashreader.data.ReadTheme
import kr.vici.ashreader.data.Settings
import kr.vici.ashreader.doc.ComicReaderDoc
import kr.vici.ashreader.doc.ComicUpscale
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
    private val marks: (page: Int, tp: () -> TextPage?) -> List<Mark> = { _, _ -> emptyList() },
) : PageSource {
    override val pageCount: Int = doc.pageCount
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

/** S-20·S-21 읽기 화면 (+ S-40 한 번만 보기, 태블릿 두 쪽 펼침, 만화 칸 단위 보기). */
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
    val colors = s.theme.colors()
    var menu by remember { mutableStateOf(false) }
    // 필수 2: 회전·복원에도 읽던 쪽 유지
    var page by rememberSaveable(target.id) { mutableIntStateOf(target.startPage) }
    var showAa by remember { mutableStateOf(false) }
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
    var hit by remember { mutableStateOf<Hit?>(null) }
    var jumpReq by remember { mutableStateOf<Int?>(null) }
    var tocTab by remember { mutableIntStateOf(0) }
    var openDoc by remember { mutableStateOf<ReaderDoc?>(null) }
    var exportOpen by remember { mutableStateOf(false) }
    val fmt = target.format
    /** TXT 인코딩(F-32): "" = 자동. 바꾸면 문서를 다시 연다. */
    var txtEnc by rememberSaveable(target.id) { mutableStateOf(target.textEncoding) }
    // 한글 문서 보기 방식(F-28): false = 원본(쪽 그림), true = 이북식(다시 배치 — 글꼴·크기·테마·글자 선택·검색·목차)
    val isHwp = fmt.group == FormatGroup.HWP
    // Word(DOCX·DOC·RTF)도 원본(쪽 모양, F-30)/이북식 두 보기. 보기 상태는 hwpEbook 을 같이 쓴다.
    val isWord = fmt.group == FormatGroup.WORD
    val dualView = isHwp || isWord
    var hwpEbook by rememberSaveable(target.id) { mutableStateOf(target.viewMode == Book.VIEW_EBOOK) }
    /** 그림만 든 PDF 를 만화 보기로 연 상태. */
    var pdfComic by rememberSaveable(target.id) { mutableStateOf(fmt == DocFormat.PDF && target.viewMode == Book.VIEW_COMIC) }
    /** 그림만 든 PDF 로 판정됨('만화로 보기' 제안). null = 아직 모름. */
    var imageOnly by rememberSaveable(target.id) { mutableStateOf<Boolean?>(null) }
    var comicOfferClosed by rememberSaveable(target.id) { mutableStateOf(false) }
    /** 첫 문서를 연 뒤 할 일(저장된 쪽 수 비교·F-28 자동 전환 검사)을 한 번만 하기 위한 표시. */
    var firstLoadDone by rememberSaveable(target.id) { mutableStateOf(false) }
    /** [page] 를 정할 때의 쪽 수. 회전 등으로 다시 배치돼 쪽 수가 바뀌면 비율로 옮긴다. */
    var pageCountSeen by rememberSaveable(target.id) { mutableIntStateOf(0) }
    val reflow = if (isWord) hwpEbook else fmt.reflowable || (isHwp && hwpEbook)
    /** 보기 방식을 바꾼 뒤 새 문서에서 이어 볼 위치(읽은 비율). */
    var pendingFrac by remember { mutableStateOf<Float?>(null) }
    /** 암호 걸린 Word·PDF 의 암호. 메모리에만 두고 저장하지 않는다. */
    var password by remember(target.id) { mutableStateOf(passwords[target.id]) }
    var pwTry by remember(target.id) { mutableIntStateOf(0) }

    fun stub(msg: String) { scope.launch { snack.showSnackbar(msg) } }

    BackHandler(enabled = menu || sel != null || hit != null) {
        when { sel != null -> sel = null; menu -> menu = false; else -> hit = null }
    }

    // 독서 기록(S-50)
    val sessionPages by rememberUpdatedState(pagesTurned)
    DisposableEffect(target.id) {
        val start = System.currentTimeMillis()
        onDispose { onSession(start, System.currentTimeMillis() - start, sessionPages) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.bgC)) {
        val wDp = maxWidth.value
        val hDp = maxHeight.value
        val spread = wDp > hDp && minOf(wDp, hDp) >= 600f
        val pageWdp = if (spread) wDp / 2f else wDp
        val layoutKey = if (reflow) listOf(s.font, s.fontSize, s.lineHeight, pageWdp.toInt(), hDp.toInt(), hwpEbook, txtEnc) else listOf(spread, hwpEbook, pdfComic)

        val load by produceState<Load>(Load.Loading, target.path, layoutKey, pwTry) {
            value = Load.Loading
            val pw = password
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    DocOpener.open(
                        context, File(target.path), fmt, pageWdp, hDp, s, hwpEbook = dualView && hwpEbook, password = pw, docId = target.id, comic = pdfComic,
                        textEncoding = TextEncoding.Kind.of(txtEnc),
                    )
                }
            }
            value = r.fold({ Load.Ok(it) }, { e ->
                when (e) {
                    is PasswordNeeded -> Load.Locked(e.wrong)
                    is DrmProtected -> Load.Err(e.message.orEmpty(), drm = true)
                    is OutOfMemoryError -> Load.Err("문서가 너무 커서 열 수 없어요.")
                    else -> Load.Err(e.message ?: "문서를 열 수 없어요.")
                }
            })
            awaitDispose { r.getOrNull()?.let { d -> Thread { GuardedSource.close(d) }.start() } }
        }

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
                    Button(onClick = { hwpEbook = true; onViewMode(Book.VIEW_EBOOK) }, modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) { Text("이북식 보기로") }
                }
                Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) { Text("서재로") }
            }
            is Load.Locked -> PasswordDialog(wrong = l.wrong, pdf = fmt == DocFormat.PDF, onSubmit = { password = it; passwords[target.id] = it; pwTry++ }, onCancel = onBack)
            is Load.Ok -> {
                val doc = l.doc
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
                        // 회전·글꼴 변경으로 다시 배치돼 쪽 수가 바뀜 → 읽던 비율로 옮긴다
                        jumpReq = locatorPage(page, locatorFrac(page, pageCountSeen), n)
                    }
                    if (n > 0) pageCountSeen = n
                    // 그림만 든 PDF 인지 한 번 본다('만화로 보기' 제안)
                    if (doc is MupdfReaderDoc && fmt == DocFormat.PDF && imageOnly == null) {
                        imageOnly = withContext(Dispatchers.IO) { doc.isImageOnly() }
                    }
                    // F-28: 원본 첫 쪽이 비어 보이거나 렌더에 실패하면 이북식으로 자동 전환(처음 열 때 한 번만)
                    if (first && doc is HwpReaderDoc) {
                        val failed = withContext(Dispatchers.IO) {
                            synchronized(GuardedSource.guard) { !GuardedSource.closed.contains(doc) && doc.firstPageFailed() }
                        }
                        if (failed) {
                            pendingFrac = if (n > 0) (page + 0.5f) / n else 0f
                            hwpEbook = true
                            onViewMode(Book.VIEW_EBOOK)
                            stub("원본 보기가 비어 보여 이북식으로 열었어요.") // 문서가 바뀌며 이 효과가 취소되므로 바깥 범위에서 띄운다
                        }
                    }
                }
                val pxW = constraints.maxWidth.toFloat()
                val pxH = constraints.maxHeight.toFloat()
                val count = doc.pageCount
                LaunchedEffect(page, count) {
                    if (count > 0) {
                        delay(400)
                        onProgress(page.coerceIn(0, count - 1), count)
                        if (page >= count - 1) onFinished()
                    }
                }
                val panelMode = doc is ComicReaderDoc && s.panelMode != PanelMode.OFF
                // AI 화질 개선(F-73): 만화 + 설정 켬일 때만. 원본을 먼저 보여 주고 2배 결과로 바꿔 낀다.
                val upscale = remember(doc, s.aiUpscale) { if (s.aiUpscale && doc is ComicReaderDoc) ComicUpscale(context, doc) else null }
                DisposableEffect(upscale) {
                    val cd = doc as? ComicReaderDoc
                    cd?.upscale = upscale
                    onDispose {
                        if (cd != null && cd.upscale === upscale) cd.upscale = null
                        upscale?.close()
                    }
                }
                if (panelMode) {
                    ComicPanelReader(
                        doc = doc as ComicReaderDoc, startPage = page, autoDefault = s.panelMode == PanelMode.AUTO, colors = colors,
                        menuVisible = menu, onToggleMenu = { menu = !menu },
                        onPage = { if (it != page) { page = it; pagesTurned++ } },
                        turnRequest = turnReq, turnForward = turnFwd,
                        upscale = upscale,
                        jumpRequest = jumpReq, onJumpHandled = { jumpReq = null },
                    )
                } else {
                    val near = if (reflow || isWord) 3 else 0 // Word 원본↔이북식은 쪽 수가 달라 근처 쪽까지 찾는다
                    val source = remember(doc, s.theme, highlights, hit) {
                        val hl = highlights; val h = hit
                        GuardedSource(doc, colors) { i, tpOf ->
                            // 쪽 수가 바뀌어도(이북식 글꼴·크기) 위치 비율로 근처 쪽을 찾은 뒤 글자로 다시 맞춘다
                            val cand = hl.filter { kotlin.math.abs(locatorPage(it.page, it.frac, count) - i) <= near }
                            val tp = if (cand.isEmpty() && h?.page != i) null else tpOf()
                            if (tp == null) emptyList<Mark>()
                            else {
                                cand.mapNotNull { x ->
                                    val r = TextGeo.resolve(tp.text, x.startIdx, x.endIdx, x.text) ?: return@mapNotNull null
                                    Mark(r.first, r.last + 1, HighlightColors[x.color.coerceIn(0, 3)])
                                } + listOfNotNull(h?.takeIf { it.page == i }?.let { searchMark(it) })
                            }
                        }
                    }
                    LaunchedEffect(jumpReq, source) {
                        val j = jumpReq ?: return@LaunchedEffect
                        page = j.coerceIn(0, (count - 1).coerceAtLeast(0))
                        curlView?.let { it.tag = source; it.setPageSource(source, page) }
                        jumpReq = null
                    }
                    val cfg = CurlConfig(
                        direction = if (doc.rtl) TurnDirection.RTL else TurnDirection.LTR,
                        effect = if (s.curl) TurnEffect.CURL else TurnEffect.SLIDE,
                        spread = SpreadMode.AUTO,
                        backColor = colors.bg,
                        shadowStrength = colors.shadow,
                    )
                    val rtl = doc.rtl
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
                                val slot = slotAt(xf * pxW, pxW, pxH, page, spreadNow, rtl)
                                scope.launch {
                                    val tp = withContext(Dispatchers.IO) { if (slot.page in 0 until count) doc.pageText(slot.page) else null }
                                    if (tp == null || tp.text.isBlank()) { stub(noTextMsg(fmt)); return@launch }
                                    val b = slot.content(tp)
                                    val i = TextGeo.charAt(tp, (xf * pxW - b.l) / (b.r - b.l), (yf * pxH - b.t) / (b.b - b.t))
                                    if (i < 0) return@launch
                                    val w = TextGeo.wordAt(tp.text, i)
                                    sel = Selection(slot, tp, w.first, w.last + 1)
                                }
                            }
                            v.onTap = { x, _ ->
                                when {
                                    sel != null -> sel = null
                                    menu -> menu = false
                                    x < 0.3f -> v.turn(next = rtl)
                                    x > 0.7f -> v.turn(next = !rtl)
                                    else -> menu = true
                                }
                            }
                            v.setConfig(cfg)
                            if (v.tag !== source) { v.tag = source; v.setPageSource(source, page) }
                        },
                        onRelease = { it.release() },
                        modifier = Modifier.fillMaxSize().semantics { contentDescription = "${target.title} ${page + 1}쪽" },
                    )
                    LaunchedEffect(turnReq) { if (turnReq > 0) curlView?.turn(turnFwd) }
                    if (upscale != null && doc is ComicReaderDoc) {
                        val readySet by upscale.ready.collectAsState()
                        LaunchedEffect(upscale, page, pxW, pxH) { upscale.request(page, pxW.toInt(), pxH.toInt()) }
                        // 보이는 쪽의 2배 결과가 나왔는데 넘김 화면이 원본으로 그렸으면 다시 그린다
                        LaunchedEffect(readySet, page, source) {
                            val vis = if (spreadNow) listOf(page, page + 1) else listOf(page)
                            if (vis.any { it in readySet && !doc.shownUpscaled(it) }) {
                                delay(300)
                                curlView?.let { v -> v.tag = source; v.setPageSource(source, page) }
                            }
                        }
                        if (!menu) UpscaleChip(upscale, page, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 8.dp, end = 12.dp))
                    }
                }

                // 볼륨 키 (화면 설계서 4.5): 메뉴·시트가 열려 있으면 시스템 음량
                val volOn = s.volumeKeys && !menu && !showAa && !showToc && sel == null && !searchOpen
                DisposableEffect(volOn) {
                    val h: (Boolean) -> Boolean = { next -> turnFwd = next; turnReq++; true }
                    VolumeKeys.handler = if (volOn) h else null
                    onDispose { VolumeKeys.handler = null }
                }

                // 정보줄 (메뉴 숨김일 때)
                if (!menu && !panelMode) InfoLine(page, count, colors, Modifier.align(Alignment.BottomCenter))

                // S-40 한 번만 보기 띠
                fun toggleHwpView() {
                    pendingFrac = if (count > 0) (page + 0.5f) / count else 0f
                    menu = false; sel = null; hit = null
                    hwpEbook = !hwpEbook
                    onViewMode(if (hwpEbook) Book.VIEW_EBOOK else Book.VIEW_ORIGINAL)
                }
                // 그림만 든 PDF ↔ 만화 보기(칸 단위·AI 화질)
                fun togglePdfComic() {
                    menu = false; sel = null; hit = null
                    comicOfferClosed = true
                    pdfComic = !pdfComic
                    onViewMode(if (pdfComic) Book.VIEW_COMIC else Book.VIEW_ORIGINAL)
                }
                val pdfToggle = fmt == DocFormat.PDF && (pdfComic || imageOnly == true)
                if (fmt == DocFormat.PDF && imageOnly == true && !pdfComic && !comicOfferClosed && !menu && hit == null) {
                    Row(
                        Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp, start = 12.dp, end = 12.dp)
                            .background(colors.barC, RoundedCornerShape(24.dp)).padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("그림으로 된 PDF예요", fontSize = 14.sp, color = colors.fgC)
                        TextButton(onClick = { togglePdfComic() }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("만화로 보기", color = colors.accentC, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { comicOfferClosed = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Close, "안내 닫기", tint = colors.fgC) }
                    }
                }
                val curBookmark = bookmarks.firstOrNull { locatorPage(it.page, it.frac, count) == page }
                if (target.once) OnceBar(fmt, page, count, colors, ebook = hwpEbook, onAdd = onAddToLibrary, onEbookView = { toggleHwpView() },
                    modifier = Modifier.align(if (menu) Alignment.Center else Alignment.BottomCenter).padding(bottom = if (menu) 0.dp else 28.dp))

                if (menu) {
                    TopBar(target.title, colors, bookmarked = curBookmark != null, onBack = onBack,
                        onBookmark = { onToggleBookmark(page, locatorFrac(page, count), curBookmark?.id) },
                        onSearch = { if (doc is MupdfReaderDoc) { menu = false; searchOpen = true } else stub(noTextMsg(fmt)) },
                        viewToggle = when {
                            dualView && !target.once -> if (hwpEbook) "원본" else "이북식"
                            pdfToggle -> if (pdfComic) "PDF" else "만화"
                            else -> null
                        },
                        viewToggleDesc = when {
                            dualView -> if (hwpEbook) "원본으로 보기" else "이북식으로 보기"
                            else -> if (pdfComic) "PDF 쪽 보기로" else "만화로 보기"
                        },
                        onViewToggle = { if (dualView) toggleHwpView() else togglePdfComic() },
                        modifier = Modifier.align(Alignment.TopCenter))
                    BottomMenu(
                        page, count, colors, s.theme, Modifier.align(Alignment.BottomCenter),
                        onSeek = { p -> jumpReq = p },
                        onToc = { showToc = true },
                        onAa = { showAa = true },
                        onTheme = { onSettings { it.copy(theme = ReadTheme.entries[(it.theme.ordinal + 1) % 3]) } },
                        onListen = { speak(context, doc, page) { stub(it) } },
                        onHighlight = { menu = false; stub(if (doc is MupdfReaderDoc) "칠할 글자를 길게 눌러 고른 뒤 ‘형광펜’을 누르세요." else noTextMsg(fmt)) },
                        onMemo = { memoOpen = true },
                    )
                }

                hit?.let { h ->
                    if (!menu && !searchOpen) Row(
                        Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)
                            .background(colors.barC, RoundedCornerShape(24.dp)).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { searchOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("검색 결과로", color = colors.accentC) }
                        IconButton(onClick = { hit = null }, modifier = Modifier.size(48.dp)) { Icon(Icons.Close, "검색 표시 지우기", tint = colors.fgC) }
                    }
                }

                sel?.let { cur ->
                    SelectionLayer(
                        sel = cur, c = colors,
                        onChange = { sel = it },
                        onDismiss = { sel = null },
                        onCopy = { copyText(context, cur.text); sel = null; stub("복사했어요.") },
                        onDict = { lookUp(context, cur.text) { stub(it) }; sel = null },
                        onHighlight = { color ->
                            if (target.once) stub("서재에 추가하면 형광펜을 남길 수 있어요.")
                            else onAddHighlight(cur.slot.page, cur.start, cur.end, cur.text, color, "", selFrac(cur, count))
                            sel = null
                        },
                        onMemo = { if (target.once) stub("서재에 추가하면 메모를 남길 수 있어요.") else selMemo = cur; sel = null },
                    )
                }

                if (searchOpen) SearchPanel(doc, colors, onClose = { searchOpen = false }) { h ->
                    searchOpen = false; hit = h; jumpReq = h.page
                }

                if (!s.tapGuideShown && !target.once) TapGuide(rtl = doc.rtl, panelMode = panelMode) { onSettings { it.copy(tapGuideShown = true) } }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp))
    }

    val txtUi = if (fmt == DocFormat.TXT) TxtEncodingUi(TextEncoding.Kind.of(txtEnc), (openDoc as? MupdfReaderDoc)?.textKind) { k ->
        txtEnc = k?.name.orEmpty()
        if (!target.once) onTextEncoding(txtEnc)
    } else null
    if (showAa) ViewSettingsSheet(s, reflow, onSettings, onDismiss = { showAa = false }, txt = txtUi)
    if (exportOpen) ExportSheet(bookId = if (target.once) null else target.id) { exportOpen = false }
    val toc by produceState<List<kr.vici.ashreader.doc.TocEntry>?>(null, openDoc, showToc) {
        val d = openDoc
        value = if (d == null) emptyList() else withContext(Dispatchers.IO) { d.toc() }
    }
    val docCount = openDoc?.pageCount ?: 0
    if (showToc) ModalBottomSheet(onDismissRequest = { showToc = false }) {
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
                    Text("형광펜은 이북식 보기에서 칠해져 보여요. 쪽은 원본 보기에 맞춰 대략 옮겼어요.", fontSize = 13.sp, color = colors.accentC, modifier = Modifier.padding(vertical = 8.dp))
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
    DisposableEffect(Unit) { onDispose { Tts.stop() } }
}

@Composable
private fun InfoLine(page: Int, count: Int, c: ReadingColors, modifier: Modifier) {
    val context = LocalContext.current
    val now by produceState(clockText(context)) { while (true) { delay(30_000); value = clockText(context) } }
    val pct = if (count > 0) ((page + 1) * 100 / count) else 0
    Row(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(now, fontSize = 12.sp, color = c.subC, modifier = Modifier.weight(1f))
        Text("${page + 1} / ${count}쪽 · $pct%", fontSize = 12.sp, color = c.subC)
    }
}

private fun clockText(context: Context): String {
    val time = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date())
    val bat = (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    return if (bat in 0..100) "$time · 배터리 $bat%" else time
}

@Composable
private fun TopBar(
    title: String, c: ReadingColors, bookmarked: Boolean, onBack: () -> Unit, onBookmark: () -> Unit, onSearch: () -> Unit,
    modifier: Modifier, viewToggle: String? = null, viewToggleDesc: String = "", onViewToggle: () -> Unit = {},
) {
    Column(modifier.fillMaxWidth().background(c.barC).statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.Back, "뒤로", tint = c.fgC) }
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.fgC, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            // 한글 문서: 원본 ↔ 이북식, 그림 PDF: PDF ↔ 만화 전환 (라벨 = 바꿀 대상)
            if (viewToggle != null) TextButton(onClick = onViewToggle, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = viewToggleDesc }) {
                Text(viewToggle, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.accentC)
            }
            IconButton(onClick = onBookmark, modifier = Modifier.size(48.dp)) { Icon(Icons.Bookmark, if (bookmarked) "책갈피 빼기" else "책갈피", tint = if (bookmarked) Color(0xFF8C5A2B) else c.fgC) }
            IconButton(onClick = onSearch, modifier = Modifier.size(48.dp)) { Icon(Icons.Search, "검색", tint = c.fgC) }
        }
        HorizontalDivider(color = c.lineC)
    }
}

@Composable
private fun BottomMenu(
    page: Int, count: Int, c: ReadingColors, theme: ReadTheme, modifier: Modifier,
    onSeek: (Int) -> Unit, onToc: () -> Unit, onAa: () -> Unit, onTheme: () -> Unit, onListen: () -> Unit, onHighlight: () -> Unit, onMemo: () -> Unit,
) {
    var drag by remember(page) { mutableFloatStateOf(page.toFloat()) }
    Column(modifier.fillMaxWidth().background(c.barC).navigationBarsPadding()) {
        HorizontalDivider(color = c.lineC)
        Row(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("1", fontSize = 12.sp, color = c.subC)
            Slider(
                value = drag, onValueChange = { drag = it }, onValueChangeFinished = { onSeek(drag.toInt()) },
                valueRange = 0f..(count - 1).coerceAtLeast(1).toFloat(),
                colors = SliderDefaults.colors(thumbColor = c.accentC, activeTrackColor = c.accentC, inactiveTrackColor = c.lineC),
                modifier = Modifier.weight(1f).semantics { contentDescription = "쪽 이동" },
            )
            Text("${drag.toInt() + 1} / ${count}쪽", fontSize = 12.sp, color = c.subC)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 8.dp)) {
            MenuButton("목차", c, Modifier.weight(1f), onToc) { Icon(Icons.Toc, null, Modifier.size(20.dp), tint = c.fgC) }
            MenuButton("보기", c, Modifier.weight(1f), onAa) { Text("Aa", fontFamily = Myeongjo, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.fgC) }
            MenuButton(theme.label, c, Modifier.weight(1f), onTheme) { Icon(Icons.Moon, null, Modifier.size(20.dp), tint = c.fgC) }
            MenuButton("듣기", c, Modifier.weight(1f), onListen) { Icon(Icons.Speaker, null, Modifier.size(20.dp), tint = c.fgC) }
            MenuButton("형광펜", c, Modifier.weight(1f), onHighlight) { Icon(Icons.Highlight, null, Modifier.size(20.dp), tint = c.fgC) }
            MenuButton("메모", c, Modifier.weight(1f), onMemo) { Icon(Icons.Pen, null, Modifier.size(20.dp), tint = c.fgC) }
        }
    }
}

@Composable
private fun MenuButton(label: String, c: ReadingColors, modifier: Modifier, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        modifier.heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon()
        Text(label, fontSize = 12.sp, color = c.fgC)
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
                if (ebook) "이북식 보기 — 글자 크기·줄 간격·테마를 바꾸고 글자를 고를 수 있어요"
                else if (fmt.group == FormatGroup.WORD) "원본 보기 — 쪽·표·머리글까지 Word 문서 모양 그대로 표시"
                else "원본 보기 — 표·서식·도장까지 한글 문서 그대로 표시",
                fontSize = 12.sp, color = c.subC,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${page + 1} / ${count}쪽", fontSize = 12.sp, color = c.subC, modifier = Modifier.weight(1f))
                Text(if (ebook) "이북식" else "원본", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.accentC, modifier = Modifier.padding(horizontal = 8.dp))
                TextButton(onClick = onEbookView, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (ebook) "원본으로 보기" else "이북식으로 보기", color = c.fgC) }
            }
        }
    }
}

/** 듣기(S-27) 최소판: 지금 쪽 글자를 기기 TTS 로 읽는다. TODO: 쪽 이어 읽기·속도·TTS 바. */
private object Tts {
    private var tts: TextToSpeech? = null
    fun stop() { tts?.stop() }
    fun say(context: Context, text: String, onError: (String) -> Unit) {
        val t = tts
        if (t != null) { if (t.isSpeaking) t.stop() else t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ash"); return }
        tts = TextToSpeech(context.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) { tts?.language = Locale.KOREAN; tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ash") }
            else onError("기기에 음성 읽기(TTS) 엔진이 없어요.")
        }
    }
}

private fun speak(context: Context, doc: ReaderDoc, page: Int, onError: (String) -> Unit) {
    val lines = doc.pageLines(page)
    if (lines.isEmpty()) { onError("이 쪽에는 읽을 글자가 없어요."); return }
    Tts.say(context, lines.joinToString(" "), onError)
}

/** 화면 x(px)가 가리키는 쪽 칸. 두 쪽 펼침이면 왼쪽/오른쪽 반(표지 단독 배치 기준). */
private fun slotAt(x: Float, w: Float, h: Float, first: Int, spread: Boolean, rtl: Boolean): PageSlot {
    if (!spread) return PageSlot(first, 0f, 0f, w, h)
    val leftHalf = x < w / 2f
    val leftIdx = if (first == 0) -1 else first // 표지 단독: 첫 펼침 = 빈 왼쪽 + 0쪽
    val idx = if (leftHalf != rtl) leftIdx else leftIdx + 1
    return PageSlot(idx, if (leftHalf) 0f else w / 2f, 0f, w / 2f, h)
}

/** 고른 글자의 보기 방식과 무관한 위치(문서 비율): 쪽 + 쪽 안 글자 위치. */
private fun selFrac(sel: Selection, count: Int): Float =
    locatorFrac(sel.slot.page, count, if (sel.tp.text.isEmpty()) 0.5f else sel.start.toFloat() / sel.tp.text.length)

private fun noTextMsg(fmt: DocFormat): String = when (fmt.group) {
    FormatGroup.HWP -> "원본 보기에서는 글자를 고를 수 없어요. ‘이북식’으로 바꾸면 글자 선택·검색·형광펜을 쓸 수 있어요."
    FormatGroup.COMIC -> "만화는 그림이라 글자를 고를 수 없어요."
    else -> "이 쪽에는 고를 수 있는 글자가 없어요. (그림으로 된 PDF일 수 있어요)"
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
                    fontSize = 14.sp, color = if (wrong) Color(0xFFB3261E) else Color.Unspecified,
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
