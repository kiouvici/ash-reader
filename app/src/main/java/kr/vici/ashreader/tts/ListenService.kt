// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kr.vici.ashreader.MainActivity
import kr.vici.ashreader.R

/**
 * 듣기 포그라운드 서비스(F-13, S-27): 화면이 꺼지거나 다른 앱으로 가도 계속 읽게 프로세스를 살려 두고,
 * 미디어 알림(제목·이전 문장·재생/멈춤·다음 문장·닫기)과 미디어 세션(이어폰 버튼)을 둔다.
 * 읽기 자체는 [Listen] 이 한다. Android 14+ 는 FOREGROUND_SERVICE_MEDIA_PLAYBACK 을 선언했다(매니페스트).
 */
class ListenService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSession? = null
    private var watch: Job? = null
    private var lastKey = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        channel(this)
        session = MediaSession(this, "ash-listen").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { Listen.resume() }
                override fun onPause() { Listen.pause() }
                override fun onSkipToNext() { Listen.next() }
                override fun onSkipToPrevious() { Listen.prev() }
                override fun onStop() { Listen.stop() }
            })
            isActive = true
        }
        foreground(build(Listen.state.value))
        watch = scope.launch {
            Listen.state.collect { st ->
                if (!st.active) { stopSelf(); return@collect }
                update(st)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_TOGGLE -> Listen.toggle()
            ACT_NEXT -> Listen.next()
            ACT_PREV -> Listen.prev()
            ACT_STOP -> Listen.stop()
        }
        // startForegroundService 뒤 5초 안에 늘 포그라운드로
        foreground(build(Listen.state.value))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        watch?.cancel()
        scope.cancel()
        session?.run { isActive = false; release() }
        session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun foreground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTI_ID, n)
    }

    private fun update(st: Listen.State) {
        val s = session ?: return
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
        s.setPlaybackState(
            PlaybackState.Builder().setActions(actions)
                .setState(if (st.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        val key = "${st.playing}|${st.title}|${st.cur?.page}"
        if (key == lastKey) return
        lastKey = key
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, st.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, st.cur?.let { "${it.page + 1}쪽" } ?: "ASH Reader")
                .build(),
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.notify(NOTI_ID, build(st))
    }

    private fun pi(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, ListenService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun act(icon: Int, title: String, action: String, code: Int) =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi(action, code)).build()

    private fun build(st: Listen.State): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_listen)
            .setContentTitle(st.title.ifEmpty { "ASH Reader" })
            .setContentText(if (st.playing) "듣는 중" + (st.cur?.let { " · ${it.page + 1}쪽" } ?: "") else "멈춤")
            .setContentIntent(open)
            .setOngoing(st.playing)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(act(android.R.drawable.ic_media_previous, "이전 문장", ACT_PREV, 1))
            .addAction(
                if (st.playing) act(android.R.drawable.ic_media_pause, "멈춤", ACT_TOGGLE, 2)
                else act(android.R.drawable.ic_media_play, "재생", ACT_TOGGLE, 2),
            )
            .addAction(act(android.R.drawable.ic_media_next, "다음 문장", ACT_NEXT, 3))
            .addAction(act(android.R.drawable.ic_menu_close_clear_cancel, "닫기", ACT_STOP, 4))
            .setDeleteIntent(pi(ACT_STOP, 5))
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        session?.let { style.setMediaSession(it.sessionToken) }
        b.setStyle(style)
        return b.build()
    }

    companion object {
        private const val CHANNEL = "listen"
        private const val NOTI_ID = 4107
        const val ACT_TOGGLE = "kr.vici.ashreader.listen.TOGGLE"
        const val ACT_NEXT = "kr.vici.ashreader.listen.NEXT"
        const val ACT_PREV = "kr.vici.ashreader.listen.PREV"
        const val ACT_STOP = "kr.vici.ashreader.listen.STOP"

        @Volatile private var running = false

        private fun channel(c: Context) {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "듣기", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "화면을 끈 뒤에도 책을 계속 읽어 주는 동안 보이는 알림"
                        setShowBadge(false)
                    },
                )
            }
        }

        /** 듣기 시작(화면에서 누를 때). 이미 떠 있으면 그대로 둔다. */
        fun start(c: Context) {
            if (running) return
            runCatching { c.startForegroundService(Intent(c, ListenService::class.java)) }
        }

        fun stop(c: Context) {
            if (!running) return
            runCatching { c.stopService(Intent(c, ListenService::class.java)) }
        }
    }
}
