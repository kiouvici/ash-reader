// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import kotlin.math.abs

/**
 * Windows 그림 파일(EMF·WMF) → 그리기 목록(Word 원본 보기 F-30의 그림). 안드로이드 의존 없음 → 단위 시험.
 * 앱이 이 목록을 안드로이드 Canvas 로 PNG 로 그려 쪽 묶음에 넣는다(MuPDF 는 EMF·WMF 를 읽지 못한다).
 *
 * 다루는 것(기본 GDI): 창·뷰포트 좌표(MM_TEXT·ANISOTROPIC·ISOTROPIC 등), 월드 변환, 펜·붓·글꼴 만들기/고르기/지우기(기본 개체 포함),
 * 선·다각형·여러 다각형·베지어·사각형·둥근 사각형·타원, 경로(BEGINPATH…FILLPATH/STROKEPATH), 글자(EXTTEXTOUTW·WMF TEXTOUT/EXTTEXTOUT),
 * 그림(BITBLT·STRETCHBLT·STRETCHDIBITS·WMF DIBSTRETCHBLT/STRETCHDIB/DIBBITBLT → BMP), 저장/복원, 사각형 자르기.
 * 다루지 않는 것: EMF+ 기록(GDI 대체 기록으로 그린다), 호·부채꼴, 무늬 붓, 래스터 연산(ROP)은 그대로 복사로 본다.
 */
