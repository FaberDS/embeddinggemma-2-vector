package dev.pocketask

import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

private fun matchVector(index: Int) = List(256) { if (it == index) 1f else 0f }

/** Missing unmatched files are deliberate: a request must not open or re-index them. */
@OptIn(ExperimentalCoroutinesApi::class)
class MatchedSourceTests {
    @Test fun savedImageDescriptionAnswersRepeatedQuestionsWithoutOpeningAnyPixels() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("matched-source").toFile()
        val db = chatStore(root.path)
        val photo = java.io.File(root, "flower.jpg").apply { writeText("synthetic flower pixels") }
        val attachments = listOf(
            Attachment("flower", "Flower.jpg", photo.path, "image/jpeg"),
            Attachment("missing-photo", "Other.jpg", "$root/missing.jpg", "image/jpeg"),
            Attachment("missing-pdf", "Report.pdf", "$root/missing.pdf", "application/pdf"))
        attachments.forEach { db.saveSource(it); db.markPrepared(it) }
        db.put("library-migrated-v1", value = "yes")
        db.addEvidence(Evidence("flower-vector", "flower", "Flower.jpg", null, "A yellow flower in a garden.", photo.path, matchVector(0)))
        db.addEvidence(Evidence("other-vector", "missing-photo", "Other.jpg", null, "", attachments[1].path, matchVector(1)))
        db.addEvidence(Evidence("pdf-vector", "missing-pdf", "Report.pdf", 1, "Saved paragraph", null, matchVector(1)))
        val opened = mutableListOf<String>()
        var queries = 0
        val runtime = object : LocalRuntime {
            override fun load(search: Boolean, path: String, callback: Completion) = callback.success()
            override fun embed(text: String, imagePath: String?, query: Boolean, callback: VectorResult) {
                assertTrue(query); assertNull(imagePath); queries++; callback.success(matchVector(0))
            }
            override fun answer(instructions: String, prompt: String, images: List<String>, callback: StreamResult) {
                assertTrue(images.isEmpty()); opened += images
                assertContains(prompt, "A yellow flower in a garden.")
                assertFalse(prompt.contains("Other.jpg")); assertFalse(prompt.contains("Report.pdf"))
                callback.token("A flower [S1]."); callback.success()
            }
            override fun release(callback: Completion) = callback.success()
            override fun cancel() {}
        }
        val inputs = object : PlatformInputs {
            override fun pick(images: Boolean, callback: ImportResult) = error("No picker during a question")
            override fun pageCount(attachment: Attachment, callback: CountResult) = error("No document extraction during search")
            override fun readPage(attachment: Attachment, page: Int, callback: PageResult) = error("No page reads during search")
            override fun open(path: String, page: Int) {}
            override fun freeBytes() = Long.MAX_VALUE
            override fun canDownload(cellular: Boolean) = true
            override fun now() = System.currentTimeMillis()
        }
        val controller = AppController(root.path, db, runtime, inputs, worker = dispatcher)
        try {
            modelSpecs.take(2).forEach { controller.models.state(it, ModelState(installed = true)) }
            repeat(2) {
                controller.newQuestion(); controller.question("Find the flower picture"); controller.ask(); advanceUntilIdle()
                assertEquals("Completed", controller.state.value.result!!.status)
                assertEquals(listOf("flower"), controller.state.value.result!!.attachments.map { it.id })
            }
            assertEquals(2, queries)
            assertTrue(opened.isEmpty())
            assertEquals(photo.path, controller.state.value.result!!.sources.single().previewImage)
            assertEquals(3, controller.state.value.library.size)
        } finally { controller.close(); runCurrent(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun aDocumentQuestionUsesSavedPassagesWithoutLoadingPagePixels() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("matched-passage").toFile(); val db = chatStore(root.path)
        val pdf = Attachment("report", "Report.pdf", "$root/missing.pdf", "application/pdf")
        db.saveSource(pdf); db.markPrepared(pdf); db.put("library-migrated-v1", value = "yes")
        db.addEvidence(Evidence("a-text", pdf.id, pdf.name, 1, "The recorded amount is 42.", null, matchVector(0)))
        db.addEvidence(Evidence("b-page", pdf.id, pdf.name, 1, "", "$root/missing-page.jpg", matchVector(0)))
        var answered = false
        val runtime = object : LocalRuntime {
            override fun load(search: Boolean, path: String, callback: Completion) = callback.success()
            override fun embed(text: String, imagePath: String?, query: Boolean, callback: VectorResult) { assertNull(imagePath); assertTrue(query); callback.success(matchVector(0)) }
            override fun answer(instructions: String, prompt: String, images: List<String>, callback: StreamResult) {
                assertTrue(images.isEmpty()); assertContains(prompt, "The recorded amount is 42.")
                answered = true; callback.token("42 [S1]."); callback.success()
            }
            override fun release(callback: Completion) = callback.success()
            override fun cancel() {}
        }
        val controller = AppController(root.path, db, runtime, ChatInputs(), worker = dispatcher)
        try {
            modelSpecs.take(2).forEach { controller.models.state(it, ModelState(installed = true)) }
            controller.question("What is the recorded amount?"); controller.ask(); advanceUntilIdle()
            assertTrue(answered); assertEquals("Completed", controller.state.value.result!!.status)
            assertEquals(listOf("a-text"), controller.state.value.result!!.sources.map { it.id })
        } finally { controller.close(); runCurrent(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
}
