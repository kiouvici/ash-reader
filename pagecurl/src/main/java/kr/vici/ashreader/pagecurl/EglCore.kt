// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.pagecurl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log

/** SurfaceView 의 Surface 에 그리는 EGL14 + GLES 2.0 컨텍스트 (전용 GL 스레드에서만 사용). */
internal class EglCore {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    /** [window] = Surface·SurfaceTexture·SurfaceHolder 중 하나 (eglCreateWindowSurface 가 받는 형식). */
    fun init(window: Any): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return fail("eglInitialize")
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
            return fail("eglChooseConfig")
        }
        val config = configs[0]
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) return fail("eglCreateContext")
        // 화면 바탕(Surface)이 GL 스레드에 닿기 전에 사라졌으면 IllegalArgumentException(API 26 CI: 회전·다시 배치 중 앱 죽음)
        surface = try {
            EGL14.eglCreateWindowSurface(display, config, window, intArrayOf(EGL14.EGL_NONE), 0)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "eglCreateWindowSurface: 화면 바탕이 이미 사라짐", e)
            EGL14.EGL_NO_SURFACE
        }
        if (surface == EGL14.EGL_NO_SURFACE) return fail("eglCreateWindowSurface")
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return fail("eglMakeCurrent")
        // vsync 에 맞춰 교체 (기본값이지만 명시)
        EGL14.eglSwapInterval(display, 1)
        return true
    }

    fun swap(): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
    }

    private fun fail(what: String): Boolean {
        Log.e(TAG, "$what 실패: 0x${Integer.toHexString(EGL14.eglGetError())}")
        release()
        return false
    }

    companion object {
        const val TAG = "PageCurl"
    }
}
