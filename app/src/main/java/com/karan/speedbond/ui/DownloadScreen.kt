package com.karan.speedbond.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karan.speedbond.engine.DownloadState
import com.karan.speedbond.service.DownloadService

/**
 * The whole app: one screen, one feature.
 * Paste a link → download it over Wi-Fi + mobile data at the same time.
 */
@Composable
fun DownloadScreen() {
    val ui by DownloadState.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var url by remember { mutableStateOf("") }
    var useWifi by remember { mutableStateOf(true) }
    var useCell by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("SpeedBond", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "One file. Wi-Fi + mobile data together. Roughly double the speed.",
            fontSize = 14.sp, color = Color.Gray,
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("File URL (https://…)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = useWifi, onCheckedChange = { useWifi = it })
            Text("Wi-Fi")
            Spacer(Modifier.padding(8.dp))
            Checkbox(checked = useCell, onCheckedChange = { useCell = it })
            Text("Mobile data")
        }
        if (useCell) {
            Text(
                "Mobile data will be used — on unlimited 5G plans this is free speed.",
                fontSize = 12.sp, color = Color.Gray,
            )
        }

        Button(
            onClick = { startDownload(context, url.trim(), useWifi, useCell) },
            enabled = url.isNotBlank() && !ui.isDownloading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (ui.isDownloading) "Downloading…" else "Download at 2x")
        }

        if (ui.isDownloading) {
            OutlinedButton(
                onClick = { stopDownload(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel") }
        }

        OutlinedButton(
            onClick = { requestBatteryWhitelist(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Allow background downloads (recommended)") }

        Spacer(Modifier.height(4.dp))

        if (ui.fileName.isNotEmpty()) {
            Text(ui.fileName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
        if (ui.totalBytes > 0) {
            LinearProgressIndicator(
                progress = { ui.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            val pct = (ui.progress * 100).toInt()
            Text(
                "${formatBytes(ui.downloadedBytes)} / ${formatBytes(ui.totalBytes)} · $pct%",
                fontSize = 13.sp,
            )
        } else if (ui.downloadedBytes > 0) {
            Text(formatBytes(ui.downloadedBytes), fontSize = 13.sp)
        }

        val totalSpeed = ui.speeds.sumOf { it.bytesPerSec }
        if (ui.isDownloading || totalSpeed > 0) {
            Text(
                "Total: ${formatSpeed(totalSpeed)}",
                fontSize = 20.sp, fontWeight = FontWeight.Bold,
            )
            ui.speeds.forEach { s ->
                Text("${s.displayName}: ${formatSpeed(s.bytesPerSec)}", fontSize = 13.sp)
            }
            if (ui.totalBytes > 0 && totalSpeed > 0) {
                val remaining = ui.totalBytes - ui.downloadedBytes
                Text("ETA: ${formatDuration(remaining / totalSpeed)}", fontSize = 13.sp, color = Color.Gray)
            }
        }

        Text(ui.status, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        ui.error?.let { Text(it, fontSize = 13.sp, color = Color(0xFFB00020)) }
    }
}

private fun startDownload(context: Context, url: String, useWifi: Boolean, useCell: Boolean) {
    val intent = Intent(context, DownloadService::class.java).apply {
        action = DownloadService.ACTION_START
        putExtra(DownloadService.EXTRA_URL, url)
        putExtra(DownloadService.EXTRA_USE_WIFI, useWifi)
        putExtra(DownloadService.EXTRA_USE_CELL, useCell)
    }
    ContextCompat.startForegroundService(context, intent)
}

private fun stopDownload(context: Context) {
    val intent = Intent(context, DownloadService::class.java).apply {
        action = DownloadService.ACTION_STOP
    }
    context.startService(intent)
}

private fun requestBatteryWhitelist(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = context.getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                },
            )
        }
    }
}

private fun formatBytes(b: Long): String = when {
    b >= 1_000_000_000 -> "%.2f GB".format(b / 1_000_000_000.0)
    b >= 1_000_000 -> "%.1f MB".format(b / 1_000_000.0)
    b >= 1_000 -> "%.0f KB".format(b / 1_000.0)
    else -> "$b B"
}

private fun formatSpeed(bps: Long): String = "${formatBytes(bps)}/s"

private fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}
