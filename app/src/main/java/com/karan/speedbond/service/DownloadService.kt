package com.karan.speedbond.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.karan.speedbond.MainActivity
import com.karan.speedbond.engine.DownloadEngine
import com.karan.speedbond.engine.DownloadState
import com.karan.speedbond.engine.DownloadUiState
import com.karan.speedbond.net.NetworkBondManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs the bonded download in a foreground service so it survives Doze and
 * app standby. Shows live progress in a persistent notification.
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.karan.speedbond.action.START"
        const val ACTION_STOP = "com.karan.speedbond.action.STOP"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_USE_WIFI = "extra_use_wifi"
        const val EXTRA_USE_CELL = "extra_use_cell"

        private const val CHANNEL_ID = "speedbond_downloads"
        private const val NOTIF_ID = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var bonds: NetworkBondManager
    private var job: Job? = null
    private var lastNotifAt = 0L

    override fun onCreate() {
        super.onCreate()
        bonds = NetworkBondManager(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                job?.cancel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> startDownload(intent)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startDownload(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val useWifi = intent.getBooleanExtra(EXTRA_USE_WIFI, true)
        val useCell = intent.getBooleanExtra(EXTRA_USE_CELL, true)
        if (url.isBlank()) {
            stopSelf()
            return
        }

        startForegroundCompat(buildNotification("Preparing…", null))
        DownloadState.reset()
        job?.cancel()
        job = scope.launch {
            // Mirror progress into the notification (throttled to 1/sec).
            launch {
                DownloadState.ui.collect { ui ->
                    val now = System.currentTimeMillis()
                    val finished = ui.status == "Done" || ui.status == "Failed"
                    if (!finished && now - lastNotifAt < 1000) return@collect
                    lastNotifAt = now
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_ID, buildNotification(ui.status, ui))
                }
            }

            try {
                bonds.bind()
                val networks = withTimeoutOrNull(10_000) {
                    bonds.networks.first { it.isNotEmpty() }
                }.orEmpty().filter {
                    (it.id == "wifi" && useWifi) || (it.id == "cellular" && useCell)
                }
                if (networks.isEmpty()) {
                    DownloadState.update {
                        it.copy(
                            status = "Failed",
                            error = "No network available — turn on Wi-Fi or mobile data.",
                        )
                    }
                    return@launch
                }
                val dir = getExternalFilesDir(null) ?: filesDir
                DownloadEngine(networks, dir).download(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DownloadState.update {
                    it.copy(status = "Failed", error = e.message ?: "Download failed")
                }
            } finally {
                bonds.unbind()
                delay(2000) // let the final notification/state be seen
                stopSelf()
            }
        }
    }

    private fun buildNotification(status: String, ui: DownloadUiState?): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val totalSpeed = ui?.speeds?.sumOf { it.bytesPerSec } ?: 0L
        val text = when {
            ui == null -> status
            ui.status == "Done" -> "Done — ${ui.fileName}"
            ui.status == "Failed" -> ui.error ?: "Failed"
            ui.totalBytes > 0 -> {
                val pct = (ui.downloadedBytes * 100 / ui.totalBytes).toInt()
                "$pct% · ${formatSpeed(totalSpeed)}"
            }
            else -> "$status · ${formatSpeed(totalSpeed)}"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("SpeedBond")
            .setContentText(text)
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (ui != null && ui.totalBytes > 0) {
            val permille = ((ui.downloadedBytes * 1000 / ui.totalBytes).toInt()).coerceIn(0, 1000)
            builder.setProgress(1000, permille, false)
        }
        return builder.build()
    }

    private fun formatSpeed(bps: Long): String = when {
        bps >= 1_000_000 -> "%.1f MB/s".format(bps / 1_000_000.0)
        bps >= 1_000 -> "%.0f KB/s".format(bps / 1_000.0)
        else -> "$bps B/s"
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
