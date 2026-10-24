// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.doc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.engine.comic.Adjust
import kr.vici.ashreader.engine.comic.BorderCrop
import kr.vici.ashreader.engine.comic.ComicArchive
import kr.vici.ashreader.engine.comic.CropBox
import kr.vici.ashreader.engine.comic.PageDecoder
import kr.vici.ashreader.engine.comic.PageSlot
import kr.vici.ashreader.engine.comic.ReadingDirection
import kr.vici.ashreader.engine.comic.Sharpen
import kr.vici.ashreader.engine.comic.SlotPart
import kr.vici.ashreader.engine.comic.SpreadLayout
import kr.vici.ashreader.engine.comic.Webtoon
import kr.vici.ashreader.engine.mupdf.MupdfDoc
import kr.vici.ashreader.engine.mupdf.PasswordException
import kr.vici.ashreader.ui.ReadingColors

/** 아직 풀리지 않은 쪽(solid CBR, N-02). 화면은 '쪽 준비 중'을 보인다. */
class PageNotReady : Exception("쪽 준비 중")

/** solid CBR 풀기 진행(N-02·N-09). [stopped] = 사용자가 멈춤(풀린 쪽까지만 볼 수 있음). */
data class ExtractState(val done: Int, val total: Int, val finished: Boolean = false, val stopped: Boolean = false)

/**
 * 만화 쪽 공급(압축 CBZ·CBR, 또는 그림만 든 PDF). 스레드 안전하게 구현한다.
 * [size] = 원본 화소 크기(AI 화질 판단), [bitmap] = 폭 근처로 줄인 그림, [full] = 원본 크기 그림(AI 입력).
 */
interface ComicPages : Closeable {
    val pageCount: Int
    val rtl: Boolean
    fun size(index: Int): Pair<Int, Int>
    fun bitmap(index: Int, w: Int): Bitmap
    fun full(index: Int): Bitmap?
    fun toc(): List<TocEntry>
    /** 싸게 알 수 있을 때만 크기(배치·웹툰 판정용). 모르면 null. */
    fun sizeOrNull(index: Int): Pair<Int, Int>? = size(index).takeIf { it.first > 0 && it.second > 0 }
    /** 그림 파일 바이트(영역 디코딩용). 그림 파일이 아니면(PDF) null. */
    fun bytes(index: Int): ByteArray? = null
    /** 지금 바로 그릴 수 있는 쪽인가(solid CBR 풀기 중이면 false). */
    fun ready(index: Int): Boolean = true
    /** CBR 풀기 진행. 해당 없으면 null. */
    val extract: StateFlow<ExtractState?>? get() = null
    /** 풀리기 전 쪽은 볼 수 없음(solid CBR) — 진행 표시를 한다(S-41). */
    val extractBlocks: Boolean get() = false
    /** 풀기 멈추기(S-41 [멈추기]). */
    fun stopExtract() {}
    /** 멈춘 풀기를 이어 하기('일부만 준비됨' → [이어 풀기]). 이미 풀린 쪽은 그대로 둔다. */
    fun resumeExtract() {}
}

/**
 * CBZ·CBR 압축 만화. CBR(RAR)은 처음 열 때 뒤에서 앞에서부터 차례로 [work] 에 풀어 두고(N-02, 상한 500MB) 거기서 읽는다.
 * - solid: 풀리지 않은 쪽은 [PageNotReady](매번 처음부터 다시 푸는 일을 막는다). 상한에 걸려 못 푼 쪽만 직접 읽는다.
 * - solid 아님: 풀리지 않은 쪽은 압축 파일에서 바로 읽는다.
 * 문서를 닫으면 [work] 를 지운다(N-09).
 */
class ArchivePages(file: File, private val work: File? = null) : ComicPages {
    private val lock = Any()
    private val arc: ComicArchive = try { ComicArchive.open(file) } catch (e: Exception) {
        throw OpenFailed(e.message ?: "만화 파일을 열 수 없어요.")
    }
    override val pageCount: Int = arc.pages.size
    override val rtl: Boolean get() = arc.direction == ReadingDirection.RTL
    private val rar = arc.kind != kr.vici.ashreader.engine.comic.ComicFormat.Kind.ZIP && work != null
    private val solid = rar && arc.solid
    private val files = AtomicReferenceArray<File?>(pageCount)
    private val sizes = ConcurrentHashMap<Int, Pair<Int, Int>>()
    private val state = MutableStateFlow(if (rar) ExtractState(0, pageCount) else null)
    override val extract: StateFlow<ExtractState?>? = if (rar) state else null
    override val extractBlocks: Boolean get() = solid
    @Volatile private var stop = false
    @Volatile private var closed = false
    private var worker: Thread? = null

