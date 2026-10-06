package dev.pocketask

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
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

class ModelManager(private val root: String, private val store: Store, private val inputs: PlatformInputs, private val createClient: () -> HttpClient = { HttpClient() }) {
    private val fs = FileSystem.SYSTEM
    private val mutable = MutableStateFlow(modelSpecs.associate { spec ->
        val installed = fs.exists(path(spec).toPath()) && fs.metadata(path(spec).toPath()).size == spec.bytes && store.value("model", spec.id) == spec.sha256
        spec.id to ModelState(installed, if (installed) "Installed" else "Not installed")
    })
    val states = mutable.asStateFlow()
    fun path(spec: ModelSpec) = "$root/models/${spec.filename}"
    fun state(spec: ModelSpec, state: ModelState) = mutable.update { it + (spec.id to state) }
    fun runtime(search: Boolean, label: String) {
        val spec = modelSpecs[if (search) 0 else 1]
        state(spec, states.value.getValue(spec.id).copy(stage = label))
    }
    suspend fun download(spec: ModelSpec, cellular: Boolean) = withContext(Dispatchers.Default) {
        require(inputs.canDownload(cellular)) { "Connect to Wi-Fi or allow cellular downloads." }
        val directory = "$root/models".toPath()
        fs.createDirectories(directory)
        val part = (path(spec) + ".part").toPath()
        val existing = fs.metadataOrNull(part)?.size ?: 0
        require(inputs.freeBytes() > spec.bytes - existing + 256_000_000) { "Not enough storage. Free at least ${formatBytes(spec.bytes - existing + 256_000_000)} and retry." }
        state(spec, ModelState(stage = "Downloading", downloaded = existing, busy = true))
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
                            state(spec, ModelState(stage = "Downloading", downloaded = downloaded, busy = true))
                        }
                    }
                }
                } finally { client.close() }
            }
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
        } catch (e: Exception) {
            state(spec, ModelState(stage = "Not installed", downloaded = fs.metadataOrNull(part)?.size ?: 0, error = if (e is kotlinx.coroutines.CancellationException) null else e.message))
            throw e
        }
    }
    fun remove(spec: ModelSpec) {
        listOf(path(spec), path(spec) + ".part").forEach { fs.delete(it.toPath(), mustExist = false) }
        store.put("model", spec.id, "")
        state(spec, ModelState())
    }
}

fun formatBytes(bytes: Long): String = if (bytes >= 1_000_000_000) "${bytes / 100_000_000 / 10.0} GB" else "${bytes / 1_000_000} MB"
