package dev.pocketask

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.pocketask.db.AppDatabase
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test

class CheckpointStoreTests {
    @Test fun sqliteReopenPreservesRecognizedTextAndHybridSearch() = runTest {
        val root = Files.createTempDirectory("ocr-disk").toFile()
        fun open() = JdbcSqliteDriver("jdbc:sqlite:${root.path}/index.db")
        try {
            val first = open()
            try { AppDatabase.Schema.create(first); seedOcr(Store(first, root.path), root.path) } finally { first.close() }
            val second = open()
            try { verifyOcr(Store(second, root.path), root.path) } finally { second.close() }
        } finally { root.deleteRecursively() }
    }
    @Test fun sqliteReopenPreservesCheckpointsAndSearchablePages() = runTest {
        val root = Files.createTempDirectory("checkpoint-disk").toFile()
        fun open() = JdbcSqliteDriver("jdbc:sqlite:${root.path}/index.db")
        try {
            val first = open()
            try { AppDatabase.Schema.create(first); seedCheckpoint(Store(first, root.path), root.path) } finally { first.close() }
            val second = open()
            try { verifyCheckpoint(Store(second, root.path), root.path) } finally { second.close() }
        } finally { root.deleteRecursively() }
    }
}
