package com.karan.speedbond.engine

import com.karan.speedbond.net.BondedNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * MVP download engine. One job: download a single file as fast as the bonded
 * networks allow.
 *
 * - Probes the URL with `Range: bytes=0-0`. If the server honors ranges, the
 *   file is split into fixed chunks downloaded in parallel across every bonded
 *   network (3 workers per network), each worker writing its chunk at the
 *   correct file offset.
 * - Slow-chunk racing (borrowed from Plexo): when the shared queue drains,
 *   idle workers re-download in-flight chunks; the first finisher wins and the
 *   loser's bytes are discarded.
 * - If the server ignores ranges (or length is unknown), falls back to a
 *   single-stream download on the first network.
 * - A chunk that fails is requeued up to [MAX_CHUNK_RETRIES] times; then the
 *   whole download fails fast with a clear error.
 */
class DownloadEngine(
    private val networks: List<BondedNetwork>,
    private val destDir: File,
) {
    data class Chunk(val index: Int, val start: Long, val endInclusive: Long) {
        val size: Long get() = endInclusive - start + 1
    }

    private data class Probe(val totalBytes: Long, val rangesSupported: Boolean)

    private val perNetBytes = ConcurrentHashMap<String, AtomicLong>()
    private val perNetNames = networks.associate { it.id to it.displayName }
    private val totalDownloaded = AtomicLong(0)

    suspend fun download(url: String) = coroutineScope {
        DownloadState.update {
            it.copy(
                status = "Starting…",
                fileName = "",
                downloadedBytes = 0,
                totalBytes = -1,
                speeds = emptyList(),
                error = null,
            )
        }
        val fileName = url.substringAfterLast('/').substringBefore('?')
            .ifBlank { "speedbond-download.bin" }
        val dest = File(destDir, fileName)
        totalDownloaded.set(0)

        val ticker = launchSpeedTicker()

        try {
            val probe = withContext(Dispatchers.IO) { probe(networks.first(), url) }
            if (probe == null || !probe.rangesSupported || probe.totalBytes <= 0) {
                singleStream(url, dest)
            } else {
                multiStream(url, dest, fileName, probe.totalBytes)
            }
        } finally {
            ticker.cancel()
        }
    }

    // ------------------------------------------------------------------ probe

    private fun probe(net: BondedNetwork, url: String): Probe? {
        val req = Request.Builder().url(url).header("Range", "bytes=0-0").build()
        return try {
            net.client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    206 -> {
                        // "bytes 0-0/12345678"
                        val total = resp.header("Content-Range")
                            ?.substringAfter('/', "")
                            ?.toLongOrNull() ?: -1
                        Probe(total, total > 0)
                    }
                    200 -> Probe(resp.header("Content-Length")?.toLongOrNull() ?: -1, false)
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ multi-stream

    private suspend fun CoroutineScope.multiStream(
        url: String,
        dest: File,
        fileName: String,
        total: Long,
    ) {
        DownloadState.update { it.copy(fileName = fileName, totalBytes = total, status = "Downloading") }

        val chunks = buildChunks(total)
        withContext(Dispatchers.IO) {
            RandomAccessFile(dest, "rw").use { it.setLength(total) }
        }

        val queue = Channel<Chunk>(Channel.UNLIMITED)
        chunks.forEach { queue.send(it) }

        val done = ConcurrentHashMap.newKeySet<Int>()
        val inFlight = ConcurrentHashMap<Int, Chunk>()
        val raced = ConcurrentHashMap.newKeySet<Int>()
        val retries = ConcurrentHashMap<Int, Int>()

        try {
            networks.flatMap { net ->
                List(WORKERS_PER_NETWORK) {
                    async(Dispatchers.IO) {
                        worker(net, url, dest, queue, chunks.size, done, inFlight, raced, retries)
                    }
                }
            }.awaitAll()
        } finally {
            queue.close()
        }

        if (done.size != chunks.size) {
            throw IllegalStateException("incomplete download (${done.size}/${chunks.size} chunks)")
        }
        DownloadState.update { it.copy(status = "Done", downloadedBytes = total) }
    }

    private suspend fun CoroutineScope.worker(
        net: BondedNetwork,
        url: String,
        dest: File,
        queue: Channel<Chunk>,
        totalChunks: Int,
        done: MutableSet<Int>,
        inFlight: ConcurrentHashMap<Int, Chunk>,
        raced: MutableSet<Int>,
        retries: ConcurrentHashMap<Int, Int>,
    ) {
        // One RandomAccessFile per worker: each has its own file pointer, so
        // concurrent writes to non-overlapping regions are safe.
        RandomAccessFile(dest, "rw").use { raf ->
            while (coroutineContext.isActive) {
                val fromQueue = queue.tryReceive().getOrNull()
                val chunk = fromQueue ?: steal(inFlight, done, raced)
                if (chunk == null) {
                    if (done.size >= totalChunks) return
                    delay(150)
                    continue
                }
                if (fromQueue != null) inFlight[chunk.index] = chunk
                try {
                    val bytes = fetchChunk(net, url, chunk)
                    if (done.add(chunk.index)) {
                        // First finisher wins; a racing loser's bytes are dropped.
                        raf.seek(chunk.start)
                        raf.write(bytes)
                        totalDownloaded.addAndGet(bytes.size.toLong())
                    }
                    inFlight.remove(chunk.index)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    inFlight.remove(chunk.index)
                    val n = (retries[chunk.index] ?: 0) + 1
                    if (n > MAX_CHUNK_RETRIES) {
                        throw IllegalStateException("chunk ${chunk.index} failed: ${e.message}")
                    }
                    retries[chunk.index] = n
                    queue.send(chunk)
                }
            }
        }
    }

    /** Pick an in-flight chunk nobody has finished or raced yet. */
    private fun steal(
        inFlight: ConcurrentHashMap<Int, Chunk>,
        done: Set<Int>,
        raced: MutableSet<Int>,
    ): Chunk? {
        val entry = inFlight.entries.firstOrNull { (idx, _) -> idx !in done && raced.add(idx) }
        return entry?.value
    }

    private suspend fun fetchChunk(net: BondedNetwork, url: String, chunk: Chunk): ByteArray =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("Range", "bytes=${chunk.start}-${chunk.endInclusive}")
                .build()
            net.client.newCall(req).execute().use { resp ->
                if (resp.code != 206) throw IllegalStateException("server rejected range (${resp.code})")
                val bytes = resp.body!!.bytes()
                perNetBytes.getOrPut(net.id) { AtomicLong() }.addAndGet(bytes.size.toLong())
                bytes
            }
        }

    private fun buildChunks(total: Long): List<Chunk> {
        val list = ArrayList<Chunk>()
        var offset = 0L
        var index = 0
        while (offset < total) {
            val end = minOf(offset + CHUNK_SIZE - 1, total - 1)
            list.add(Chunk(index++, offset, end))
            offset = end + 1
        }
        return list
    }

    // ---------------------------------------------------------- single stream

    private suspend fun singleStream(url: String, dest: File) {
        val net = networks.first()
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).build()
            net.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val total = resp.header("Content-Length")?.toLongOrNull() ?: -1
                DownloadState.update {
                    it.copy(
                        fileName = dest.name,
                        totalBytes = total,
                        status = "Downloading (single connection)",
                    )
                }
                val counter = perNetBytes.getOrPut(net.id) { AtomicLong() }
                resp.body!!.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            counter.addAndGet(n.toLong())
                            totalDownloaded.addAndGet(n.toLong())
                        }
                    }
                }
                DownloadState.update { it.copy(status = "Done", downloadedBytes = totalDownloaded.get()) }
            }
        }
    }

    // ---------------------------------------------------------------- ticker

    private fun CoroutineScope.launchSpeedTicker() = launch {
        val last = mutableMapOf<String, Long>()
        while (isActive) {
            delay(500)
            val speeds = perNetBytes.map { (id, counter) ->
                val now = counter.get()
                val bps = (now - (last[id] ?: 0L)) * 2 // per 500ms window
                last[id] = now
                NetworkSpeed(id, perNetNames[id] ?: id, bps)
            }
            DownloadState.update {
                it.copy(speeds = speeds, downloadedBytes = totalDownloaded.get())
            }
        }
    }

    companion object {
        const val CHUNK_SIZE: Long = 4L * 1024 * 1024
        const val WORKERS_PER_NETWORK = 3
        const val MAX_CHUNK_RETRIES = 5
    }
}