    private class Stopped : RuntimeException()

    init { if (rar && pageCount > 0) startExtract(work!!, fresh = true) }

    /**
     * [fresh] = 처음부터(폴더 비움). 이어 풀기는 임시 폴더(r/)에 풀어, 아직 없는 쪽만 옮긴다
     * (이미 보이는 쪽 파일을 덮어쓰는 동안 읽어 깨지는 일을 막는다).
     */
    private fun startExtract(dir: File, fresh: Boolean) {
        if (fresh) { dir.deleteRecursively(); dir.mkdirs() }
        val into = if (fresh) dir else File(dir, "r").apply { deleteRecursively(); mkdirs() }
        val byName = HashMap<String, Int>(pageCount * 2)
        arc.pages.forEachIndexed { i, p -> byName[p.name] = i }
        worker = Thread({
            try {
                arc.extractAll(into, MAX_EXTRACT) { i, name ->
                    if (stop || closed) throw Stopped()
                    val p = byName[name]
                    val written = File(into, "%06d.bin".format(i))
                    if (p != null && files.get(p) == null) {
                        val f = if (fresh) written else File(dir, "%06d.bin".format(i)).also { t -> if (!written.renameTo(t)) written.copyTo(t, overwrite = true) }
                        files.set(p, f)
                        state.update { it?.copy(done = it.done + 1) }
                    } else if (!fresh) written.delete()
                }
            } catch (e: Throwable) {
                // 멈춤·손상·암호: 풀린 쪽까지만 캐시에서, 나머지는 아래 규칙대로
            } finally {
                state.update { it?.copy(finished = true, stopped = stop) }
                if (!fresh) into.deleteRecursively()
                if (closed) dir.deleteRecursively()
            }
        }, "ash-unrar").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }
    }

    override fun resumeExtract() {
        val dir = work ?: return
        if (!rar || closed || worker?.isAlive == true) return
        val st = state.value ?: return
        if (!st.stopped) return
        stop = false
        state.value = ExtractState((0 until pageCount).count { files.get(it) != null }, pageCount)
        startExtract(dir, fresh = false)
    }

    override fun ready(index: Int): Boolean {
        if (!solid || files.get(index) != null) return true
        val st = state.value ?: return true
        return st.finished && !st.stopped
    }

    override fun stopExtract() { stop = true }

    override fun bytes(index: Int): ByteArray {
        files.get(index)?.let { f -> runCatching { return f.readBytes() } }
        if (!ready(index)) throw PageNotReady()
        return synchronized(lock) { arc.readPage(index) }
    }

    override fun size(index: Int): Pair<Int, Int> = sizes[index] ?: PageDecoder.bounds(bytes(index)).also { if (it.first > 0) sizes[index] = it }

    override fun sizeOrNull(index: Int): Pair<Int, Int>? {
        sizes[index]?.let { return it }
        val s = if (!rar) synchronized(lock) { runCatching { arc.pageBounds(index) }.getOrNull() }
        else files.get(index)?.let { f -> runCatching { f.inputStream().buffered().use { PageDecoder.bounds(it) } }.getOrNull() }
        if (s != null) sizes[index] = s
        return s
    }

    override fun bitmap(index: Int, w: Int): Bitmap = PageDecoder.decodeFit(bytes(index), w.coerceAtLeast(1))

    override fun full(index: Int): Bitmap? {
        val b = bytes(index)
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
    }

    /** 목차 = 압축 안 파일(쪽) 순서. 폴더가 여럿이면 폴더 → 파일 2단. */
    override fun toc(): List<TocEntry> {
        val pages = arc.pages
        val folders = pages.map { it.name.substringBeforeLast('/', "") }
        val multi = folders.distinct().size > 1
        val out = ArrayList<TocEntry>(pages.size + 8)
        var last: String? = null
        pages.forEachIndexed { i, p ->
            val f = folders[i]
            if (multi && f != last) { out += TocEntry(f.substringAfterLast('/').ifEmpty { "(맨 위)" }, 0, i); last = f }
            out += TocEntry(p.name.substringAfterLast('/'), if (multi) 1 else 0, i)
        }
        return out
    }

    override fun close() {
        closed = true; stop = true
        synchronized(lock) { arc.close() }
        val w = worker
        if (w == null || !w.isAlive) work?.deleteRecursively()
    }

    companion object {
        /** 풀어 두는 상한(N-09). */
        const val MAX_EXTRACT = 500L shl 20
        fun workDir(c: Context, docId: String) = File(c.cacheDir, "comic-x/$docId")
    }
}

