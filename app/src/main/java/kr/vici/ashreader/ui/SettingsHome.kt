// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.vici.ashreader.AppInfo
import kr.vici.ashreader.Links
import kr.vici.ashreader.ash
import kr.vici.ashreader.data.BodyFont
import kr.vici.ashreader.data.InfoItem
import kr.vici.ashreader.data.Prefs
import kr.vici.ashreader.data.ReadTheme
import kr.vici.ashreader.data.Rotation
import kr.vici.ashreader.data.ScreenTimeout
import kr.vici.ashreader.data.Settings
import kr.vici.ashreader.data.TapLayout
import kr.vici.ashreader.doc.AiCapability
import kr.vici.ashreader.doc.ComicUpscale
import kr.vici.ashreader.engine.convert.TextEncoding
import kr.vici.ashreader.tts.Listen

// 작업 06-D: 설정 재구성(대표님 목업 SettingsNew 통과 2026-10-24 19:22).
// 첫 화면 = 묶음별 목록(서재 / 읽기 화면 / 형식별 / 기능 / 앱), 항목을 누르면 하위 화면. 태블릿 가로 = 왼쪽 목록 + 오른쪽 하위 화면.
// 저장값은 그대로(Settings) — 줄만 옮겼다. 읽기 화면 Aa 시트에서만 바꾸던 글자 크기·줄 간격·여백·정렬·줄 바꿈·테마도 여기서 바꿀 수 있다.

/** 설정 색(목업 값, 어두운 화면은 같은 관계로). */
private class SetColors(val bg: Color, val card: Color, val ink: Color, val sub: Color, val line: Color, val action: Color, val chevron: Color, val sel: Color)

@Composable
private fun setColors(): SetColors {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) SetColors(Color(0xFF121417), Color(0xFF22252A), Color(0xFFE6E6E8), Color(0xFF9A9EA3), Color(0x1FFFFFFF), Color(0xFF5AA9FF), Color(0xFF6B6F75), Color(0xFF0A84FF))
    else SetColors(Color(0xFFF2F2F7), Color.White, Color(0xFF111316), Color(0xFF8E8E93), Color(0xFFECECEF), Color(0xFF0A6CD6), Color(0xFFC4C4C8), Color(0xFF0A84FF))
}

/** 하위 화면 하나: 이름·아이콘 색·아이콘 선(24 격자). */
private enum class SetPage(val title: String, val color: Long, val path: String) {
    FOLDERS("서재 폴더", 0xFF0A84FF, "M3 7.5A1.5 1.5 0 0 1 4.5 6h4.3l2 2.2h8.7A1.5 1.5 0 0 1 21 9.7v8.8a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18.5z"),
    META("책 정보", 0xFF5E5CE6, "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 11v6M12 7.5v.01"),
    BACKUP("백업과 기록", 0xFF30B0C7, "M12 3v12M7 10l5 5 5-5M4 19h16"),
    TEXT("글자와 화면", 0xFFFF9F0A, "M5 6h14M12 6v13M9 19h6"),
    THEME("테마와 밝기", 0xFF5856D6, "M12 3a9 9 0 1 0 9 9 7 7 0 0 1-9-9z"),
    TURN("넘김과 조작", 0xFF34C759, "M12 6.8C10 5.3 7 4.8 3.5 5.1v12.6c3.5-.3 6.5.2 8.5 1.7 2-1.5 5-2 8.5-1.7V5.1C17 4.8 14 5.3 12 6.8zM12 6.8v12.6"),
    READING("읽는 동안", 0xFFFF375F, "M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"),
    COMIC("만화", 0xFFFF9500, "M4 4h16v16H4zM4 11.5h8.5M12.5 4v16M12.5 14.5H20"),
    DOC("문서", 0xFF007AFF, "M6.5 3h7.5l4 4v14H6V3.5zM14 3v4h4M9 12h6M9 15.5h6"),
    AI("AI 화질 향상", 0xFFAF52DE, "M12 3l1.8 4.2L18 9l-4.2 1.8L12 15l-1.8-4.2L6 9l4.2-1.8zM18 15l.9 2.1L21 18l-2.1.9L18 21l-.9-2.1L15 18l2.1-.9z"),
    LISTEN("듣기와 사전", 0xFFFF2D55, "M4 10v4M8 7v10M12 4v16M16 7v10M20 10v4"),
    ABOUT("앱 정보", 0xFF8E8E93, "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 8v.01M11 12h1v5h1"),
    ;

    val icon: ImageVector by lazy {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
            pathData = addPathNodes(path), fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ).build()
    }
}

/** 첫 화면 묶음(목업 순서). */
private val SET_GROUPS = listOf(
    "서재" to listOf(SetPage.FOLDERS, SetPage.META, SetPage.BACKUP),
    "읽기 화면" to listOf(SetPage.TEXT, SetPage.THEME, SetPage.TURN, SetPage.READING),
    "형식별" to listOf(SetPage.COMIC, SetPage.DOC),
    "기능" to listOf(SetPage.AI, SetPage.LISTEN),
    "앱" to listOf(SetPage.ABOUT),
)

