package dev.pocketask

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.HashingSource
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

class ModelManager(private val root: String, private val store: Store, private val inputs: PlatformInputs, private val transfers: ModelTransfers? = null, private val catalog: List<ModelSpec> = modelSpecs, private val createClient: () -> HttpClient = { HttpClient() }) {
    private val fs = FileSystem.SYSTEM
    private val mutable = MutableStateFlow(catalog.associate { spec ->
        val installed = fs.exists(path(spec).toPath()) && fs.metadata(path(spec).toPath()).size == spec.bytes && store.value("model", spec.id) == spec.sha256
        spec.id to ModelState(installed, if (installed) "Installed" else "Not installed")
    })
    val states = mutable.asStateFlow()
    fun path(spec: ModelSpec) = "$root/models/${spec.filename}"
    fun state(spec: ModelSpec, state: ModelState) = mutable.update { it + (spec.id to state) }
    fun runtime(spec: ModelSpec, label: String) {
        state(spec, states.value.getValue(spec.id).copy(stage = label))
    }
    suspend fun download(spec: ModelSpec, cellular: Boolean) = withContext(Dispatchers.Default) {
        require(inputs.canDownload(cellular)) { "Connect to Wi-Fi or allow cellular downloads." }
        val directory = "$root/models".toPath()
        fs.createDirectories(directory)
        val part = (path(spec) + ".part").toPath()
        fs.createDirectories(part.parent!!)
        val existing = fs.metadataOrNull(part)?.size ?: 0
        require(inputs.freeBytes() > spec.bytes - existing + 256_000_000) { "Not enough storage. Free at least ${formatBytes(spec.bytes - existing + 256_000_000)} and retry." }
        state(spec, ModelState(stage = "Downloading", downloaded = existing, busy = true))
        val speed = DownloadEstimate(inputs.now(), existing)
        try {
            if (existing != spec.bytes) {
                val client = createClient()
                try {
                client.prepareGet(spec.url) { if (existing > 0) header(HttpHeaders.Range, "bytes=$existing-") }.execute { response ->
                    require(response.status.value == 200 || response.status.value == 206) { "Download failed (${response.status.value}). Retry when connected." }
                    val append = response.status.value == 206 && existing > 0
                    if (append) require(response.headers[HttpHeaders.ContentRange]?.startsWith("bytes $existing-") == true) { "Invalid resumed download. Remove the partial model and retry." }
                    var downloaded = if (append) existing else 0
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(65536)
                    val output = if (append) fs.appendingSink(part) else fs.sink(part)
                    output.buffer().use { sink ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            require(inputs.canDownload(cellular)) { "The permitted network is unavailable. Reconnect and retry to resume." }
                            val count = channel.readAvailable(buffer, 0, buffer.size)
                            if (count < 0) break
                            if (count == 0) continue
                            downloaded += count
                            require(downloaded <= spec.bytes) { "Unexpected model size." }
                            sink.write(buffer, 0, count)
                            state(spec, speed.state(spec.bytes, downloaded, inputs.now()))
                        }
                    }
                }
                } finally { client.close() }
            }
            verify(spec, part.toString())
        } catch (e: Exception) {
            state(spec, ModelState(stage = "Not installed", downloaded = fs.metadataOrNull(part)?.size ?: 0, error = if (e is kotlinx.coroutines.CancellationException) null else e.message))
            throw e
        }
    }
    val supportsBackground get() = transfers != null
    private val estimates = mutableMapOf<String, DownloadEstimate>()
    private val transferMutex = Mutex()

    suspend fun startDownloads(specs: List<ModelSpec>, cellular: Boolean) = withContext(Dispatchers.Default) {
        transferMutex.withLock {
            val native = checkNotNull(transfers)
            // Reserve space for queued models and any cross-directory verification copy.
            val pending = specs.filter { !states.value.getValue(it.id).installed }
            val required = pending.sumOf { it.bytes } + (pending.maxOfOrNull { native.copyReserve(it) } ?: 0) + 256_000_000
            require(inputs.freeBytes() > required) { "Free at least ${formatBytes(required)} for download and verification." }
            pending.forEach { spec ->
                native.start(spec, cellular)
                state(spec, ModelState(stage = "Queued", busy = true))
                estimates.remove(spec.id)
            }
        }
    }

    suspend fun observeTransfers() {
        if (transfers == null) return
        while (true) { refreshTransfers(); delay(1000) }
    }

    suspend fun refreshTransfers() = withContext(Dispatchers.Default) {
        val native = transfers ?: return@withContext
        transferMutex.withLock {
            catalog.forEach { spec ->
                if (!states.value.getValue(spec.id).installed) {
                    val transfer = native.snapshot(spec)
                    val partial = (path(spec) + ".part").toPath()
                    val completePartial = fs.metadataOrNull(partial)?.size == spec.bytes
                    when {
                        transfer.path != null || completePartial -> {
                            try {
                                val part = (path(spec) + ".part").toPath()
                                fs.createDirectories(part.parent!!)
                                // Android downloads into app-owned external storage; iOS can rename.
                                state(spec, ModelState(stage = "Preparing download", downloaded = spec.bytes, busy = true))
                                if (!fs.exists(part) || fs.metadata(part).size != spec.bytes) {
                                    val source = checkNotNull(transfer.path).toPath()
                                    if (source.parent == part.parent) fs.atomicMove(source, part)
                                    else { fs.copy(source, part); fs.delete(source) }
                                }
                                verify(spec, part.toString())
                                native.cancel(spec) // remove completed OS task bookkeeping
                                estimates.remove(spec.id)
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) {
                                native.cancel(spec)
                                state(spec, ModelState(error = e.message ?: "Model verification failed."))
                            }
                        }
                        transfer.active -> {
                            val estimate = estimates.getOrPut(spec.id) { DownloadEstimate(inputs.now(), transfer.downloaded) }
                            state(spec, estimate.state(spec.bytes, transfer.downloaded, inputs.now()).copy(stage = transfer.stage,
                            remainingSeconds = if (transfer.stage == "Downloading") estimate.remaining else null))
                        }
                        transfer.stage != "Idle" || states.value.getValue(spec.id).busy -> {
                            estimates.remove(spec.id)
                            state(spec, ModelState(stage = if (transfer.stage == "Idle") "Not installed" else transfer.stage, downloaded = transfer.downloaded, error = transfer.error ?: states.value.getValue(spec.id).error))
                        }
                    }
                }
            }
        }
    }

    private suspend fun verify(spec: ModelSpec, partial: String) {
        val part = partial.toPath()
        require(fs.metadata(part).size == spec.bytes) { "Incomplete download. Retry to resume." }
        state(spec, ModelState(stage = "Checking download", downloaded = spec.bytes, busy = true))
        val hashing = HashingSource.sha256(fs.source(part))
        hashing.buffer().use { source ->
            val buffer = okio.Buffer()
            while (source.read(buffer, 65536) != -1L) { currentCoroutineContext().ensureActive(); buffer.clear() }
        }
        if (hashing.hash.hex() != spec.sha256) { fs.delete(part); error("Download verification failed. Retry to download a fresh copy.") }
        currentCoroutineContext().ensureActive()
        fs.atomicMove(part, path(spec).toPath())
        store.put("model", spec.id, spec.sha256)
        state(spec, ModelState(installed = true, stage = "Installed", downloaded = spec.bytes))
    }

    suspend fun cancelDownloads() = withContext(Dispatchers.Default) {
        transferMutex.withLock {
            catalog.filter { states.value.getValue(it.id).busy }.forEach { spec ->
                transfers?.cancel(spec)
                estimates.remove(spec.id)
                state(spec, ModelState(stage = "Paused"))
            }
        }
    }

    suspend fun remove(spec: ModelSpec) = withContext(Dispatchers.Default) {
        transferMutex.withLock {
            transfers?.cancel(spec)
            listOf(path(spec), path(spec) + ".part").forEach { fs.delete(it.toPath(), mustExist = false) }
            store.put("model", spec.id, "")
            state(spec, ModelState())
        }
    }
}