/** 그림만 든 PDF 를 만화로(MuPDF 로 쪽을 비트맵으로 그린다). 원본 크기 = 쪽을 덮는 그림의 화소 크기. */
class PdfComicPages(file: File, password: String?) : ComicPages {
    private val lock = Any()
    private val doc: MupdfDoc = try { MupdfDoc.open(file.absolutePath, null, null, password) } catch (e: PasswordException) {
        throw PasswordNeeded(e.wrong)
    } catch (e: Exception) {
        throw OpenFailed("문서를 열 수 없어요. 파일이 손상되었거나 지원하지 않는 형식일 수 있어요.")
    }
    override val pageCount: Int = synchronized(lock) { doc.pageCount() }
    override val rtl: Boolean = false
    private val sizes = ConcurrentHashMap<Int, Pair<Int, Int>>()

    override fun size(index: Int): Pair<Int, Int> = sizes.getOrPut(index) {
        val info = synchronized(lock) { runCatching { doc.imageInfo(index) }.getOrNull() }
        if (info != null && info.imageW > 0 && info.imageH > 0) info.imageW to info.imageH else 0 to 0
    }

    override fun bitmap(index: Int, w: Int): Bitmap = synchronized(lock) { doc.renderPage(index, w.coerceAtLeast(1)) }

    override fun full(index: Int): Bitmap? {
        val (w, h) = size(index)
        if (w <= 0 || h <= 0) return null
        return synchronized(lock) { doc.renderPage(index, w) }
    }

    override fun toc(): List<TocEntry> = synchronized(lock) {
        runCatching { doc.outline().map { TocEntry(it.title, it.depth, it.page) } }.getOrDefault(emptyList())
    }

    override fun close() = synchronized(lock) { doc.close() }
}

/** 만화 그림 보기 값(책마다, S-33 보기 탭). */
data class ComicLook(
    /** 테두리 자동 자르기(F-52). */
    val crop: Boolean = true,
    /** 자르기 민감도 0=약 1=보통 2=강. */
    val cropSens: Int = 1,
    /** 밝기·대비·감마·흑백·반전(F-57). */
    val adjust: Adjust = Adjust(),
    /** 확대·축소 고품질 보간(F-56). false = 빠름. */
    val hq: Boolean = true,
    /** 선명하게 0~3(F-56). */
    val sharpen: Int = 1,
)