class Metafile(
    /** 그림 범위(장치 단위). */
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val ops: List<Op>,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    sealed class Seg {
        class Move(val x: Float, val y: Float) : Seg()
        class Line(val x: Float, val y: Float) : Seg()
        class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : Seg()
        object Close : Seg()
    }

    sealed class Op {
        /** [fill]·[stroke] = ARGB(없으면 null). [strokeW] = 장치 단위. */
        class Shape(val segs: List<Seg>, val fill: Int?, val stroke: Int?, val strokeW: Float, val evenOdd: Boolean) : Op()
        /** 글자: 기준점 ([x], [y]), [size] = 글자 높이(장치 단위), [hAlign] 0 왼 1 가운데 2 오른, [vAlign] 0 위 1 기준선 2 아래, [angle] 도(반시계). */
        class Text(
            val x: Float, val y: Float, val text: String, val size: Float, val bold: Boolean, val italic: Boolean, val underline: Boolean,
            val color: Int, val hAlign: Int, val vAlign: Int, val face: String?, val angle: Float,
            /** 글자마다 앞 글자에서 띄울 거리(장치 단위, 없으면 null). */
            val dx: FloatArray?,
        ) : Op()
        /** 그림(BMP 파일 바이트)을 사각형에. */
        class Image(val l: Float, val t: Float, val r: Float, val b: Float, val bmp: ByteArray) : Op()
        object Save : Op()
        object Restore : Op()
        class Clip(val l: Float, val t: Float, val r: Float, val b: Float) : Op()
    }

    companion object {
        /** EMF·WMF 바이트를 읽는다. 알 수 없는 형식이거나 그릴 것이 없으면 null. */
        fun parse(bytes: ByteArray): Metafile? = runCatching {
            when {
                isEmf(bytes) -> Emf(bytes).run()
                isWmf(bytes) -> Wmf(bytes).run()
                else -> null
            }
        }.getOrNull()?.takeIf { it.ops.isNotEmpty() && it.width > 0f && it.height > 0f }

        fun isEmf(b: ByteArray) = b.size >= 88 && le32(b, 0) == 1 && le32(b, 40) == 0x464D4520
        fun isWmf(b: ByteArray) = b.size >= 18 && (le32(b, 0) == 0x9AC6CDD7.toInt() || (le16(b, 0) in 1..2 && le16(b, 2) == 9))

        internal fun le32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
        internal fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        /** COLORREF(0x00BBGGRR) → ARGB. */
        internal fun colorRef(v: Int): Int = (0xFF shl 24) or ((v and 0xFF) shl 16) or (v and 0xFF00) or ((v shr 16) and 0xFF)

        /** DIB(BITMAPINFOHEADER + 색표 + 화소) → BMP 파일 바이트(BITMAPFILEHEADER 를 붙임). */
        fun dibToBmp(dib: ByteArray, bitsOffsetInDib: Int = -1): ByteArray? {
            if (dib.size < 40) return null
            val hdr = le32(dib, 0)
            if (hdr < 12 || hdr > dib.size) return null
            val off = if (bitsOffsetInDib >= 0) bitsOffsetInDib else {
                if (hdr == 12) return null // OS/2 머리(드묾)
                val bpp = le16(dib, 14)
                val comp = le32(dib, 16)
                var used = le32(dib, 32)
                if (used == 0 && bpp <= 8) used = 1 shl bpp
                val masks = if (comp == 3 && hdr == 40) 12 else 0
                hdr + masks + used * 4
            }
            if (off > dib.size) return null
            val out = ByteArray(14 + dib.size)
            out[0] = 'B'.code.toByte(); out[1] = 'M'.code.toByte()
            put32(out, 2, out.size); put32(out, 10, 14 + off)
            System.arraycopy(dib, 0, out, 14, dib.size)
            return out
        }

        private fun put32(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte() }

        /** 타원을 베지어 4개로. */
        internal fun ellipse(l: Float, t: Float, r: Float, b: Float): List<Seg> {
            val cx = (l + r) / 2f; val cy = (t + b) / 2f; val rx = (r - l) / 2f; val ry = (b - t) / 2f
            val k = 0.5522848f
            return listOf(
                Seg.Move(cx + rx, cy),
                Seg.Cubic(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry),
                Seg.Cubic(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy),
                Seg.Cubic(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry),
                Seg.Cubic(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy),
                Seg.Close,
            )
        }

        internal fun roundRect(l: Float, t: Float, r: Float, b: Float, rx0: Float, ry0: Float): List<Seg> {
            val rx = minOf(abs(rx0), (r - l) / 2f); val ry = minOf(abs(ry0), (b - t) / 2f)
            if (rx <= 0f || ry <= 0f) return rect(l, t, r, b)
            val k = 0.5522848f
            return listOf(
                Seg.Move(l + rx, t), Seg.Line(r - rx, t), Seg.Cubic(r - rx + rx * k, t, r, t + ry - ry * k, r, t + ry),
                Seg.Line(r, b - ry), Seg.Cubic(r, b - ry + ry * k, r - rx + rx * k, b, r - rx, b),
                Seg.Line(l + rx, b), Seg.Cubic(l + rx - rx * k, b, l, b - ry + ry * k, l, b - ry),
                Seg.Line(l, t + ry), Seg.Cubic(l, t + ry - ry * k, l + rx - rx * k, t, l + rx, t), Seg.Close,
            )
        }

        internal fun rect(l: Float, t: Float, r: Float, b: Float): List<Seg> =
            listOf(Seg.Move(l, t), Seg.Line(r, t), Seg.Line(r, b), Seg.Line(l, b), Seg.Close)

        /** 한국어 WMF 글자(CP949) 해석기. */
        internal val cp949: Charset = listOf("x-windows-949", "MS949", "EUC-KR").firstNotNullOfOrNull { n -> runCatching { Charset.forName(n) }.getOrNull() } ?: Charsets.ISO_8859_1
    }

    // ───────────── 공통 GDI 상태 ─────────────

    private class Pen(val color: Int, val width: Float, val none: Boolean)
    private class Brush(val color: Int, val none: Boolean)
    private class FontSpec(val height: Float, val bold: Boolean, val italic: Boolean, val underline: Boolean, val face: String?, val escapement: Float, val charset: Int)

    /** 좌표·개체 상태(저장/복원 단위). */
    private class Dc(
        var mapMode: Int = 1,
        var winOrgX: Float = 0f, var winOrgY: Float = 0f, var winExtX: Float = 1f, var winExtY: Float = 1f,
        var vpOrgX: Float = 0f, var vpOrgY: Float = 0f, var vpExtX: Float = 1f, var vpExtY: Float = 1f,
        // 월드 변환 m11 m12 m21 m22 dx dy
        var w: FloatArray = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f),
        var pen: Pen = Pen(0xFF000000.toInt(), 0f, false),
        var brush: Brush = Brush(0xFFFFFFFF.toInt(), false),
        var font: FontSpec = FontSpec(12f, false, false, false, null, 0f, 0),
        var textColor: Int = 0xFF000000.toInt(),
        var textAlign: Int = 0,
        var evenOdd: Boolean = true,
        var curX: Float = 0f, var curY: Float = 0f,
    ) {
        fun copy() = Dc(mapMode, winOrgX, winOrgY, winExtX, winExtY, vpOrgX, vpOrgY, vpExtX, vpExtY, w.copyOf(), pen, brush, font, textColor, textAlign, evenOdd, curX, curY)

        fun sx(): Float = if (mapMode == 7 || mapMode == 8) (if (winExtX != 0f) vpExtX / winExtX else 1f) else 1f
        fun sy(): Float = if (mapMode == 7 || mapMode == 8) (if (winExtY != 0f) vpExtY / winExtY else 1f) else 1f

        /** 논리 좌표 → 장치 좌표. */
        fun x(lx: Float, ly: Float): Float { val wx = w[0] * lx + w[2] * ly + w[4]; return (wx - winOrgX) * scaleX() + vpOrgX }
        fun y(lx: Float, ly: Float): Float { val wy = w[1] * lx + w[3] * ly + w[5]; return (wy - winOrgY) * scaleY() + vpOrgY }
        /** 길이(펜 굵기·글자 높이) → 장치 단위. */
        fun len(v: Float): Float = abs(v) * (abs(scaleX()) + abs(scaleY())) / 2f * kotlin.math.sqrt(abs(w[0] * w[3] - w[1] * w[2]).coerceAtLeast(1e-6f))

        fun scaleX(): Float = when (mapMode) {
            7, 8 -> sx()
            2 -> 3.7795277f // MM_LOMETRIC 0.1mm → 96dpi 화소
            3 -> 0.37795277f // MM_HIMETRIC 0.01mm
            4 -> 0.96f // MM_LOENGLISH 0.01in
            5 -> 0.096f // MM_HIENGLISH 0.001in
            6 -> 0.06666667f // MM_TWIPS
            else -> 1f
        }
        // y 가 위로 가는 매핑 모드는 뒤집는다
        fun scaleY(): Float = when (mapMode) { 7, 8 -> sy(); 1 -> 1f; else -> -scaleX() }
    }

    private abstract class Reader(val b: ByteArray) {
        val ops = ArrayList<Op>()
        var dc = Dc()
        val stack = ArrayList<Dc>()
        val objects = HashMap<Int, Any>()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE

        fun grow(x: Float, y: Float) { if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y }

        fun stockObject(i: Int): Any? = when (i) {
            0 -> Brush(0xFFFFFFFF.toInt(), false)
            1 -> Brush(0xFFC0C0C0.toInt(), false)
            2 -> Brush(0xFF808080.toInt(), false)
            3 -> Brush(0xFF404040.toInt(), false)
            4 -> Brush(0xFF000000.toInt(), false)
            5 -> Brush(0, true)
            6 -> Pen(0xFFFFFFFF.toInt(), 0f, false)
            7 -> Pen(0xFF000000.toInt(), 0f, false)
            8 -> Pen(0, 0f, true)
            else -> null
        }

        fun select(o: Any?) {
            when (o) {
                is Pen -> dc.pen = o
                is Brush -> dc.brush = o
                is FontSpec -> dc.font = o
            }
        }

        fun penW(): Float = if (dc.pen.width <= 0f) 1f else dc.len(dc.pen.width).coerceAtLeast(0.5f)

        fun shape(segs: List<Seg>, fill: Boolean, stroke: Boolean) {
            if (segs.isEmpty()) return
            for (s in segs) when (s) {
                is Seg.Move -> grow(s.x, s.y)
                is Seg.Line -> grow(s.x, s.y)
                is Seg.Cubic -> grow(s.x, s.y)
                Seg.Close -> {}
            }
            val f = if (fill && !dc.brush.none) dc.brush.color else null
            val st = if (stroke && !dc.pen.none) dc.pen.color else null
            if (f == null && st == null) return
            ops += Op.Shape(segs, f, st, penW(), dc.evenOdd)
        }

        fun pts(xs: FloatArray, ys: FloatArray, close: Boolean): List<Seg> {
            if (xs.isEmpty()) return emptyList()
            val out = ArrayList<Seg>(xs.size + 1)
            out += Seg.Move(dc.x(xs[0], ys[0]), dc.y(xs[0], ys[0]))
            for (i in 1 until xs.size) out += Seg.Line(dc.x(xs[i], ys[i]), dc.y(xs[i], ys[i]))
            if (close) out += Seg.Close
            return out
        }

        /** 논리 사각형 → 장치 사각형(뒤집힘 정리). */
        fun box(l: Float, t: Float, r: Float, bb: Float): FloatArray {
            val x0 = dc.x(l, t); val y0 = dc.y(l, t); val x1 = dc.x(r, bb); val y1 = dc.y(r, bb)
            return floatArrayOf(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
        }

        fun text(lx: Float, ly: Float, s: String, dxLogical: FloatArray?) {
            if (s.isEmpty()) return
            val f = dc.font
            val size = dc.len(if (f.height == 0f) 12f else f.height)
            val x = dc.x(lx, ly); val y = dc.y(lx, ly)
            val ta = dc.textAlign
            val h = when (ta and 6) { 6 -> 1; 2 -> 2; else -> 0 }
            val v = when (ta and 24) { 24 -> 1; 8 -> 2; else -> 0 }
            val dx = dxLogical?.let { a -> FloatArray(a.size) { i -> dc.len(a[i]) } }
            grow(x, y); grow(x, y + (if (v == 0) size else -size))
            ops += Op.Text(x, y, s, size, f.bold, f.italic, f.underline, dc.textColor, h, v, f.face, f.escapement, dx)
        }

        abstract fun run(): Metafile?
    }

    // ───────────── EMF ─────────────

    private class Emf(b: ByteArray) : Reader(b) {
        val bb: ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var path: ArrayList<Seg>? = null
        var pathStartX = 0f; var pathStartY = 0f

        fun i32(o: Int) = bb.getInt(o)
        fun f32(o: Int) = bb.getFloat(o)
        fun i16(o: Int) = bb.getShort(o).toInt()

        fun emit(segs: List<Seg>, fill: Boolean, stroke: Boolean) {
            val p = path
            if (p != null) { p += segs; return }
            shape(segs, fill, stroke)
        }

        override fun run(): Metafile? {
            // 머리: rclBounds(장치) 8, rclFrame(0.01mm) 24
            var bl = i32(8).toFloat(); var bt = i32(12).toFloat(); var br = i32(16).toFloat(); var bbm = i32(20).toFloat()
            var o = 0
            var guard = 0
            while (o + 8 <= b.size && guard++ < 2_000_000) {
                val type = i32(o); val size = i32(o + 4)
                if (size < 8 || o + size > b.size) break
                runCatching { record(type, o, size) }
                if (type == 14) break
                o += size
            }
            if (br <= bl || bbm <= bt) { bl = minX; bt = minY; br = maxX; bbm = maxY }
            if (!(br > bl && bbm > bt)) return null
            return Metafile(bl, bt, br + 1f, bbm + 1f, ops)
        }

        fun polyPts(o: Int, n: Int, small: Boolean): Pair<FloatArray, FloatArray> {
            val xs = FloatArray(n); val ys = FloatArray(n)
            for (i in 0 until n) {
                if (small) { xs[i] = i16(o + i * 4).toFloat(); ys[i] = i16(o + i * 4 + 2).toFloat() }
                else { xs[i] = i32(o + i * 8).toFloat(); ys[i] = i32(o + i * 8 + 4).toFloat() }
            }
            return xs to ys
        }

        /** 베지어 점들 → 조각. [startWithMove] = 첫 점이 시작점(POLYBEZIER), 아니면 지금 위치에서 시작(POLYBEZIERTO). */
        fun bezier(xs: FloatArray, ys: FloatArray, startWithMove: Boolean, sx: Float, sy: Float): List<Seg> {
            val out = ArrayList<Seg>()
            var i = 0
            if (startWithMove) { out += Seg.Move(dc.x(xs[0], ys[0]), dc.y(xs[0], ys[0])); i = 1 } else out += Seg.Move(dc.x(sx, sy), dc.y(sx, sy))
            while (i + 2 < xs.size) {
                out += Seg.Cubic(dc.x(xs[i], ys[i]), dc.y(xs[i], ys[i]), dc.x(xs[i + 1], ys[i + 1]), dc.y(xs[i + 1], ys[i + 1]), dc.x(xs[i + 2], ys[i + 2]), dc.y(xs[i + 2], ys[i + 2]))
                i += 3
            }
            return out
        }

        fun lineTo(x: Float, y: Float) {
            val p = path
            if (p != null) { p += Seg.Line(dc.x(x, y), dc.y(x, y)) }
            else shape(listOf(Seg.Move(dc.x(dc.curX, dc.curY), dc.y(dc.curX, dc.curY)), Seg.Line(dc.x(x, y), dc.y(x, y))), fill = false, stroke = true)
            dc.curX = x; dc.curY = y
        }

        fun record(t: Int, o: Int, size: Int) {
            when (t) {
                2, 3, 4, 85, 86, 87 -> { // POLYBEZIER POLYGON POLYLINE (16)
                    val small = t >= 85
                    val n = i32(o + 24)
                    val (xs, ys) = polyPts(o + 28, n, small)
                    if (n == 0) return
                    when (t) {
                        2, 85 -> emit(bezier(xs, ys, true, 0f, 0f), fill = false, stroke = true)
                        3, 86 -> emit(pts(xs, ys, true), fill = true, stroke = true)
                        else -> emit(pts(xs, ys, false), fill = false, stroke = true)
                    }
                }
                5, 6, 88, 89 -> { // POLYBEZIERTO POLYLINETO
                    val small = t >= 88
                    val n = i32(o + 24)
                    val (xs, ys) = polyPts(o + 28, n, small)
                    if (n == 0) return
                    val segs: List<Seg> = if (t == 5 || t == 88) bezier(xs, ys, false, dc.curX, dc.curY)
                    else listOf<Seg>(Seg.Move(dc.x(dc.curX, dc.curY), dc.y(dc.curX, dc.curY))) + (0 until n).map { k -> Seg.Line(dc.x(xs[k], ys[k]), dc.y(xs[k], ys[k])) }
                    val p = path
                    if (p != null) p += segs.drop(1) else shape(segs, fill = false, stroke = true)
                    dc.curX = xs[n - 1]; dc.curY = ys[n - 1]
                }
                7, 8, 90, 91 -> { // POLYPOLYLINE POLYPOLYGON
                    val small = t >= 90
                    val nPolys = i32(o + 24); val total = i32(o + 28)
                    if (nPolys <= 0 || nPolys > 100_000) return
                    val counts = IntArray(nPolys) { i32(o + 32 + it * 4) }
                    var p0 = o + 32 + nPolys * 4
                    val segs = ArrayList<Seg>()
                    for (c in counts) {
                        val (xs, ys) = polyPts(p0, c, small)
                        segs += pts(xs, ys, t == 8 || t == 91)
                        p0 += c * if (small) 4 else 8
                    }
                    if (total > 0) emit(segs, fill = t == 8 || t == 91, stroke = true)
                }
                9 -> { dc.winExtX = i32(o + 8).toFloat(); dc.winExtY = i32(o + 12).toFloat() }
                10 -> { dc.winOrgX = i32(o + 8).toFloat(); dc.winOrgY = i32(o + 12).toFloat() }
                11 -> { dc.vpExtX = i32(o + 8).toFloat(); dc.vpExtY = i32(o + 12).toFloat() }
                12 -> { dc.vpOrgX = i32(o + 8).toFloat(); dc.vpOrgY = i32(o + 12).toFloat() }
                17 -> dc.mapMode = i32(o + 8)
                19 -> dc.evenOdd = i32(o + 8) == 1
                22 -> dc.textAlign = i32(o + 8)
                24 -> dc.textColor = colorRef(i32(o + 8))
                27 -> { dc.curX = i32(o + 8).toFloat(); dc.curY = i32(o + 12).toFloat(); path?.add(Seg.Move(dc.x(dc.curX, dc.curY), dc.y(dc.curX, dc.curY))) }
                30 -> { // INTERSECTCLIPRECT
                    val r = box(i32(o + 8).toFloat(), i32(o + 12).toFloat(), i32(o + 16).toFloat(), i32(o + 20).toFloat())
                    ops += Op.Clip(r[0], r[1], r[2], r[3])
                }
                33 -> { stack += dc.copy(); ops += Op.Save }
                34 -> { // RESTOREDC(상대 번호, 보통 -1)
                    val n = i32(o + 8)
                    val k = if (n < 0) -n else (stack.size - n + 1).coerceAtLeast(1)
                    repeat(k) { if (stack.isNotEmpty()) { dc = stack.removeAt(stack.size - 1); ops += Op.Restore } }
                }
                35 -> dc.w = floatArrayOf(f32(o + 8), f32(o + 12), f32(o + 16), f32(o + 20), f32(o + 24), f32(o + 28))
                36 -> { // MODIFYWORLDTRANSFORM
                    val m = floatArrayOf(f32(o + 8), f32(o + 12), f32(o + 16), f32(o + 20), f32(o + 24), f32(o + 28))
                    when (i32(o + 32)) {
                        1 -> dc.w = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
                        2 -> dc.w = mul(m, dc.w) // 왼쪽 곱
                        3 -> dc.w = mul(dc.w, m) // 오른쪽 곱
                        4 -> dc.w = m
                    }
                }
                37 -> { val h = i32(o + 8); select(if (h < 0) stockObject(h and 0x7FFFFFFF) else objects[h]) }
                38 -> { // CREATEPEN: ihPen, style, width(x,y), color
                    val style = i32(o + 12)
                    objects[i32(o + 8)] = Pen(colorRef(i32(o + 24)), i32(o + 16).toFloat(), (style and 0xF) == 5)
                }
                95 -> { // EXTCREATEPEN: ihPen, offBmi, cbBmi, offBits, cbBits, style, width, brushStyle, color
                    val style = i32(o + 28)
                    objects[i32(o + 8)] = Pen(colorRef(i32(o + 40)), i32(o + 32).toFloat(), (style and 0xF) == 5)
                }
                39 -> { // CREATEBRUSHINDIRECT: ihBrush, style, color, hatch
                    val style = i32(o + 12)
                    objects[i32(o + 8)] = Brush(colorRef(i32(o + 16)), style == 1)
                }
                93, 94 -> objects[i32(o + 8)] = Brush(0xFF808080.toInt(), false) // 무늬·DIB 붓 → 회색
                40 -> objects.remove(i32(o + 8))
                42 -> { val r = box(i32(o + 8).toFloat(), i32(o + 12).toFloat(), i32(o + 16).toFloat(), i32(o + 20).toFloat()); emit(ellipse(r[0], r[1], r[2], r[3]), true, true) }
                43 -> { val r = box(i32(o + 8).toFloat(), i32(o + 12).toFloat(), i32(o + 16).toFloat(), i32(o + 20).toFloat()); emit(rect(r[0], r[1], r[2], r[3]), true, true) }
                44 -> {
                    val r = box(i32(o + 8).toFloat(), i32(o + 12).toFloat(), i32(o + 16).toFloat(), i32(o + 20).toFloat())
                    emit(roundRect(r[0], r[1], r[2], r[3], dc.len(i32(o + 24).toFloat()) / 2f, dc.len(i32(o + 28).toFloat()) / 2f), true, true)
                }
                54 -> lineTo(i32(o + 8).toFloat(), i32(o + 12).toFloat())
                59 -> { path = ArrayList() }
                60 -> {}
                61 -> path?.add(Seg.Close)
                62, 63, 64 -> { // FILLPATH STROKEANDFILLPATH STROKEPATH
                    val p = path ?: return
                    path = null
                    shape(p, fill = t != 64, stroke = t != 62)
                }
                76 -> blt(o, size, stretch = false) // BITBLT
                77 -> blt(o, size, stretch = true) // STRETCHBLT
                81 -> stretchDib(o, size)
                82 -> font(o)
                84, 83 -> extText(o, wide = t == 84)
                else -> {}
            }
        }

        fun mul(a: FloatArray, m: FloatArray): FloatArray = floatArrayOf(
            a[0] * m[0] + a[1] * m[2], a[0] * m[1] + a[1] * m[3],
            a[2] * m[0] + a[3] * m[2], a[2] * m[1] + a[3] * m[3],
            a[4] * m[0] + a[5] * m[2] + m[4], a[4] * m[1] + a[5] * m[3] + m[5],
        )

        fun font(o: Int) {
            val ih = i32(o + 8)
            val lf = o + 12
            val h = i32(lf).toFloat()
            val esc = i32(lf + 8) / 10f
            val weight = i32(lf + 16)
            val italic = b[lf + 20].toInt() != 0
            val under = b[lf + 21].toInt() != 0
            val charset = b[lf + 23].toInt() and 0xFF
            val sb = StringBuilder()
            var k = 0
            while (k < 32) { val c = bb.getChar(lf + 28 + k * 2); if (c.code == 0) break; sb.append(c); k++ }
            objects[ih] = FontSpec(abs(h), weight >= 600, italic, under, sb.toString().ifEmpty { null }, esc, charset)
        }

        fun extText(o: Int, wide: Boolean) {
            // EMRTEXT 은 o+36 부터: ptlReference(8) nChars(4) offString(4) options(4) rcl(16) offDx(4)
            val e = o + 36
            val x = i32(e).toFloat(); val y = i32(e + 4).toFloat()
            val n = i32(e + 8); val off = i32(e + 12); val offDx = i32(e + 36)
            if (n <= 0 || n > 100_000) return
            val s = if (wide) {
                val cs = CharArray(n) { bb.getChar(o + off + it * 2) }
                String(cs)
            } else String(b, o + off, n, if (dc.font.charset == 129) cp949 else Charsets.ISO_8859_1)
            val dx = if (offDx > 0 && o + offDx + n * 4 <= b.size) FloatArray(s.length.coerceAtMost(n)) { i32(o + offDx + it * 4).toFloat() } else null
            val useCp = (dc.textAlign and 1) != 0
            text(if (useCp) dc.curX else x, if (useCp) dc.curY else y, s.filter { it.code >= 0x20 || it == '\t' }, dx?.takeIf { it.size == s.length })
        }

        fun blt(o: Int, size: Int, stretch: Boolean) {
            // BITBLT: rclBounds(16) xDest yDest cxDest cyDest dwRop xSrc ySrc xformSrc(24) crBkColorSrc iUsageSrc offBmiSrc cbBmiSrc offBitsSrc cbBitsSrc [cxSrc cySrc]
            val xd = i32(o + 24).toFloat(); val yd = i32(o + 28).toFloat(); val cx = i32(o + 32).toFloat(); val cy = i32(o + 36).toFloat()
            val offBmi = i32(o + 84); val cbBmi = i32(o + 88); val offBits = i32(o + 92); val cbBits = i32(o + 96)
            if (cbBmi <= 0 || cbBits <= 0) {
                // 그림 없는 BITBLT = 붓으로 칠하기(PATCOPY 등)
                val r = box(xd, yd, xd + cx, yd + cy)
                if (!dc.brush.none) ops += Op.Shape(rect(r[0], r[1], r[2], r[3]), dc.brush.color, null, 0f, false)
                return
            }
            image(xd, yd, cx, cy, o + offBmi, cbBmi, o + offBits, cbBits)
        }

        fun stretchDib(o: Int, size: Int) {
            // STRETCHDIBITS: rclBounds(16) xDest yDest xSrc ySrc cxSrc cySrc offBmiSrc cbBmiSrc offBitsSrc cbBitsSrc iUsageSrc dwRop cxDest cyDest
            val xd = i32(o + 24).toFloat(); val yd = i32(o + 28).toFloat()
            val offBmi = i32(o + 48); val cbBmi = i32(o + 52); val offBits = i32(o + 56); val cbBits = i32(o + 60)
            val cx = i32(o + 72).toFloat(); val cy = i32(o + 76).toFloat()
            if (cbBmi <= 0 || cbBits <= 0) return
            image(xd, yd, cx, cy, o + offBmi, cbBmi, o + offBits, cbBits)
        }

        fun image(xd: Float, yd: Float, cx: Float, cy: Float, bmiAt: Int, cbBmi: Int, bitsAt: Int, cbBits: Int) {
            if (bmiAt + cbBmi > b.size || bitsAt + cbBits > b.size) return
            val dib = ByteArray(cbBmi + cbBits)
            System.arraycopy(b, bmiAt, dib, 0, cbBmi)
            System.arraycopy(b, bitsAt, dib, cbBmi, cbBits)
            val bmp = dibToBmp(dib, cbBmi) ?: return
            val r = box(xd, yd, xd + cx, yd + cy)
            grow(r[0], r[1]); grow(r[2], r[3])
            ops += Op.Image(r[0], r[1], r[2], r[3], bmp)
        }
    }

    // ───────────── WMF ─────────────

    private class Wmf(b: ByteArray) : Reader(b) {
        val bb: ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        fun s16(o: Int) = bb.getShort(o).toInt()
        fun u16(o: Int) = bb.getShort(o).toInt() and 0xFFFF
        fun u32(o: Int) = bb.getInt(o)

        override fun run(): Metafile? {
            var o = 0
            var bounds: FloatArray? = null
            if (u32(0) == 0x9AC6CDD7.toInt()) {
                // 놓을 수 있는 머리(placeable): 범위(논리 단위)와 인치당 단위
                bounds = floatArrayOf(s16(6).toFloat(), s16(8).toFloat(), s16(10).toFloat(), s16(12).toFloat())
                dc.mapMode = 8
                dc.winOrgX = bounds[0]; dc.winOrgY = bounds[1]; dc.winExtX = bounds[2] - bounds[0]; dc.winExtY = bounds[3] - bounds[1]
                dc.vpExtX = dc.winExtX; dc.vpExtY = dc.winExtY
                o = 22
            }
            val hdrWords = u16(o + 2)
            o += hdrWords * 2
            var guard = 0
            while (o + 6 <= b.size && guard++ < 2_000_000) {
                val sizeWords = u32(o)
                val fn = u16(o + 4)
                if (sizeWords < 3 || o + sizeWords * 2 > b.size) break
                if (fn == 0) break
                runCatching { record(fn, o + 6, sizeWords * 2 - 6) }
                o += sizeWords * 2
            }
            // 장치 범위: 창 범위를 뷰포트로 옮긴 사각형(뷰포트 크기를 모르면 창 그대로)
            val l: Float; val t: Float; val r: Float; val bt: Float
            if (minX <= maxX && minY <= maxY) { l = minX; t = minY; r = maxX; bt = maxY }
            else return null
            val wx0 = dc.x(dc.winOrgX, dc.winOrgY); val wy0 = dc.y(dc.winOrgX, dc.winOrgY)
            val wx1 = dc.x(dc.winOrgX + dc.winExtX, dc.winOrgY + dc.winExtY); val wy1 = dc.y(dc.winOrgX + dc.winExtX, dc.winOrgY + dc.winExtY)
            val useWin = abs(wx1 - wx0) > 1f && abs(wy1 - wy0) > 1f
            return if (useWin) Metafile(minOf(wx0, wx1), minOf(wy0, wy1), maxOf(wx0, wx1), maxOf(wy0, wy1), ops) else Metafile(l, t, r, bt, ops)
        }

        /** 개체 표의 가장 낮은 빈 자리에 넣는다(WMF 규칙). */
        fun add(o: Any) { var i = 0; while (objects.containsKey(i)) i++; objects[i] = o }

        fun record(fn: Int, p: Int, n: Int) {
            when (fn) {
                0x0103 -> dc.mapMode = s16(p)
                0x020B -> { dc.winOrgY = s16(p).toFloat(); dc.winOrgX = s16(p + 2).toFloat() }
                0x020C -> { dc.winExtY = s16(p).toFloat(); dc.winExtX = s16(p + 2).toFloat(); if (dc.mapMode != 7 && dc.mapMode != 8) dc.mapMode = 8; if (dc.vpExtX == 1f && dc.vpExtY == 1f) { dc.vpExtX = dc.winExtX; dc.vpExtY = dc.winExtY } }
                0x020D -> { dc.vpOrgY = s16(p).toFloat(); dc.vpOrgX = s16(p + 2).toFloat() }
                0x020E -> { dc.vpExtY = s16(p).toFloat(); dc.vpExtX = s16(p + 2).toFloat() }
                0x0209 -> dc.textColor = colorRef(u32(p))
                0x012E -> dc.textAlign = u16(p)
                0x0106 -> dc.evenOdd = u16(p) == 1
                0x001E -> { stack += dc.copy(); ops += Op.Save }
                0x0127 -> { if (stack.isNotEmpty()) { dc = stack.removeAt(stack.size - 1); ops += Op.Restore } }
                0x0416 -> { // INTERSECTCLIPRECT: bottom right top left
                    val r = box(s16(p + 6).toFloat(), s16(p + 4).toFloat(), s16(p + 2).toFloat(), s16(p).toFloat())
                    ops += Op.Clip(r[0], r[1], r[2], r[3])
                }
                0x02FA -> { // CREATEPENINDIRECT: style, width(x,y), color
                    val style = u16(p)
                    add(Pen(colorRef(u32(p + 6)), s16(p + 2).toFloat(), (style and 0xF) == 5))
                }
                0x02FC -> add(Brush(colorRef(u32(p + 2)), u16(p) == 1)) // CREATEBRUSHINDIRECT: style, color, hatch
                0x0142, 0x01F9 -> add(Brush(0xFF808080.toInt(), false)) // DIBCREATEPATTERNBRUSH, CREATEPALETTE 자리
                0x00F7 -> add(Any()) // CREATEPALETTE
                0x02FB -> { // CREATEFONTINDIRECT
                    val h = s16(p).toFloat()
                    val esc = s16(p + 4) / 10f
                    val weight = s16(p + 8)
                    val italic = b[p + 10].toInt() != 0
                    val under = b[p + 11].toInt() != 0
                    val charset = b[p + 13].toInt() and 0xFF
                    var e = p + 18
                    val end = minOf(p + n, p + 18 + 32)
                    while (e < end && b[e].toInt() != 0) e++
                    val face = String(b, p + 18, e - (p + 18), if (charset == 129) cp949 else Charsets.ISO_8859_1)
                    add(FontSpec(abs(h), weight >= 600, italic, under, face.ifEmpty { null }, esc, charset))
                }
                0x012D -> select(objects[u16(p)])
                0x01F0 -> objects.remove(u16(p))
                0x0214 -> { dc.curY = s16(p).toFloat(); dc.curX = s16(p + 2).toFloat() }
                0x0213 -> {
                    val y = s16(p).toFloat(); val x = s16(p + 2).toFloat()
                    shape(listOf(Seg.Move(dc.x(dc.curX, dc.curY), dc.y(dc.curX, dc.curY)), Seg.Line(dc.x(x, y), dc.y(x, y))), fill = false, stroke = true)
                    dc.curX = x; dc.curY = y
                }
                0x041B -> { val r = box(s16(p + 6).toFloat(), s16(p + 4).toFloat(), s16(p + 2).toFloat(), s16(p).toFloat()); shape(rect(r[0], r[1], r[2], r[3]), true, true) }
                0x0418 -> { val r = box(s16(p + 6).toFloat(), s16(p + 4).toFloat(), s16(p + 2).toFloat(), s16(p).toFloat()); shape(ellipse(r[0], r[1], r[2], r[3]), true, true) }
                0x061C -> { // ROUNDRECT: height width bottom right top left
                    val r = box(s16(p + 10).toFloat(), s16(p + 8).toFloat(), s16(p + 6).toFloat(), s16(p + 4).toFloat())
                    shape(roundRect(r[0], r[1], r[2], r[3], dc.len(s16(p + 2).toFloat()) / 2f, dc.len(s16(p).toFloat()) / 2f), true, true)
                }
                0x0324, 0x0325 -> {
                    val c = s16(p)
                    if (c <= 0) return
                    val xs = FloatArray(c) { s16(p + 2 + it * 4).toFloat() }; val ys = FloatArray(c) { s16(p + 4 + it * 4).toFloat() }
                    if (fn == 0x0324) shape(pts(xs, ys, true), true, true) else shape(pts(xs, ys, false), false, true)
                }
                0x0538 -> { // POLYPOLYGON
                    val np = s16(p)
                    if (np <= 0) return
                    val counts = IntArray(np) { s16(p + 2 + it * 2) }
                    var q = p + 2 + np * 2
                    val segs = ArrayList<Seg>()
                    for (c in counts) {
                        val xs = FloatArray(c) { s16(q + it * 4).toFloat() }; val ys = FloatArray(c) { s16(q + 2 + it * 4).toFloat() }
                        segs += pts(xs, ys, true); q += c * 4
                    }
                    shape(segs, true, true)
                }
                0x0521 -> { // TEXTOUT: count, string(짝수 바이트), y, x
                    val c = s16(p)
                    if (c <= 0) return
                    val sLen = (c + 1) and 1.inv()
                    val y = s16(p + 2 + sLen).toFloat(); val x = s16(p + 4 + sLen).toFloat()
                    wmfText(x, y, p + 2, c, null)
                }
                0x0A32 -> { // EXTTEXTOUT: y, x, count, options, [rect], string, [dx]
                    val y = s16(p).toFloat(); val x = s16(p + 2).toFloat(); val c = s16(p + 4); val opt = u16(p + 6)
                    if (c <= 0) return
                    var q = p + 8
                    if (opt and 0x0006 != 0) q += 8
                    val sLen = (c + 1) and 1.inv()
                    val dxAt = q + sLen
                    val dx = if (dxAt + c * 2 <= p + n) FloatArray(c) { s16(dxAt + it * 2).toFloat() } else null
                    wmfText(x, y, q, c, dx)
                }
                0x0F43 -> { // STRETCHDIB: rop(4) usage(2) srcH srcW ySrc xSrc destH destW yDst xDst DIB
                    val dh = s16(p + 14).toFloat(); val dw = s16(p + 16).toFloat(); val yd = s16(p + 18).toFloat(); val xd = s16(p + 20).toFloat()
                    dib(xd, yd, dw, dh, p + 22, p + n)
                }
                0x0B41 -> { // DIBSTRETCHBLT: rop(4) srcH srcW ySrc xSrc destH destW yDst xDst DIB
                    val dh = s16(p + 12).toFloat(); val dw = s16(p + 14).toFloat(); val yd = s16(p + 16).toFloat(); val xd = s16(p + 18).toFloat()
                    dib(xd, yd, dw, dh, p + 20, p + n)
                }
                0x0940 -> { // DIBBITBLT: rop(4) ySrc xSrc height width yDst xDst DIB
                    val h = s16(p + 8).toFloat(); val w = s16(p + 10).toFloat(); val yd = s16(p + 12).toFloat(); val xd = s16(p + 14).toFloat()
                    dib(xd, yd, w, h, p + 16, p + n)
                }
                else -> {}
            }
        }

        fun wmfText(x: Float, y: Float, at: Int, count: Int, dx: FloatArray?) {
            if (at + count > b.size) return
            val cs = if (dc.font.charset == 129) cp949 else Charsets.ISO_8859_1
            val s = String(b, at, count, cs)
            // 두 바이트 글자(CP949)는 dx 가 바이트마다 있다 → 글자 수에 맞게 합친다
            val dxc = if (dx != null && s.length != dx.size) {
                val out = ArrayList<Float>(); var bi = 0
                for (ch in s) {
                    val nb = ch.toString().toByteArray(cs).size
                    var sum = 0f
                    repeat(nb) { if (bi < dx.size) sum += dx[bi++] }
                    out += sum
                }
                out.toFloatArray()
            } else dx
            text(x, y, s.filter { it.code >= 0x20 }, dxc?.takeIf { it.size == s.length })
        }

        fun dib(xd: Float, yd: Float, w: Float, h: Float, from: Int, to: Int) {
            if (to <= from || to > b.size) return
            val bmp = dibToBmp(b.copyOfRange(from, to)) ?: return
            val r = box(xd, yd, xd + w, yd + h)
            grow(r[0], r[1]); grow(r[2], r[3])
            ops += Op.Image(r[0], r[1], r[2], r[3], bmp)
        }
    }
}
