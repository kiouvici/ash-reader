// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.bench

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExporter {
    private val HEADER = listOf("측정시각", "실험", "항목", "지표", "값", "단위", "비고")

    fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }

    private fun row(fields: List<String>) = fields.joinToString(",") { escape(it) }

    /** 앞부분에 '# 항목,값' 형식으로 기기 정보, 빈 줄 다음 측정 표. 엑셀 한글 깨짐 방지로 UTF-8 BOM. */
    fun build(device: DeviceInfo?, results: List<Measurement>): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        return buildString {
            append('﻿')
            device?.items?.forEach { (k, v) -> append(row(listOf("# $k", v))).append("\r\n") }
            append("\r\n")
            append(row(HEADER)).append("\r\n")
            results.forEach { m ->
                append(
                    row(
                        listOf(
                            ts.format(Date(m.timestampMs)), m.experiment, m.item, m.metric,
                            formatValue(m.value), m.unit, m.note,
                        ),
                    ),
                ).append("\r\n")
            }
        }
    }

    /** 자동 저장 파일(결과를 지우면 새 파일). */
    private var autoName: String? = null
    private var autoUri: Uri? = null

    /** [ResultStore.clear] 뒤: 다음 결과부터 새 파일에 쓴다. */
    fun resetAuto() { autoName = null; autoUri = null }

    /**
     * 자동 저장(작업 03): 결과가 늘 때마다 다운로드/AshBench/ash-bench-<모델>-<시각>.csv 를 덮어쓴다(adb pull 용).
     * 안드로이드 10+ 는 MediaStore(권한 없음), 9 이하는 앱 외부 폴더 files/exports/. 저장한 경로를 돌려준다.
     */
    fun autoSave(context: Context, device: DeviceInfo?, results: List<Measurement>): String {
        val name = autoName ?: run {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val model = Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "_")
            "ash-bench-$model-$stamp.csv".also { autoName = it }
        }
        val bytes = build(device, results).toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = context.contentResolver
            val written = autoUri?.let { u -> runCatching { cr.openOutputStream(u, "wt")?.use { it.write(bytes) } != null }.getOrDefault(false) } == true
            if (!written) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$AUTO_DIR")
                }
                val u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("다운로드 폴더에 만들 수 없음")
                cr.openOutputStream(u, "wt")?.use { it.write(bytes) } ?: error("다운로드 폴더에 쓸 수 없음")
                autoUri = u
            }
            return "/sdcard/${Environment.DIRECTORY_DOWNLOADS}/$AUTO_DIR/$name"
        }
        val f = File(File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }, name)
        f.writeBytes(bytes)
        return f.absolutePath
    }

    private const val AUTO_DIR = "AshBench"

    fun formatValue(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong().toString()
        else String.format(Locale.ROOT, "%.3f", v)

    /** 캐시/exports 에 저장하고 공유 시트를 연다 (사용자가 직접 실행하는 공유, N-05 ④). */
    fun share(context: Context, device: DeviceInfo?, results: List<Measurement>) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val model = Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "ash-bench-$model-$stamp.csv")
        file.writeText(build(device, results), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "측정 결과 CSV 보내기"))
    }
}
