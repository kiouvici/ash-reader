// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.pagecurl

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * GLES 2.0 그리기. GL 스레드에서만 쓴다.
 * 좌표는 논리 화면 픽셀(y 아래). 오→왼은 정점 셰이더에서 화면 x 를 반전하고, 그림이 뒤집히지 않도록
 * 텍스처 u 도 함께 반전한다(F-48).
 */
internal class CurlRenderer {
    private var program = 0
    private var aPos = 0
    private var aUv = 0
    private val uni = HashMap<String, Int>()

    private val cols = MESH_COLS + 1
    private val rows = MESH_ROWS + 1
    private val meshData = FloatArray(cols * rows * STRIDE)
    private val meshBuf: FloatBuffer = floatBuffer(meshData.size)
    private val meshIdx: ShortBuffer
    private val meshIdxCount: Int
    private val quadData = FloatArray(4 * STRIDE)
    private val quadBuf: FloatBuffer = floatBuffer(quadData.size)
    private val tmp = FloatArray(4)

    var maxTextureSize = 2048
        private set

    init {
        val idx = ShortArray(MESH_COLS * MESH_ROWS * 6)
        var k = 0
        for (r in 0 until MESH_ROWS) {
            for (c in 0 until MESH_COLS) {
                val i0 = (r * cols + c).toShort()
                val i1 = (r * cols + c + 1).toShort()
                val i2 = ((r + 1) * cols + c).toShort()
                val i3 = ((r + 1) * cols + c + 1).toShort()
                idx[k++] = i0; idx[k++] = i1; idx[k++] = i2
                idx[k++] = i1; idx[k++] = i3; idx[k++] = i2
            }
        }
        meshIdxCount = k
        meshIdx = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        meshIdx.put(idx)
        meshIdx.position(0)
    }