/** S-51 설정(작업 06 재구성). */
@Composable
fun AppSettingsScreen(
    s: Settings, onChange: ((Settings) -> Settings) -> Unit, onClearOnce: () -> Unit, onLicenses: () -> Unit = {},
    onStorage: () -> Unit = {}, onFolders: () -> Unit = {}, onStoreEdit: () -> Unit = {}, onExportAll: () -> Unit = {},
    /** S-53 백업 가져오기(폴더에서 백업 파일 고르기) 화면으로. */
    onBackupImport: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = setColors()
    var pageName by rememberSaveable { mutableStateOf<String?>(null) }
    val page = pageName?.let { n -> SetPage.entries.firstOrNull { it.name == n } }
    var info by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var upTick by remember { mutableIntStateOf(0) }
    var warmTime by remember { mutableStateOf(false) }
    var tapEditor by remember { mutableStateOf(false) }
    var infoItems by remember { mutableStateOf(false) }
    // 작업 06: 내 글꼴 관리·내 테마 만들기·독서 기록 지우기·사전 앱 고르기 창
    var fontsOpen by remember { mutableStateOf(false) }
    var themeOpen by remember { mutableStateOf(false) }
    var clearHistory by remember { mutableStateOf(false) }
    var dictPick by remember { mutableStateOf(false) }
    // 만화 기본값(새로 여는 만화가 따름 — 만화 보기 시트의 '모든 만화 기본값으로'와 같은 저장소)
    val comicStore = remember { kr.vici.ashreader.data.ComicPrefsStore(context) }
    var comicDef by remember { mutableStateOf(comicStore.defaults()) }
    fun setComic(f: (kr.vici.ashreader.data.ComicBookPrefs) -> kr.vici.ashreader.data.ComicBookPrefs) { comicDef = f(comicDef); comicStore.saveDefaults(comicDef) }
    // 서재 폴더마다 책 수(목업: 'Download 23권')
    val libBooks by remember { context.ash.db.books().recent() }.collectAsState(initial = emptyList())
    val upUsed by produceState<Long?>(null, upTick, s.upscaleCacheMb) {
        // 상한을 바꾸면 앱(AshRoot)이 넘치는 만큼 지운다. 잠깐 기다렸다가 쓰는 양을 다시 잰다.
        if (upTick > 0 || s.upscaleCacheMb != 1024) kotlinx.coroutines.delay(300)
        value = withContext(Dispatchers.IO) { ComicUpscale.usedBytes(context.applicationContext) }
    }
    // S-53 백업: 저장 위치는 사용자가 고른다(SAF, 기억한 백업 폴더에서 시작). 가져오기는 백업 가져오기 화면. 네트워크 없음.
    val exportBackup = rememberBackupExporter(onBusy = { busy = it }, onResult = { info = it })
    // S-17 서재 폴더: 폴더 이름들
    val libFolders by remember { context.ash.db.books().scanFolders() }.collectAsState(initial = emptyList())
    val folderNames = libFolders.filter { it.include }.map { f ->
        val cn = kr.vici.ashreader.data.StorageAccess.canonOf(f.path)
        if (cn.startsWith("path:") || cn.startsWith("tree:")) f.label.substringAfterLast('/')
        else kr.vici.ashreader.data.FolderRules.nameOf(cn) { v -> kr.vici.ashreader.data.StorageAccess.volumeLabel(context, v) }
    }
    val aiCap = remember { AiCapability.cached(context) }
    val aiBad = aiCap?.verdict?.let { it != AiCapability.Verdict.OK } == true

    val dictName = remember(s.dictPkg) { dictLabel(context, s.dictPkg) }

    /** 첫 화면 항목 아래 지금 값 요약. */
    fun hint(p: SetPage): String = when (p) {
        SetPage.FOLDERS -> folderNames.joinToString(" · ").ifEmpty { "아직 고른 폴더가 없어요" }
        SetPage.META -> if (s.metaNet) "인터넷에서 찾기 · " + META_AUTO[s.metaAuto] else "인터넷에서 찾기 꺼짐 · 파일 속 정보만"
        SetPage.BACKUP -> "백업 · 가져오기 · 내보내기"
        SetPage.TEXT -> "${s.font.label.substringBefore(" (")} · ${s.fontSize} · ${if (s.align == 1) "왼쪽" else "양쪽"}"
        SetPage.THEME -> s.theme.label + if (s.warmAuto) " · 따뜻한 색 자동" else ""
        SetPage.TURN -> "${turnLabel(s)} · ${s.tapLayout.label}"
        SetPage.READING -> "화면 꺼짐 · 회전 · 정보줄"
        SetPage.COMIC -> listOf("방향 자동", "왼→오", "오→왼", "웹툰")[comicDef.dir.coerceIn(0, 3)] + " · 두 쪽 " + listOf("자동", "한 쪽", "두 쪽")[comicDef.spread.coerceIn(0, 2)]
        SetPage.DOC -> "야간 그림 반전 " + if (s.nightKeepImages) "안 함" else "함"
        SetPage.AI -> if (aiBad) "이 기기에서는 쓸 수 없어요" else "만화 " + (if (s.aiUpscale) "켬" else "끔") + " · 문서 그림 " + (if (s.epubAiUpscale) "켬" else "끔")
        SetPage.LISTEN -> "${"%.1f".format(java.util.Locale.ROOT, s.ttsRate)}× · $dictName"
        SetPage.ABOUT -> AppInfo.VERSION_LABEL
    }

    @Composable
    fun ColumnScope.pageBody(p: SetPage) {
        when (p) {
            SetPage.FOLDERS -> {
                Section("고른 폴더", c) {
                    if (folderNames.isEmpty()) Act("폴더 추가", c, onClick = onFolders)
                    else {
                        val labels = libFolders.filter { it.include }.map { it.label }
                        folderNames.forEachIndexed { i, n ->
                            val l = labels.getOrNull(i).orEmpty()
                            val count = libBooks.count { b -> b.folder == l || b.folder.startsWith("$l/") }
                            Value(n, "${count}권", c, onClick = onFolders)
                        }
                        Act("폴더 추가·빼기", c, onClick = onFolders)
                    }
                }
                Section("묶기", c) {
                    Toggle("시리즈 자동으로 묶기", s.seriesAuto, c, sub = "같은 폴더, 이름 끝 권·화 번호만 다른 책 · 끄면 직접 묶은 것만") { v -> onChange { it.copy(seriesAuto = v) } }
                    Value("서재 기본 보기", if (s.libList) "목록" else "표지", c, sub = "책을 표지 격자로 / 한 줄씩 목록으로") { onChange { it.copy(libList = !it.libList) } }
                }
                Section("서재", c) {
                    val access = remember { kr.vici.ashreader.data.StorageAccess.hasFileAccess(context) }
                    Value("파일 접근 권한", if (access) "허용됨" else "허용 안 됨", c, sub = "기기 안 책·문서를 찾는 권한(S-02 다시 보기)", onClick = onStorage)
                    Toggle("서점 바로가기 줄 보이기", s.showStores, c, sub = "서재 첫 화면 아래 '내 서점 앱' 줄") { v -> onChange { it.copy(showStores = v) } }
                    Value("서점 바로가기 편집", "", c, sub = "더하기·빼기·순서", onClick = onStoreEdit)
                }
            }
            SetPage.META -> {
                // 작업 06(D-024): 인터넷에서 책 정보 찾기 — 기본 꺼짐, 정리한 제목만 보냄, 한글·Word 는 자동으로 찾지 않음
                Section("인터넷에서 찾기", c, foot = "파일 안에 정확한 정보가 있는 책은 그대로 둬요. 정리한 제목만 보내고 책 내용·폴더 이름은 보내지 않아요. 한글·Word 문서는 자동으로 찾지 않아요(책 정보에서 직접 찾을 수는 있어요).") {
                    Toggle("인터넷에서 책 정보 찾기", s.metaNet, c, sub = "표지·지은이·출판사") { v ->
                        onChange { it.copy(metaNet = v, metaAsked = true) }
                        // 켜면 전에 인터넷 없이 훑은 책도 다시 찾게
                        if (v) context.ash.appScope.launch { context.ash.db.books().resetMetaChecked() }
                    }
                    if (s.metaNet) Value("찾을 책", "이북·만화·스캔 PDF", c, chevron = false) {}
                }
                if (s.metaNet) Section("자동으로 찾기", c) {
                    META_AUTO.forEachIndexed { i, label -> Value(label, if (s.metaAuto == i) "✓" else "", c, chevron = false) { onChange { it.copy(metaAuto = i) } } }
                }
                Section("관리", c) {
                    Act("지금 모든 책 정보 다시 찾기", c, sub = "직접 고른 책은 그대로") {
                        context.ash.appScope.launch { context.ash.db.books().resetMetaChecked() }
                        info = "서재의 책 정보를 다시 찾아요. 서재 화면에서 조금씩 채워져요."
                    }
                    Act("인터넷에서 받은 정보 모두 지우기", c, sub = "파일 정보로 되돌려요") {
                        context.ash.appScope.launch {
                            val dao = context.ash.db.books()
                            dao.allBooks().filter { it.coverSource == kr.vici.ashreader.data.Book.COVER_INTERNET }.forEach { kr.vici.ashreader.doc.Covers.deleteChosen(context, it.id) }
                            dao.clearNetInfo()
                            kr.vici.ashreader.net.BookInfoNet.clearCache(context)
                        }
                        info = "인터넷에서 받은 지은이·출판사·표지를 지웠어요. 파일 속 정보는 다시 읽어요."
                    }
                }
                Section("정보 출처", c) {
                    Value("국립중앙도서관", if (kr.vici.ashreader.BuildConfig.NL_KEY.isBlank()) "키 없음" else "씀", c, chevron = false) {}
                    Value("카카오 책 검색", if (kr.vici.ashreader.BuildConfig.KAKAO_KEY.isBlank()) "키 없음" else "씀", c, chevron = false) {}
                    Value("구글 북스", "씀", c, chevron = false) {}
                }
            }
            SetPage.BACKUP -> {
                Section("백업", c, foot = "서재 정보·읽은 위치·책갈피·메모·형광펜·독서 기록·설정을 백업 파일(ASH백업_날짜.ashbak) 하나로 저장해요. 책 파일은 담지 않아요. 가져올 때는 지금 기록과 합치거나 모두 바꿀 수 있어요.") {
                    Act("지금 백업하기", c, enabled = !busy, onClick = exportBackup)
                    Act("백업 가져오기", c, enabled = !busy, onClick = onBackupImport)
                }
                Section("기록", c) {
                    Act("형광펜·메모 모두 내보내기", c, sub = ".txt 또는 .md 로 저장하거나 공유", onClick = onExportAll)
                    Toggle("한 번만 보기 기록 지우기", s.clearOnceOnExit, c, sub = "앱 닫으면 지워요") { v -> onChange { it.copy(clearOnceOnExit = v) }; if (v) onClearOnce() }
                    Act("독서 기록 지우기", c, sub = "읽은 시간·날짜 기록(독서 기록 화면). 읽은 위치·책갈피·메모는 그대로") { clearHistory = true }
                }
            }
            SetPage.TEXT -> {
                Section("글꼴", c, foot = "읽는 중에는 읽기 메뉴의 'Aa'에서도 바꿀 수 있어요.") {
                    Value("본문 글꼴", s.font.label.substringBefore(" ("), c, sub = "나눔명조 · ASH 바탕 · ASH 부리") {
                        onChange { it.copy(font = BodyFont.entries[(it.font.ordinal + 1) % BodyFont.entries.size]) }
                    }
                    Value("글자 크기", "${s.fontSize}", c) {
                        onChange { it.copy(fontSize = if (it.fontSize + 2 > Prefs.MAX_SIZE) Prefs.MIN_SIZE else it.fontSize + 2) }
                    }
                    Act("내 글꼴 관리", c, sub = if (s.userFont.isNotEmpty()) "지금 ‘${kr.vici.ashreader.doc.UserFonts.label(s.userFont)}’" else "TTF·OTF 글꼴 더하기·고르기·지우기") { fontsOpen = true }
                }
                Section("문단", c) {
                    val heights = listOf(1.4f, 1.6f, 1.8f, 2.0f, 2.2f)
                    Value("줄 간격", "%.1f".format(java.util.Locale.ROOT, s.lineHeight), c) {
                        onChange { cur -> cur.copy(lineHeight = heights[(heights.indexOfFirst { h -> h >= cur.lineHeight - 0.05f }.let { i -> if (i < 0) 0 else i + 1 }) % heights.size]) }
                    }
                    val margins = listOf(5 to "좁게", 15 to "보통", 25 to "넓게")
                    Value("여백", margins.minByOrNull { kotlin.math.abs(it.first - s.marginH) }!!.second, c, sub = "좌우 여백") {
                        onChange { cur -> cur.copy(marginH = margins[(margins.indexOfFirst { m -> m.first >= cur.marginH }.let { i -> if (i < 0) 0 else i + 1 }) % margins.size].first) }
                    }
                    Value("정렬", if (s.align == 1) "왼쪽" else "양쪽", c) { onChange { it.copy(align = 1 - it.align.coerceIn(0, 1)) } }
                    Value("줄 바꿈 단위", if (s.breakChar) "글자" else "띄어쓰기", c, sub = if (s.breakChar) "줄 끝을 꽉 채워요" else "낱말을 끊지 않아요") { onChange { it.copy(breakChar = !it.breakChar) } }
                }
                Section("TXT 파일", c, foot = "TXT 정리는 화면에만 적용하고 파일은 그대로 둬요.") {
                    val encs = listOf("") + TextEncoding.Kind.entries.map { it.name }
                    Value("TXT 기본 인코딩", TextEncoding.Kind.of(s.txtDefaultEncoding)?.label ?: "자동", c, sub = "책마다 따로 정한 것이 없을 때") {
                        onChange { it.copy(txtDefaultEncoding = encs[(encs.indexOf(it.txtDefaultEncoding).coerceAtLeast(0) + 1) % encs.size]) }
                    }
                    Value("TXT 빈 줄", listOf("그대로", "하나로", "모두 없앰")[s.txtBlank], c) { onChange { it.copy(txtBlank = (it.txtBlank + 1) % 3) } }
                    Toggle("TXT 끊긴 줄 합치기", s.txtJoin, c, sub = "문장이 끝나지 않은 줄을 다음 줄과 이어요") { v -> onChange { it.copy(txtJoin = v) } }
                    Toggle("TXT 줄 앞 공백 정리", s.txtTrim, c, sub = "빈칸·전각 빈칸·탭") { v -> onChange { it.copy(txtTrim = v) } }
                }
            }
            SetPage.THEME -> {
                Section("읽기 테마", c) {
                    Value("테마", s.theme.label, c) { onChange { it.copy(theme = ReadTheme.entries[(it.theme.ordinal + 1) % ReadTheme.entries.size]) } }
                    Value(
                        "따뜻한 색 자동 시각", if (s.warmAuto) "${LightMath.hm(s.warmFrom)}~${LightMath.hm(s.warmTo)}" else "끔", c,
                        sub = if (s.warmAuto) "이 시간에만 따뜻한 색을 켜요" else "강도는 읽기 화면 '밝기'에서",
                    ) { warmTime = true }
                    if (s.warmAuto) Toggle("따뜻한 색 자동 켜기", true, c) { v -> onChange { it.copy(warmAuto = v) } }
                    Act("내 테마 만들기", c, sub = "바탕·글자 색을 골라 '내 테마'로") { themeOpen = true }
                }
                Section("밝기", c) {
                    Toggle("가장자리로 밝기 조절", s.edgeBrightness, c, sub = "화면 왼쪽 가장자리 가운데를 위아래로 밀어요") { v -> onChange { it.copy(edgeBrightness = v) } }
                }
                Section("앱 화면", c, foot = "서재·설정 같은 앱 화면의 색이에요. 읽는 화면의 종이 색은 '읽기 테마'를 따라요.") {
                    Value("앱 테마", listOf("시스템", "밝게", "어둡게")[s.appTheme.coerceIn(0, 2)], c) { onChange { it.copy(appTheme = (it.appTheme + 1) % 3) } }
                }
            }
            SetPage.TURN -> {
                Section("넘김", c) {
                    Value("넘김 효과", turnLabel(s), c) {
                        onChange { when { it.noTurnEffect -> it.copy(noTurnEffect = false, curl = true); it.curl -> it.copy(curl = false); else -> it.copy(noTurnEffect = true) } }
                    }
                    // 목업대로 '쪽·스크롤' 값 줄(같은 저장값 scrollView)
                    Value("쪽·스크롤", if (s.scrollView) "스크롤" else "쪽", c, sub = "스크롤 = 쪽을 위아래로 이어서 보기(넘김 효과는 쓰지 않아요)") { onChange { it.copy(scrollView = !it.scrollView) } }
                    Toggle("두 손가락으로 글자 크기", s.pinchFont, c, sub = "이북·TXT 를 읽는 중 두 손가락을 벌리면 크게, 오므리면 작게") { v -> onChange { it.copy(pinchFont = v) } }
                }
                Section("누르기와 버튼", c) {
                    Value("화면 누르는 곳", s.tapLayout.label, c, sub = "좌우 · L자 · 어디든 다음 · 내 방식") {
                        onChange { it.copy(tapLayout = TapLayout.entries[(it.tapLayout.ordinal + 1) % TapLayout.entries.size], tapGuideShown = false) }
                    }
                    Value("내 방식 편집", "", c, sub = "3×3 칸마다 이전·다음·메뉴·없음") { tapEditor = true }
                    Toggle("왼손잡이(좌우 바꾸기)", s.leftHanded, c) { v -> onChange { it.copy(leftHanded = v, tapGuideShown = false) } }
                    Toggle("볼륨 버튼으로 넘기기", s.volumeKeys, c, sub = "듣는 동안에는 소리 크기") { v -> onChange { it.copy(volumeKeys = v) } }
                    Act("탭 영역 안내 다시 보기", c, sub = "다음에 책을 열 때 보여 줘요") { onChange { it.copy(tapGuideShown = false) }; info = "다음에 책을 열면 안내가 다시 나와요." }
                }
                Section("자동", c, foot = "자동 넘김·자동 스크롤은 읽기 화면 '넘김'에서 켜요.") {
                    val gaps = listOf(5, 10, 15, 20, 30, 60)
                    Value("자동 넘김 간격", "${s.autoTurnSec}초", c) {
                        onChange { it.copy(autoTurnSec = gaps[(gaps.indexOf(it.autoTurnSec).let { i -> if (i < 0) 0 else i + 1 }) % gaps.size]) }
                    }
                    Value("자동 스크롤 속도", "${s.autoScrollSpeed}단계", c, sub = "스크롤 보기에서") { onChange { it.copy(autoScrollSpeed = it.autoScrollSpeed % 10 + 1) } }
                }
            }
            SetPage.READING -> {
                Section("화면", c) {
                    Value("화면 꺼짐 시간", s.screenTimeout.label, c) { onChange { it.copy(screenTimeout = ScreenTimeout.entries[(it.screenTimeout.ordinal + 1) % ScreenTimeout.entries.size]) } }
                    Value("화면 회전", s.rotation.label, c, sub = "읽기 메뉴의 회전 잠금 버튼과 같아요") { onChange { it.copy(rotation = Rotation.entries[(it.rotation.ordinal + 1) % Rotation.entries.size]) } }
                }
                Section("정보줄", c) {
                    Value("정보줄 항목", "${InfoItem.entries.count { s.infoItems and it.bit != 0 }}개", c, sub = "시계·배터리·이번 장 남은 쪽·진행률·남은 시간") { infoItems = true }
                    Toggle("정보줄을 위에", s.infoTop, c, sub = "끄면 아래") { v -> onChange { it.copy(infoTop = v) } }
                    Toggle("두 쪽 펼침에서 쪽마다 정보줄", s.infoPerPage, c, sub = "태블릿 가로: 각 쪽 아래에 쪽 번호. 끄면 전체 아래 한 줄") { v -> onChange { it.copy(infoPerPage = v) } }
                    Toggle("메뉴 버튼 이름 보이기", s.menuLabels, c, sub = "읽기 메뉴 아래 단추(목차·보기·넘김…) 이름") { v -> onChange { it.copy(menuLabels = v) } }
                }
            }
            SetPage.COMIC -> {
                // 작업 06: 모든 만화 기본값(새로 여는 만화가 따름). 읽는 중 만화 보기 시트에서도 책마다 바꾸고 '모든 만화 기본값으로' 저장할 수 있다
                Section("보기", c) {
                    val d = comicDef
                    Value("읽는 방향", listOf("자동", "왼→오", "오→왼", "위→아래 · 웹툰")[d.dir.coerceIn(0, 3)], c) { setComic { it.copy(dir = (it.dir + 1) % 4) } }
                    Value("두 쪽", listOf("자동", "한 쪽", "두 쪽")[d.spread.coerceIn(0, 2)], c, sub = "태블릿 가로·펼친 화면") { setComic { it.copy(spread = (it.spread + 1) % 3) } }
                    Toggle("첫 쪽 단독", d.coverAlone, c, sub = "두 쪽 보기에서 표지를 혼자 보여요") { v -> setComic { it.copy(coverAlone = v) } }
                    Value("가로로 긴 쪽", listOf("반으로 나눔", "회전", "그대로")[d.wide.coerceIn(0, 2)], c) { setComic { it.copy(wide = (it.wide + 1) % 3) } }
                }
                Section("화면 맞춤", c, foot = "새로 여는 만화의 기본값이에요. 이미 연 만화는 그 책에 정한 값을 써요(읽는 중 메뉴 '보기').") {
                    val d = comicDef
                    Value("맞춤", listOf("화면", "폭", "높이", "원본")[d.fit.coerceIn(0, 3)], c) { setComic { it.copy(fit = (it.fit + 1) % 4) } }
                    Toggle("테두리 자르기", d.crop, c, sub = "쪽 둘레 흰 여백을 자동으로") { v -> setComic { it.copy(crop = v) } }
                    Value("확대 방식", (if (d.hq) "고품질" else "빠름") + "·" + listOf("끔", "약", "중", "강")[d.sharpen.coerceIn(0, 3)], c, sub = "빠름 → 고품질·끔 → 고품질·약 … 차례로") {
                        setComic { cur -> if (!cur.hq) cur.copy(hq = true, sharpen = 0) else if (cur.sharpen < 3) cur.copy(sharpen = cur.sharpen + 1) else cur.copy(hq = false, sharpen = 1) }
                    }
                    Toggle("웹툰 제안", s.webtoonSuggest, c, sub = "세로로 긴 만화를 처음 열 때 웹툰 보기로 물어봐요") { v -> onChange { it.copy(webtoonSuggest = v) } }
                }
                if (!aiBad) Section("AI 화질 향상", c) {
                    Toggle("새 만화에 AI 화질 향상 켜기", s.aiUpscale, c, sub = "책마다 바꿀 수 있어요 · 배터리 더 씀") { v -> onChange { it.copy(aiUpscale = v) } }
                }
            }
            SetPage.DOC -> {
                Section("한글·Word", c) {
                    Value("처음 열 때", if (s.docDefaultView == 1) "이북식" else "원본", c, sub = "처음 여는 문서의 보기 방식 · 연 뒤에는 책마다 기억") { onChange { it.copy(docDefaultView = 1 - it.docDefaultView.coerceIn(0, 1)) } }
                }
                Section("PDF·한글·Word 공통", c) {
                    Toggle("야간에 그림은 반전 안 함", s.nightKeepImages, c, sub = "글자 있는 PDF 등 고정 쪽 문서") { v -> onChange { it.copy(nightKeepImages = v) } }
                    Toggle("여백 자르기 기본", s.cropDefault, c, sub = "처음 여는 문서에 쪽 둘레 흰 여백 자르기 · 책마다 Aa 에서 바꿀 수 있어요") { v -> onChange { it.copy(cropDefault = v) } }
                    val zooms = listOf(1.5f, 2f, 3f)
                    Value("두 번 탭 확대", "${if (s.doubleTapZoom % 1f == 0f) s.doubleTapZoom.toInt().toString() else s.doubleTapZoom.toString()}배", c, sub = "쪽 고정 문서·만화를 두 번 누르면") {
                        onChange { cur -> cur.copy(doubleTapZoom = zooms[(zooms.indexOfFirst { z -> z >= cur.doubleTapZoom - 0.01f }.let { i -> if (i < 0) 0 else i + 1 }) % zooms.size]) }
                    }
                }
            }
            SetPage.AI -> {
                // 작업 04: AI 화질 향상 한 화면(만화 새 책 기본값 · 이북·PDF 문서 그림 · 저장 공간). 읽는 중에는 보기 메뉴 맨 위 같은 칸.
                Section("켜기", c, foot = if (aiBad) "이 기기에서는 쓸 수 없어요(메모리·처리 속도)." else null) {
                    if (aiBad) Value("AI 화질 향상", "쓸 수 없음", c) {}
                    else {
                        // F-73: 만화는 책마다 켜고 끈다(기본 끔). 여기서는 아직 정하지 않은 새 만화의 기본값만.
                        Toggle("새 만화에 기본으로 켜기", s.aiUpscale, c, sub = "책마다 바꿀 수 있어요") { v -> onChange { it.copy(aiUpscale = v) } }
                        // F-73 확장(D-021): 이북·PDF 속 그림·사진만 2배로. 글자에는 적용 안 함. 앱 전체 한 값. 기본 끔.
                        Toggle("문서 그림 AI 화질 향상", s.epubAiUpscale, c, sub = "이북·PDF 속 작은 그림·사진 · 글자·한글·Word 에는 적용 안 함") { v -> onChange { it.copy(epubAiUpscale = v) } }
                    }
                }
                Section("저장 공간", c) {
                    // F-73: 저장 공간 상한(300MB / 1GB / 2GB, 만화·문서 그림 함께)
                    Value("저장 공간 상한", mbLabel(s.upscaleCacheMb.toLong() shl 20), c, sub = "AI 화질 향상으로 처리한 그림(만화·문서)") {
                        onChange { cur -> val ch = Prefs.UPSCALE_CACHE_CHOICES; cur.copy(upscaleCacheMb = ch[(ch.indexOf(cur.upscaleCacheMb) + 1).mod(ch.size)]) }
                    }
                    Act("처리한 그림 지우기", c, sub = upUsed?.let { "지금 ${mbLabel(it)}" } ?: "") {
                        scope.launch {
                            withContext(Dispatchers.IO) { ComicUpscale.clear(context.applicationContext) }
                            upTick++
                            info = "AI 화질 향상으로 처리해 둔 그림을 모두 지웠어요. 다시 볼 때 새로 처리해요."
                        }
                    }
                }
            }
            SetPage.LISTEN -> {
                Section("듣기", c) {
                    Act("읽어 주기 엔진 설정 열기", c, sub = "한국어 음성 받기·엔진 고르기") {
                        if (Listen.settingsIntents().none { i -> runCatching { context.startActivity(i) }.isSuccess }) info = "읽어 주기 설정 화면을 열 수 없어요."
                    }
                    val rates = listOf(0.8f, 1.0f, 1.2f, 1.5f, 2.0f)
                    Value("기본 속도", "${"%.1f".format(java.util.Locale.ROOT, s.ttsRate)}×", c) {
                        onChange { it.copy(ttsRate = rates[(rates.indexOfFirst { r -> r >= it.ttsRate - 0.01f }.let { i -> if (i < 0) 0 else i + 1 }) % rates.size]) }
                    }
                    Value("잠자기 타이머 기본", SLEEP_CHOICES.firstOrNull { it.first == s.ttsSleep }?.second ?: "끄기", c) {
                        onChange { cur -> cur.copy(ttsSleep = SLEEP_CHOICES[(SLEEP_CHOICES.indexOfFirst { it.first == cur.ttsSleep } + 1).mod(SLEEP_CHOICES.size)].first) }
                    }
                }
                Section("사전", c, foot = "본문 글자를 길게 눌러 고른 뒤 '사전'을 누르면 이 앱으로 찾아요. 앱이 없으면 고를 수 있는 앱을 보여 줘요.") {
                    Value("사전 앱", dictName, c) { dictPick = true }
                }
            }
            SetPage.ABOUT -> {
                Section("앱", c) {
                    Value("버전", AppInfo.VERSION_LABEL, c, chevron = false) {}
                    Act("오픈소스 라이선스", c, sub = "AGPL-3.0 · 사용한 라이브러리", onClick = onLicenses)
                    Act("소스 코드 받는 법", c) {
                        info = "이 앱은 GNU AGPL 3.0 이상으로 배포돼요. 누구나 소스 코드를 받아 고치고 다시 배포할 수 있어요.\n\n소스 코드: ${AppInfo.SOURCE_URL}\n\n함께 넣은 오픈소스 구성 요소의 소스도 같은 곳에서 판마다 따로 묶어 드려요."
                    }
                    Act("처음 안내 다시 보기", c, sub = "파일 접근 안내 · 탭 영역 안내 · 책 정보 찾기 묻기") {
                        onChange { it.copy(tapGuideShown = false, metaAsked = false) }
                        onStorage()
                    }
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val split = maxWidth >= 840.dp && maxWidth > maxHeight
        if (split) {
            // 태블릿 가로: 왼쪽 목록(400dp, 고른 항목 파란 칸) + 오른쪽 하위 화면
            val cur = page ?: SetPage.FOLDERS
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(400.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    HomeList(c, compact = true, selected = cur, hint = ::hint) { pageName = it.name }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                    PageTitle(cur, c, big = true)
                    pageBody(cur)
                    Spacer(Modifier.height(30.dp))
                }
            }
        } else if (page == null) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                HomeList(c, compact = false, selected = null, hint = ::hint) { pageName = it.name }
            }
        } else {
            BackHandler { pageName = null }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.padding(start = 10.dp, top = 6.dp)) { BackSquare(c) { pageName = null } }
                PageTitle(page, c, big = false)
                pageBody(page)
                Spacer(Modifier.height(30.dp))
            }
        }
    }

    info?.let {
        AlertDialog(onDismissRequest = { info = null }, text = { Text(it) }, confirmButton = { TextButton(onClick = { info = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("확인") } })
    }
    if (warmTime) WarmTimeDialog(s.warmFrom, s.warmTo, onSave = { f, t -> warmTime = false; onChange { it.copy(warmFrom = f, warmTo = t, warmAuto = true) } }, onDismiss = { warmTime = false })
    if (tapEditor) TapEditorDialog(s.tapCustom, onSave = { str -> tapEditor = false; onChange { it.copy(tapLayout = TapLayout.CUSTOM, tapCustom = str, tapGuideShown = false) } }, onDismiss = { tapEditor = false })
    if (infoItems) AlertDialog(
        onDismissRequest = { infoItems = false },
        title = { Text("정보줄 항목") },
        text = {
            Column {
                InfoItem.entries.forEach { item ->
                    SwitchLine(item.label, "", s.infoItems and item.bit != 0) { v -> onChange { it.copy(infoItems = if (v) it.infoItems or item.bit else it.infoItems and item.bit.inv()) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = { infoItems = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") } },
    )
    // 작업 06: 읽기 Aa 시트와 같은 내 글꼴 관리
    if (fontsOpen) AlertDialog(
        onDismissRequest = { fontsOpen = false },
        title = { Text("내 글꼴") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { UserFontsManager(s, onChange) } },
        confirmButton = { TextButton(onClick = { fontsOpen = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") } },
    )
    // 작업 06: 읽기 화면 '밝기'의 내 테마와 같은 색 고르기
    if (themeOpen) CustomThemeDialog(
        s.customBg, s.customFg,
        onSave = { bg, fg -> themeOpen = false; onChange { it.copy(customBg = bg, customFg = fg, theme = ReadTheme.CUSTOM) } },
        onDismiss = { themeOpen = false },
    )
    if (clearHistory) AlertDialog(
        onDismissRequest = { clearHistory = false },
        title = { Text("독서 기록을 지울까요?") },
        text = { Text("읽은 시간·날짜 기록(독서 기록 화면의 그래프·연속 일수)을 모두 지워요. 읽은 위치·책갈피·메모·형광펜은 그대로예요. 되돌릴 수 없어요.") },
        confirmButton = {
            TextButton(onClick = {
                clearHistory = false
                context.ash.appScope.launch { context.ash.db.books().clearSessions() }
                info = "독서 기록을 지웠어요."
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("지우기") }
        },
        dismissButton = { TextButton(onClick = { clearHistory = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } },
    )
    if (dictPick) {
        val apps = remember { dictApps(context) }
        AlertDialog(
            onDismissRequest = { dictPick = false },
            title = { Text("사전 앱") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // 말글사전(기본) + 글자를 받을 수 있는 앱(PROCESS_TEXT)
                    (listOf("" to "말글사전" + if (Links.isInstalled(context, Links.MALGEUL)) "" else " (설치 안 됨)") + apps.filter { it.first != Links.MALGEUL }).forEach { (pkg, label) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onChange { it.copy(dictPkg = pkg) }; dictPick = false },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(selected = s.dictPkg == pkg, onClick = null)
                            Text(label, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dictPick = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") } },
        )
    }
}

/** 글자를 받아 찾는 앱(ACTION_PROCESS_TEXT) — (패키지, 이름). 이 앱 자신은 뺀다. */
private fun dictApps(context: android.content.Context): List<Pair<String, String>> = runCatching {
    val pm = context.packageManager
    val i = android.content.Intent(android.content.Intent.ACTION_PROCESS_TEXT).setType("text/plain")
    @Suppress("DEPRECATION")
    pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .filter { it.first != context.packageName }.distinctBy { it.first }.sortedBy { it.second }
}.getOrDefault(emptyList())

/** 사전 앱 이름("" = 말글사전). */
private fun dictLabel(context: android.content.Context, pkg: String): String =
    if (pkg.isBlank()) "말글사전" else dictApps(context).firstOrNull { it.first == pkg }?.second ?: pkg

/** 책 정보 자동으로 찾는 때(Settings.metaAuto 순서). */
private val META_AUTO = listOf("와이파이에서만", "와이파이 + 모바일 데이터", "직접 찾을 때만")

private fun turnLabel(s: Settings) = when { s.noTurnEffect -> "없음"; s.curl -> "종이책"; else -> "슬라이드" }

/** 첫 화면 목록: 큰 제목 '설정' + 묶음 제목(13sp 굵게 회색, heading) + 흰 카드 속 항목(색 칸 아이콘 + 이름 + 지금 값 + ›). */
@Composable
private fun HomeList(c: SetColors, compact: Boolean, selected: SetPage?, hint: (SetPage) -> String, onPick: (SetPage) -> Unit) {
    Text("설정", fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.02).em, color = c.ink, modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 6.dp).semantics { heading() })
    SET_GROUPS.forEach { (title, pages) ->
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.sub, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp).semantics { heading() })
        Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(c.card)) {
            pages.forEachIndexed { i, p ->
                val sel = p == selected
                Row(
                    Modifier.fillMaxWidth().background(if (sel) c.sel else Color.Transparent).clickable { onPick(p) }.heightIn(min = if (compact) 48.dp else 52.dp).padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(p, if (compact) 28 else 30)
                    Row(Modifier.weight(1f).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(p.title, fontSize = if (compact) 15.5.sp else 16.sp, fontWeight = FontWeight.Medium, color = if (sel) Color.White else c.ink)
                            if (!compact) Text(hint(p), fontSize = 12.sp, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
                        }
                        if (!compact) Icon(Icons.Chevron, contentDescription = null, tint = c.chevron, modifier = Modifier.padding(start = 8.dp, end = 14.dp).size(14.dp))
                    }
                }
                if (i < pages.size - 1) HorizontalDivider(color = c.line, modifier = Modifier.padding(start = 56.dp))
            }
        }
    }
    Spacer(Modifier.height(30.dp))
}

/** 색 칸 아이콘(모서리 8dp, 위쪽 옅은 광택). */
@Composable
private fun IconTile(p: SetPage, size: Int) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.27f).dp)).background(Color(p.color))
            .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.28f), 0.55f to Color.Transparent)),
        contentAlignment = Alignment.Center,
    ) { Icon(p.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size((size * 0.6f).dp)) }
}

