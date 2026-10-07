package dev.pocketask

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.pocketask.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

private class IngestionInputs : PlatformInputs {
    var selection = emptyList<Pair<File, String>>()
    var reads = 0
    var counts = 0
    var failSecondPage = false
    override fun pick(images: Boolean, callback: ImportResult) {
        selection.forEach { (file, type) -> callback.item(file.name, file.path, type) }
        callback.success()
    }
    override fun pageCount(attachment: Attachment, callback: CountResult) {
        counts++
        callback.success(if (attachment.type == "application/pdf") 2 else 1)
    }
    override fun readPage(attachment: Attachment, page: Int, callback: PageResult) {
        reads++
        when {
            attachment.isImage -> callback.success(PageInput("", attachment.path))
            attachment.type == "application/pdf" -> {
                if (failSecondPage && page == 1) callback.failure("Unreadable page")
                else callback.success(PageInput("Passage on PDF page ${page + 1}", "${File(attachment.path).parent}/page-$page.jpg"))
            }
            else -> callback.success(PageInput(File(attachment.path).readText(), null))
        }
    }
    override fun open(path: String, page: Int) {}
    override fun freeBytes() = Long.MAX_VALUE
    override fun canDownload(cellular: Boolean) = true
    override fun now() = System.currentTimeMillis()
}

