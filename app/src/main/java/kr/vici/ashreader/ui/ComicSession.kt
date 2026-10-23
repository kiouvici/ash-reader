// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.vici.ashreader.data.ComicBookPrefs
import kr.vici.ashreader.data.ComicPrefsStore
import kr.vici.ashreader.data.PanelMode
import kr.vici.ashreader.doc.AiCapability
import kr.vici.ashreader.doc.ComicLook
import kr.vici.ashreader.doc.ComicReaderDoc
import kr.vici.ashreader.doc.ComicUpscale
import kr.vici.ashreader.doc.ReaderDoc
import kr.vici.ashreader.engine.comic.Adjust
import kr.vici.ashreader.engine.comic.SpreadLayout
import kr.vici.ashreader.engine.comic.Webtoon
import kr.vici.ashreader.engine.comic.WideMode
import kr.vici.ashreader.pagecurl.SpreadMode

/**
 * 만화 한 권을 읽는 동안의 설정 상태(S-33). 바꾸면 바로 문서에 반영하고 책마다 저장한다(한 번만 보기 문서는 저장하지 않음).
 * [layoutVersion] 이 바뀌면 넘김 화면은 쪽 공급을 다시 받는다(펼침 배치·그림 보기 값이 바뀜).
 */
@Stable
class ComicSession(
    val doc: ComicReaderDoc,
    private val bookId: String,
    private val persist: Boolean,
    private val store: ComicPrefsStore,
    /** 이 기기에서 AI 화질 향상을 쓸 수 있나(판정 전 = null). */
    aiVerdict: AiCapability.Verdict?,
    autoSpread: Boolean = false,
    landscape: Boolean = false,
    globalPanel: PanelMode = PanelMode.OFF,
) {
    var prefs by mutableStateOf(store.load(bookId))
        private set
    /** 넘김 화면 쪽 공급을 새로 만들 때마다 오른다. */
    var layoutVersion by mutableIntStateOf(0)
    var aiVerdict by mutableStateOf(aiVerdict)

    init {
        applyToDoc(prefs)
        // 처음 배치는 지난번에 기록한 쪽 크기로 바로 정한다(저장된 읽던 쪽 번호가 같은 배치 기준이므로). 나머지는 ComicSessionEffects 가 뒤에서.
        if (!webtoon && panelMode(globalPanel) == PanelMode.OFF) {
            val sp = when (prefs.spread) { 1 -> false; 2 -> true; else -> autoSpread }
            doc.commitLayout(SpreadLayout.build(doc.cachedSizes(), sp, landscape, WideMode.entries[prefs.wide.coerceIn(0, 2)], doc.rtl, prefs.coverAlone, prefs.shift), sp)
        }
    }

    fun update(f: (ComicBookPrefs) -> ComicBookPrefs) {
        val n = f(prefs)
        if (n == prefs) return
        val lookChanged = look(n) != doc.look || dirOf(n) != doc.dirOverride
        prefs = n
        applyToDoc(n)
        if (persist) store.save(bookId, n)
        if (lookChanged) layoutVersion++
    }

    /** [모든 만화 기본값으로]. */
    fun saveAsDefaults() = store.saveDefaults(prefs)

    private fun dirOf(p: ComicBookPrefs): Boolean? = when (p.dir) {
        ComicBookPrefs.DIR_LTR -> false
        ComicBookPrefs.DIR_RTL -> true
        else -> null
    }

    private fun look(p: ComicBookPrefs) = ComicLook(
        crop = p.crop, cropSens = p.cropSens,
        adjust = Adjust(p.brightness, p.contrast, p.gamma, p.gray, p.invert),
        hq = p.hq, sharpen = p.sharpen,
    )

    private fun applyToDoc(p: ComicBookPrefs) {
        doc.dirOverride = dirOf(p)
        doc.look = look(p)
    }

    val webtoon: Boolean get() = prefs.webtoon

    /** 칸 단위 보기(책마다 지정이 없으면 앱 설정). 웹툰에서는 쓰지 않는다. */
    fun panelMode(global: PanelMode): PanelMode = if (webtoon) PanelMode.OFF else when (prefs.panel) {
        0 -> PanelMode.OFF
        1 -> PanelMode.TAP_ZOOM
        2 -> PanelMode.AUTO
        else -> global
    }

    /** AI 화질 향상(책마다, 기본은 앱 설정). 이 기기에서 쓸 수 없다고 판정됐으면 끈다(F-73). */
    fun aiOn(global: Boolean): Boolean {
        val want = when (prefs.ai) { 0 -> false; 1 -> true; else -> global }
        val v = aiVerdict
        return want && (v == null || v == AiCapability.Verdict.OK)
    }

    fun spreadMode(): SpreadMode = when (prefs.spread) { 1 -> SpreadMode.SINGLE; 2 -> SpreadMode.SPREAD; else -> SpreadMode.AUTO }
}

/** 만화 문서면 [ComicSession], 아니면 null. */
@Composable
fun rememberComicSession(doc: ReaderDoc, bookId: String, once: Boolean, autoSpread: Boolean, landscape: Boolean, globalPanel: PanelMode): ComicSession? {
    val context = LocalContext.current
    return remember(doc) {
        (doc as? ComicReaderDoc)?.let {
            ComicSession(it, bookId, !once, ComicPrefsStore(context), AiCapability.cached(context)?.verdict, autoSpread, landscape, globalPanel)
        }
    }
}

