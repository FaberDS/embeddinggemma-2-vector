package dev.pocketask

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import java.nio.file.Files
import kotlin.test.*

private class TimingRuntime : LocalRuntime {
    lateinit var stream: StreamResult
    var search = false
    var detailCalls = 0
    var failLoad = false
    var failRelease = false
    override fun load(search: Boolean, path: String, callback: Completion) {
        this.search = search
        if (failLoad) callback.failure("Load failed") else callback.success()
    }
    override fun embed(text: String, imagePath: String?, query: Boolean, callback: VectorResult) = callback.success(List(256) { if (it == 0) 1f else 0f })
    override fun answer(instructions: String, prompt: String, images: List<String>, callback: StreamResult) { stream = callback }
    override fun inferenceDetails(): String { detailCalls++; return if (search) "CPU · 4 threads" else "GPU · 8192-token context" }
    override fun release(callback: Completion) { if (failRelease) callback.failure("Release failed") else callback.success() }
    override fun cancel() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryTests {
    @Test fun enabledRequestsShowSeparatePhasesAndPersistOnlyOneFirstTextEvent() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("request-timing").toFile()
        val store = chatStore(root.path)
        val source = Attachment("pdf", "Source.pdf", "/missing.pdf", "application/pdf")
        store.saveSource(source); store.markPrepared(source)
        store.addEvidence(Evidence("passage", source.id, source.name, 1, "Google Core Web Vitals", null, List(256) { if (it == 0) 1f else 0f }))
        val runtime = TimingRuntime()
        val controller = AppController(root.path, store, runtime, ChatInputs(), worker = dispatcher)
        var restored: AppController? = null
        try {
            assertFalse(controller.state.value.telemetryEnabled)
            controller.telemetry(true)
            modelSpecs.take(2).forEach { controller.models.state(it, ModelState(installed = true)) }
            controller.question("Google Core Web Vitals"); controller.ask(); runCurrent()
            val pending = controller.state.value.result!!
            assertEquals("Waiting for first text", pending.timings.last().status)
            assertContains(pending.timings.first { it.status == "Embedding question" }.detail, "CPU")
            assertContains(pending.timings.last().detail, "GPU")
            assertContains(pending.timings.first { it.status == "Sources selected" }.detail, "1 supplied passages")
            runtime.stream.token(" "); runCurrent()
            assertTrue(controller.state.value.result!!.timings.none { it.status == "First text displayed" })
            runtime.stream.token("A"); runCurrent()
            assertEquals("First text displayed", controller.state.value.result!!.timings.last().status)
            runtime.stream.token(" reply."); runtime.stream.success(); advanceUntilIdle()
            val answer = controller.state.value.result!!
            assertEquals("Completed", answer.timings.last().status)
            assertEquals(1, answer.timings.count { it.status == "First text displayed" })
            assertTrue(answer.timings.zipWithNext().all { (a, b) -> a.elapsedMs <= b.elapsedMs })
            assertEquals(answer.timings, store.history().single().timings)
            controller.close(); advanceUntilIdle()
            restored = AppController(root.path, store, runtime, ChatInputs(), worker = dispatcher)
            assertTrue(restored.state.value.telemetryEnabled)
            assertEquals(answer.timings, restored.state.value.history.single().timings)
        } finally { restored?.close(); controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun offRequestsDoNotCollectDetailsAndFailuresAndStopsFinishTheirTrace() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("request-timing-off").toFile()
        val runtime = TimingRuntime()
        val store = chatStore(root.path)
        val controller = AppController(root.path, store, runtime, ChatInputs(), worker = dispatcher)
        try {
            controller.models.state(modelSpecs[1], ModelState(installed = true))
            controller.question("Off"); controller.ask(); runCurrent()
            runtime.stream.token("Reply"); runtime.stream.success(); advanceUntilIdle()
            assertTrue(controller.state.value.result!!.timings.isEmpty())
            assertEquals(0, runtime.detailCalls)
            controller.telemetry(true); runtime.failLoad = true
            controller.background(); advanceUntilIdle(); controller.foreground()
            controller.question("Fail"); controller.ask(); advanceUntilIdle()
            assertEquals("Failed", controller.state.value.result!!.timings.last().status)
            assertNull(controller.state.value.stage)
            runtime.failLoad = false
            controller.question("Stop"); controller.ask(); runCurrent()
            controller.stop(); advanceUntilIdle()
            assertEquals("Stopped", controller.state.value.result!!.timings.last().status)
            assertNull(controller.state.value.stage)
            runtime.failRelease = true
            controller.question("Release fails"); controller.ask(); runCurrent()
            runtime.stream.failure("Generation failed"); advanceUntilIdle()
            assertEquals("Failed", controller.state.value.result!!.timings.last().status)
            assertEquals("Generation failed", controller.state.value.result!!.error)
            assertTrue(controller.state.value.result!!.timings.any { it.status == "Model release failed" })
            assertNull(controller.state.value.stage)
            runtime.failRelease = false
            controller.telemetry(false)
            assertEquals("no", store.value("request-telemetry"))
            controller.question("Off again"); controller.ask(); runCurrent()
            runtime.stream.success(); advanceUntilIdle()
            assertTrue(controller.state.value.result!!.timings.isEmpty())
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
}
