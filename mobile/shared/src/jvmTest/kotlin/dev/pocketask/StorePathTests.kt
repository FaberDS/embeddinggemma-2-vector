package dev.pocketask

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.pocketask.db.AppDatabase
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class StorePathTests {
    private fun driver() = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AppDatabase.Schema.create(it) }

    @Test fun legacyDraftHistoryAndPageImagesFollowTheRelocatedContainer() {
        val parent = Files.createTempDirectory("pocketask-relocation").toFile()
        val oldRoot = File(parent, "old-container/PocketAsk").apply { mkdirs() }
        val newRoot = File(parent, "new-container/PocketAsk")
        val image = Attachment("photo", "Photo.jpg", File(oldRoot, "inputs/photo/original.jpg").path, "image/jpeg")
        val pdf = Attachment("pdf", "Report.pdf", File(oldRoot, "inputs/pdf/original.pdf").path, "application/pdf")
        val page = Evidence("page", pdf.id, pdf.name, 2, "", File(oldRoot, "inputs/pdf/page-1.jpg").path, List(256) { 0f })
        listOf(image.path, pdf.path, page.image!!).forEach { File(it).apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) } }
        val driver = driver()
        val legacy = Store(driver)
        legacy.saveDraft(Draft("Compare", listOf(image, pdf)))
        legacy.save(Answer("answer", "Compare", listOf(image, pdf), sources = listOf(page), status = "Completed"))
        legacy.addEvidence(page); legacy.markPrepared(pdf)
        try {
            newRoot.parentFile.mkdirs()
            Files.move(oldRoot.toPath(), newRoot.toPath())
            assertFalse(File(image.path).exists())
            val restored = Store(driver, newRoot.path)
            val draft = restored.draft()
            assertEquals(File(newRoot, "inputs/photo/original.jpg").path, draft.attachments.first().path)
            draft.attachments.forEach { assertTrue(File(it.path).exists()) }
            val history = restored.history().single()
            history.attachments.forEach { assertTrue(File(it.path).exists()) }
            assertTrue(File(history.sources.single().image!!).exists())
            assertTrue(File(restored.evidence(pdf.id, 0).single().image!!).exists())
            assertTrue(restored.prepared(pdf))
            val library = restored.library(newRoot.path)
            assertEquals(setOf(image.id, pdf.id), library.map { it.id }.toSet())
            library.forEach { assertTrue(File(it.path).exists()) }
            assertTrue(library.first { it.id == pdf.id }.prepared)
            restored.saveDraft(draft)
            restored.save(history)
            restored.addEvidence(restored.evidence(pdf.id, 0).single())
            assertFalse(restored.value("draft")!!.contains("container"))
            assertFalse(restored.value("history", "answer")!!.contains("container"))
        } finally { driver.close(); parent.deleteRecursively() }
    }

    @Test fun newRecordsStoreRelativePathsAndResolveAgainstEachCurrentRoot() {
        val driver = driver()
        val first = Store(driver, "/container-one/PocketAsk")
        val attachment = Attachment("photo", "Photo.jpg", "/container-one/PocketAsk/inputs/photo/original.jpg", "image/jpeg")
        val evidence = Evidence("image", attachment.id, attachment.name, null, "Saved flower description", null, emptyList(), previewImage = attachment.path)
        try {
            first.saveDraft(Draft(attachments = listOf(attachment)))
            first.saveSource(attachment)
            first.addEvidence(evidence)
            first.save(Answer("caption-reply", "Find flowers", listOf(attachment), text = "A flower [S1].", sources = listOf(evidence), status = "Completed"))
            assertContains(first.value("draft")!!, "inputs/photo/original.jpg")
            assertFalse(first.value("draft")!!.contains("container-one"))
            val second = Store(driver, "/container-two/PocketAsk")
            assertEquals("/container-two/PocketAsk/inputs/photo/original.jpg", second.draft().attachments.single().path)
            assertEquals(second.draft().attachments.single().path, second.evidence("photo", 0).single().previewImage)
            assertEquals(second.draft().attachments.single().path, second.history().single().sources.single().previewImage)
            assertFalse(first.value("history", "caption-reply")!!.contains("container-one"))
            assertEquals(second.draft().attachments.single().path, second.library("/container-two/PocketAsk").single().path)
            assertFalse(first.value("library", attachment.id)!!.contains("container-one"))
        } finally { driver.close() }
    }

    @Test fun savedPathsCannotEscapeTheAttachmentDirectory() {
        val paths = AssetPaths("/current/PocketAsk")
        assertFailsWith<IllegalArgumentException> { paths.resolve("inputs/photo/../other.jpg", "photo") }
        assertFailsWith<IllegalArgumentException> { paths.resolve("inputs/../original.jpg", "..") }
        assertEquals("/external/photo.jpg", paths.resolve("/external/photo.jpg", "photo"))
    }

    @Test fun migrationRecoversIndexedAndPendingImportsLostByOldNewChatFlowOnlyOnce() {
        val root = Files.createTempDirectory("pocketask-library-migration").toFile()
        val driver = driver()
        val db = Store(driver, root.path)
        val photo = Attachment("a".repeat(64), "Holiday.jpg", File(root, "inputs/${"a".repeat(64)}/original.jpg").path, "image/jpeg")
        val note = Attachment("b".repeat(64), "Unsaved note.md", File(root, "inputs/${"b".repeat(64)}/original.md").path, "text/markdown")
        listOf(photo, note).forEach { File(it.path).apply { parentFile.mkdirs(); writeText("content") } }
        db.addEvidence(Evidence("image", photo.id, photo.name, null, "", photo.path, List(256) { if (it == 0) 1f else 0f }))
        db.markPrepared(photo)
        try {
            val library = db.library(root.path)
            assertEquals(2, library.size)
            assertEquals("Holiday.jpg", library.first { it.id == photo.id }.name)
            assertTrue(library.first { it.id == photo.id }.prepared)
            assertFalse(library.first { it.id == note.id }.prepared)
            assertEquals("text/markdown", library.first { it.id == note.id }.type)
            db.removeSource(photo)
            assertTrue(File(photo.path).exists())
            assertEquals(listOf(note.id), Store(driver, root.path).library(root.path).map { it.id })
        } finally { driver.close(); root.deleteRecursively() }
    }
}
