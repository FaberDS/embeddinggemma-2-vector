package dev.pocketask

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.pocketask.db.AppDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import java.nio.file.Files
import kotlin.test.*

class DownloadTests {
    private val content = "A verified model fixture".encodeToByteArray()
    private val spec = ModelSpec("fixture", "Fixture", "fixture.litertlm", "https://example.test/model", content.size.toLong(), content.toByteString().sha256().hex())
    private fun database(): Store {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return Store(driver)
    }
    private val inputs = object : PlatformInputs {
        override fun pick(images: Boolean, callback: ImportResult) = callback.success()
        override fun pageCount(attachment: Attachment, callback: CountResult) = callback.success(1)
        override fun readPage(attachment: Attachment, page: Int, callback: PageResult) = callback.success(PageInput("", null))
        override fun open(path: String, page: Int) {}
        override fun freeBytes() = Long.MAX_VALUE
        override fun canDownload(cellular: Boolean) = true
        override fun now() = 1L
    }
    @Test fun nestedVoiceAssetsCreateDirectoriesAndAreVerifiedBeforeUse() = runTest {
        val root = Files.createTempDirectory("speech-download").toFile()
        val nested = spec.copy(id = "speech-fixture", filename = "supertonic/voice_styles/F1.json")
        val manager = ModelManager(root.path, database(), inputs, catalog = listOf(nested)) { HttpClient(MockEngine { respond(content) }) }
        try {
            manager.download(nested, false)
            assertContentEquals(content, java.io.File(manager.path(nested)).readBytes())
            assertTrue(manager.states.value.getValue(nested.id).installed)
        } finally { root.deleteRecursively() }
    }

    @Test fun partialDownloadResumesWithoutTruncatingTheExistingBytes() = runTest {
        val root = Files.createTempDirectory("pocketask-download").toFile()
        val partial = java.io.File(root, "models/${spec.filename}.part").apply { parentFile.mkdirs(); writeBytes(content.take(7).toByteArray()) }
        val db = database()
        val manager = ModelManager(root.path, db, inputs) {
            HttpClient(MockEngine { request ->
                assertEquals("bytes=7-", request.headers[HttpHeaders.Range])
                respond(content.drop(7).toByteArray(), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 7-${content.lastIndex}/${content.size}"))
            })
        }
        try {
            manager.download(spec, cellular = false)
            assertContentEquals(content, java.io.File(manager.path(spec)).readBytes())
            assertFalse(partial.exists())
            assertTrue(manager.states.value.getValue(spec.id).installed)
            assertEquals(spec.sha256, db.value("model", spec.id))
        } finally { root.deleteRecursively() }
    }
    @Test fun serverIgnoringRangeSafelyRestartsTheDownload() = runTest {
        val root = Files.createTempDirectory("pocketask-download").toFile()
        java.io.File(root, "models/${spec.filename}.part").apply { parentFile.mkdirs(); writeBytes(content.take(7).toByteArray()) }
        val manager = ModelManager(root.path, database(), inputs) { HttpClient(MockEngine { respond(content) }) }
        try { manager.download(spec, false); assertContentEquals(content, java.io.File(manager.path(spec)).readBytes()) }
        finally { root.deleteRecursively() }
    }
    @Test fun corruptedDownloadIsRejectedAndRemovedBeforeItCanBeLoaded() = runTest {
        val root = Files.createTempDirectory("pocketask-download").toFile()
        val broken = content.copyOf().apply { this[0] = 0 }
        val manager = ModelManager(root.path, database(), inputs) { HttpClient(MockEngine { respond(broken) }) }
        try {
            assertFailsWith<IllegalStateException> { manager.download(spec, false) }
            assertFalse(manager.states.value.getValue(spec.id).installed)
            assertFalse(java.io.File(manager.path(spec)).exists())
            assertFalse(java.io.File(manager.path(spec) + ".part").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun estimateUsesRecentSpeedAndResetsAfterSuspension() {
        val estimate = DownloadEstimate(0, 100)
        assertNull(estimate.state(1100, 100, 500).remainingSeconds)
        assertEquals(4, estimate.state(1100, 300, 1000).remainingSeconds)
        assertEquals(200.0, estimate.state(1100, 500, 2000).bytesPerSecond)
        assertNull(estimate.state(1100, 600, 9000).remainingSeconds)
        assertNull(estimate.state(1100, 600, 10000).remainingSeconds)
        assertEquals("About 2 min left", formatRemaining(61))
    }

    private class NativeFixture(var result: TransferState) : ModelTransfers {
        var cancelled = false
        override fun copyReserve(spec: ModelSpec) = 0L
        override fun start(spec: ModelSpec, cellular: Boolean) {}
        override fun snapshot(spec: ModelSpec) = result
        override fun cancel(spec: ModelSpec) { cancelled = true; result = TransferState("Paused") }
    }

    @Test fun completedBackgroundTransferIsVerifiedAndRecoveredOnNextLaunch() = runTest {
        val root = Files.createTempDirectory("pocketask-native").toFile()
        val transfer = java.io.File(root, "models/${spec.filename}.transfer").apply { parentFile.mkdirs(); writeBytes(content) }
        val native = NativeFixture(TransferState("Downloaded", content.size.toLong(), transfer.path))
        val db = database()
        val manager = ModelManager(root.path, db, inputs, native, listOf(spec))
        try {
            manager.refreshTransfers()
            assertContentEquals(content, java.io.File(manager.path(spec)).readBytes())
            assertTrue(native.cancelled)
            assertFalse(transfer.exists())
            assertTrue(ModelManager(root.path, db, inputs, native, listOf(spec)).states.value.getValue(spec.id).installed)
        } finally { root.deleteRecursively() }
    }

    @Test fun interruptedVerificationRecoversACompletePartialFile() = runTest {
        val root = Files.createTempDirectory("pocketask-native").toFile()
        java.io.File(root, "models/${spec.filename}.part").apply { parentFile.mkdirs(); writeBytes(content) }
        val native = NativeFixture(TransferState())
        val manager = ModelManager(root.path, database(), inputs, native, listOf(spec))
        try { manager.refreshTransfers(); assertTrue(manager.states.value.getValue(spec.id).installed) }
        finally { root.deleteRecursively() }
    }

    @Test fun backgroundChecksumFailureRemainsVisibleAndCannotInstallAModel() = runTest {
        val root = Files.createTempDirectory("pocketask-native").toFile()
        val transfer = java.io.File(root, "models/${spec.filename}.transfer").apply { parentFile.mkdirs(); writeBytes(content.copyOf().apply { this[0] = 0 }) }
        val native = NativeFixture(TransferState("Downloaded", content.size.toLong(), transfer.path))
        val manager = ModelManager(root.path, database(), inputs, native, listOf(spec))
        try {
            manager.refreshTransfers(); manager.refreshTransfers()
            assertFalse(manager.states.value.getValue(spec.id).installed)
            assertContains(manager.states.value.getValue(spec.id).error!!, "verification failed")
            assertFalse(java.io.File(manager.path(spec)).exists())
        } finally { root.deleteRecursively() }
    }
}
