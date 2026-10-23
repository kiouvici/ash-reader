// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.tts

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 듣기가 읽을 글을 주는 쪽(읽기 화면이 연 문서). 모든 함수는 백그라운드 스레드에서 불린다. */
interface ListenSource {
    val bookId: String
    val title: String
    val pageCount: Int
    /** 쪽 [page]의 문장들. 글자가 없으면 빈 목록. */
    fun sentences(page: Int): List<Sentence>
    /** [page]가 든 장의 끝(다음 장 첫 쪽, 없으면 pageCount) — '장 끝까지' 잠자기 타이머(F-47). */
    fun chapterEnd(page: Int): Int
}

/**
 * 듣기(F-13, S-27): 기기 TTS 엔진으로 문장 단위로 읽고, 쪽·장을 넘어 이어 읽는다.
 * 화면이 꺼지거나 다른 앱으로 가도 [ListenService](포그라운드 서비스 + 미디어 알림)가 프로세스를 살려 둔다.
 * 상태는 [state] 로 읽기 화면·알림에 알린다. 쪽 글 읽기·문장 나누기는 전용 스레드에서 한다(화면 스레드 아님).
 */
object Listen {
    data class State(
        /** 듣기 바를 보이는 중(멈춤 포함). */
        val active: Boolean = false,
        val playing: Boolean = false,
        val bookId: String = "",
        val title: String = "",
        val cur: Sentence? = null,
        /** 쪽 경계에 걸친 [cur] 를 읽는 중, 다음 쪽 몫에 들어섰다(읽기 화면이 다음 쪽으로 넘긴다). */
        val onNext: Boolean = false,
        /** 잠자기 타이머: 끝나는 시각(elapsedRealtime), 0 = 없음. [sleepChapter] = 이 장 끝까지. */
        val sleepAt: Long = 0L,
        val sleepChapter: Boolean = false,
        /** 화면에 한 번 띄울 안내(끝까지 읽음 등). */
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "ash-listen").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + exec.asCoroutineDispatcher())

    private var app: Context? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private var source: ListenSource? = null
    private var page = 0
    private var list: List<Sentence> = emptyList()
    private var idx = 0
    private var gen = 0
    private var rate = 1f
    private var pitch = 1f
    private var chapterEnd = Int.MAX_VALUE
    private var sleepJob: Job? = null
    private var focus: AudioFocusRequest? = null
    /** 화면 회전 등으로 문서를 다시 여는 동안 재생 중이었나. */
    private var resumeOnAttach = false

    /** TTS 엔진 상태(처음 누를 때 확인, S-27 ①). */
    enum class Check { OK, NO_ENGINE, NO_KOREAN }

    /** 엔진을 준비하고 한국어 음성이 있는지 본다. [done]은 화면 스레드가 아닐 수 있다. */
    fun prepare(context: Context, done: (Check) -> Unit) {
        val c = context.applicationContext
        app = c
        scope.launch {
            val t = tts
            if (t != null && ready) { done(korean(t)); return@launch }
            var holder: TextToSpeech? = null
            holder = TextToSpeech(c) { st ->
                scope.launch {
                    val tt = holder
                    if (st != TextToSpeech.SUCCESS || tt == null) { runCatching { tt?.shutdown() }; tts = null; ready = false; done(Check.NO_ENGINE); return@launch }
                    tts = tt; ready = true
                    tt.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    tt.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        // 쪽 경계에 걸친 문장: 다음 쪽 몫을 읽기 시작하면 화면도 넘긴다(API 26+)
                        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                            scope.launch { onRange(utteranceId, start) }
                        }
                        override fun onDone(utteranceId: String?) { scope.launch { onUtteranceDone(utteranceId) } }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { scope.launch { onUtteranceDone(utteranceId) } }
                        override fun onError(utteranceId: String?, errorCode: Int) { scope.launch { onUtteranceDone(utteranceId) } }
                    })
                    done(korean(tt))
                }
            }
        }
    }

    private fun korean(t: TextToSpeech): Check {
        val r = runCatching { t.isLanguageAvailable(Locale.KOREAN) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        return if (r >= TextToSpeech.LANG_AVAILABLE) { runCatching { t.setLanguage(Locale.KOREAN) }; Check.OK } else Check.NO_KOREAN
    }

    /** 한국어 음성 받기·엔진 설정 화면(S-27 확인창의 [설정 열기]). */
    fun settingsIntents(): List<Intent> = listOf(
        Intent("com.android.settings.TTS_SETTINGS"),
        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
    ).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    /** [src]의 [startPage]쪽 처음부터(또는 [startChar] 위치 문장부터) 읽기 시작. */
    fun start(context: Context, src: ListenSource, startPage: Int, rate: Float, pitch: Float, sleepMin: Int, startChar: Int = 0) {
        app = context.applicationContext
        scope.launch {
            this@Listen.rate = rate; this@Listen.pitch = pitch
            source = src
            gen++
            if (!load(startPage.coerceIn(0, (src.pageCount - 1).coerceAtLeast(0)), forward = true)) { finish("이 문서에는 읽을 글자가 없어요."); return@launch }
            idx = list.indexOfFirst { it.end > startChar }.coerceAtLeast(0)
            _state.value = State(active = true, playing = true, bookId = src.bookId, title = src.title)
            applySleep(sleepMin)
            requestFocus()
            startService()
            speakCurrent()
        }
    }

    /** 읽기 화면이 다시 만들어짐(회전 등): 새로 연 문서로 바꿔 끼우고 읽던 비율 위치에서 잇는다. */
    fun attach(src: ListenSource) {
        scope.launch {
            val st = _state.value
            if (!st.active || st.bookId != src.bookId) return@launch
            val old = source
            source = src
            if (old != null && old !== src && old.pageCount != src.pageCount && old.pageCount > 0) {
                val p = ((page + 0.5f) / old.pageCount * src.pageCount).toInt().coerceIn(0, src.pageCount - 1)
                gen++
                if (load(p, forward = true)) idx = 0
            }
            if (resumeOnAttach) { resumeOnAttach = false; play() }
        }
    }

    /** 문서가 닫힘. [keep] = 곧 다시 붙음(회전) → 멈춰 두기만, 아니면 끝. */
    fun detach(src: ListenSource, keep: Boolean) {
        scope.launch {
            if (source !== src) return@launch
            if (keep) { resumeOnAttach = _state.value.playing; pauseInternal() } else finish(null)
        }
    }

    /** 읽기 화면을 떠남(회전 아님): 이 문서를 듣는 중이면 끝내고 서비스·알림을 내린다. */
    fun leave(bookId: String) {
        scope.launch { if (_state.value.active && _state.value.bookId == bookId) finish(null) }
    }

    fun toggle() = scope.launch { if (_state.value.playing) pauseInternal() else play() }
    fun pause() = scope.launch { pauseInternal() }
    fun resume() = scope.launch { play() }

    /** 멈춘 상태에서 사용자가 다른 쪽으로 넘겼으면 그 쪽부터 다시 읽는다. */
    fun resumeAt(page: Int) = scope.launch {
        val src = source ?: return@launch
        if (page != this@Listen.page && page in 0 until src.pageCount) {
            gen++
            if (!load(page, forward = true)) return@launch
            idx = 0
        }
        play()
    }

    fun next() = scope.launch { step(+1) }
    fun prev() = scope.launch { step(-1) }
    fun stop() = scope.launch { finish(null) }

    fun setRate(r: Float) = scope.launch { rate = r; if (_state.value.playing) speakCurrent() }
    fun setPitch(p: Float) = scope.launch { pitch = p; if (_state.value.playing) speakCurrent() }

    /** 잠자기 타이머(F-47): 분(0 = 끔, -1 = 이 장 끝까지). */
    fun setSleep(min: Int) = scope.launch { applySleep(min) }

    fun consumeMessage() { _state.value = _state.value.copy(message = null) }

    // ───────── 안쪽(전용 스레드) ─────────

    private fun applySleep(min: Int) {
        sleepJob?.cancel(); sleepJob = null
        val src = source
        when {
            min > 0 -> {
                val at = SystemClock.elapsedRealtime() + min * 60_000L
                _state.value = _state.value.copy(sleepAt = at, sleepChapter = false)
                sleepJob = scope.launch {
                    delay(min * 60_000L)
                    pauseInternal()
                    _state.value = _state.value.copy(sleepAt = 0L, message = "잠자기 타이머로 듣기를 멈췄어요.")
                }
            }
            min < 0 && src != null -> {
                chapterEnd = runCatching { src.chapterEnd(page) }.getOrDefault(src.pageCount)
                _state.value = _state.value.copy(sleepAt = 0L, sleepChapter = true)
            }
            else -> _state.value = _state.value.copy(sleepAt = 0L, sleepChapter = false)
        }
    }

    /** [p]쪽(없으면 [forward] 방향으로 글자 있는 쪽)을 불러온다. 실패하면 false. */
    private fun load(p: Int, forward: Boolean): Boolean {
        val src = source ?: return false
        var q = p
        var tries = 0
        while (q in 0 until src.pageCount && tries < 40) {
            val l = runCatching { src.sentences(q) }.getOrDefault(emptyList())
            if (l.isNotEmpty()) { page = q; list = l; return true }
            q += if (forward) 1 else -1
            tries++
        }
        return false
    }

    private fun play() {
        if (source == null || list.isEmpty()) return
        if (!requestFocus()) return
        _state.value = _state.value.copy(active = true, playing = true)
        startService()
        speakCurrent()
    }

    private fun pauseInternal() {
        gen++
        runCatching { tts?.stop() }
        _state.value = _state.value.copy(playing = false)
    }

    private fun speakCurrent() {
        val t = tts ?: return
        val s = list.getOrNull(idx) ?: return
        gen++
        _state.value = _state.value.copy(cur = s, onNext = false)
        t.setSpeechRate(rate); t.setPitch(pitch)
        t.speak(s.text, TextToSpeech.QUEUE_FLUSH, null, "ash-$gen")
    }

    private fun onRange(id: String?, start: Int) {
        if (id != "ash-$gen") return
        val st = _state.value
        val c = st.cur ?: return
        if (c.spans && !st.onNext && start >= c.split) _state.value = st.copy(onNext = true)
    }

    private fun onUtteranceDone(id: String?) {
        if (id != "ash-$gen" || !_state.value.playing) return
        step(+1)
    }

    private fun step(d: Int) {
        val src = source ?: return
        var i = idx + d
        if (i >= list.size) {
            val np = page + 1
            if (_state.value.sleepChapter && np >= chapterEnd) {
                pauseInternal(); _state.value = _state.value.copy(sleepChapter = false, message = "이 장을 다 읽어서 듣기를 멈췄어요."); return
            }
            if (np >= src.pageCount || !load(np, forward = true)) { pauseInternal(); _state.value = _state.value.copy(message = "끝까지 읽었어요."); return }
            i = 0
        } else if (i < 0) {
            if (page <= 0 || !load(page - 1, forward = false)) i = 0 else i = list.size - 1
        }
        idx = i.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        if (_state.value.playing) speakCurrent() else _state.value = _state.value.copy(cur = list.getOrNull(idx), onNext = false)
    }

    private fun finish(msg: String?) {
        gen++
        sleepJob?.cancel(); sleepJob = null
        runCatching { tts?.stop() }
        abandonFocus()
        source = null; list = emptyList(); idx = 0
        _state.value = State(message = msg)
        app?.let { ListenService.stop(it) }
    }

    private fun startService() { app?.let { ListenService.start(it) } }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) scope.launch { pauseInternal() }
    }

    private fun requestFocus(): Boolean {
        val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(focusListener)
            .build().also { focus = it }
        return am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }

    private fun abandonFocus() {
        val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        focus?.let { am.abandonAudioFocusRequest(it) }
    }

    /** 시험·진단용: 지금 SDK 가 알림 권한을 따로 묻나(Android 13+). */
    val needsNotificationPermission: Boolean get() = Build.VERSION.SDK_INT >= 33
}
