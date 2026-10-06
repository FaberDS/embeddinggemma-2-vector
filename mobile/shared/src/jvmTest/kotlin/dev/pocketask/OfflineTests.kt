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
    @Test fun citationsRejectInventedIdsAndPreserveSourceIdentity() {
        val sources = listOf(source("one", "one"), source("two", "two"))
        val (answer, cited) = validCitations("Second [S2], first [S1], invented [S9].", sources)
        assertEquals("Second [S2], first [S1], invented .", answer)
        assertEquals(listOf("one", "two"), cited.map { it.id })
    }
    @Test fun visualEvidenceIncludesActualPixelsAndExplainsComparisonLimits() {
        val image = Attachment("image", "photo.jpg", "/private/photo.jpg", "image/jpeg")
        val result = evidencePackage("What is in this picture?", emptyList(), listOf(image))
        assertEquals(listOf(image.path), result.images)
        assertTrue(result.prompt.contains("[S1]"))
        assertFailsWith<IllegalArgumentException> { evidencePackage("Compare all images", emptyList(), List(5) { image.copy(id = "$it") }) }
    }
    @Test fun chunksPreserveTheEndOfTextAndNormalizationRejectsBadVectors() {
        val text = "0123456789".repeat(400)
        val chunks = textChunks(text)
        assertTrue(chunks.last().endsWith(text.takeLast(200)))
        assertEquals(chunks[0].takeLast(160), chunks[1].take(160))
        assertFailsWith<IllegalArgumentException> { normalize(List(256) { 0f }) }
        assertFailsWith<IllegalArgumentException> { normalize(List(256) { Float.NaN }) }
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
    var stream: StreamResult? = null
    var finishImmediately = true
    var cancels = 0
    override fun load(search: Boolean, path: String, callback: Completion) { loads += search; callback.success() }
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
