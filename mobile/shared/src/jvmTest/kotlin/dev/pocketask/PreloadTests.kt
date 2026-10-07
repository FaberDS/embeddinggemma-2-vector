package dev.pocketask

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import java.nio.file.Files
import kotlin.test.*

private class WarmRuntime : LocalRuntime {
    val loads = mutableListOf<Pair<Boolean, String>>()
    var engine: Boolean? = null
    var holdLoad = false
    var failNextLoad = false
    var pendingLoad: Completion? = null
    private var pendingRelease: Completion? = null
    var answers = 0
    override fun load(search: Boolean, path: String, callback: Completion) {
        assertNull(engine, "Only one model should be resident")
        loads += search to path
        if (failNextLoad) { failNextLoad = false; callback.failure("Initialization failed") }
        else if (holdLoad) pendingLoad = callback
        else { engine = search; callback.success() }
    }
    fun finishLoad() {
        holdLoad = false
        val callback = pendingLoad!!; pendingLoad = null
        engine = loads.last().first
        callback.success()
        pendingRelease?.let { pendingRelease = null; engine = null; it.success() }
    }
    override fun embed(text: String, imagePath: String?, query: Boolean, callback: VectorResult) {
        assertEquals(true, engine)
        callback.success(List(256) { if (it == 0) 1f else 0f })
    }
    override fun answer(instructions: String, prompt: String, images: List<String>, callback: StreamResult) {
        assertEquals(false, engine)
        answers++; callback.token("A reply [S1]."); callback.success()
    }
    override fun release(callback: Completion) {
        if (pendingLoad != null) pendingRelease = callback
        else { engine = null; callback.success() }
    }
    override fun cancel() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class PreloadTests {
    @Test fun foregroundWarmsAnswerOnceAndQuestionsReuseItUntilBackground() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("warm-answer").toFile()
        val runtime = WarmRuntime()
        val controller = AppController(root.path, chatStore(root.path), runtime, ChatInputs(), worker = dispatcher, initiallyActive = false)
        try {
            controller.models.state(modelSpecs[1], ModelState(installed = true)); advanceUntilIdle()
            assertTrue(runtime.loads.isEmpty())
            controller.foreground(); advanceUntilIdle()
            assertEquals(listOf(false), runtime.loads.map { it.first })
            assertEquals("Ready", controller.models.states.value.getValue("answer").stage)
            assertNull(controller.state.value.stage)
            controller.foreground(); advanceUntilIdle()
            controller.telemetry(true)
            repeat(2) {
                controller.question("Question $it"); controller.ask(); advanceUntilIdle()
                assertEquals("Completed", controller.state.value.result!!.status)
                assertTrue(controller.state.value.result!!.timings.any { it.status == "Using preloaded answer model" })
            }
            assertEquals(1, runtime.loads.size)
            controller.background(); advanceUntilIdle()
            assertNull(runtime.engine)
            assertEquals("Installed", controller.models.states.value.getValue("answer").stage)
            controller.foreground(); advanceUntilIdle()
            assertEquals(2, runtime.loads.size)
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun documentChatsWarmSearchFirstAndNeverKeepTwoEnginesResident() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("warm-search").toFile()
        val store = chatStore(root.path)
        val pdf = Attachment("pdf", "Report.pdf", "/missing.pdf", "application/pdf")
        store.saveSource(pdf); store.markPrepared(pdf)
        store.addEvidence(Evidence("passage", pdf.id, pdf.name, 1, "Core Web Vitals", null, List(256) { if (it == 0) 1f else 0f }))
        val runtime = WarmRuntime()
        val controller = AppController(root.path, store, runtime, ChatInputs(), worker = dispatcher)
        try {
            modelSpecs.take(2).forEach { controller.models.state(it, ModelState(installed = true)) }; advanceUntilIdle()
            assertEquals(listOf(true), runtime.loads.map { it.first })
            controller.telemetry(true); controller.question("Core Web Vitals"); controller.ask(); advanceUntilIdle()
            assertEquals("Completed", controller.state.value.result!!.status)
            assertTrue(controller.state.value.result!!.timings.any { it.status == "Using preloaded search model" })
            assertEquals(listOf(true, false, true), runtime.loads.map { it.first })
            assertEquals(true, runtime.engine) // Search is ready for the next document question.
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun sendingDuringPreloadWaitsForTheExistingLoadInsteadOfStartingAnother() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("warm-in-flight").toFile()
        val runtime = WarmRuntime().apply { holdLoad = true }
        val controller = AppController(root.path, chatStore(root.path), runtime, ChatInputs(), worker = dispatcher)
        try {
            controller.models.state(modelSpecs[1], ModelState(installed = true)); runCurrent()
            assertNotNull(runtime.pendingLoad); assertNull(controller.state.value.stage)
            controller.telemetry(true); controller.question("Hello"); controller.ask(); runCurrent()
            assertEquals("Waiting for model preload", controller.state.value.stage)
            assertEquals(1, runtime.loads.size); assertEquals(0, runtime.answers)
            runtime.finishLoad(); advanceUntilIdle()
            assertEquals("Completed", controller.state.value.result!!.status)
            assertEquals(1, runtime.loads.size)
            assertTrue(controller.state.value.result!!.timings.any { it.status == "Waiting for model preload" })
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun backgroundDuringPreloadCannotMakeACancelledModelReadyOnResume() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("warm-background").toFile()
        val runtime = WarmRuntime().apply { holdLoad = true }
        val controller = AppController(root.path, chatStore(root.path), runtime, ChatInputs(), worker = dispatcher)
        try {
            controller.models.state(modelSpecs[1], ModelState(installed = true)); runCurrent()
            controller.background(); runCurrent()
            controller.foreground(); runCurrent()
            assertEquals(1, runtime.loads.size)
            runtime.finishLoad(); advanceUntilIdle()
            assertEquals(2, runtime.loads.size)
            assertEquals("Ready", controller.models.states.value.getValue("answer").stage)
            controller.question("Resume"); controller.ask(); advanceUntilIdle()
            assertEquals("Completed", controller.state.value.result!!.status)
            assertEquals(2, runtime.loads.size)
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun aFailedPreloadRetriesOnSendAndASelectionChangeWarmsTheNewModel() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("warm-retry").toFile()
        val runtime = WarmRuntime().apply { failNextLoad = true }
        val controller = AppController(root.path, chatStore(root.path), runtime, ChatInputs(), worker = dispatcher)
        try {
            controller.models.state(modelSpecs[1], ModelState(installed = true)); advanceUntilIdle()
            assertEquals(1, runtime.loads.size); assertNull(runtime.engine)
            assertNotNull(controller.models.states.value.getValue("answer").error)
            controller.question("Retry"); controller.ask(); advanceUntilIdle()
            assertEquals("Completed", controller.state.value.result!!.status)
            assertEquals(2, runtime.loads.size)
            assertNull(controller.models.states.value.getValue("answer").error)
            controller.models.state(modelSpecs[2], ModelState(installed = true))
            controller.selectAnswerModel("answer-e4b"); advanceUntilIdle()
            assertEquals(3, runtime.loads.size)
            assertTrue(runtime.loads.last().second.endsWith(modelSpecs[2].filename))
            controller.question("Larger model"); controller.ask(); advanceUntilIdle()
            assertEquals("answer-e4b", controller.state.value.result!!.modelId)
            assertEquals(3, runtime.loads.size)
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
}
