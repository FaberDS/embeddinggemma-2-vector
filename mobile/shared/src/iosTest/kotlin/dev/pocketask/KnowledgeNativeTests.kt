package dev.pocketask

import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import dev.pocketask.db.AppDatabase
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.*

class KnowledgeNativeTests {
    @Test fun nativeSqliteReopenPreservesRecognizedTextAndHybridSearch() = runTest {
        val root = (NSTemporaryDirectory() + "ocr-${Random.nextLong()}").toPath()
        FileSystem.SYSTEM.createDirectories(root)
        fun open() = NativeSqliteDriver(AppDatabase.Schema, "index.db", onConfiguration = {
            it.copy(extendedConfig = it.extendedConfig.copy(basePath = root.toString()))
        })
        try {
            val first = open()
            try { seedOcr(Store(first, root.toString()), root.toString()) } finally { first.close() }
            val second = open()
            try { verifyOcr(Store(second, root.toString()), root.toString()) } finally { second.close() }
        } finally { FileSystem.SYSTEM.deleteRecursively(root) }
    }
    @Test fun nativeSqliteReopenPreservesCheckpointsAndSearchablePages() = runTest {
        val root = (NSTemporaryDirectory() + "checkpoint-${Random.nextLong()}").toPath()
        FileSystem.SYSTEM.createDirectories(root)
        fun open() = NativeSqliteDriver(AppDatabase.Schema, "index.db", onConfiguration = {
            it.copy(extendedConfig = it.extendedConfig.copy(basePath = root.toString()))
        })
        try {
            val first = open()
            try { seedCheckpoint(Store(first, root.toString()), root.toString()) } finally { first.close() }
            val second = open()
            try { verifyCheckpoint(Store(second, root.toString()), root.toString()) } finally { second.close() }
        } finally { FileSystem.SYSTEM.deleteRecursively(root) }
    }

    @Test fun nativeDriverUpgradesAnExistingDatabaseAndMeasuresItsRealFiles() = runTest {
        val root = (NSTemporaryDirectory() + "knowledge-${Random.nextLong()}").toPath()
        FileSystem.SYSTEM.createDirectories(root)
        fun open(schema: SqlSchema<QueryResult.Value<Unit>>) = NativeSqliteDriver(schema, "pocketask.db", onConfiguration = {
            it.copy(extendedConfig = it.extendedConfig.copy(basePath = root.toString()))
        })
        try {
            val legacy = open(LegacyKnowledgeSchema)
            try { seedLegacyKnowledge(legacy, root.toString()) } finally { legacy.close() }
            val upgraded = open(AppDatabase.Schema)
            try {
                val stats = Store(upgraded, root.toString()).knowledgeStats()
                assertTrue(stats.databaseOnDisk); assertTrue(stats.databaseBytes > 0)
                verifyMigratedKnowledge(upgraded, root.toString())
            } finally { upgraded.close() }
        } finally { FileSystem.SYSTEM.deleteRecursively(root) }
    }
}
