// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.vici.ashreader.data.Book
import kr.vici.ashreader.data.DocFormat
import kr.vici.ashreader.data.SeriesGroups
import kr.vici.ashreader.data.progress

/**
 * S-13 시리즈 안 화면. ⋮ = 묶기 풀기 · 이름 바꾸기 · 권 추가(직접 묶기)(F-62).
 * 권 순서는 파일명 숫자 순서(1, 2, 10).
 */
@Composable
fun SeriesScreen(
    name: String,
    vols: List<Book>,
    all: List<Book>,
    onBack: () -> Unit,
    onOpen: (Book) -> Unit,
    onUngroup: () -> Unit,
    onRename: (String) -> Unit,
    onAdd: (List<Book>) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val done = vols.count { it.status == Book.DONE }
    val next = vols.firstOrNull { it.status == Book.READING } ?: vols.firstOrNull { it.status != Book.DONE }
    var more by remember { mutableStateOf(false) }
    var confirmUngroup by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.Back, contentDescription = "뒤로") }
            Text(name, fontFamily = Myeongjo, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = cs.primary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { more = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.More, contentDescription = "시리즈 메뉴") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("권 추가(직접 묶기)") }, onClick = { more = false; adding = true }, modifier = Modifier.heightIn(min = 48.dp))
                    DropdownMenuItem(text = { Text("이름 바꾸기") }, onClick = { more = false; rename = true }, modifier = Modifier.heightIn(min = 48.dp))
                    DropdownMenuItem(text = { Text("묶기 풀기") }, onClick = { more = false; confirmUngroup = true }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        if (vols.isEmpty()) {
            Text("이 시리즈에 남은 책이 없어요.", fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            return@Column
        }
        Text(
            "전 ${vols.size}권 · ${if (done > 0) "${done}권 다 읽음" else "아직 안 읽음"}${next?.let { " · 다음은 ${SeriesGroups.volumeLabel(it)}" } ?: ""}",
            fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
        )
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(vols, key = { it.id }) { b ->
                val state = when {
                    b.status == Book.DONE -> "다 읽음"
                    b.status == Book.READING -> "${(b.progress() * 100).toInt()}% 읽는 중"
                    b == next -> "다음 차례"
                    else -> "새 문서"
                }
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(b) }.padding(horizontal = 20.dp, vertical = 8.dp)
                        .semantics { contentDescription = "${SeriesGroups.volumeLabel(b)}, $state" },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Cover(b, Modifier.width(56.dp))
                    Column(Modifier.weight(1f)) {
                        Text(SeriesGroups.volumeLabel(b), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (b.volume > 0) Text(b.title, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(progress = { b.progress() }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), drawStopIndicator = {})
                        Text(state, fontSize = 12.sp, color = if (b == next) cs.secondary else cs.onSurfaceVariant)
                    }
                    Badge(DocFormat.of(b.format))
                }
            }
        }
    }
    if (confirmUngroup) AlertDialog(
        onDismissRequest = { confirmUngroup = false },
        title = { Text("묶기 풀기") },
        text = { Text("묶음만 풀고 파일은 그대로 둡니다.", fontSize = 15.sp) },
        confirmButton = { TextButton(onClick = { confirmUngroup = false; onUngroup() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("풀기") } },
        dismissButton = { TextButton(onClick = { confirmUngroup = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } },
    )
    if (rename) NameDialog("이름 바꾸기", name, onDismiss = { rename = false }) { n -> rename = false; onRename(n) }
    if (adding) {
        val ids = vols.map { it.id }.toSet()
        PickBooksDialog("'$name'에 넣을 책", all.filter { it.id !in ids }, onDismiss = { adding = false }) { picked -> adding = false; if (picked.isNotEmpty()) onAdd(picked) }
    }
}

/** 이름 입력 확인창(시리즈 이름). */
@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { text = it.take(80) }, singleLine = true, label = { Text("시리즈 이름") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        },
        confirmButton = { TextButton(onClick = { onOk(text.trim()) }, enabled = text.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } },
    )
}

/** 여러 책 고르기(권 추가). */
@Composable
private fun PickBooksDialog(title: String, books: List<Book>, onDismiss: () -> Unit, onOk: (List<Book>) -> Unit) {
    val picked = remember { mutableStateListOf<String>() }
    val sorted = remember(books) { books.sortedWith(SeriesGroups.order) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (sorted.isEmpty()) Text("더 넣을 책이 없어요.", fontSize = 14.sp)
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(sorted, key = { it.id }) { b ->
                    val on = b.id in picked
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { if (on) picked.remove(b.id) else picked.add(b.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = { if (it) picked.add(b.id) else picked.remove(b.id) })
                        Text(b.title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onOk(sorted.filter { it.id in picked }) }, enabled = picked.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (picked.isEmpty()) "넣기" else "${picked.size}권 넣기")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } },
    )
}

/**
 * 문서 메뉴(S-14)의 '시리즈로 묶기': 이미 있는 시리즈를 고르거나 새 이름을 넣는다.
 * [names] = 지금 있는 시리즈 이름들.
 */
@Composable
fun GroupIntoSeriesDialog(b: Book, names: List<String>, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(b.seriesKey.ifBlank { kr.vici.ashreader.data.SeriesParser.parse(b.title)?.first ?: b.title }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("시리즈로 묶기") },
        text = {
            Column {
                OutlinedTextField(text, { text = it.take(80) }, singleLine = true, label = { Text("시리즈 이름") }, modifier = Modifier.fillMaxWidth())
                if (names.isNotEmpty()) {
                    Text("있는 시리즈에 넣기", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(names) { n ->
                            Text(n, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { text = n }.padding(vertical = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(text.trim()) }, enabled = text.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("묶기") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } },
    )
}
