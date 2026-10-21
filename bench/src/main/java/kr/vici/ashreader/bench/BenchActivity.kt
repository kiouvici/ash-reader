// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.bench

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BenchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BenchScreen()
                }
            }
        }
    }
}

@Composable
private fun BenchScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var device by remember { mutableStateOf<DeviceInfo?>(null) }
    var assetFiles by remember { mutableStateOf<List<TestFile>>(emptyList()) }
    var treeUri by remember { mutableStateOf<Uri?>(null) }
    var treeFiles by remember { mutableStateOf<List<TestFile>>(emptyList()) }
    var running by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun reloadTree(uri: Uri?) {
        treeUri = uri
        treeFiles = if (uri == null) emptyList() else withContext(Dispatchers.IO) {
            runCatching { TestFiles.listTree(context, uri) }.getOrElse {
                message = "폴더를 읽지 못했습니다: ${it.message}"
                emptyList()
            }
        }
    }

    LaunchedEffect(Unit) {
        device = DeviceInfoCollector.collect(context)
        assetFiles = withContext(Dispatchers.IO) { TestFiles.listAssets(context) }
        reloadTree(TestFiles.savedTree(context))
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { TestFiles.saveTree(context, uri) }
                reloadTree(uri)
            }
        }
    }

    val results = ResultStore.results

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "ASH 측정",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
        }

        // ① 기기 정보
        item {
            Section("기기 정보") {
                val d = device
                if (d == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("확인 중…")
                    }
                } else {
                    d.items.forEach { (k, v) -> KeyValue(k, v) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        scope.launch { device = DeviceInfoCollector.collect(context, refresh = true) }
                    }) { Text("다시 확인") }
                }
            }
        }

        // ④ 시험 파일
        item {
            Section("시험 파일") {
                Text("앱에 든 공개 파일 ${assetFiles.size}개", style = MaterialTheme.typography.bodyMedium)
                assetFiles.take(20).forEach { Text("· ${it.name} (${sizeText(it.size)})", style = MaterialTheme.typography.bodySmall) }
                if (assetFiles.size > 20) Text("… 외 ${assetFiles.size - 20}개", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (treeUri == null) "고른 폴더 없음" else "고른 폴더의 파일 ${treeFiles.size}개",
                    style = MaterialTheme.typography.bodyMedium,
                )
                treeFiles.take(20).forEach { Text("· ${it.name} (${sizeText(it.size)})", style = MaterialTheme.typography.bodySmall) }
                if (treeFiles.size > 20) Text("… 외 ${treeFiles.size - 20}개", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickFolder.launch(null) }) { Text("폴더 고르기") }
                    if (treeUri != null) {
                        OutlinedButton(onClick = { scope.launch { reloadTree(treeUri) } }) { Text("새로 읽기") }
                    }
                }
            }
        }

        // ② 실험 버튼
        item {
            Section("실험") {
                Experiments.all.forEach { exp ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(exp.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (exp.ready) exp.description else "준비 중 — ${exp.description}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (exp.screens.isNotEmpty()) {
                            Column(horizontalAlignment = Alignment.End) {
                                exp.screens.forEach { (label, cls) ->
                                    Button(onClick = { context.startActivity(android.content.Intent(context, cls)) }) { Text(label) }
                                }
                            }
                            return@Row
                        }
                        Button(
                            enabled = exp.ready && running == null,
                            onClick = {
                                running = exp.title
                                scope.launch {
                                    val files = assetFiles + treeFiles
                                    val rows = withContext(Dispatchers.Default) {
                                        runCatching { exp.run(context, files) }.getOrElse {
                                            listOf(Measurement(exp.title, "-", "실행 오류", Double.NaN, "", "${it.javaClass.simpleName}: ${it.message}"))
                                        }
                                    }
                                    ResultStore.addAll(rows)
                                    message = if (rows.isEmpty()) "${exp.title}: 측정할 파일이 없습니다" else "${exp.title}: ${rows.size}행 추가"
                                    running = null
                                }
                            },
                        ) {
                            if (running == exp.title) {
                                CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text(if (exp.ready) "측정" else "준비 중")
                            }
                        }
                    }
                }
                message?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // ③ 결과 표 + CSV 공유
        item {
            Section("측정 결과 (${results.size}행)") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = results.isNotEmpty(),
                        onClick = {
                            runCatching { CsvExporter.share(context, device, results.toList()) }
                                .onFailure { message = "CSV 내보내기 실패: ${it.message}" }
                        },
                    ) { Text("CSV 공유") }
                    OutlinedButton(enabled = results.isNotEmpty(), onClick = { ResultStore.clear() }) { Text("지우기") }
                }
                Spacer(Modifier.height(8.dp))
                if (results.isEmpty()) {
                    Text("아직 측정 결과가 없습니다.", style = MaterialTheme.typography.bodySmall)
                } else {
                    ResultTable(results)
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(key, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

private val COLS = listOf("실험" to 150, "항목" to 160, "지표" to 120, "값" to 90, "단위" to 48, "비고" to 220)

@Composable
private fun ResultTable(rows: List<Measurement>) {
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row { COLS.forEach { (h, w) -> Cell(h, w, bold = true) } }
        HorizontalDivider()
        rows.forEach { m ->
            Row {
                Cell(m.experiment, COLS[0].second)
                Cell(m.item, COLS[1].second)
                Cell(m.metric, COLS[2].second)
                Cell(if (m.value.isNaN()) "-" else CsvExporter.formatValue(m.value), COLS[3].second, mono = true)
                Cell(m.unit, COLS[4].second)
                Cell(m.note, COLS[5].second)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun Cell(text: String, widthDp: Int, bold: Boolean = false, mono: Boolean = false) {
    Text(
        text,
        modifier = Modifier.width(widthDp.dp).padding(horizontal = 4.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.SemiBold else null,
        fontFamily = if (mono) FontFamily.Monospace else null,
    )
}

private fun sizeText(bytes: Long): String = when {
    bytes < 0 -> "크기 모름"
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> String.format(java.util.Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024))
}
