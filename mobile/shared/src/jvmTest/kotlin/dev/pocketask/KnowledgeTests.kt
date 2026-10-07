package dev.pocketask

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.pocketask.db.AppDatabase
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class KnowledgeTests {
    @Test fun migrationPreservesOldVectorsAndHistoryAndBackfillsMoreThanOnePage() = runTest {
        val root = Files.createTempDirectory("knowledge-migration").toFile()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LegacyKnowledgeSchema.create(driver); seedLegacyKnowledge(driver, root.path)
            assertEquals(2L, AppDatabase.Schema.version)
            AppDatabase.Schema.migrate(driver, 1, AppDatabase.Schema.version)
            verifyMigratedKnowledge(driver, root.path)
        } finally { driver.close(); root.deleteRecursively() }
    }

    @Test fun emptyDatabaseHasNoVectorsAndAnHonestAllocationInsteadOfZeroDiskUsage() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            AppDatabase.Schema.create(driver)
            val stats = Store(driver).knowledgeStats()
            assertEquals(0L, stats.vectors); assertEquals(0L, stats.indexBytes); assertEquals(0L, stats.rawVectorBytes)
            assertEquals("0 × 256", stats.shape); assertFalse(stats.databaseOnDisk); assertTrue(stats.databaseBytes > 0)
        } finally { driver.close() }
    }

    @Test fun liveMetadataCountsActualDimensionsAndUnicodeBytesAndUpdatesReplacedRows() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            AppDatabase.Schema.create(driver)
            val store = Store(driver)
            val source = Attachment("source", "Never opened.md", "/missing/file.md", "text/markdown", isMemory = true)
            store.saveSource(source)
            val evidence = Evidence("v", source.id, source.name, null, "Überblick 🌷", null, listOf(.1f, .2f, .3f))
            store.addEvidence(evidence)
            val pending = store.knowledgeStats()
            assertEquals(1L, pending.vectors); assertEquals(0L, pending.searchableVectors); assertEquals(12L, pending.rawVectorBytes)
            assertEquals("1 × 3", pending.shape); assertEquals(encodedRecordBytes(driver), pending.indexBytes)
            store.markPrepared(source); store.addEvidence(evidence.copy(image = "/missing/picture.jpg", text = ""))
            val replaced = store.knowledgeStats()
            assertEquals(1L, replaced.vectors); assertEquals(1L, replaced.imageVectors); assertEquals(0L, replaced.textVectors)
            assertEquals(1L, replaced.searchableVectors); assertEquals(encodedRecordBytes(driver), replaced.indexBytes)
            store.addEvidence(evidence.copy(id = "caption", text = "A saved flower description", previewImage = "/missing/picture.jpg"))
            val captioned = store.knowledgeStats()
            assertEquals(2L, captioned.vectors); assertEquals(1L, captioned.imageVectors); assertEquals(1L, captioned.textVectors)
            assertEquals(2L, captioned.searchableVectors); assertEquals(encodedRecordBytes(driver), captioned.indexBytes)
        } finally { driver.close() }
    }
}
