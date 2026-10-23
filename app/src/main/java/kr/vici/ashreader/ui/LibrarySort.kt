// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.Collator
import java.util.Locale
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.progress

/**
 * F-03 서재 정렬. 순서(ordinal)를 설정에 저장하므로 끝에만 더한다.
 * [ascLabel]/[descLabel] = 오름·내림이 무엇을 뜻하는지.
 */
enum class LibSort(val label: String, val ascLabel: String, val descLabel: String) {
    TITLE("이름", "가 → 하", "하 → 가"),
    RECENT("최근 읽음", "오래된 것 먼저", "최근 것 먼저"),
    ADDED("추가한 날", "오래된 것 먼저", "최근 것 먼저"),
    FORMAT("형식", "가 → 하", "하 → 가"),
    PROGRESS("읽은 정도", "적게 읽은 것 먼저", "많이 읽은 것 먼저"),
    SIZE("파일 크기", "작은 것 먼저", "큰 것 먼저"),
    ;

    companion object {
        fun of(i: Int) = entries.getOrElse(i) { TITLE }
    }
}

private val collator: Collator by lazy { Collator.getInstance(Locale.KOREAN) }

/** 정렬. 같은 값이면 이름순(오름)으로 둘째 기준. 이름은 한국어 사전 순서(자연스러운 숫자 순서는 시리즈 화면이 맡음). */
fun sortBooks(list: List<Book>, sort: LibSort, desc: Boolean): List<Book> {
    val byTitle = Comparator<Book> { a, b -> collator.compare(a.title, b.title) }
    val primary: Comparator<Book> = when (sort) {
        LibSort.TITLE -> byTitle
        LibSort.RECENT -> compareBy<Book> { it.lastOpenedAt }
        LibSort.ADDED -> compareBy<Book> { it.addedAt }
        LibSort.FORMAT -> compareBy<Book> { DocFormat.of(it.format).badge }
        LibSort.PROGRESS -> compareBy<Book> { if (it.status == Book.DONE) 2f else it.progress() }
        LibSort.SIZE -> compareBy<Book> { it.sizeBytes }
    }
    return list.sortedWith((if (desc) primary.reversed() else primary).then(byTitle))
}

/** 정렬 고르기(S-10 '더 보기' → 정렬). 고르면 바로 바뀐다. */
@Composable
fun LibrarySortDialog(sort: LibSort, desc: Boolean, onPick: (LibSort, Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("정렬") },
        text = {
            Column {
                LibSort.entries.forEach { o ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton) { onPick(o, desc) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = o == sort, onClick = null)
                        Text(o.label, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                Text("순서", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(sort.ascLabel, !desc) { onPick(sort, false) }
                    Pill(sort.descLabel, desc) { onPick(sort, true) }
                }
                Text("‘최근’ 칸은 늘 최근 읽은 순서예요.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp).semantics { contentDescription = "최근 칸은 늘 최근 읽은 순서" })
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") } },
    )
}