    fun init() {
        program = link(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "a_pos")
        aUv = GLES20.glGetAttribLocation(program, "a_uv")
        for (name in UNIFORMS) uni[name] = GLES20.glGetUniformLocation(program, name)
        val max = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0)
        if (max[0] > 0) maxTextureSize = max[0]
    }

    fun release() {
        if (program != 0) GLES20.glDeleteProgram(program)
        program = 0
    }

    fun upload(bitmap: Bitmap): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        // NPOT 텍스처: ES 2.0 에서 CLAMP_TO_EDGE + 밉맵 없음이면 허용
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return t[0]
    }

    fun deleteTexture(id: Int) {
        if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
    }

    private var curViewW = 0
    private var curViewH = 0

    /**
     * 위아래 [y0]..[y1](논리 좌표, y 아래) 띠 밖에는 그리지 않는다 — 종이만 넘길 때 위아래 여백에 말림·그림자가 넘치지 않게
     * (대표님 2026-10-25 00:16). 가로는 화면 전체.
     */
    fun clipY(y0: Float, y1: Float) {
        val top = y0.toInt().coerceIn(0, curViewH); val bottom = kotlin.math.ceil(y1).toInt().coerceIn(top, curViewH)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(0, curViewH - bottom, curViewW, bottom - top)
    }

    fun noClip() = GLES20.glDisable(GLES20.GL_SCISSOR_TEST)

    fun begin(viewW: Float, viewH: Float, mirror: Boolean, backColor: Int, backMix: Float, shadow: Float) {
        curViewW = viewW.toInt(); curViewH = viewH.toInt()
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        val r = Color.red(backColor) / 255f
        val g = Color.green(backColor) / 255f
        val b = Color.blue(backColor) / 255f
        GLES20.glViewport(0, 0, viewW.toInt(), viewH.toInt())
        GLES20.glClearColor(r, g, b, 1f)
        GLES20.glClearDepthf(1f)
        GLES20.glDepthMask(true)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(u("u_view"), viewW, viewH)
        GLES20.glUniform1f(u("u_mirror"), if (mirror) -1f else 1f)
        GLES20.glUniform1f(u("u_zRange"), maxOf(viewW, viewH) * 2f)
        GLES20.glUniform4f(u("u_backColor"), r, g, b, 1f)
        GLES20.glUniform1f(u("u_backMix"), backMix)
        GLES20.glUniform1f(u("u_shadow"), shadow)
        GLES20.glUniform1i(u("u_tex"), 0)
        GLES20.glUniform1i(u("u_texBack"), 1)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        // 논리 좌표(y 아래)의 격자는 화면에서 시계 방향 → 앞면 = CW. x 반전 시 감김도 반전.
        GLES20.glFrontFace(if (mirror) GLES20.GL_CCW else GLES20.GL_CW)
    }

    /**
     * 평평한 쪽. tex=0 이면 바탕색 빈 종이(M-06). shadowFrom 이 있으면 말린 쪽의 그림자를 받는다.
     * [uv] = 텍스처에서 쓸 부분(u0, v0, u1, v1). null 이면 전체.
     */
    fun drawFlat(x0: Float, y0: Float, w: Float, h: Float, tex: Int, shadowFrom: CurlMath.Curl? = null, uv: FloatArray? = null) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        val q = quadData
        fun v(i: Int, x: Float, y: Float, uu: Float, vv: Float) {
            val o = i * STRIDE
            q[o] = x; q[o + 1] = y; q[o + 2] = 0f; q[o + 3] = 1f; q[o + 4] = uu; q[o + 5] = vv
        }
        val u0 = uv?.get(0) ?: 0f; val v0 = uv?.get(1) ?: 0f; val u1 = uv?.get(2) ?: 1f; val v1 = uv?.get(3) ?: 1f
        v(0, x0, y0, u0, v0)
        v(1, x0 + w, y0, u1, v0)
        v(2, x0, y0 + h, u0, v1)
        v(3, x0 + w, y0 + h, u1, v1)
        quadBuf.position(0)
        quadBuf.put(q)
        GLES20.glUniform1i(u("u_mode"), 0)
        bindFront(tex)
        GLES20.glUniform1f(u("u_hasBackTex"), 0f)
        GLES20.glUniform1f(u("u_backIsTex"), 0f)
        if (shadowFrom != null) {
            GLES20.glUniform1f(u("u_castShadow"), 1f)
            GLES20.glUniform4f(u("u_axis"), shadowFrom.posX, shadowFrom.posY, shadowFrom.dirX, shadowFrom.dirY)
            GLES20.glUniform1f(u("u_radius"), shadowFrom.radius)
        } else {
            GLES20.glUniform1f(u("u_castShadow"), 0f)
        }
        attrib(quadBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * 말리는 쪽 (x0, y0, w, h). 뒷면은 backIsTex 면 텍스처 back(두 쪽 펼침에서 다음 펼침의 왼쪽 쪽),
     * 아니면 앞면을 비춘 모습에 테마 바탕색을 섞는다(M-03).
     * [uv] = 앞면 텍스처에서 쓸 부분(u0, v0, u1, v1, 종이만 말 때). 뒷면은 좌우를 뒤집어(1 - u) 같은 부분을 쓴다.
     */
    fun drawCurl(
        x0: Float, y0: Float, w: Float, h: Float,
        front: Int, back: Int, backIsTex: Boolean, curl: CurlMath.Curl, uv: FloatArray? = null,
    ) {
        val d = meshData
        val u0 = uv?.get(0) ?: 0f; val v0 = uv?.get(1) ?: 0f; val u1 = uv?.get(2) ?: 1f; val v1 = uv?.get(3) ?: 1f
        var o = 0
        for (r in 0 until rows) {
            val vv = r.toFloat() / MESH_ROWS
            val y = y0 + vv * h
            for (c in 0 until cols) {
                val uu = c.toFloat() / MESH_COLS
                CurlMath.apply(x0 + uu * w, y, curl, tmp, 0)
                d[o] = tmp[0]; d[o + 1] = tmp[1]; d[o + 2] = tmp[2]; d[o + 3] = tmp[3]
                d[o + 4] = u0 + uu * (u1 - u0); d[o + 5] = v0 + vv * (v1 - v0)
                o += STRIDE
            }
        }
        meshBuf.position(0)
        meshBuf.put(d)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDepthMask(true)
        GLES20.glUniform1i(u("u_mode"), 1)
        GLES20.glUniform1f(u("u_castShadow"), 0f)
        bindFront(front)
        GLES20.glUniform1f(u("u_backIsTex"), if (backIsTex) 1f else 0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (backIsTex) back else 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1f(u("u_hasBackTex"), if (backIsTex && back != 0) 1f else 0f)
        attrib(meshBuf)
        meshIdx.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, meshIdxCount, GLES20.GL_UNSIGNED_SHORT, meshIdx)
    }

    private fun bindFront(tex: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1f(u("u_hasTex"), if (tex != 0) 1f else 0f)
    }

    private fun attrib(buf: FloatBuffer) {
        buf.position(0)
        GLES20.glVertexAttribPointer(aPos, 4, GLES20.GL_FLOAT, false, STRIDE * 4, buf)
        GLES20.glEnableVertexAttribArray(aPos)
        buf.position(4)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, STRIDE * 4, buf)
        GLES20.glEnableVertexAttribArray(aUv)
        buf.position(0)
    }

    private fun u(name: String): Int = uni[name] ?: -1

    private fun floatBuffer(n: Int): FloatBuffer =
        ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw IllegalStateException("셰이더 컴파일 실패: $log")
        }
        return s
    }

    private fun link(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("프로그램 링크 실패: ${GLES20.glGetProgramInfoLog(p)}")
        return p
    }

    companion object {
        /** 기술 설계서 4.2: 쪽을 40×60 격자로 나눈다 */
        const val MESH_COLS = 40
        const val MESH_ROWS = 60
        /** x, y, z, 밝기, u, v */
        const val STRIDE = 6

        private val UNIFORMS = listOf(
            "u_view", "u_mirror", "u_zRange", "u_tex", "u_texBack", "u_mode", "u_hasTex", "u_hasBackTex",
            "u_backIsTex", "u_backColor", "u_backMix", "u_axis", "u_radius", "u_shadow", "u_castShadow",
        )

        private const val VERTEX = """
uniform vec2 u_view;
uniform float u_mirror;
uniform float u_zRange;
attribute vec4 a_pos;
attribute vec2 a_uv;
varying vec2 v_uv;
varying float v_shade;
varying vec2 v_screen;
void main() {
    v_screen = a_pos.xy;
    float nx = (a_pos.x / u_view.x * 2.0 - 1.0) * u_mirror;
    float ny = 1.0 - a_pos.y / u_view.y * 2.0;
    gl_Position = vec4(nx, ny, -a_pos.z / u_zRange, 1.0);
    v_uv = vec2(u_mirror < 0.0 ? 1.0 - a_uv.x : a_uv.x, a_uv.y);
    v_shade = a_pos.w;
}
"""

        private const val FRAGMENT = """
precision mediump float;
uniform sampler2D u_tex;
uniform sampler2D u_texBack;
uniform int u_mode;
uniform float u_hasTex;
uniform float u_hasBackTex;
uniform float u_backIsTex;
uniform vec4 u_backColor;
uniform float u_backMix;
uniform float u_shadow;
uniform float u_castShadow;
uniform vec4 u_axis;
uniform float u_radius;
varying vec2 v_uv;
varying float v_shade;
varying vec2 v_screen;
void main() {
    vec4 front = u_hasTex > 0.5 ? texture2D(u_tex, v_uv) : u_backColor;
    vec4 c = front;
    if (u_mode == 1 && !gl_FrontFacing) {
        if (u_backIsTex > 0.5) {
            c = u_hasBackTex > 0.5 ? texture2D(u_texBack, vec2(1.0 - v_uv.x, v_uv.y)) : u_backColor;
        } else {
            c = mix(front, u_backColor, u_backMix);
        }
    }
    c.rgb *= mix(1.0, v_shade, u_shadow);
    if (u_castShadow > 0.5) {
        float d = dot(v_screen - u_axis.xy, u_axis.zw);
        float w = u_radius * 1.2 + 24.0;
        float s = (1.0 - smoothstep(u_radius, u_radius + w, d)) * step(0.0, d);
        c.rgb *= 1.0 - 0.35 * u_shadow * s;
    }
    gl_FragColor = vec4(c.rgb, 1.0);
}
"""
    }
}