/**
 * 만화 읽기 화면의 뒤 작업: 펼침 배치(F-51), 웹툰 보기 제안(S-35), AI 화질 멈춤 안내(S-36).
 * [onRelayout] = 배치가 바뀌었을 때 옛 쪽 번호 → 새 쪽 번호 변환(읽는 화면이 지금 쪽을 옮긴다).
 */
@Composable
fun ComicSessionEffects(
    cs: ComicSession,
    autoSpread: Boolean,
    landscape: Boolean,
    /** 칸 단위 보기·웹툰 = 쪽마다 한 자리(배치 없음). */
    plainPages: Boolean,
    upscale: ComicUpscale?,
    onRelayout: ((Int) -> Int) -> Unit,
    onNotice: (String) -> Unit,
) {
    val p = cs.prefs
    val ext = cs.doc.extract?.collectAsState()?.value
    val relayout by rememberUpdatedState(onRelayout)
    val rtl = cs.doc.rtl
    LaunchedEffect(cs, p.spread, p.wide, p.coverAlone, p.shift, rtl, autoSpread, landscape, plainPages, ext?.finished) {
        val spread = when (p.spread) { 1 -> false; 2 -> true; else -> autoSpread }
        val slots = if (plainPages) null else withContext(Dispatchers.IO) {
            SpreadLayout.build(cs.doc.sizes(), spread, landscape, WideMode.entries[p.wide.coerceIn(0, 2)], rtl, p.coverAlone, p.shift)
        }
        val f = cs.doc.commitLayout(slots, spread && !plainPages)
        if (f != null) { relayout(f); cs.layoutVersion++ }
    }

    // S-35: 처음 10장 중 6장 이상이 세로로 길면 처음 열 때 한 번 묻는다(책마다)
    var ask by remember(cs) { mutableStateOf(false) }
    var judged by remember(cs) { mutableStateOf(false) }
    val doneKey = ext?.done?.coerceAtMost(10)
    LaunchedEffect(cs, doneKey, ext?.finished) {
        if (judged || p.webtoonAsked || p.webtoon) return@LaunchedEffect
        val sizes = withContext(Dispatchers.IO) { cs.doc.sizes(10) }
        if (sizes.any { it == null } && ext != null && !ext.finished) return@LaunchedEffect // 앞쪽이 풀리면 다시
        judged = true
        if (Webtoon.suggest(sizes)) ask = true
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false; cs.update { it.copy(webtoonAsked = true) } },
        text = { Text("세로로 긴 그림이 많은 만화예요. 웹툰처럼 위아래로 내려 읽을까요?", fontSize = 16.sp) },
        confirmButton = {
            TextButton(onClick = { ask = false; cs.update { it.copy(webtoonAsked = true, dir = ComicBookPrefs.DIR_TTB) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("웹툰으로 보기") }
        },
        dismissButton = {
            TextButton(onClick = { ask = false; cs.update { it.copy(webtoonAsked = true) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("그대로 보기") }
        },
    )

    val notice by rememberUpdatedState(onNotice)
    LaunchedEffect(upscale) { upscale?.notices?.collect { notice(it) } }
}

/**
 * solid CBR 풀기 진행(S-41): 위 가장자리 2dp 진행 막대(메뉴 숨김에도 보임), 아직 풀리지 않은 쪽이면 '쪽 준비 중… n / 전체' + [멈추기].
 * [pages] = 지금 보이는 원본 쪽. 보이는 쪽이 풀리면 [onReady] 로 다시 그리게 한다.
 */
@Composable
fun ComicExtractOverlay(doc: ComicReaderDoc, pages: List<Int>, colors: ReadingColors, menuVisible: Boolean = false, onReady: () -> Unit) {
    val flow = doc.extract ?: return
    if (!doc.extractBlocks) return
    val st by flow.collectAsState()
    val s = st ?: return
    val ready = pages.all { doc.ready(it) }
    val readyNow by rememberUpdatedState(onReady)
    var wasWaiting by remember { mutableStateOf(false) }
    LaunchedEffect(ready) {
        if (!ready) wasWaiting = true
        else if (wasWaiting) { wasWaiting = false; readyNow() }
    }
    Box(Modifier.fillMaxSize()) {
        if (!ready) Column(
            Modifier.fillMaxSize().background(colors.bgC).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            if (s.stopped) {
                Text("이 쪽은 준비되지 않았어요.", fontSize = 16.sp, color = colors.fgC)
                Text("풀기를 멈춰서 ${s.done}쪽까지만 볼 수 있어요.", fontSize = 14.sp, color = colors.subC, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                if (s.finished) OutlinedButton(onClick = { doc.resumeExtract() }, modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) { Text("이어 풀기", color = colors.accentC) }
            } else {
                Text("쪽 준비 중… ${s.done} / ${s.total}", fontSize = 16.sp, color = colors.fgC)
                OutlinedButton(onClick = { doc.stopExtract() }, modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) { Text("멈추기", color = colors.accentC) }
            }
        }
        // 멈춘 뒤(S-41): '일부만 준비됨'을 메뉴와 함께 남긴다. [이어 풀기]로 나머지를 마저 푼다.
        if (ready && s.stopped && s.finished && menuVisible) Row(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 68.dp)
                .background(colors.barC, RoundedCornerShape(24.dp)).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("일부만 준비됨 · ${s.done} / ${s.total}쪽", fontSize = 13.sp, color = colors.fgC)
            TextButton(onClick = { doc.resumeExtract() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("이어 풀기", color = colors.accentC) }
        }
        if (!s.finished && s.total > 0) LinearProgressIndicator(
            progress = { s.done.toFloat() / s.total },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().fillMaxWidth().height(2.dp),
            color = colors.accentC, trackColor = colors.lineC,
        )
    }
}
