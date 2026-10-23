// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.office

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 6B: Agile 암호 정보 XML 의 DOCTYPE·엔티티 거부(XXE). */
class AgileXxeTest {
    @Test fun rejectsDoctype() {
        val x = "<?xml version=\"1.0\"?><!DOCTYPE e [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><encryption>&x;</encryption>"
        assertTrue(AgileDecryptor.hasDoctype(x.toByteArray()))
        assertTrue(AgileDecryptor.hasDoctype(x.toByteArray(Charsets.UTF_16LE)))
    }

    @Test fun acceptsPlain() {
        assertFalse(AgileDecryptor.hasDoctype("<?xml version=\"1.0\"?><encryption xmlns=\"x\"><keyData/></encryption>".toByteArray()))
    }
}
