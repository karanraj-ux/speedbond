package com.karan.speedbond.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Live per-network throughput, bytes/sec. */
data class NetworkSpeed(
    val id: String,
    val displayName: String,
    val bytesPerSec: Long,
)

/** Everything the single UI screen (and the notification) needs to render. */
data class DownloadUiState(
    val status: String = "Idle",
    val fileName: String = "",
    val downloadedBytes: Long = 0,
    val totalBytes: Long = -1,
    val speeds: List<NetworkSpeed> = emptyList(),
    val error: String? = null,
) {
    val isDownloading: Boolean get() = status == "Downloading" || status.startsWith("Starting")
    val progress: Float get() =
        if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * Process-wide singleton the [com.karan.speedbond.service.DownloadService]
 * writes and the Compose UI reads. Keeps the MVP to one screen with no
 * service-binding ceremony.
 */
object DownloadState {
    private val _ui = MutableStateFlow(DownloadUiState())
    val ui: StateFlow<DownloadUiState> = _ui

    fun update(fn: (DownloadUiState) -> DownloadUiState) {
        _ui.value = fn(_ui.value)
    }

    fun reset() {
        _ui.value = DownloadUiState()
    }
}
