// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import java.io.File
import org.junit.Assume

object TestFiles {
    private val root = File(System.getProperty("ash.testFiles") ?: "../test-files")
    fun public(name: String): File = File(root, "public/$name").also { check(it.exists()) { "시험 파일 없음: $it" } }

    /** test-files/private 은 저장소에 없다(D-006). 있을 때만 시험하고, 없으면 건너뛴다. */
    fun private(name: String): File {
        val dir = System.getProperty("ash.privateFiles")?.let { File(it) } ?: File(root, "private")
        val f = File(dir, name)
        Assume.assumeTrue("실무 파일 없음(건너뜀): $name", f.exists())
        return f
    }

    fun norm(s: String) = s.replace(Regex("\\s+"), "")
}