/** 만화 보기 문서(CBZ·CBR·그림 PDF). [cacheKey] = 문서 식별 해시(AI 화질 캐시 폴더 이름), [meta] = 쪽 크기 기록(다시 열 때 배치를 바로). */
class ComicReaderDoc(
    private val src: ComicPages,
    override val format: DocFormat,
    val cacheKey: String,
    private val meta: File? = null,
) : ReaderDoc {
    /** 원본 쪽 수. */
    val sourceCount: Int = src.pageCount

    /** 쪽 자리 배치(F-51). null = 쪽마다 한 자리. 화면(메인) 스레드에서 [commitLayout] 으로만 바꾼다. */
    @Volatile var slots: List<PageSlot>? = null
        private set
    /** 지금 배치가 두 쪽 펼침인가(반쪽을 가운데 쪽으로 붙여 그림). */
    @Volatile private var spreadLayout = false
    override val pageCount: Int get() = slots?.size ?: sourceCount

    /** 읽는 방향 직접 지정(F-48). null = 파일 정보(F-49). */
    @Volatile var dirOverride: Boolean? = null
    override val rtl: Boolean get() = dirOverride ?: src.rtl
    /** 파일이 정한 방향(ComicInfo, F-49). */
    val fileRtl: Boolean get() = src.rtl

    @Volatile var look: ComicLook = ComicLook()
        set(v) { if (field.cropSens != v.cropSens) crops.clear(); field = v }
    private val crops = ConcurrentHashMap<Int, FloatArray>()

    /** AI 화질 개선(F-73). 켜져 있으면 읽기 화면이 넣는다. */
    @Volatile var upscale: ComicUpscale? = null
    /** 넘김 화면에 2배 결과로 그린 원본 쪽(바꿔 끼울 쪽 판단용). */
    private val shownUpscaled: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    /** 원본 쪽 [index] 를 2배 결과로 그렸나. */
    fun shownUpscaled(index: Int) = index in shownUpscaled

    val extract: StateFlow<ExtractState?>? get() = src.extract
    /** solid CBR: 풀리기 전 쪽은 '쪽 준비 중'(S-41). */
    val extractBlocks: Boolean get() = src.extractBlocks
    fun stopExtract() = src.stopExtract()
    fun resumeExtract() = src.resumeExtract()
    fun ready(sourcePage: Int): Boolean = sourcePage !in 0 until sourceCount || src.ready(sourcePage)

    /** 자리 [index] 의 원본 쪽(빈 자리는 다음 쪽). */
    fun sourceOf(index: Int): Int {
        val s = slots ?: return index
        val slot = s.getOrNull(index) ?: return index.coerceIn(0, (sourceCount - 1).coerceAtLeast(0))
        if (slot.page >= 0) return slot.page
        return s.drop(index).firstOrNull { it.page >= 0 }?.page ?: s.lastOrNull { it.page >= 0 }?.page ?: 0
    }

    /** 원본 쪽 [page] 가 처음 나오는 자리. */
    fun indexOf(page: Int): Int = SpreadLayout.translate(null, slots, page)

    /** 새 배치로 바꾸고, 옛 자리 → 새 자리 변환을 돌려준다(바뀐 것이 없으면 null). 화면(메인) 스레드에서. */
    fun commitLayout(new: List<PageSlot>?, spread: Boolean): ((Int) -> Int)? {
        val old = slots
        spreadLayout = spread
        val identity = new == null || (new.size == sourceCount && new.withIndex().all { (i, s) -> s.page == i && s.part == SlotPart.WHOLE })
        val n = if (identity) null else new
        if (old == n) return null
        slots = n
        clearAhead()
        return { SpreadLayout.translate(old, n, it) }
    }

    // ---- 미리 풀기(F-58) ----
    /**
     * 넘김 방향으로 2쪽, 반대로 1쪽을 미리 디코딩해 둔 그림. 쓰는 쪽이 꺼내 가면(소유권 넘김) 지운다.
     * 상한([aheadBudget], 기기 RAM 의 1/8)을 넘으면 지금 쪽에서 먼 것부터 버린다.
     */
    private class Ahead(val index: Int, val bmp: Bitmap)
    private val ahead = LinkedHashMap<String, Ahead>()
    @Volatile var aheadBudget: Long = 256L shl 20
    @Volatile private var aheadCenter = 0

    private fun aheadKey(kind: String, index: Int, w: Int) = "$kind:$index:$w:${look.hashCode()}:${System.identityHashCode(slots)}"

    /** 미리 풀어 둔 그림을 꺼낸다(없으면 null). 꺼낸 그림은 부른 쪽 것이다. */
    fun takeAhead(kind: String, index: Int, w: Int): Bitmap? = synchronized(ahead) { ahead.remove(aheadKey(kind, index, w))?.bmp }

    private fun clearAhead() = synchronized(ahead) { ahead.clear() }

    /**
     * [center] 를 보는 중: 넘김 방향([forward])으로 2자리, 반대로 1자리를 [make] 로 미리 만들어 둔다. 백그라운드에서 부른다.
     * [make] 는 자리 번호 → 그림(못 만들면 null).
     */
    fun prefetch(kind: String, center: Int, forward: Boolean, w: Int, count: Int, make: (Int) -> Bitmap?) {
        aheadCenter = center
        val step = if (forward) 1 else -1
        val want = listOf(center + step, center + 2 * step, center - step).filter { it in 0 until count }
        // 먼 것부터 버리기
        synchronized(ahead) {
            val it = ahead.entries.iterator()
            while (it.hasNext()) { val e = it.next(); if (kotlin.math.abs(e.value.index - center) > 2) it.remove() }
        }
        for (i in want) {
            if (aheadCenter != center) return // 그새 넘김
            val key = aheadKey(kind, i, w)
            if (synchronized(ahead) { ahead.containsKey(key) }) continue
            if (i < sourceCount && !src.ready(sourceOf(i))) continue
            val b = try { make(i) } catch (e: Exception) { null } catch (e: OutOfMemoryError) { clearAhead(); null } ?: continue
            synchronized(ahead) {
                ahead[key] = Ahead(i, b)
                var total = ahead.values.sumOf { it.bmp.allocationByteCount.toLong() }
                while (total > aheadBudget && ahead.isNotEmpty()) {
                    val far = ahead.entries.maxByOrNull { kotlin.math.abs(it.value.index - aheadCenter) } ?: break
                    ahead.remove(far.key)
                    total -= far.value.bmp.allocationByteCount
                }
            }
        }
    }

    // ---- 쪽 크기(배치·웹툰) ----
    private val sizeCache: Array<Pair<Int, Int>?> = arrayOfNulls(sourceCount)
    @Volatile private var metaLoaded = false

    private fun loadMeta() {
        if (metaLoaded) return
        metaLoaded = true
        val f = meta ?: return
        runCatching {
            if (!f.exists()) return
            f.readLines().forEachIndexed { i, line ->
                if (i >= sourceCount) return@forEachIndexed
                val p = line.split(' ')
                val w = p.getOrNull(0)?.toIntOrNull() ?: 0; val h = p.getOrNull(1)?.toIntOrNull() ?: 0
                if (w > 0 && h > 0) sizeCache[i] = w to h
            }
        }
    }

    private fun saveMeta() {
        val f = meta ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(sizeCache.joinToString("\n") { it?.let { s -> "${s.first} ${s.second}" } ?: "" })
        }
    }

    /** 지난번에 기록해 둔 크기만(파일 하나 읽음 — 화면 스레드에서도 됨). 처음 배치를 바로 정할 때. */
    @Synchronized
    fun cachedSizes(): List<Pair<Int, Int>?> { loadMeta(); return sizeCache.toList() }

    /** 앞에서 [limit] 쪽까지의 크기(모르면 null). 무거울 수 있으니 백그라운드에서. */
    @Synchronized
    fun sizes(limit: Int = sourceCount): List<Pair<Int, Int>?> {
        loadMeta()
        var changed = false
        val n = limit.coerceIn(0, sourceCount)
        for (i in 0 until n) {
            if (sizeCache[i] != null) continue
            val s = runCatching { src.sizeOrNull(i) }.getOrNull()
            if (s != null && s.first > 0 && s.second > 0) { sizeCache[i] = s; changed = true }
        }
        if (changed) saveMeta()
        return sizeCache.take(n)
    }

    /** 원본 화소 크기. */
    fun pageSize(index: Int): Pair<Int, Int> = sizeCache.getOrNull(index) ?: src.size(index)

    /** AI 입력용 원본 크기 그림. */
    fun fullBitmap(index: Int): Bitmap? = src.full(index)

    /** 그림 원본 비트맵(폭 [w] 근처로 줄임). 만화 그림은 테마 색을 입히지 않는다. */
    fun pageBitmap(index: Int, w: Int): Bitmap = src.bitmap(index, w.coerceAtLeast(1))

    /**
     * 맞춤 방식 보기(F-54)용: 원본 쪽 [p] 를 테두리 자르기·그림 보기 값을 입혀 잘린 모양 그대로(바탕 없이) 그린다.
     * 폭은 [maxW] 이하, 화소 수는 [maxPixels] 이하. [nativeW] = 자른 뒤 원본 화소 폭(원본 맞춤 배율 계산).
     */
    class Free(val bmp: Bitmap, val nativeW: Int)

    fun renderFree(p: Int, maxW: Int, maxPixels: Long = 12_000_000L): Free? {
        if (p !in 0 until sourceCount || !src.ready(p)) return null
        val (pw, ph) = pageSize(p)
        var w = maxW.coerceAtLeast(1)
        if (pw > 0) w = minOf(w, pw)
        if (pw > 0 && ph > 0 && w.toLong() * w * ph / pw > maxPixels) w = kotlin.math.sqrt(maxPixels.toDouble() * pw / ph).toInt().coerceAtLeast(64)
        val up = upscale?.load(p, w)
        val raw = try { up ?: src.bitmap(p, w) } catch (e: PageNotReady) { return null }
        val l = look
        val box = if (l.crop) cropOf(p, raw) else CropBox(0, 0, raw.width, raw.height)
        if (box.width <= 0 || box.height <= 0) { raw.recycle(); return null }
        val k = minOf(1f, w.toFloat() / box.width)
        val ow = (box.width * k).toInt().coerceAtLeast(1); val oh = (box.height * k).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(raw, Rect(box.l, box.t, box.r, box.b), RectF(0f, 0f, ow.toFloat(), oh.toFloat()),
            Paint(if (l.hq) Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG else 0))
        val nativeW = if (pw > 0 && raw.width > 0) (box.width.toLong() * pw / raw.width).toInt() else box.width
        raw.recycle()
        return Free(withLook(out), nativeW.coerceAtLeast(1))
    }

    private val freeNative = ConcurrentHashMap<String, Int>()

    /** 맞춤 방식 보기에서 미리 풀어 둔 쪽(F-58). 꺼내면 부른 쪽 것. */
    fun takeAheadFree(i: Int, w: Int): Free? {
        val b = takeAhead("free", i, w) ?: return null
        return Free(b, freeNative.remove("$i:$w") ?: b.width)
    }

    /** 맞춤 방식 보기: 넘김 방향 2쪽·반대 1쪽을 미리 그려 둔다(백그라운드). */
    fun prefetchFree(center: Int, forward: Boolean, w: Int) = prefetch("free", center, forward, w, pageCount) { i ->
        renderFree(sourceOf(i), w)?.also { freeNative["$i:$w"] = it.nativeW }?.bmp
    }

    /** F-59 쪽 그림 원본(공유·저장): 압축 안 그림 파일 바이트와 MIME. 그림 파일이 아니면(PDF) PNG 로 만든다. */
    fun pageImage(p: Int): Pair<ByteArray, String>? {
        if (p !in 0 until sourceCount || !src.ready(p)) return null
        val raw = runCatching { src.bytes(p) }.getOrNull()
        if (raw != null && raw.size > 8) {
            val mime = when {
                raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte() -> "image/jpeg"
                raw[0] == 0x89.toByte() && raw[1] == 'P'.code.toByte() -> "image/png"
                raw[0] == 'G'.code.toByte() && raw[1] == 'I'.code.toByte() -> "image/gif"
                raw[0] == 'R'.code.toByte() && raw[8] == 'W'.code.toByte() -> "image/webp"
                else -> null
            }
            if (mime != null) return raw to mime
        }
        val b = runCatching { src.full(p) }.getOrNull() ?: return null
        return try {
            val o = java.io.ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.PNG, 100, o)
            o.toByteArray() to "image/png"
        } finally { b.recycle() }
    }

    /** 밝기·대비·선명하게(F-56·57)를 입힌 그림. 바꿀 것이 없으면 [b] 그대로. */
    fun withLook(b: Bitmap): Bitmap {
        val l = look
        if (l.adjust.isIdentity && l.sharpen <= 0) return b
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        Sharpen.apply(px, b.width, b.height, l.sharpen)
        l.adjust.apply(px)
        val out = Bitmap.createBitmap(px, b.width, b.height, Bitmap.Config.ARGB_8888)
        b.recycle()
        return out
    }

    private fun blank(w: Int, h: Int, colors: ReadingColors): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(colors.bg) }

    override fun render(index: Int, w: Int, h: Int, colors: ReadingColors): Bitmap {
        val slot = slots?.getOrNull(index) ?: PageSlot(index, SlotPart.WHOLE)
        val p = slot.page
        if (slot.part == SlotPart.BLANK || p !in 0 until sourceCount || !src.ready(p)) return blank(w, h, colors)
        val part = slot.part
        val needW = when (part) { SlotPart.LEFT, SlotPart.RIGHT -> w * 2; SlotPart.ROTATE -> h; else -> w }
        val up = upscale?.load(p, needW)
        if (up != null) shownUpscaled += p else shownUpscaled -= p
        val raw = try { up ?: src.bitmap(p, needW) } catch (e: PageNotReady) { return blank(w, h, colors) }
        val l = look
        var box = if (l.crop) cropOf(p, raw) else CropBox(0, 0, raw.width, raw.height)
        if (SpreadLayout.isWide(raw.width, raw.height)) box = BorderCrop.symmetric(box, raw.width)
        val mid = (box.l + box.r) / 2
        val sr = when (part) {
            SlotPart.LEFT -> Rect(box.l, box.t, mid, box.b)
            SlotPart.RIGHT -> Rect(mid, box.t, box.r, box.b)
            else -> Rect(box.l, box.t, box.r, box.b)
        }
        if (sr.width() <= 0 || sr.height() <= 0) { raw.recycle(); return blank(w, h, colors) }
        val out = blank(w, h, colors)
        val rot = part == SlotPart.ROTATE
        val sw = (if (rot) sr.height() else sr.width()).toFloat()
        val sh = (if (rot) sr.width() else sr.height()).toFloat()
        val k = min(w / sw, h / sh)
        val dw = sw * k; val dh = sh * k
        // 두 쪽 펼침의 반쪽은 가운데(제본선) 쪽으로 붙인다
        val left = when {
            spreadLayout && part == SlotPart.LEFT -> w - dw
            spreadLayout && part == SlotPart.RIGHT -> 0f
            else -> (w - dw) / 2f
        }
        val top = (h - dh) / 2f
        val paint = Paint(if (l.hq) Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG else 0)
        val c = Canvas(out)
        if (rot) {
            val cx = left + dw / 2f; val cy = top + dh / 2f
            c.save()
            // 읽기 시작하는 쪽(왼→오면 왼쪽, 오→왼이면 오른쪽)이 위로 오게
            c.rotate(if (rtl) -90f else 90f, cx, cy)
            c.drawBitmap(raw, sr, RectF(cx - dh / 2f, cy - dw / 2f, cx + dh / 2f, cy + dw / 2f), paint)
            c.restore()
        } else {
            c.drawBitmap(raw, sr, RectF(left, top, left + dw, top + dh), paint)
        }
        raw.recycle()
        if (!l.adjust.isIdentity || l.sharpen > 0) {
            val r = Rect(left.toInt().coerceIn(0, w), top.toInt().coerceIn(0, h), ceil(left + dw).toInt().coerceIn(0, w), ceil(top + dh).toInt().coerceIn(0, h))
            if (r.width() > 0 && r.height() > 0) {
                val px = IntArray(r.width() * r.height())
                out.getPixels(px, 0, r.width(), r.left, r.top, r.width(), r.height())
                Sharpen.apply(px, r.width(), r.height(), l.sharpen)
                l.adjust.apply(px)
                out.setPixels(px, 0, r.width(), r.left, r.top, r.width(), r.height())
            }
        }
        return out
    }

    /** 테두리 자르기 상자(F-52). 쪽마다 한 번 재고 비율로 기억한다. */
    private fun cropOf(p: Int, raw: Bitmap): CropBox {
        val f = crops.getOrPut(p) {
            val scale = min(1f, max(600f / max(raw.width, raw.height), min(1f, 200f / raw.width)))
            val sw = max(1, (raw.width * scale).toInt()); val sh = max(1, (raw.height * scale).toInt())
            val small = if (scale < 1f) Bitmap.createScaledBitmap(raw, sw, sh, true) else raw
            try {
                val px = IntArray(small.width * small.height)
                small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
                val b = BorderCrop.detect(px, small.width, small.height, look.cropSens)
                floatArrayOf(b.l / small.width.toFloat(), b.t / small.height.toFloat(), b.r / small.width.toFloat(), b.b / small.height.toFloat())
            } finally { if (small !== raw) small.recycle() }
        }
        val l = (f[0] * raw.width).toInt().coerceIn(0, raw.width)
        val r = ceil(f[2] * raw.width).toInt().coerceIn(l, raw.width)
        val t = (f[1] * raw.height).toInt().coerceIn(0, raw.height)
        val b = ceil(f[3] * raw.height).toInt().coerceIn(t, raw.height)
        return CropBox(l, t, r, b)
    }

    // ---- 웹툰 띠(F-53) ----
    private val regionLock = Any()
    private val decoders = LinkedHashMap<Int, BitmapRegionDecoder>(4, 0.75f, true)
    /** 닫힌 뒤에는 영역 디코더를 새로 만들지 않는다(닫는 중 만든 디코더가 남는 누수 방지). */
    @Volatile private var regionClosed = false

    /** 쓰는 중(synchronized(d))인 디코더는 끝난 뒤 버린다. */
    private fun recycleDecoder(d: BitmapRegionDecoder) { synchronized(d) { d.recycle() } }

    private fun decoder(p: Int): BitmapRegionDecoder? = synchronized(regionLock) {
        if (regionClosed) return null
        decoders[p]?.let { return it }
        val bytes = src.bytes(p) ?: return null
        val d = PageDecoder.regionDecoder(bytes)
        decoders[p] = d
        while (decoders.size > 3) { val e = decoders.entries.iterator().next(); decoders.remove(e.key); recycleDecoder(e.value) }
        d
    }

    /**
     * 웹툰 띠 하나: 원본 [y0, y1) 줄을 폭 [displayW] 근처로 디코딩(보이는 띠만, 큰 그림은 영역 디코딩 — 메모리 한도).
     * 아직 풀리지 않은 쪽이면 [PageNotReady].
     */
    fun strip(p: Int, y0: Int, y1: Int, displayW: Int, cropL: Float = 0f, cropR: Float = 1f): Bitmap? {
        if (!src.ready(p)) throw PageNotReady()
        val (pw, ph) = sizeCache.getOrNull(p) ?: src.size(p)
        if (pw <= 0 || ph <= 0) return null
        // 웹툰 테두리 자르기: 책 전체에 같은 좌우 비율로만 자른다([webtoonCrop]) — 띠 높이가 쪽마다 흔들리지 않게
        val cl = (cropL * pw).toInt().coerceIn(0, pw - 1); val cr = (cropR * pw).toInt().coerceIn(cl + 1, pw)
        // AI 화질 향상(F-73) 결과가 있으면 2배 그림에서 같은 자리를 읽는다
        upDecoder(p)?.let { (ud, k) ->
            val o = BitmapFactory.Options().apply { inSampleSize = Webtoon.sampleFor((cr - cl) * k, displayW); inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val b = runCatching { synchronized(ud) { ud.decodeRegion(Rect(cl * k, y0.coerceIn(0, ph) * k, cr * k, y1.coerceIn(0, ph) * k), o) } }.getOrNull()
            if (b != null) return withLook(b)
        }
        val d = decoder(p)
        val b: Bitmap = (if (d != null) {
            val o = BitmapFactory.Options().apply {
                inSampleSize = Webtoon.sampleFor(cr - cl, displayW)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            synchronized(d) { if (d.isRecycled) null else d.decodeRegion(Rect(cl, y0.coerceIn(0, ph), cr, y1.coerceIn(0, ph)), o) }
        } else {
            // PDF: 쪽 전체를 화면 폭으로 그려 띠만 잘라 낸다
            val full = src.bitmap(p, displayW)
            val k = full.width.toFloat() / pw
            val r = Rect((cl * k).toInt(), (y0 * k).toInt().coerceIn(0, full.height - 1), (cr * k).toInt().coerceAtMost(full.width), (y1 * k).toInt().coerceIn(1, full.height))
            Bitmap.createBitmap(full, r.left, r.top, r.width().coerceAtLeast(1), (r.bottom - r.top).coerceAtLeast(1)).also { if (it !== full) full.recycle() }
        }) ?: return null
        return withLook(b)
    }

    /** 2배 결과 영역 디코더(정수 배율 2). 결과가 없으면 null. */
    private val upDecoders = LinkedHashMap<Int, BitmapRegionDecoder>(4, 0.75f, true)

    private fun upDecoder(p: Int): Pair<BitmapRegionDecoder, Int>? {
        val u = upscale ?: return null
        if (p !in u.ready.value) return null
        synchronized(regionLock) {
            if (regionClosed) return null
            upDecoders[p]?.let { return it to 2 }
            val f = u.file(p)
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: return null
            val d = runCatching { PageDecoder.regionDecoder(bytes) }.getOrNull() ?: return null
            val (pw, _) = pageSize(p)
            if (pw <= 0 || d.width != pw * 2) { d.recycle(); return null }
            upDecoders[p] = d
            while (upDecoders.size > 3) { val e = upDecoders.entries.iterator().next(); upDecoders.remove(e.key); recycleDecoder(e.value) }
            return d to 2
        }
    }

    /**
     * 웹툰 좌우 테두리(F-52 웹툰판): 앞쪽 몇 장을 재서 모든 쪽에 같이 쓸 좌우 자르기 비율(l, r).
     * 그림을 자르지 않도록 쪽들 중 가장 덜 자르는 값을 쓴다. 자를 것이 없으면 (0, 1).
     */
    fun webtoonCrop(samples: Int = 6): Pair<Float, Float> {
        var l = 1f; var r = 0f; var n = 0
        for (p in 0 until minOf(sourceCount, samples * 2)) {
            if (n >= samples) break
            if (!src.ready(p)) continue
            val (pw, ph) = runCatching { pageSize(p) }.getOrNull() ?: continue
            if (pw <= 0 || ph <= 0) continue
            val d = runCatching { decoder(p) }.getOrNull()
            // 위쪽 한 화면 정도만 작게 읽는다
            val b = runCatching {
                if (d != null) synchronized(d) {
                    d.decodeRegion(Rect(0, 0, pw, minOf(ph, pw * 3)), BitmapFactory.Options().apply { inSampleSize = Webtoon.sampleFor(pw, 240) })
                } else src.bitmap(p, 240)
            }.getOrNull() ?: continue
            try {
                val px = IntArray(b.width * b.height)
                b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
                val box = BorderCrop.detect(px, b.width, b.height, look.cropSens)
                l = minOf(l, box.l / b.width.toFloat()); r = maxOf(r, box.r / b.width.toFloat()); n++
            } finally { b.recycle() }
        }
        if (n == 0 || r <= l || r - l < 0.5f) return 0f to 1f
        return l to r
    }

    override fun toc(): List<TocEntry> = src.toc().map { if (slots == null) it else it.copy(page = if (it.page >= 0) indexOf(it.page) else it.page) }

    override fun close() {
        val olds = synchronized(regionLock) {
            regionClosed = true
            (decoders.values + upDecoders.values).also { decoders.clear(); upDecoders.clear() }
        }
        olds.forEach { recycleDecoder(it) }
        clearAhead()
        src.close()
    }

    companion object {
        fun metaFile(c: Context, docId: String) = File(c.cacheDir, "comic-meta/$docId")
    }
}
