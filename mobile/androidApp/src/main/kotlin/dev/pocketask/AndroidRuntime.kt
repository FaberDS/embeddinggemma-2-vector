package dev.pocketask

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

class AndroidRuntime(context: Context) : LocalRuntime {
    private val cache = context.cacheDir.absolutePath
    private val profile = context.getSystemService(ActivityManager::class.java).let { manager ->
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        androidInferenceProfile(memory.totalMem, manager.isLowRamDevice, Build.MODEL)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var embedding: EmbeddingEngine? = null
    private var generation: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile private var details = "No model loaded"
    private val cancelled = AtomicBoolean(false)

    private fun operation(callback: Completion, block: suspend () -> Unit) {
        scope.launch {
            try { lock.withLock { block() }; callback.success() }
            catch (e: Throwable) { callback.failure(e.message ?: "Local runtime error.") }
        }
    }
    override fun load(search: Boolean, path: String, callback: Completion) = operation(callback) {
        closeEngines(); cancelled.set(false)
        Log.i("PocketAskRuntime", "Loading ${if (search) "embedding" else "answer"} engine; backend=${if (search || profile.cpu) "CPU" else "GPU"}")
        if (search) {
            embedding = EmbeddingEngine(EmbeddingEngineConfig(modelPath = path, backend = Backend.CPU(4), visionBackend = Backend.CPU(4), cacheDir = cache))
            embedding!!.initialize()
        } else {
            generation = Engine(EngineConfig(modelPath = path, backend = if (profile.cpu) Backend.CPU(4) else Backend.GPU(), visionBackend = Backend.CPU(4), maxNumTokens = profile.contextTokens, maxNumImages = 1, cacheDir = cache))
            generation!!.initialize()
        }
        check(!cancelled.get()) { "Loading stopped." }
        details = if (search) "CPU · 4 threads · 256-value embeddings" else "${if (profile.cpu) "CPU · 4 threads" else "GPU"} · ${profile.contextTokens}-token context · 512-token output limit"
        Log.i("PocketAskRuntime", "Engine ready")
    }
    override fun embed(text: String, imagePath: String?, query: Boolean, callback: VectorResult) {
        scope.launch {
            try {
                val values = lock.withLock {
                    check(!cancelled.get()) { "Preparation stopped." }
                    val input = if (imagePath == null) InputData.Text(if (query) "task: search result | query: $text" else "title: none | text: $text") else InputData.Image(java.io.File(imagePath).readBytes())
                    checkNotNull(embedding).computeEmbedding(listOf(input), EmbeddingOptions(normalize = true, outputSize = 256)).embedding.toList()
                }
                callback.success(values)
                Log.i("PocketAskRuntime", "Embedding complete; image=${imagePath != null}")
            } catch (e: Throwable) { callback.failure(e.message ?: "Embedding failed.") }
        }
    }
    override fun answer(instructions: String, prompt: String, images: List<String>, callback: StreamResult) {
        scope.launch {
            try {
                lock.withLock {
                    check(!cancelled.get()) { "Answer stopped." }
                    val current = checkNotNull(generation).createConversation(ConversationConfig(systemInstruction = Contents.of(instructions), samplerConfig = SamplerConfig(40, 0.9, 0.2), maxOutputToken = 512, thinkingConfig = ThinkingConfig(enableThinking = false)))
                    conversation = current
                    try {
                        val contents = mutableListOf<Content>(Content.Text(prompt))
                        images.forEach { contents += Content.ImageFile(it) }
                        current.sendMessageAsync(Contents.of(contents)).collect { message ->
                            if (!cancelled.get()) callback.token(message.toString())
                        }
                        callback.success()
                        Log.i("PocketAskRuntime", "Generation complete; images=${images.size}")
                    } finally { conversation = null; current.close() }
                }
            } catch (e: Throwable) { callback.failure(e.message ?: "Answer generation failed.") }
        }
    }
    override fun cancel() { cancelled.set(true); runCatching { conversation?.cancelProcess() } }
    override fun inferenceDetails() = details
    override fun release(callback: Completion) = operation(callback) { closeEngines() }
    private fun closeEngines() {
        embedding?.let { if (it.isInitialized()) it.close() }; embedding = null
        generation?.let { if (it.isInitialized()) it.close() }; generation = null
        details = "No model loaded"
        Log.i("PocketAskRuntime", "Engines released")
    }
}
