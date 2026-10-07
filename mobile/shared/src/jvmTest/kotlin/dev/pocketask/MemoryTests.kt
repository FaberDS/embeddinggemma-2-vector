package dev.pocketask

import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

internal class MemoryRuntime : LocalRuntime {
    val indexed = mutableListOf<String>()
    val loads = mutableListOf<Boolean>()
    var titles = 0
    var holdTitle = false
    var title: StreamResult? = null
    var titleFails = false
    override fun load(search: Boolean, path: String, callback: Completion) { loads += search; callback.success() }
    override fun embed(text: String, imagePath: String?, query: Boolean, callback: VectorResult) {
        assertNull(imagePath)
        if (!query) indexed += text
        callback.success(List(256) { if (it == 0) 1f else 0f })
    }
    override fun answer(instructions: String, prompt: String, images: List<String>, callback: StreamResult) {
        assertTrue(images.isEmpty())
        if (prompt.startsWith("Recorded memory transcript:")) {
            titles++; title = callback
            if (titleFails) callback.failure("Title model failed")
            else if (!holdTitle) { callback.token("Garden layout ideas"); callback.success() }
        } else { assertContains(prompt, "Plant flowers near the fence"); callback.token("Remember the flower bed [S1]."); callback.success() }
    }
    override fun release(callback: Completion) = callback.success()
    override fun cancel() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryTests {
    @Test fun spokenMemoryGetsALocalTitleAndIndexAndIsAvailableInLaterChatsAndRestarts() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("memory-flow").toFile(); val db = chatStore(root.path)
        val platform = FakeSpeech(); val runtime = MemoryRuntime()
        val controller = AppController(root.path, db, runtime, ChatInputs(), worker = dispatcher, platformSpeech = platform)
        var restored: AppController? = null
        try {
            modelSpecs.take(2).forEach { controller.models.state(it, ModelState(installed = true)) }
            controller.question("A chat draft to keep")
            controller.startMemory(); runCurrent()
            platform.dictation!!.text("Plant flowers", false); runCurrent()
            assertEquals("A chat draft to keep", controller.state.value.draft.question)
            controller.finishMemory(); assertEquals(1, platform.finished)
            val callback = platform.dictation!!
            callback.text("Plant flowers near the fence", true); advanceUntilIdle()
            val memory = controller.state.value.library.single()
            assertTrue(memory.isMemory); assertTrue(memory.prepared); assertNotNull(memory.createdAt)
            assertEquals("Garden layout ideas.md", memory.name)
            assertEquals("# Garden layout ideas\n\nPlant flowers near the fence\n", java.io.File(memory.path).readText())
            assertEquals(1, runtime.titles); assertContains(runtime.indexed.single(), "Plant flowers near the fence")
            assertEquals("Saved", controller.state.value.memory!!.status)
            assertNull(db.memoryDraft()); assertTrue(db.history().isEmpty())
            callback.text("A duplicate late callback", true); advanceUntilIdle()
            assertEquals(1, runtime.titles); assertEquals(1, controller.state.value.library.size)
            controller.closeMemory(); controller.newQuestion(); controller.question("What should I remember about flowers?"); controller.ask(); advanceUntilIdle()
            assertEquals(memory.id, controller.state.value.result!!.sources.single().attachmentId)
            controller.close(); runCurrent()
            val nextRuntime = MemoryRuntime()
            restored = AppController(root.path, db, nextRuntime, ChatInputs(), worker = dispatcher, platformSpeech = platform)
            restored.models.state(modelSpecs[0], ModelState(installed = true)); advanceUntilIdle()
            assertTrue(restored.state.value.library.single().isMemory); assertTrue(nextRuntime.indexed.isEmpty())
        } finally { restored?.close(); controller.close(); runCurrent(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
    @Test fun backgroundPreservesAnUnfinishedTranscriptAndRejectsLateRecognition() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("memory-pause").toFile(); val db = chatStore(root.path); val platform = FakeSpeech()
        val controller = AppController(root.path, db, MemoryRuntime(), ChatInputs(), worker = dispatcher, platformSpeech = platform)
        var restored: AppController? = null
        try {
            controller.startMemory(); runCurrent(); val callback = platform.dictation!!
            callback.text("A thought to keep", false); runCurrent(); controller.background()
            callback.text("Late replacement", true); runCurrent()
            assertEquals("A thought to keep", db.memoryDraft()!!.transcript)
            assertEquals("Ready", controller.state.value.memory!!.status)
            assertTrue(controller.state.value.library.isEmpty())
            controller.close(); runCurrent()
            restored = AppController(root.path, db, MemoryRuntime(), ChatInputs(), worker = dispatcher, platformSpeech = platform)
            assertEquals("A thought to keep", restored.state.value.memory!!.transcript)
            restored.startMemory(); runCurrent()
            platform.dictation!!.text("and continue later", true); advanceUntilIdle()
            assertEquals("A thought to keep and continue later", restored.state.value.memory!!.transcript)
            assertTrue(restored.state.value.library.single().isMemory)
        } finally { restored?.close(); controller.close(); runCurrent(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
    @Test fun cancelledOrEmptyRecordingNeverCreatesAMemory() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("memory-cancel").toFile(); val db = chatStore(root.path); val platform = FakeSpeech()
        val controller = AppController(root.path, db, MemoryRuntime(), ChatInputs(), worker = dispatcher, platformSpeech = platform)
        try {
            controller.startMemory(); runCurrent(); val old = platform.dictation!!
            old.text("Discard this", false); runCurrent(); controller.closeMemory()
            old.text("Late final", true); advanceUntilIdle()
            assertNull(controller.state.value.memory); assertNull(db.memoryDraft()); assertTrue(controller.state.value.library.isEmpty())
            controller.startMemory(); runCurrent(); platform.dictation!!.text("", true); advanceUntilIdle()
            assertTrue(controller.state.value.library.isEmpty()); assertContains(controller.state.value.memory!!.error!!, "No speech")
        } finally { controller.close(); runCurrent(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
    @Test fun cancelledTitleGenerationCanRetryAndLateTokensDoNotCreateExtraMemories() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("memory-title-pause").toFile(); val platform = FakeSpeech()
        val runtime = MemoryRuntime().apply { holdTitle = true }
        val controller = AppController(root.path, chatStore(root.path), runtime, ChatInputs(), worker = dispatcher, platformSpeech = platform)
        try {
            controller.models.state(modelSpecs[1], ModelState(installed = true))
            controller.startMemory(); runCurrent(); platform.dictation!!.text("Plant flowers near the fence", true); runCurrent()
            assertEquals("Generating memory title", controller.state.value.stage); val old = runtime.title!!
            controller.stop(); advanceUntilIdle()
            assertEquals("Ready", controller.state.value.memory!!.status); assertTrue(controller.state.value.library.isEmpty())
            runtime.holdTitle = false; controller.saveMemory(); advanceUntilIdle()
            old.token("Wrong late title"); old.success(); runCurrent()
            assertEquals("Garden layout ideas.md", controller.state.value.library.single().name)
            assertEquals(1, controller.state.value.library.size)
        } finally { controller.close(); runCurrent(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
    @Test fun modelOrPermissionFailuresKeepTheTranscriptAndAllowSavingWithoutModels() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("memory-fallback").toFile(); val platform = FakeSpeech()
        val runtime = MemoryRuntime().apply { titleFails = true }
        val controller = AppController(root.path, chatStore(root.path), runtime, ChatInputs(), worker = dispatcher, platformSpeech = platform)
        try {
            controller.startMemory(); runCurrent(); platform.dictation!!.failure("Microphone denied"); runCurrent()
            assertEquals("Ready", controller.state.value.memory!!.status); assertContains(controller.state.value.memory!!.error!!, "denied")
            controller.memoryText("Plant flowers near the fence")
            controller.models.state(modelSpecs[1], ModelState(installed = true))
            controller.saveMemory(); advanceUntilIdle()
            assertEquals("Plant flowers near the fence.md", controller.state.value.library.single().name)
            assertFalse(controller.state.value.library.single().prepared)
            controller.models.state(modelSpecs[0], ModelState(installed = true)); advanceUntilIdle()
            assertTrue(controller.state.value.library.single().prepared)
            assertContains(runtime.indexed.single(), "Plant flowers near the fence")
        } finally { controller.close(); runCurrent(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
}