private class IngestionRuntime : LocalRuntime {
    data class Embedding(val text: String, val image: String?, val query: Boolean)
    val embeddings = mutableListOf<Embedding>()
    val answerImages = mutableListOf<List<String>>()
    val descriptionImages = mutableListOf<List<String>>()
    val prompts = mutableListOf<String>()
    var holdNextDocument = false
    var held: VectorResult? = null
    var holdDescriptionAt: Int? = null
    var heldDescription: StreamResult? = null
    var failDescription = false
    private var engine: Boolean? = null
    override fun load(search: Boolean, path: String, callback: Completion) {
        assertNull(engine, "Only one engine may be resident")
        engine = search; callback.success()
    }
    override fun embed(text: String, imagePath: String?, query: Boolean, callback: VectorResult) {
        assertEquals(true, engine)
        embeddings += Embedding(text, imagePath, query)
        if (!query && holdNextDocument) { holdNextDocument = false; held = callback }
        else callback.success(List(256) { if (it == 0) 1f else 0f })
    }
    override fun answer(instructions: String, prompt: String, images: List<String>, callback: StreamResult) {
        assertEquals(false, engine)
        if (images.isNotEmpty()) {
            descriptionImages += images
            if (descriptionImages.size == holdDescriptionAt) { heldDescription = callback; return }
            if (failDescription) { callback.failure("Description failed"); return }
            callback.token("A yellow flower in a garden.")
        } else {
            answerImages += images; prompts += prompt
            callback.token("From the source [S1].")
        }
        callback.success()
    }
    override fun release(callback: Completion) { engine = null; callback.success() }
    override fun cancel() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class IngestionTests {
    private fun database(root: File): Store {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return Store(driver, root.path)
    }

    @Test fun imagesAndPdfPassagesAreIndexedOnImportAndNotReadAgainForQuestions() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("pocketask-ingestion").toFile()
        val inputs = IngestionInputs()
        val runtime = IngestionRuntime()
        val db = database(root)
        val controller = AppController(root.path, db, runtime, inputs, worker = dispatcher)
        try {
            controller.models.state(modelSpecs[0], ModelState(installed = true))
            controller.models.state(modelSpecs[1], ModelState(installed = true))
            controller.models.state(modelSpecs[1], ModelState(installed = true))
            val image = File(root, "photo.jpg").apply { writeText("fixture image") }
            val pdf = File(root, "report.pdf").apply { writeText("fixture pdf") }
            inputs.selection = listOf(image to "image/jpeg", pdf to "application/pdf")
            controller.pick(false); advanceUntilIdle()
            assertEquals(3, inputs.reads)
            assertEquals(8, runtime.embeddings.size)
            assertEquals(3, runtime.descriptionImages.size)
            assertTrue(runtime.embeddings.none { it.query })
            assertTrue(controller.state.value.library.all { it.prepared })
            val indexedPdf = controller.state.value.library.first { it.type == "application/pdf" }
            assertEquals(setOf(1, 2), db.evidence(indexedPdf.id, 0).map { it.page }.toSet())
            repeat(2) {
                controller.newQuestion()
                assertEquals(2, controller.state.value.library.size)
                controller.question("Question $it"); controller.ask(); advanceUntilIdle()
                assertEquals("Completed", controller.state.value.result?.status)
            }
            assertEquals(3, inputs.reads)
            assertEquals(2, inputs.counts)
            assertTrue(runtime.embeddings.drop(8).all { it.query && it.image == null })
            assertEquals(10, runtime.embeddings.size)
            assertTrue(runtime.answerImages.all { it.isEmpty() })
            assertEquals(3, runtime.descriptionImages.size)
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun allImportedImagesHaveSearchableDescriptionsButOnlyOnePreviewEntersTheAnswer() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("pocketask-image-batch").toFile()
        val inputs = IngestionInputs()
        val runtime = IngestionRuntime()
        val controller = AppController(root.path, database(root), runtime, inputs, worker = dispatcher)
        try {
            controller.models.state(modelSpecs[0], ModelState(installed = true))
            controller.models.state(modelSpecs[1], ModelState(installed = true))
            inputs.selection = List(9) { index -> File(root, "photo-$index.jpg").apply { writeText("fixture $index") } to "image/jpeg" }
            controller.pick(true); advanceUntilIdle()
            assertEquals(9, inputs.reads)
            assertEquals(9, controller.state.value.library.size)
            assertTrue(controller.state.value.library.all { it.prepared })
            controller.question("Find a picture"); controller.ask(); advanceUntilIdle()
            assertEquals("Completed", controller.state.value.result?.status)
            assertTrue(runtime.answerImages.single().isEmpty())
            assertEquals(9, runtime.descriptionImages.size)
            assertTrue(runtime.prompts.single().contains("yellow flower"))
            assertEquals(1, controller.state.value.result!!.attachments.size)
            assertEquals(9, controller.state.value.library.size)
            assertEquals(1, controller.state.value.result!!.sources.mapNotNull { it.displayImage }.distinct().size)
            assertEquals(9, inputs.reads)
            assertEquals(1, runtime.embeddings.count { it.query })
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun writtenMarkdownWaitsForTheModelThenPersistsItsIndexAcrossControllerRestarts() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("pocketask-note").toFile()
        val inputs = IngestionInputs()
        val runtime = IngestionRuntime()
        val db = database(root)
        val controller = AppController(root.path, db, runtime, inputs, worker = dispatcher)
        var restored: AppController? = null
        try {
            val markdown = "# Notes\n\nÜber Wien 🌳\n" + "Detailed passage. ".repeat(150)
            controller.addText("My notes", markdown); advanceUntilIdle()
            val note = controller.state.value.library.single()
            assertEquals("My notes.md", note.name)
            assertEquals("text/markdown", note.type)
            assertEquals("md", File(note.path).extension)
            assertEquals(markdown, File(note.path).readText())
            assertFalse(note.prepared)
            assertTrue(runtime.embeddings.isEmpty())
            controller.models.state(modelSpecs[0], ModelState(installed = true)); advanceUntilIdle()
            assertTrue(db.prepared(note))
            assertTrue(controller.state.value.library.single().prepared)
            assertEquals(textChunks(markdown).size, runtime.embeddings.size)
            controller.addText("Duplicate", markdown); advanceUntilIdle()
            assertEquals(1, controller.state.value.library.size)
            assertEquals(1, inputs.reads)
            controller.close(); advanceUntilIdle()
            val restoredRuntime = IngestionRuntime()
            restored = AppController(root.path, db, restoredRuntime, inputs, worker = dispatcher)
            restored.models.state(modelSpecs[0], ModelState(installed = true)); advanceUntilIdle()
            assertTrue(restored.state.value.library.single().prepared)
            assertTrue(restoredRuntime.embeddings.isEmpty())
            assertEquals(1, inputs.reads)
        } finally { restored?.close(); controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun interruptedIngestionCannotBeSearchedAndResumesOnForegroundWithoutLateWrites() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("pocketask-paused").toFile()
        val inputs = IngestionInputs()
        val runtime = IngestionRuntime().apply { holdNextDocument = true }
        val db = database(root)
        val controller = AppController(root.path, db, runtime, inputs, worker = dispatcher)
        try {
            controller.models.state(modelSpecs[0], ModelState(installed = true))
            controller.models.state(modelSpecs[1], ModelState(installed = true))
            controller.addText("Paused note", "Important text"); advanceUntilIdle()
            val note = controller.state.value.library.single()
            val late = runtime.held!!
            controller.background(); advanceUntilIdle()
            assertNull(controller.state.value.stage)
            assertFalse(db.prepared(note))
            controller.question("Search note"); controller.ask(); advanceUntilIdle()
            assertNull(controller.state.value.result)
            assertTrue(runtime.answerImages.isEmpty())
            controller.foreground(); advanceUntilIdle()
            assertTrue(db.prepared(note))
            assertEquals(1, db.evidence(note.id, 0).size)
            late.success(List(256) { if (it == 1) 1f else 0f }); advanceUntilIdle()
            assertEquals(1f, db.evidence(note.id, 0).single().vector[0])
            controller.ask(); advanceUntilIdle()
            assertEquals("Completed", controller.state.value.result?.status)
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun failedPdfIndexingRetriesOnlyUnfinishedSources() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("pocketask-retry").toFile()
        val inputs = IngestionInputs().apply { failSecondPage = true }
        val runtime = IngestionRuntime()
        val db = database(root)
        val controller = AppController(root.path, db, runtime, inputs, worker = dispatcher)
        try {
            controller.models.state(modelSpecs[0], ModelState(installed = true))
            controller.models.state(modelSpecs[1], ModelState(installed = true))
            val image = File(root, "photo.jpg").apply { writeText("fixture image") }
            val pdf = File(root, "report.pdf").apply { writeText("fixture pdf") }
            inputs.selection = listOf(image to "image/jpeg", pdf to "application/pdf")
            controller.pick(false); advanceUntilIdle()
            assertEquals(listOf(true, false), controller.state.value.library.map { it.prepared })
            assertEquals("Unreadable page", controller.state.value.error)
            inputs.failSecondPage = false
            controller.indexAttachments(); advanceUntilIdle()
            assertTrue(controller.state.value.library.all { it.prepared })
            assertEquals(1, runtime.embeddings.count { it.image?.endsWith("original.jpg") == true })
            val indexedPdf = controller.state.value.library.last()
            assertEquals(6, db.evidence(indexedPdf.id, 0).size)
            assertNull(controller.state.value.error)
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
    @Test fun knowledgeBaseSurvivesHistoryDeletionAndRestartAndRemovalDoesNotResurrectIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("pocketask-knowledge").toFile()
        val inputs = IngestionInputs()
        val db = database(root)
        val controller = AppController(root.path, db, IngestionRuntime(), inputs, worker = dispatcher)
        var restored: AppController? = null
        var reopened: AppController? = null
        try {
            controller.models.state(modelSpecs[0], ModelState(installed = true))
            controller.models.state(modelSpecs[1], ModelState(installed = true))
            controller.addText("Permanent notes", "A fact for every conversation."); advanceUntilIdle()
            val note = controller.state.value.library.single()
            controller.question("First chat"); controller.ask(); advanceUntilIdle()
            val first = controller.state.value.result!!
            controller.newQuestion(); controller.question("Second chat"); controller.ask(); advanceUntilIdle()
            assertNotEquals(first.conversationId, controller.state.value.result!!.conversationId)
            assertEquals(note.id, controller.state.value.result!!.sources.single().attachmentId)
            controller.clearHistory()
            assertTrue(db.history().isEmpty())
            assertTrue(db.prepared(note))
            assertTrue(File(note.path).exists())
            assertEquals(1, controller.state.value.library.size)
            controller.close(); advanceUntilIdle()
            val runtime = IngestionRuntime()
            restored = AppController(root.path, db, runtime, inputs, worker = dispatcher)
            restored.models.state(modelSpecs[0], ModelState(installed = true))
            restored.models.state(modelSpecs[1], ModelState(installed = true)); advanceUntilIdle()
            assertTrue(runtime.embeddings.isEmpty())
            restored.question("Third chat"); restored.ask(); advanceUntilIdle()
            assertEquals(note.id, restored.state.value.result!!.sources.single().attachmentId)
            assertTrue(runtime.embeddings.all { it.query && it.image == null })
            assertEquals(1, inputs.reads)
            restored.removeAttachment(note.id)
            assertTrue(restored.state.value.library.isEmpty())
            assertFalse(db.prepared(note))
            assertTrue(db.evidence(note.id, 0).isEmpty())
            assertTrue(File(note.path).exists()) // Earlier answers retain their original source link.
            restored.close(); advanceUntilIdle()
            reopened = AppController(root.path, db, IngestionRuntime(), inputs, worker = dispatcher)
            assertTrue(reopened.state.value.library.isEmpty()) // History must not silently re-add removed sources.
            reopened.clearHistory()
            assertFalse(File(note.path).exists())
        } finally { reopened?.close(); restored?.close(); controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun unfinishedSourcesDoNotEnterSearchOrBlockAlreadyIndexedKnowledge() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("pocketask-ready-knowledge").toFile()
        val inputs = IngestionInputs().apply { failSecondPage = true }
        val runtime = IngestionRuntime()
        val db = database(root)
        val controller = AppController(root.path, db, runtime, inputs, worker = dispatcher)
        try {
            controller.models.state(modelSpecs[0], ModelState(installed = true))
            controller.models.state(modelSpecs[1], ModelState(installed = true))
            controller.addText("Ready note", "Already indexed fact"); advanceUntilIdle()
            val ready = controller.state.value.library.single()
            inputs.selection = listOf(File(root, "broken.pdf").apply { writeText("fixture") } to "application/pdf")
            controller.pick(false); advanceUntilIdle()
            assertEquals(1, controller.state.value.library.count { it.prepared })
            controller.newQuestion(); controller.question("Search my knowledge"); controller.ask(); advanceUntilIdle()
            assertEquals("Completed", controller.state.value.result!!.status)
            assertEquals(listOf(ready.id), controller.state.value.result!!.sources.map { it.attachmentId })
            assertEquals(listOf(ready.id), controller.state.value.result!!.attachments.map { it.id })
            assertTrue(controller.state.value.result!!.usesKnowledgeBase)
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun visualAssetsWaitForTheAnswerModelWhileTextStillIndexes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("caption-model-wait").toFile()
        val inputs = IngestionInputs(); val runtime = IngestionRuntime(); val db = database(root)
        val controller = AppController(root.path, db, runtime, inputs, worker = dispatcher)
        try {
            controller.models.state(modelSpecs[0], ModelState(installed = true))
            inputs.selection = listOf(
                File(root, "flower.jpg").apply { writeText("photo fixture") } to "image/jpeg",
                File(root, "notes.md").apply { writeText("A searchable note") } to "text/markdown")
            controller.pick(false); advanceUntilIdle()
            assertEquals(1, inputs.reads)
            assertTrue(controller.state.value.library.single { !it.isImage }.prepared)
            assertFalse(controller.state.value.library.single { it.isImage }.prepared)
            assertTrue(runtime.descriptionImages.isEmpty())
            assertContains(controller.state.value.error!!, "Install Gemma")
            controller.models.state(modelSpecs[1], ModelState(installed = true)); advanceUntilIdle()
            assertTrue(controller.state.value.library.all { it.prepared })
            assertEquals(1, runtime.descriptionImages.size)
            assertEquals(2, inputs.reads)
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun finishingTheAnswerDownloadDuringTextIndexingResumesPendingImages() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("caption-install-race").toFile()
        val inputs = IngestionInputs(); val runtime = IngestionRuntime().apply { holdNextDocument = true }; val db = database(root)
        val controller = AppController(root.path, db, runtime, inputs, worker = dispatcher)
        try {
            controller.models.state(modelSpecs[0], ModelState(installed = true))
            inputs.selection = listOf(
                File(root, "flower.jpg").apply { writeText("photo fixture") } to "image/jpeg",
                File(root, "notes.md").apply { writeText("A searchable note") } to "text/markdown")
            controller.pick(false); advanceUntilIdle()
            assertNotNull(runtime.held); assertNotNull(controller.state.value.stage)
            controller.models.state(modelSpecs[1], ModelState(installed = true)); advanceUntilIdle()
            assertTrue(runtime.descriptionImages.isEmpty())
            runtime.held!!.success(List(256) { if (it == 0) 1f else 0f }); advanceUntilIdle()
            assertTrue(controller.state.value.library.all { it.prepared })
            assertEquals(1, runtime.descriptionImages.size); assertNull(controller.state.value.stage)
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun failedDescriptionsStayPendingAndRetryWithoutBeingSearched() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("caption-failure").toFile()
        val inputs = IngestionInputs(); val runtime = IngestionRuntime().apply { failDescription = true }; val db = database(root)
        val controller = AppController(root.path, db, runtime, inputs, worker = dispatcher)
        try {
            modelSpecs.take(2).forEach { controller.models.state(it, ModelState(installed = true)) }
            inputs.selection = listOf(File(root, "flower.jpg").apply { writeText("photo fixture") } to "image/jpeg")
            controller.pick(true); advanceUntilIdle()
            val photo = controller.state.value.library.single()
            assertFalse(db.prepared(photo)); assertEquals("Description failed", controller.state.value.error)
            assertNull(db.imageDescription(db.evidence(photo.id, 0).single()))
            controller.question("What is in the photo?"); controller.ask(); advanceUntilIdle()
            assertNull(controller.state.value.result); assertTrue(runtime.answerImages.isEmpty())
            runtime.failDescription = false
            controller.indexAttachments(); advanceUntilIdle()
            assertTrue(db.prepared(photo)); assertEquals(2, db.evidence(photo.id, 0).size)
            controller.ask(); advanceUntilIdle()
            assertEquals("Completed", controller.state.value.result!!.status)
            assertTrue(runtime.answerImages.single().isEmpty())
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun completedPageDescriptionsSurviveInterruptedImportsAndIgnoreLateCallbacks() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("caption-cancellation").toFile()
        val inputs = IngestionInputs(); val runtime = IngestionRuntime().apply { holdDescriptionAt = 2 }; val db = database(root)
        val controller = AppController(root.path, db, runtime, inputs, worker = dispatcher)
        try {
            modelSpecs.take(2).forEach { controller.models.state(it, ModelState(installed = true)) }
            inputs.selection = listOf(File(root, "report.pdf").apply { writeText("pdf fixture") } to "application/pdf")
            controller.pick(false); advanceUntilIdle()
            val pdf = controller.state.value.library.single()
            val images = db.evidence(pdf.id, 0).filter { it.image != null }.sortedBy { it.page }
            assertContains(controller.state.value.stage!!, "Describing")
            assertNotNull(db.imageDescription(images[0])); assertNull(db.imageDescription(images[1]))
            val late = runtime.heldDescription!!
            controller.background(); advanceUntilIdle()
            assertFalse(db.prepared(pdf)); assertNull(controller.state.value.stage)
            late.token("Ignore this late hallucination"); late.success(); advanceUntilIdle()
            assertNull(db.imageDescription(images[1]))
            controller.foreground(); advanceUntilIdle()
            assertTrue(db.prepared(pdf))
            assertEquals(3, runtime.descriptionImages.size) // First page is reused; only the second is retried.
            assertEquals(6, db.evidence(pdf.id, 0).size)
            assertTrue(images.all { db.imageDescription(it)?.text == "A yellow flower in a garden." })
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun legacyVisualSourcesAreDescribedOnceAndRestartsAndRemovalPreserveTheRightData() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("caption-migration").toFile()
        val inputs = IngestionInputs(); val runtime = IngestionRuntime(); val db = database(root)
        val photo = Attachment("legacy-photo", "Flower.jpg", File(root, "flower.jpg").apply { writeText("photo fixture") }.path, "image/jpeg")
        val original = Evidence("${photo.id}-0-image", photo.id, photo.name, null, "", photo.path, List(256) { if (it == 0) 1f else 0f })
        db.saveSource(photo); db.addEvidence(original)
        db.put("prepared-v1-256", photo.id, "yes") // Real pre-description index, with no new readiness marker.
        val controller = AppController(root.path, db, runtime, inputs, worker = dispatcher)
        var restored: AppController? = null
        try {
            assertFalse(controller.state.value.library.single().prepared)
            modelSpecs.take(2).forEach { controller.models.state(it, ModelState(installed = true)) }; advanceUntilIdle()
            assertTrue(db.prepared(photo)); assertEquals(1, runtime.descriptionImages.size)
            assertEquals("answer", db.imageDescription(original)!!.modelId)
            controller.close(); advanceUntilIdle()
            val later = IngestionRuntime()
            restored = AppController(root.path, db, later, inputs, worker = dispatcher)
            modelSpecs.take(2).forEach { restored.models.state(it, ModelState(installed = true)) }; advanceUntilIdle()
            assertTrue(later.embeddings.isEmpty()); assertTrue(later.descriptionImages.isEmpty())
            File(photo.path).delete() // Saved descriptions answer even if original pixels cannot be read.
            repeat(2) {
                restored.newQuestion(); restored.question("Find the yellow flower"); restored.ask(); advanceUntilIdle()
                assertEquals("Completed", restored.state.value.result!!.status)
                assertTrue(restored.state.value.result!!.sources.all { it.image == null && it.previewImage == photo.path })
                assertContains(later.prompts.last(), "yellow flower")
            }
            assertEquals(1, inputs.reads); assertTrue(later.descriptionImages.isEmpty())
            assertTrue(later.answerImages.all { it.isEmpty() })
            restored.clearHistory(); assertNotNull(db.imageDescription(original))
            restored.removeAttachment(photo.id)
            assertNull(db.imageDescription(original)); assertTrue(db.evidence(photo.id, 0).isEmpty())
        } finally { restored?.close(); controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

}