@Composable
private fun PageTitle(p: SetPage, c: SetColors, big: Boolean) {
    Row(Modifier.padding(start = if (big) 4.dp else 20.dp, top = if (big) 30.dp else 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(p, if (big) 40 else 36)
        Text(p.title, fontSize = if (big) 30.sp else 28.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.02).em, color = c.ink, modifier = Modifier.padding(start = 12.dp).semantics { heading() })
    }
}

@Composable
private fun BackSquare(c: SetColors, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).padding(2.dp).clip(RoundedCornerShape(12.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "뒤로", onClick = onClick).semantics { this.contentDescription = "뒤로" },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Back, contentDescription = null, tint = c.ink, modifier = Modifier.size(20.dp)) }
}

/** 소제목(13sp 굵게 회색, heading) + 흰 카드 + 아래 설명. */
@Composable
private fun Section(title: String, c: SetColors, foot: String? = null, rows: @Composable ColumnScope.() -> Unit) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.sub, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp).semantics { heading() })
    Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(c.card), content = rows)
    if (foot != null) Text(foot, fontSize = 12.5.sp, lineHeight = 20.sp, color = c.sub, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
}

/** 값 줄: 이름(+설명) · 지금 값 · ›. 누르면 값이 돌거나 다른 화면·창. */
@Composable
private fun Value(name: String, value: String, c: SetColors, sub: String = "", chevron: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(start = 14.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(name, fontSize = 15.5.sp, color = c.ink)
            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = c.sub, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        if (value.isNotEmpty()) Text(value, fontSize = 14.5.sp, color = c.sub, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        if (chevron) Icon(Icons.Chevron, contentDescription = null, tint = c.chevron, modifier = Modifier.padding(start = 6.dp).size(14.dp))
    }
    HorizontalDivider(color = c.line, modifier = Modifier.padding(start = 14.dp))
}

/** 스위치 줄: 줄 전체를 눌러 켜고 끔(role=switch, 이름 = 줄 이름). */
@Composable
private fun Toggle(name: String, on: Boolean, c: SetColors, sub: String = "", onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = onToggle).heightIn(min = 52.dp).padding(start = 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(name, fontSize = 15.5.sp, color = c.ink)
            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = c.sub, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = on, onCheckedChange = null, modifier = Modifier.padding(start = 8.dp),
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedTrackColor = Color(0xFF34C759), checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent,
            ),
        )
    }
    HorizontalDivider(color = c.line, modifier = Modifier.padding(start = 14.dp))
}

/** 실행 줄(파란 글자): 백업하기·지우기·화면 열기. */
@Composable
private fun Act(name: String, c: SetColors, sub: String = "", enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 52.dp).padding(start = 14.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(name, fontSize = 15.5.sp, color = if (enabled) c.action else c.sub)
            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = c.sub, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
    HorizontalDivider(color = c.line, modifier = Modifier.padding(start = 14.dp))
}
