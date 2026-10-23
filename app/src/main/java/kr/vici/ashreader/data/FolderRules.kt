// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.data

/**
 * 서재 폴더(S-17, F-01)의 겹침·셈 규칙. 안드로이드 클래스를 쓰지 않는다(단위 시험).
 *
 * 폴더 위치는 '정규 위치' 문자열 "볼륨:상대경로"로 비교한다(SAF 폴더 문서 id 와 같은 꼴).
 *  - 내장 저장공간 /storage/emulated/0/Download/소설 → "primary:Download/소설"
 *  - SD 카드 /storage/1A2B-3C4D/Comics → "1A2B-3C4D:Comics"
 *  - 볼륨 맨 위 → "primary:" (상대경로 빈 글)
 * 볼륨 이름은 대소문자를 가리지 않는다(SD 카드 uuid 가 경로·SAF 에서 대소문자가 다를 수 있음).
 */
object FolderRules {
    /** 서재 폴더 한 줄. [key] = DB 의 path(경로 또는 SAF 주소), [canon] = 정규 위치. */
    data class Entry(val key: String, val canon: String, val subfolders: Boolean, val name: String)

    /** 경로 → 정규 위치. [primaryRoot] = 내장 저장공간 경로(/storage/emulated/0). 저장공간 밖이면 null. */
    fun canonicalOfPath(path: String, primaryRoot: String): String? {
        val p = path.trimEnd('/')
        val root = primaryRoot.trimEnd('/')
        if (p == root) return "primary:"
        if (p.startsWith("$root/")) return "primary:" + p.substring(root.length + 1)
        if (p.startsWith("/storage/")) {
            val rest = p.removePrefix("/storage/")
            val vol = rest.substringBefore('/')
            if (vol.isEmpty() || vol == "emulated" || vol == "self") return null
            return vol + ":" + rest.substringAfter('/', "")
        }
        return null
    }

    /** SAF 문서 id("primary:Download/소설/") → 정규 위치(끝 '/' 없음). ':' 가 없으면 null. */
    fun canonicalOfDocId(docId: String): String? {
        val i = docId.indexOf(':')
        if (i <= 0) return null
        return docId.substring(0, i) + ":" + docId.substring(i + 1).trim('/')
    }

    private fun vol(c: String) = c.substringBefore(':').lowercase()
    private fun rel(c: String) = c.substringAfter(':', "")

    /** [child] 가 [parent] 와 같거나 그 안에 있나. */
    fun within(parent: String, child: String): Boolean {
        if (vol(parent) != vol(child)) return false
        val pr = rel(parent); val cr = rel(child)
        return pr.isEmpty() || cr == pr || cr.startsWith("$pr/")
    }

    /** 같은 폴더인가(볼륨 대소문자 무시). */
    fun same(a: String, b: String) = vol(a) == vol(b) && rel(a) == rel(b)

    /**
     * 새로 고른 폴더 [canon] 이 이미 있는 폴더에 들어 있나. 같은 폴더이거나, '하위 폴더도 포함'인 폴더 안이면 그 폴더.
     * (하위 폴더를 포함하지 않는 폴더의 안쪽 폴더는 겹치지 않으므로 더할 수 있다.)
     */
    fun coveringFolder(canon: String, existing: List<Entry>): Entry? =
        existing.firstOrNull { same(it.canon, canon) } ?: existing.firstOrNull { it.subfolders && within(it.canon, canon) }

    /** 다른 폴더('하위 폴더도 포함')에 덮인 폴더를 뺀, 실제로 훑을 폴더들(같은 폴더가 둘이면 먼저 것). 두 번 세지 않기. */
    fun walkRoots(entries: List<Entry>): List<Entry> = entries.filterIndexed { i, e ->
        entries.withIndex().none { (j, o) ->
            j != i && o.subfolders && within(o.canon, e.canon) && (!same(o.canon, e.canon) || j < i)
        }
    }

    /** 책이 든 폴더 [dir](정규 위치)이 서재 폴더 [f] 에 속하나('하위 폴더도 포함'이 꺼져 있으면 바로 안만). */
    fun holds(f: Entry, dir: String): Boolean = if (f.subfolders) within(f.canon, dir) else same(f.canon, dir)

    /** 파일 정규 위치 → 그 파일이 든 폴더. "primary:Download/a.epub" → "primary:Download". */
    fun parentOf(fileCanon: String): String {
        val r = rel(fileCanon)
        val head = fileCanon.substringBefore(':')
        return head + ":" + (if (r.contains('/')) r.substringBeforeLast('/') else "")
    }

    /** 폴더별 책 수와 서재 전체 책 수(겹친 폴더의 책은 한 번만). [bookDirs] = 책마다 든 폴더(정규 위치). */
    fun counts(folders: List<Entry>, bookDirs: List<String>): Pair<List<Int>, Int> {
        val per = IntArray(folders.size)
        var total = 0
        for (d in bookDirs) {
            var any = false
            folders.forEachIndexed { i, f -> if (holds(f, d)) { per[i]++; any = true } }
            if (any) total++
        }
        return per.toList() to total
    }

    /** 화면에 보일 폴더 이름: 마지막 마디, 볼륨 맨 위면 볼륨 이름. */
    fun nameOf(canon: String, volumeLabel: (String) -> String): String =
        rel(canon).substringAfterLast('/').ifEmpty { volumeLabel(canon.substringBefore(':')) }

    /** 화면에 보일 경로: "내장 저장공간/Documents/업무", "SanDisk SD 카드/Comics". */
    fun pathOf(canon: String, volumeLabel: (String) -> String): String {
        val v = volumeLabel(canon.substringBefore(':'))
        val r = rel(canon)
        return if (r.isEmpty()) v else "$v/$r"
    }

    /**
     * 가운데를 줄인 경로(좁은 화면 360dp). 앞 마디(볼륨)와 끝 마디(폴더 이름)는 남기고 가운데를 '…'로.
     * [max] 글자 안에 들면 그대로.
     */
    fun middleEllipsis(text: String, max: Int): String {
        if (text.length <= max || max < 5) return text
        val keepEnd = (max - 1) * 3 / 5
        val keepStart = max - 1 - keepEnd
        return text.take(keepStart) + "…" + text.takeLast(keepEnd)
    }
}