fun formatBytes(bytes: Long): String = if (bytes >= 1_000_000_000) "${bytes / 100_000_000 / 10.0} GB" else "${bytes / 1_000_000} MB"

/** Recent speed, rather than lifetime average; no ETA while waiting for the network. */
class DownloadEstimate(now: Long, downloaded: Long) {
    private var lastTime = now
    private var lastBytes = downloaded
    private var speed: Double? = null
    var remaining: Long? = null
        private set
    fun state(total: Long, downloaded: Long, now: Long): ModelState {
        val elapsed = now - lastTime
        if (downloaded < lastBytes || elapsed > 5000) { speed = null; lastTime = now; lastBytes = downloaded }
        else if (elapsed >= 1000) {
            val measured = (downloaded - lastBytes).toDouble() * 1000 / elapsed
            speed = if (measured <= 0) null else speed?.let { it * 0.6 + measured * 0.4 } ?: measured
            lastTime = now; lastBytes = downloaded
        }
        remaining = speed?.takeIf { it > 0 }?.let { kotlin.math.ceil((total - downloaded).coerceAtLeast(0) / it).toLong() }
        return ModelState(stage = "Downloading", downloaded = downloaded, busy = true, bytesPerSecond = speed, remainingSeconds = remaining)
    }
}

fun formatRemaining(seconds: Long): String = when {
    seconds < 60 -> "About ${seconds.coerceAtLeast(1)} sec left"
    seconds < 3600 -> "About ${(seconds + 59) / 60} min left"
    else -> "About ${seconds / 3600} hr ${(seconds % 3600 + 59) / 60} min left"
}
