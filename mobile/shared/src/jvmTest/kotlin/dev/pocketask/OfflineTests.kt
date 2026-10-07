package dev.pocketask

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.pocketask.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import java.nio.file.Files
import kotlin.test.*

private fun store(): Store {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    AppDatabase.Schema.create(driver)
    return Store(driver)
}
private fun vector(index: Int) = List(256) { if (it == index) 1f else 0f }
private fun source(id: String, owner: String, index: Int = 0, image: String? = null) = Evidence(id, owner, "$owner.pdf", 1, "Evidence $id", image, vector(index))

class RetrievalTests {
    @Test fun selectedAttachmentsRemainSearchableBeyondTheFirstSqlitePage() {
        val db = store()
        repeat(150) { db.addEvidence(source("a-$it", "unselected")) }
        repeat(70) { db.addEvidence(source("b-${it.toString().padStart(3, '0')}", "selected", if (it == 69) 0 else 1)) }
        val result = retrieve(db, setOf("selected"), vector(0), limit = 1)
        assertEquals("b-069", result.single().id)
        assertTrue(retrieve(db, emptySet(), vector(0)).isEmpty())
    }
    @Test fun unrelatedVectorsDoNotForceFilesIntoAnAnswer() {
        val db = store()
        db.addEvidence(source("unrelated-image", "photo", index = 1, image = "/unopened/photo.jpg"))
        db.addEvidence(source("opposite-document", "pdf").copy(vector = vector(0).map { -it }))
        assertTrue(retrieve(db, setOf("photo", "pdf"), vector(0)).isEmpty())
    }
    @Test fun historyRestoresInterruptedRequestsAndKeepsSharedAssets() {
        val db = store()
        val directory = Files.createTempDirectory("pocketask-test").toFile()
        val attachment = Attachment("shared", "shared.txt", "${directory.path}/inputs/shared/original.txt", "text/plain")
        java.io.File(attachment.path).apply { parentFile.mkdirs(); writeText("Saved evidence") }
        db.save(Answer("1", "First", listOf(attachment), status = "Answering", text = "Partial"))
        assertEquals("Interrupted", db.history().single().status)
        db.clean(listOf(attachment), setOf(attachment.id), directory.path)
        assertTrue(java.io.File(attachment.path).exists())
        db.clean(listOf(attachment), emptySet(), directory.path)
        assertFalse(java.io.File(attachment.path).exists())
        directory.deleteRecursively()
    }
}

private class FakeInputs : PlatformInputs {
    var time = 100L
    override fun pick(images: Boolean, callback: ImportResult) = callback.success()
    override fun pageCount(attachment: Attachment, callback: CountResult) = callback.success(1)
    override fun readPage(attachment: Attachment, page: Int, callback: PageResult) = callback.success(PageInput("Document text", null))
    override fun open(path: String, page: Int) {}
    override fun freeBytes() = Long.MAX_VALUE
    override fun canDownload(cellular: Boolean) = true
    override fun now() = time++
}
private class FakeRuntime : LocalRuntime {
    val loads = mutableListOf<Boolean>()
    val paths = mutableListOf<String>()
    var stream: StreamResult? = null
    var finishImmediately = true
    var cancels = 0
    override fun load(search: Boolean, path: String, callback: Completion) { loads += search; paths += path; callback.success() }
    override fun embed(text: String, imagePath: String?, query: Boolean, callback: VectorResult) = callback.success(vector(0))
    override fun answer(instructions: String, prompt: String, images: List<String>, callback: StreamResult) {
        stream = callback; callback.token("A compact answer.")
        if (finishImmediately) callback.success()
    }
    override fun release(callback: Completion) = callback.success()
    override fun cancel() { cancels++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RequestTests {
    @Test fun generalQuestionsNeedOnlyTheAnswerModelAndPersistHistory() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val runtime = FakeRuntime()
        val db = store()
        val root = Files.createTempDirectory("pocketask-test").toFile()
        val controller = AppController(root.path, db, runtime, FakeInputs())
        try {
            controller.models.state(modelSpecs[1], ModelState(installed = true))
            controller.question("Explain embeddings")
            controller.ask(); advanceUntilIdle()
            assertEquals(listOf(false), runtime.loads)
            assertEquals("Completed", controller.state.value.result?.status)
            assertEquals("A compact answer.", db.history().single().text)
            assertNull(controller.state.value.stage)
        } finally { controller.close(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
    @Test fun selectedAnswerModelIsPersistedAndUsedForInference() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val runtime = FakeRuntime()
        val db = store()
        val root = Files.createTempDirectory("pocketask-selection").toFile()
        val controller = AppController(root.path, db, runtime, FakeInputs())
        try {
            val larger = modelSpecs.first { it.id == "answer-e4b" }
            controller.selectAnswerModel(larger.id)
            assertEquals(larger.id, db.value("answer-model"))
            assertEquals(listOf("search", larger.id), controller.selectedModels().map { it.id })
            controller.models.state(larger, ModelState(installed = true))
            controller.question("Explain embeddings"); controller.ask(); advanceUntilIdle()
            assertEquals(listOf(controller.models.path(larger)), runtime.paths)
            assertEquals("Completed", controller.state.value.result?.status)
            val restored = AppController(root.path, db, FakeRuntime(), FakeInputs())
            assertEquals(larger.id, restored.state.value.answerModel)
            restored.close()
        } finally { controller.close(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun cancellationSavesPartialAnswerAndLateTokensCannotReplaceTheNextResult() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val runtime = FakeRuntime().apply { finishImmediately = false }
        val root = Files.createTempDirectory("pocketask-test").toFile()
        val controller = AppController(root.path, store(), runtime, FakeInputs())
        try {
            controller.models.state(modelSpecs[1], ModelState(installed = true))
            controller.question("First question"); controller.ask(); runCurrent()
            val oldStream = runtime.stream!!
            controller.stop(); advanceUntilIdle()
            assertEquals("Stopped", controller.state.value.result?.status)
            assertEquals("A compact answer.", controller.state.value.result?.text)
            runtime.finishImmediately = true
            controller.question("Second question"); controller.ask(); advanceUntilIdle()
            oldStream.token("Stale token"); oldStream.success(); advanceUntilIdle()
            assertEquals("Second question", controller.state.value.result?.question)
            assertEquals("A compact answer.", controller.state.value.result?.text)
            assertEquals(1, runtime.cancels)
        } finally { controller.close(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
}
