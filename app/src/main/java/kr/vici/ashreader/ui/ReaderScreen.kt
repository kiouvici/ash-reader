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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import kr.vici.ashreader.data.Bookmark
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.FormatGroup
import kr.vici.ashreader.data.Note
import kr.vici.ashreader.data.PanelMode
import kr.vici.ashreader.data.ReadTheme
import kr.vici.ashreader.data.Settings
import kr.vici.ashreader.doc.ComicReaderDoc
import kr.vici.ashreader.doc.DocOpener
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
)

/** 볼륨 키 → 읽기 화면 (MainActivity 가 연결). 반환 true = 키를 소비. */
object VolumeKeys {
    @Volatile var handler: ((next: Boolean) -> Boolean)? = null
}

private sealed interface Load {
    data object Loading : Load
    data class Ok(val doc: ReaderDoc) : Load
    data class Err(val msg: String) : Load
}

/** 닫힌 문서에 렌더 요청이 와도 죽지 않게 감싼다. */
private class GuardedSource(private val doc: ReaderDoc, private val colors: ReadingColors) : PageSource {
    override val pageCount: Int = doc.pageCount
    override fun render(index: Int, width: Int, height: Int): Bitmap = synchronized(guard) {
        if (closed.contains(doc)) Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(colors.bg) }
        else runCatching { doc.render(index, width, height, colors) }.getOrElse {
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
    onToggleBookmark: (page: Int) -> Unit,
    onAddNote: (page: Int, text: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val colors = s.theme.colors()
    var menu by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(target.startPage) }
    var showAa by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var memoOpen by remember { mutableStateOf(false) }
    var turnReq by remember { mutableIntStateOf(0) }
    var turnFwd by remember { mutableStateOf(true) }
    var curlView by remember { mutableStateOf<PageCurlView?>(null) }
    var pagesTurned by remember { mutableIntStateOf(0) }
    val fmt = target.format

    fun stub(msg: String) { scope.launch { snack.showSnackbar(msg) } }

    BackHandler(enabled = menu) { menu = false }

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
        val layoutKey = if (fmt.reflowable) listOf(s.font, s.fontSize, s.lineHeight, pageWdp.toInt(), hDp.toInt()) else listOf(spread)

        val load by produceState<Load>(Load.Loading, target.path, layoutKey) {
            value = Load.Loading
            val r = withContext(Dispatchers.IO) {
                runCatching { DocOpener.open(context, File(target.path), fmt, pageWdp, hDp, s) }
            }
            value = r.fold({ Load.Ok(it) }, { Load.Err(it.message ?: "문서를 열 수 없어요.") })
            awaitDispose { r.getOrNull()?.let { d -> Thread { GuardedSource.close(d) }.start() } }
        }

        when (val l = load) {
            Load.Loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = colors.accentC)
                Text("여는 중…", color = colors.subC, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
            }
            is Load.Err -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(l.msg, color = colors.fgC, fontSize = 16.sp)
                Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) { Text("서재로") }
            }
            is Load.Ok -> {
                val doc = l.doc
                val count = doc.pageCount
                LaunchedEffect(page, count) {
                    if (count > 0) {
                        delay(400)
                        onProgress(page.coerceIn(0, count - 1), count)
                        if (page >= count - 1) onFinished()
                    }
                }
                val panelMode = doc is ComicReaderDoc && s.panelMode != PanelMode.OFF
                if (panelMode) {
                    ComicPanelReader(
                        doc = doc as ComicReaderDoc, startPage = page, autoDefault = s.panelMode == PanelMode.AUTO, colors = colors,
                        menuVisible = menu, onToggleMenu = { menu = !menu },
                        onPage = { if (it != page) { page = it; pagesTurned++ } },
                        turnRequest = turnReq, turnForward = turnFwd,
                    )
                } else {
                    val source = remember(doc, s.theme) { GuardedSource(doc, colors) }
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
                                v.onPageChanged = { first, _ -> if (first != page) { page = first; pagesTurned++ } }
                            }
                        },
                        update = { v ->
                            v.onTap = { x, _ ->
                                when {
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
                }

                // 볼륨 키 (화면 설계서 4.5): 메뉴·시트가 열려 있으면 시스템 음량
                val volOn = s.volumeKeys && !menu && !showAa && !showToc
                DisposableEffect(volOn) {
                    VolumeKeys.handler = if (volOn) { next -> turnFwd = next; turnReq++; true } else null
                    onDispose { VolumeKeys.handler = null }
                }

                // 정보줄 (메뉴 숨김일 때)
                if (!menu && !panelMode) InfoLine(page, count, colors, Modifier.align(Alignment.BottomCenter))

                // S-40 한 번만 보기 띠
                if (target.once) OnceBar(fmt, page, count, colors, onAdd = onAddToLibrary, onEbookView = { stub("이북식으로 보기는 다음 판에서 열려요.") },
                    modifier = Modifier.align(if (menu) Alignment.Center else Alignment.BottomCenter).padding(bottom = if (menu) 0.dp else 28.dp))

                if (menu) {
                    TopBar(target.title, colors, bookmarked = bookmarks.any { it.page == page }, onBack = onBack,
                        onBookmark = { onToggleBookmark(page) }, onSearch = { stub("본문 검색은 다음 판에서 열려요.") },
                        modifier = Modifier.align(Alignment.TopCenter))
                    BottomMenu(
                        page, count, colors, s.theme, Modifier.align(Alignment.BottomCenter),
                        onSeek = { p -> page = p; curlView?.setPageSource(GuardedSource(doc, colors).also { curlView?.tag = it }, p) },
                        onToc = { showToc = true },
                        onAa = { showAa = true },
                        onTheme = { onSettings { it.copy(theme = ReadTheme.entries[(it.theme.ordinal + 1) % 3]) } },
                        onListen = { speak(context, doc, page) { stub(it) } },
                        onHighlight = { stub("글자를 길게 눌러 형광펜 칠하기는 다음 판에서 열려요.") },
                        onMemo = { memoOpen = true },
                    )
                }

                if (!s.tapGuideShown && !target.once) TapGuide(rtl = doc.rtl, panelMode = panelMode) { onSettings { it.copy(tapGuideShown = true) } }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp))
    }

    if (showAa) ViewSettingsSheet(s, fmt.reflowable, onSettings) { showAa = false }
    if (showToc) ModalBottomSheet(onDismissRequest = { showToc = false }) {
        LazyColumn(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            item { Text("목차 · 책갈피 · 메모", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp)) }
            // TODO(S-22): 문서 목차(outline) — 엔진 API(MupdfDoc 목차) 추가 후 연결
            item { Text("목차는 다음 판에서 보여 줘요.", fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) }
            items(bookmarks, key = { "b${it.id}" }) { b ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                    showToc = false; page = b.page; curlView?.setPageSource(curlView?.tag as? PageSource ?: return@clickable, b.page)
                }, verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Bookmark, null, Modifier.size(20.dp)); Text("  ${b.page + 1}쪽", fontSize = 15.sp) }
            }
            items(notes, key = { "n${it.id}" }) { n ->
                Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp)) {
                    Text("${n.page + 1}쪽", fontSize = 12.sp)
                    Text(n.text, fontSize = 15.sp)
                }
                HorizontalDivider()
            }
        }
    }
    if (memoOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { memoOpen = false },
            title = { Text("${page + 1}쪽 메모") },
            text = { OutlinedTextField(text, { text = it }, minLines = 3) },
            confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onAddNote(page, text.trim()); memoOpen = false }) { Text("저장") } },
            dismissButton = { TextButton(onClick = { memoOpen = false }) { Text("취소") } },
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
private fun TopBar(title: String, c: ReadingColors, bookmarked: Boolean, onBack: () -> Unit, onBookmark: () -> Unit, onSearch: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().background(c.barC).statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.Back, "뒤로", tint = c.fgC) }
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.fgC, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
private fun OnceBar(fmt: DocFormat, page: Int, count: Int, c: ReadingColors, onAdd: () -> Unit, onEbookView: () -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(horizontal = 16.dp).fillMaxWidth().background(c.barC, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("한 번만 보는 중이에요", fontSize = 14.sp, color = c.fgC, modifier = Modifier.weight(1f))
            TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) { Text("서재에 추가", color = c.accentC, fontWeight = FontWeight.Bold) }
        }
        if (fmt.group == FormatGroup.HWP) {
            Text("원본 보기 — 표·서식·도장까지 한글 문서 그대로 표시", fontSize = 12.sp, color = c.subC)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${page + 1} / ${count}쪽", fontSize = 12.sp, color = c.subC, modifier = Modifier.weight(1f))
                Text("원본", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.accentC, modifier = Modifier.padding(horizontal = 8.dp))
                TextButton(onClick = onEbookView, modifier = Modifier.heightIn(min = 48.dp)) { Text("이북식으로 보기", color = c.fgC) }
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
