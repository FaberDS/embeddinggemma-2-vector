package dev.pocketask

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.pocketask.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

private open class IngestionInputs : PlatformInputs {
    var selection = emptyList<Pair<File, String>>()
    var reads = 0
    var counts = 0
    var failSecondPage = false
    var pages = 2
    override fun pick(images: Boolean, callback: ImportResult) {
        selection.forEach { (file, type) -> callback.item(file.name, file.path, type) }
        callback.success()
    }
    override fun pageCount(attachment: Attachment, callback: CountResult) {
        counts++
        callback.success(if (attachment.type == "application/pdf") pages else 1)
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
    val loads = mutableListOf<Boolean>()
    var holdNextDocument = false
    var held: VectorResult? = null
    var holdDescriptionAt: Int? = null
    var heldDescription: StreamResult? = null
    var failDescription = false
    var holdEmbeddingAt: Int? = null
    private var engine: Boolean? = null
    override fun load(search: Boolean, path: String, callback: Completion) {
        assertNull(engine, "Only one engine may be resident")
        loads += search; engine = search; callback.success()
    }
    override fun embed(text: String, imagePath: String?, query: Boolean, callback: VectorResult) {
        assertEquals(true, engine)
        embeddings += Embedding(text, imagePath, query)
        if (!query && (holdNextDocument || embeddings.size == holdEmbeddingAt)) { holdNextDocument = false; held = callback }
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

private class TextFirstInputs : IngestionInputs(), DocumentInputs {
    val textPages = mutableListOf<Int>()
    val imagePages = mutableListOf<Int>()
    override fun readTextPage(attachment: Attachment, page: Int, callback: PageResult) {
        textPages += page
        callback.success(PageInput("Searchable passage on page ${page + 1}", null))
    }
    override fun readImagePage(attachment: Attachment, page: Int, callback: PageResult) {
        imagePages += page
        callback.success(PageInput("", "${File(attachment.path).parent}/page-$page.jpg"))
    }
}

private class OcrInputs : IngestionInputs(), DocumentInputs {
    val textPages = mutableListOf<Pair<String, Int>>()
    var failOcr = false
    override fun readTextPage(attachment: Attachment, page: Int, callback: PageResult) {
        textPages += attachment.id to page
        if (failOcr) { callback.failure("OCR failed; retry indexing"); return }
        callback.success(PageInput(if (attachment.isImage) "" else "Invoice",
            if (attachment.isImage) attachment.path else "${File(attachment.path).parent}/page-$page.jpg",
            "Invoice\nINV-42\nTotal: EUR 125.70"))
    }
    override fun readImagePage(attachment: Attachment, page: Int, callback: PageResult) {
        error("OCR already cached the page image")
    }
}

private class TestIndexingTasks : IndexingTasks {
    var permit: IndexingPermit? = null
    var backgroundAllowed = true
    val starts = mutableListOf<Boolean>()
    val finishes = mutableListOf<Boolean>()
    val updates = mutableListOf<IndexingProgress>()
    override fun start(userInitiated: Boolean, callback: IndexingPermit) { starts += userInitiated; permit = callback; callback.ready(backgroundAllowed) }
    override fun progress(value: IndexingProgress) { updates += value }
    override fun finish(success: Boolean) { finishes += success }
    override fun schedule() {}
}

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class IngestionTests {
    private fun database(root: File): Store {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return Store(driver, root.path)
    }

    @Test fun ocrPassagesAreIndexedOnceAndRemainSearchableInNewChatsWithoutReadingImages() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("ocr-ingestion").toFile()
        val inputs = OcrInputs(); val runtime = IngestionRuntime(); val db = database(root)
        val controller = AppController(root.path, db, runtime, inputs, worker = dispatcher)
        try {
            modelSpecs.take(2).forEach { controller.models.state(it, ModelState(installed = true)) }
            inputs.selection = listOf(File(root, "receipt.jpg").apply { writeText("synthetic image") } to "image/jpeg",
                File(root, "invoice.pdf").apply { writeText("synthetic PDF") } to "application/pdf")
            controller.pick(false); advanceUntilIdle()
            val sources = controller.state.value.library
            assertTrue(sources.all { it.prepared })
            val ocr = sources.flatMap { db.evidence(it.id, 0) }.filter { it.kind == "ocr" }
            assertEquals(3, ocr.size)
            assertTrue(ocr.all { it.text.contains("INV-42") && it.displayImage != null && it.image == null })
            assertEquals(setOf(1, 2), ocr.filter { it.name.endsWith(".pdf") }.map { it.page }.toSet())
            assertTrue(ocr.filter { it.page != null }.none { it.text.contains("Invoice") }) // Embedded text is retained separately.
            assertEquals(3, inputs.textPages.size)
            val vectors = runtime.embeddings.count { !it.query }
            repeat(2) {
                controller.newQuestion(); controller.question("Find INV-42"); controller.ask(); advanceUntilIdle()
                assertEquals("Completed", controller.state.value.result!!.status)
                assertContains(runtime.prompts.last(), "On-device OCR transcription")
                assertTrue(runtime.answerImages.last().isEmpty())
            }
            assertEquals(3, inputs.textPages.size); assertEquals(vectors, runtime.embeddings.count { !it.query })
            controller.clearHistory()
            assertEquals(3, sources.flatMap { db.evidence(it.id, 0) }.count { it.kind == "ocr" })
            sources.forEach { controller.removeAttachment(it.id) }
            assertTrue(sources.all { db.evidence(it.id, 0).isEmpty() })
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun interruptedOcrEmbeddingReusesCachedRecognitionAfterRestart() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("ocr-restart").toFile()
        val inputs = OcrInputs(); val runtime = IngestionRuntime().apply { holdEmbeddingAt = 1 }
        val db = database(root); val controller = AppController(root.path, db, runtime, inputs, worker = dispatcher)
        var restored: AppController? = null
        try {
            modelSpecs.take(2).forEach { controller.models.state(it, ModelState(installed = true)) }
            inputs.selection = listOf(File(root, "receipt.jpg").apply { writeText("synthetic image") } to "image/jpeg")
            controller.pick(false); advanceUntilIdle()
            val photo = controller.state.value.library.single()
            assertEquals(0, db.checkpoint(photo)!!.ocr)
            assertContains(db.pageInput(photo, 0)!!.ocr!!, "INV-42")
            val late = runtime.held!!; controller.close(); advanceUntilIdle()
            val next = IngestionRuntime()
            restored = AppController(root.path, db, next, inputs, worker = dispatcher)
            modelSpecs.take(2).forEach { restored.models.state(it, ModelState(installed = true)) }; advanceUntilIdle()
            assertTrue(db.prepared(photo)); assertEquals(1, inputs.textPages.size)
            assertEquals(1, db.evidence(photo.id, 0).count { it.kind == "ocr" })
            val saved = db.evidence(photo.id, 0)
            late.success(List(256) { if (it == 1) 1f else 0f }); advanceUntilIdle()
            assertEquals(saved, db.evidence(photo.id, 0))
        } finally { restored?.close(); controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun completedLegacyAssetsGainOcrWithoutRepeatingExistingVectorsOrDescriptions() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("ocr-upgrade").toFile()
        val inputs = OcrInputs(); val runtime = IngestionRuntime(); val db = database(root)
        val vector = List(256) { if (it == 0) 1f else 0f }
        val photo = Attachment("legacy-photo", "receipt.jpg", File(root, "receipt.jpg").apply { writeText("synthetic image") }.path, "image/jpeg")
        val pdf = Attachment("legacy-pdf", "invoice.pdf", File(root, "invoice.pdf").apply { writeText("synthetic PDF") }.path, "application/pdf")
        val existing = listOf(photo, pdf).flatMap { asset ->
            val count = if (asset.isImage) 1 else 2
            db.saveSource(asset); db.put("prepared-v1-256", asset.id, "yes"); db.put("described-v1", asset.id, "yes")
            if (!asset.isImage) db.saveCheckpoint(asset, IndexCheckpoint(count, count, count, count, count))
            List(count) { page ->
                Evidence("${asset.id}-$page-image", asset.id, asset.name, if (asset.isImage) null else page + 1,
                    "Existing saved description", asset.path, vector).also {
                    db.addEvidence(it); db.saveImageDescription(it, ImageDescription(it.text, "old-answer-model"))
                }
            }
        }
        val controller = AppController(root.path, db, runtime, inputs, worker = dispatcher)
        try {
            assertTrue(controller.state.value.library.all { it.searchable && !it.prepared })
            controller.models.state(modelSpecs[0], ModelState(installed = true)); advanceUntilIdle()
            assertTrue(controller.state.value.library.all { it.prepared })
            assertTrue(runtime.descriptionImages.isEmpty())
            assertTrue(runtime.embeddings.all { it.image == null && it.text.contains("INV-42") })
            assertEquals(3, inputs.textPages.size)
            existing.forEach { old ->
                assertEquals(old, db.evidence(old.attachmentId, 0).first { it.id == old.id })
                assertEquals("old-answer-model", db.imageDescription(old)!!.modelId)
            }
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun ocrFailureStaysPendingAndCanBeRetriedWithoutMakingEmptyVectors() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("ocr-failure").toFile()
        val inputs = OcrInputs().apply { failOcr = true }; val runtime = IngestionRuntime(); val db = database(root)
        val controller = AppController(root.path, db, runtime, inputs, worker = dispatcher)
        try {
            modelSpecs.take(2).forEach { controller.models.state(it, ModelState(installed = true)) }
            inputs.selection = listOf(File(root, "receipt.jpg").apply { writeText("synthetic image") } to "image/jpeg")
            controller.pick(false); advanceUntilIdle()
            val photo = controller.state.value.library.single()
            assertFalse(photo.prepared); assertTrue(db.evidence(photo.id, 0).isEmpty())
            assertContains(controller.state.value.error!!, "OCR failed")
            inputs.failOcr = false; controller.indexAttachments(); advanceUntilIdle()
            assertTrue(db.prepared(photo)); assertEquals(1, db.evidence(photo.id, 0).count { it.kind == "ocr" })
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
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
            assertEquals(2, inputs.reads)
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
            assertEquals(2, inputs.reads)
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
            assertEquals(0, inputs.reads)
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
            assertEquals(0, inputs.reads)
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

    @Test fun completedPagesOfUnfinishedSourcesAreSearchableAlongsideIndexedKnowledge() = runTest {
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
            assertEquals(setOf(ready.id, controller.state.value.library.last().id), controller.state.value.result!!.sources.map { it.attachmentId }.toSet())
            assertEquals(setOf(ready.id, controller.state.value.library.last().id), controller.state.value.result!!.attachments.map { it.id }.toSet())
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
            assertEquals(1, inputs.reads)
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
            val liveReport = controller.state.value.importReport!!
            assertEquals(3, liveReport.stages.count { it.status == ImportStageStatus.Completed })
            assertEquals("Describing pages", liveReport.stages.single { it.status == ImportStageStatus.Active }.phase)
            assertEquals(1, liveReport.stages.single { it.status == ImportStageStatus.Active }.completed)
            assertNotNull(db.imageDescription(images[0])); assertNull(db.imageDescription(images[1]))
            val late = runtime.heldDescription!!
            controller.background(); advanceUntilIdle()
            assertFalse(db.prepared(pdf)); assertNull(controller.state.value.stage)
            assertEquals("Paused", controller.state.value.importReport!!.status)
            assertEquals(ImportStageStatus.Paused, controller.state.value.importReport!!.stages[3].status)
            assertEquals(controller.state.value.importReport, db.savedImportReport())
            late.token("Ignore this late hallucination"); late.success(); advanceUntilIdle()
            assertNull(db.imageDescription(images[1]))
            controller.foreground(); advanceUntilIdle()
            assertTrue(db.prepared(pdf))
            assertEquals("Completed", controller.state.value.importReport!!.status)
            assertTrue(controller.state.value.importReport!!.stages.all { it.status == ImportStageStatus.Completed })
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
            assertEquals(0, inputs.reads); assertTrue(later.descriptionImages.isEmpty())
            assertTrue(later.answerImages.all { it.isEmpty() })
            restored.clearHistory(); assertNotNull(db.imageDescription(original))
            restored.removeAttachment(photo.id)
            assertNull(db.imageDescription(original)); assertTrue(db.evidence(photo.id, 0).isEmpty())
        } finally { restored?.close(); controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun hundredPagePdfCanAnswerWhileVisualDescriptionsArePendingWithoutRepeatingTextWork() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("hundred-page-pdf").toFile()
        val inputs = TextFirstInputs().apply { pages = 100 }
        val runtime = IngestionRuntime().apply { holdDescriptionAt = 1 }
        val db = database(root)
        val controller = AppController(root.path, db, runtime, inputs, worker = dispatcher)
        try {
            modelSpecs.take(2).forEach { controller.models.state(it, ModelState(installed = true)) }
            inputs.selection = listOf(File(root, "large.pdf").apply { writeText("synthetic 100-page fixture") } to "application/pdf")
            controller.pick(false); advanceUntilIdle()
            val pdf = controller.state.value.library.single()
            assertTrue(pdf.searchable); assertFalse(pdf.prepared)
            assertEquals(100, db.checkpoint(pdf)!!.text)
            assertEquals((0 until 100).toList(), inputs.textPages)
            assertEquals(100, runtime.embeddings.count { it.image == null && !it.query })
            runtime.holdDescriptionAt = 2 // Keep the resumed visual pass pending after answering.
            controller.question("Find my PDF passage"); controller.ask(); advanceUntilIdle()
            assertEquals("Completed", controller.state.value.result!!.status)
            assertTrue(runtime.prompts.single().contains("Searchable passage"))
            assertTrue(runtime.answerImages.single().isEmpty())
            assertEquals(100, runtime.embeddings.count { it.image == null && !it.query })
            assertEquals(100, inputs.textPages.size); assertEquals(100, inputs.imagePages.size)
            assertFalse(db.prepared(pdf)); assertNotNull(runtime.heldDescription)
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun interruptedHundredPageTextPassReusesExtractionAndCommittedVectorsAfterRestart() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("checkpoint-restart").toFile()
        val inputs = TextFirstInputs().apply { pages = 100 }
        val runtime = IngestionRuntime().apply { holdEmbeddingAt = 2 }
        val db = database(root)
        val controller = AppController(root.path, db, runtime, inputs, worker = dispatcher)
        var restored: AppController? = null
        try {
            modelSpecs.take(2).forEach { controller.models.state(it, ModelState(installed = true)) }
            inputs.selection = listOf(File(root, "large.pdf").apply { writeText("synthetic checkpoint fixture") } to "application/pdf")
            controller.pick(false); advanceUntilIdle()
            val pdf = controller.state.value.library.single()
            assertEquals(1, db.checkpoint(pdf)!!.text); assertTrue(db.searchable(pdf))
            assertContains(controller.state.value.stage!!, "Indexing text")
            val late = runtime.held!!
            controller.close(); advanceUntilIdle()
            val next = IngestionRuntime()
            restored = AppController(root.path, db, next, inputs, worker = dispatcher)
            modelSpecs.take(2).forEach { restored.models.state(it, ModelState(installed = true)) }; advanceUntilIdle()
            assertTrue(db.prepared(pdf)); assertEquals(500, db.checkpoint(pdf)!!.completed)
            assertEquals(100, inputs.textPages.size); assertEquals(100, inputs.textPages.toSet().size)
            assertEquals(1, next.embeddings.count { it.text == "Searchable passage on page 2" })
            assertTrue(next.embeddings.none { it.text == "Searchable passage on page 1" })
            val before = db.evidence(pdf.id, 0)
            late.success(List(256) { if (it == 1) 1f else 0f }); advanceUntilIdle()
            assertEquals(before, db.evidence(pdf.id, 0))
        } finally { restored?.close(); controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun interruptedVisualAndCaptionStepsResumeTheirOwnCheckpoints() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        for (stopAt in listOf(5, 8)) { // Three text vectors; pause the second image or second caption.
            val root = Files.createTempDirectory("visual-checkpoint").toFile()
            val inputs = TextFirstInputs().apply { pages = 3 }
            val runtime = IngestionRuntime().apply { holdEmbeddingAt = stopAt }
            val db = database(root)
            val controller = AppController(root.path, db, runtime, inputs, worker = dispatcher)
            try {
                modelSpecs.take(2).forEach { controller.models.state(it, ModelState(installed = true)) }
                inputs.selection = listOf(File(root, "report.pdf").apply { writeText("fixture $stopAt") } to "application/pdf")
                controller.pick(false); advanceUntilIdle()
                val pdf = controller.state.value.library.single()
                assertEquals(if (stopAt == 5) 1 else 3, db.checkpoint(pdf)!!.images)
                assertEquals(if (stopAt == 5) 0 else 1, db.checkpoint(pdf)!!.captions)
                controller.background(); advanceUntilIdle(); controller.foreground(); advanceUntilIdle()
                assertTrue(db.prepared(pdf))
                assertEquals(listOf(0, 1, 2), inputs.textPages); assertEquals(listOf(0, 1, 2), inputs.imagePages)
                assertEquals(3, runtime.descriptionImages.size)
                assertEquals(1, runtime.embeddings.count { it.text == "Searchable passage on page 1" })
                assertEquals(9, db.evidence(pdf.id, 0).size)
            } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively() }
        }
        Dispatchers.resetMain()
    }

    @Test fun backgroundLeaseContinuesIndexingAndExpirationPausesSafely() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("indexing-lease").toFile()
        val inputs = TextFirstInputs().apply { pages = 3 }
        val runtime = IngestionRuntime().apply { holdEmbeddingAt = 2 }
        val db = database(root); val tasks = TestIndexingTasks()
        val controller = AppController(root.path, db, runtime, inputs, worker = dispatcher, indexingTasks = tasks)
        try {
            modelSpecs.take(2).forEach { controller.models.state(it, ModelState(installed = true)) }
            inputs.selection = listOf(File(root, "report.pdf").apply { writeText("background fixture") } to "application/pdf")
            controller.pick(false); advanceUntilIdle()
            val pdf = controller.state.value.library.single()
            assertEquals(listOf(true), tasks.starts)
            controller.background(); advanceUntilIdle()
            assertNotNull(controller.state.value.stage); assertTrue(tasks.finishes.isEmpty())
            tasks.permit!!.expired(); advanceUntilIdle()
            assertNull(controller.state.value.stage); assertEquals(listOf(false), tasks.finishes)
            assertEquals(1, db.checkpoint(pdf)!!.text)
            controller.resumeIndexingInBackground(); advanceUntilIdle()
            assertTrue(db.prepared(pdf)); assertEquals(listOf(false, true), tasks.finishes)
            assertTrue(tasks.updates.all { it.background })
            assertEquals(15L, tasks.updates.last().completedUnits)
            assertEquals(15L, tasks.updates.last().totalUnits)
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun composerCanSendAQuestionWhilePdfEnrichmentIsRunning() = runComposeUiTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val root = Files.createTempDirectory("partial-pdf-composer").toFile()
        val inputs = TextFirstInputs().apply { pages = 3 }
        val runtime = IngestionRuntime().apply { holdDescriptionAt = 1 }
        val controller = AppController(root.path, database(root), runtime, inputs, worker = Dispatchers.Main)
        try {
            runOnIdle {
                controller.finishOnboarding()
                modelSpecs.take(2).forEach { controller.models.state(it, ModelState(installed = true)) }
                inputs.selection = listOf(File(root, "report.pdf").apply { writeText("composer fixture") } to "application/pdf")
                controller.pick(false)
            }
            setContent { Box(Modifier.size(390.dp, 844.dp)) { PocketAskApp(controller) } }
            waitUntil { controller.state.value.library.single().searchable }
            onNodeWithTag("chat.input").assertIsEnabled().performTextInput("Find a passage")
            onNodeWithTag("chat.send").assertIsEnabled()
            runOnIdle { runtime.holdDescriptionAt = 2 }
            onNodeWithTag("chat.send").performClick()
            waitUntil { controller.state.value.result?.status == "Completed" }
            runOnIdle { assertTrue(runtime.answerImages.single().isEmpty()); assertFalse(controller.state.value.library.single().prepared) }
        } finally { controller.close(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun deniedBackgroundPermissionDoesNotLoadModelsAndForegroundCanStillResume() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("expired-before-start").toFile()
        val inputs = TextFirstInputs().apply { pages = 3 }
        val runtime = IngestionRuntime(); val tasks = TestIndexingTasks().apply { backgroundAllowed = false }
        val db = database(root)
        val pdf = Attachment("pending", "report.pdf", File(root, "report.pdf").apply { writeText("fixture") }.path, "application/pdf")
        db.saveSource(pdf)
        val controller = AppController(root.path, db, runtime, inputs, worker = dispatcher, indexingTasks = tasks, initiallyActive = false)
        try {
            modelSpecs.take(2).forEach { controller.models.state(it, ModelState(installed = true)) }; advanceUntilIdle()
            controller.resumeIndexingInBackground(); advanceUntilIdle()
            assertTrue(runtime.loads.isEmpty()); assertNull(db.checkpoint(pdf)); assertEquals(listOf(false), tasks.finishes)
            controller.foreground(); advanceUntilIdle()
            assertTrue(db.prepared(pdf)); assertEquals(listOf(false, true), tasks.finishes)
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

}
