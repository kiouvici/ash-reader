// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.bench

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Display
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** 첫 화면 기기 정보 (기술 설계서 9.0.2). 표시 순서대로 (항목, 값). */
data class DeviceInfo(val items: List<Pair<String, String>>) {
    fun value(key: String): String = items.firstOrNull { it.first == key }?.second ?: ""
}

object DeviceInfoCollector {
    @Volatile private var cached: DeviceInfo? = null

    suspend fun collect(context: Context, refresh: Boolean = false): DeviceInfo {
        if (!refresh) cached?.let { return it }
        val app = context.applicationContext
        val gl = withContext(Dispatchers.Default) { GlProbe.query() }
        val tts = withContext(Dispatchers.Main) { TtsProbe.query(app) }
        val items = buildList {
            add("모델" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            add("안드로이드" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), 빌드 ${Build.DISPLAY}")
            add("SoC" to socName())
            add("GPU" to (gl?.let { "${it.renderer} (${it.vendor})" } ?: "확인 실패"))
            add("OpenGL ES" to (gl?.version ?: "확인 실패"))
            add("Vulkan" to vulkanVersion(app.packageManager))
            add("ABI" to Build.SUPPORTED_ABIS.joinToString())
            add("RAM" to ramText(app))
            addAll(displayItems(app))
            add("기본 TTS 엔진" to tts)
            addAll(stateItems(app))
            add("측정 앱 빌드" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
                "${BuildConfig.BUILD_TYPE}, 커밋 ${BuildConfig.GIT_SHA.take(7)}")
        }
        return DeviceInfo(items).also { cached = it }
    }

    private fun socName(): String {
        val hw = "hardware ${Build.HARDWARE}, board ${Build.BOARD}"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL} ($hw)"
        } else {
            "$hw (SOC_MODEL은 Android 12+)"
        }
    }

    /** FEATURE_VULKAN_HARDWARE_VERSION 값을 major.minor.patch 로 풀어 쓴다. */
    fun vulkanVersion(pm: PackageManager): String {
        val features = pm.systemAvailableFeatures
        val ver = features.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }?.version
        val level = features.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL }?.version
        if (ver == null || ver == 0) return "지원 안 함"
        return "${decodeVulkan(ver)} (level ${level ?: "?"})"
    }

    fun decodeVulkan(v: Int): String = "${v ushr 22}.${(v ushr 12) and 0x3ff}.${v and 0xfff}"

    private fun ramText(context: Context): String {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val gb = mi.totalMem / (1024.0 * 1024 * 1024)
        return String.format(Locale.ROOT, "%.1f GB (앱 힙 %d MB / large %d MB)", gb, am.memoryClass, am.largeMemoryClass)
    }

    private fun displayItems(context: Context): List<Pair<String, String>> {
        val dm = context.getSystemService(DisplayManager::class.java)
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return listOf("화면" to "확인 실패")
        val mode = d.mode
        val rates = d.supportedModes.map { it.refreshRate }.distinct().sorted()
            .joinToString("/") { String.format(Locale.ROOT, "%.0f", it) }
        val cfg = context.resources.configuration
        val tablet = cfg.smallestScreenWidthDp >= 600
        return listOf(
            "화면 해상도" to "${mode.physicalWidth}×${mode.physicalHeight}, ${context.resources.displayMetrics.densityDpi}dpi, " +
                "짧은 변 ${cfg.smallestScreenWidthDp}dp (${if (tablet) "태블릿" else "휴대폰"})",
            "주사율" to String.format(Locale.ROOT, "현재 %.1fHz (지원 %sHz)", d.refreshRate, rates),
            "방향" to if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) "가로" else "세로",
        )
    }

    /** 측정 규칙 9.0의 기기 상태(충전량·온도·절전·비행기 모드·밝기) */
    private fun stateItems(context: Context): List<Pair<String, String>> {
        val bat = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val pm = context.getSystemService(PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus.toString() else "-"
        val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val brightness = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()
        val pct = if (scale > 0 && level >= 0) level * 100 / scale else -1
        return listOf(
            "배터리" to String.format(Locale.ROOT, "%d%%, %.1f℃, 충전기 %s", pct, temp, if (plugged) "연결" else "분리"),
            "기기 상태" to "절전 ${if (pm.isPowerSaveMode) "켜짐" else "꺼짐"}, 발열 단계 $thermal, " +
                "비행기 모드 ${if (airplane) "켜짐" else "꺼짐"}, 밝기 값 ${brightness ?: "?"}/255",
        )
    }
}

/** 화면 밖 1×1 EGL 컨텍스트로 GPU 이름을 읽는다. 공유 디스플레이라 eglTerminate 는 하지 않는다. */
private object GlProbe {
    data class Gl(val renderer: String, val vendor: String, val version: String)

    fun query(): Gl? = runCatching {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        check(EGL14.eglInitialize(dpy, v, 0, v, 1))
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(dpy, attrs, 0, configs, 0, 1, num, 0) && num[0] > 0)
        val ctx = EGL14.eglCreateContext(
            dpy, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        val surf = EGL14.eglCreatePbufferSurface(
            dpy, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        try {
            check(EGL14.eglMakeCurrent(dpy, surf, surf, ctx))
            Gl(
                renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "?",
                vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "?",
                version = GLES20.glGetString(GLES20.GL_VERSION) ?: "?",
            )
        } finally {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surf)
            EGL14.eglDestroyContext(dpy, ctx)
        }
    }.getOrNull()
}

/** 기본 TTS 엔진과 설치된 엔진 목록. 중국판(ZUI) 기기는 엔진이 없을 수 있다(9.0.2 ①). */
private object TtsProbe {
    suspend fun query(context: Context): String {
        var tts: TextToSpeech? = null
        val ok = withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { cont ->
                tts = TextToSpeech(context) { status -> if (cont.isActive) cont.resume(status == TextToSpeech.SUCCESS) }
            }
        } ?: false
        val t = tts ?: return "확인 실패"
        return try {
            val engines = t.engines.map { it.name }
            if (engines.isEmpty()) return "설치된 엔진 없음"
            val def = t.defaultEngine ?: "?"
            val voice = if (ok) runCatching { t.voice?.locale?.toLanguageTag() }.getOrNull() else null
            buildString {
                append(def)
                if (voice != null) append(" (음성 $voice)")
                if (!ok) append(" (초기화 실패)")
                if (engines.size > 1) append(", 설치: ${engines.joinToString()}")
            }
        } finally {
            t.shutdown()
        }
    }
}
