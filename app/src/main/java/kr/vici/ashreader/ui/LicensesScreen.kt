// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.vici.ashreader.AppInfo
import org.json.JSONObject

/** 고지 목록 한 줄(assets/licenses/index.json). [texts] = 원문 파일(assets/licenses 기준 경로). */
data class LicenseItem(
    val name: String,
    val version: String,
    val license: String,
    val group: String,
    val copyright: String,
    val note: String,
    val texts: List<String>,
)

object LicenseCatalog {
    const val DIR = "licenses"

    fun load(context: Context): List<LicenseItem> {
        val json = context.assets.open("$DIR/index.json").use { String(it.readBytes(), Charsets.UTF_8) }
        val arr = JSONObject(json).getJSONArray("components")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val t = o.optJSONArray("texts")
            LicenseItem(
                name = o.optString("name"), version = o.optString("version"), license = o.optString("license"),
                group = o.optString("group"), copyright = o.optString("copyright"), note = o.optString("note"),
                texts = if (t == null) emptyList() else (0 until t.length()).map { t.getString(it) },
            )
        }
    }

    fun text(context: Context, item: LicenseItem): String = item.texts.joinToString("\n\n────────────────\n\n") { p ->
        runCatching { context.assets.open("$DIR/$p").use { String(it.readBytes(), Charsets.UTF_8) } }.getOrDefault("($p 를 읽지 못했어요)")
    }
}

/** S-52 오픈소스 라이선스 고지(C1). 기기 안 파일만 읽는다. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf<LicenseItem?>(null) }
    val items by produceState<List<LicenseItem>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { LicenseCatalog.load(context) }.getOrDefault(emptyList()) }
    }
    BackHandler(enabled = open != null) { open = null }

    val cur = open
    if (cur != null) {
        val text by produceState<String?>(null, cur) { value = withContext(Dispatchers.IO) { LicenseCatalog.text(context, cur) } }
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(cur.name) { open = null }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                item {
                    Text(listOf(cur.version, cur.license).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 14.sp, color = cs.primary)
                    if (cur.copyright.isNotBlank()) Text(cur.copyright, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    if (cur.note.isNotBlank()) Text(cur.note, fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = cs.outlineVariant)
                }
                val t = text
                if (t == null) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
                else {
                    // 긴 원문은 문단 단위로 나눠 그린다(한 Text 에 수만 자를 넣지 않음)
                    val parts = t.split("\n\n")
                    items(parts.size) { i ->
                        SelectionContainer {
                            Text(parts[i], fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 10.dp))
                        }
                    }
                    item { Text("", modifier = Modifier.padding(bottom = 24.dp)) }
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("오픈소스 라이선스", onBack)
        val list = items
        if (list == null) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainer).padding(16.dp)) {
                    Text("${AppInfo.NAME} ${AppInfo.VERSION_LABEL}", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "이 앱은 GNU AGPL 3.0 이상(AGPL-3.0-or-later)으로 배포돼요. 누구나 소스 코드를 받아 고치고 다시 배포할 수 있어요. " +
                            "소스 코드: ${AppInfo.SOURCE_URL} (함께 넣은 오픈소스 구성 요소의 소스도 같은 곳에서 판마다 따로 묶어 드려요.) " +
                            "64비트 기기 전용이에요.",
                        fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                    Text("아래는 앱에 함께 들어간 오픈소스 구성 요소예요. 누르면 라이선스 원문을 볼 수 있어요.", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
            if (list.isEmpty()) item {
                Text("고지 목록을 읽지 못했어요.", fontSize = 14.sp, modifier = Modifier.padding(20.dp))
            }
            val groups = list.groupBy { it.group }
            for ((g, rows) in groups) {
                item(key = "g:$g") {
                    Text("$g (${rows.size})", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.primary,
                        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp))
                }
                items(rows, key = { "c:${it.group}:${it.name}:${it.version}" }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { open = c }.heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(if (c.version.isBlank()) c.name else "${c.name} ${c.version}", fontSize = 15.sp)
                            Text(c.license, fontSize = 12.sp, color = cs.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(color = cs.outlineVariant, modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
            item { Text("", modifier = Modifier.padding(bottom = 24.dp)) }
        }
    }
}
