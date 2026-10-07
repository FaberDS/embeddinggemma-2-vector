package dev.pocketask

import app.cash.sqldelight.db.*
import kotlinx.serialization.encodeToString
import kotlin.math.sin
import kotlin.test.*

internal object LegacyKnowledgeSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version = 1L
    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        driver.execute(null, "CREATE TABLE item(kind TEXT NOT NULL, id TEXT NOT NULL, json TEXT NOT NULL, PRIMARY KEY(kind,id))", 0)
        driver.execute(null, "CREATE TABLE evidence(attachment_id TEXT NOT NULL, id TEXT NOT NULL PRIMARY KEY, json TEXT NOT NULL)", 0)
        driver.execute(null, "CREATE INDEX evidence_attachment ON evidence(attachment_id)", 0)
        return QueryResult.Value(Unit)
    }
    override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> = error("Version 1 fixture")
}

internal fun seedLegacyKnowledge(driver: SqlDriver, root: String) {
    val store = Store(driver, root)
    val ready = Attachment("ready", "Old document.pdf", "$root/missing.pdf", "application/pdf")
    val pending = Attachment("pending", "Old memory.md", "$root/missing.md", "text/markdown", isMemory = true)
    store.saveSource(ready); store.saveSource(pending); store.markPrepared(ready)
    store.save(Answer("history", "Keep my conversation", listOf(ready), status = "Completed", text = "A saved reply"))
    val vector = normalize(List(256) { sin(it * 0.7).toFloat() })
    for (i in 0 until 129) {
        val image = i % 3 == 0
        val evidence = Evidence("vector-${i.toString().padStart(3, '0')}", if (i < 100) ready.id else pending.id,
            "Saved source", if (i < 100) i + 1 else null, if (image) "" else "Überblick 🌷 passage $i", if (image) "$root/missing-$i.jpg" else null, vector)
        driver.execute(null, "INSERT INTO evidence(attachment_id,id,json) VALUES(?,?,?)", 3) {
            bindString(0, evidence.attachmentId); bindString(1, evidence.id); bindString(2, json.encodeToString(evidence))
        }
    }
}

internal suspend fun verifyMigratedKnowledge(driver: SqlDriver, root: String) {
    val store = Store(driver, root)
    val stats = store.knowledgeStats()
    assertEquals(129L, stats.vectors); assertEquals(86L, stats.textVectors); assertEquals(43L, stats.imageVectors)
    assertEquals(100L, stats.searchableVectors); assertEquals(129L * 256 * 4, stats.rawVectorBytes)
    assertEquals("129 × 256", stats.shape); assertTrue(stats.databaseBytes > 0)
    assertEquals(encodedRecordBytes(driver), stats.indexBytes)
    assertEquals(normalize(List(256) { sin(it * 0.7).toFloat() }), stats.sample)
    assertEquals(stats.vectors, store.knowledgeStats().vectors)
    assertEquals("Keep my conversation", store.history().single().question)
    val original = store.evidence("ready", 0).first()
    store.addEvidence(original.copy(image = null, text = "A replacement 🌼"))
    val replaced = store.knowledgeStats()
    assertEquals(129L, replaced.vectors); assertEquals(87L, replaced.textVectors); assertEquals(42L, replaced.imageVectors)
    assertEquals(encodedRecordBytes(driver), replaced.indexBytes)
    store.removeSource(store.library(root).first { it.id == "pending" })
    val removed = store.knowledgeStats()
    assertEquals(100L, removed.vectors); assertEquals(100L, removed.searchableVectors)
    store.removeSource(store.library(root).single())
    val empty = store.knowledgeStats()
    assertEquals(0L, empty.vectors); assertEquals(0L, empty.indexBytes); assertEquals(0L, empty.rawVectorBytes)
    assertTrue(empty.sample.isEmpty()); assertEquals("0 × 256", empty.shape)
    assertEquals("A saved reply", store.history().single().text)
}

internal fun encodedRecordBytes(driver: SqlDriver): Long = driver.executeQuery(null, "SELECT COALESCE(SUM(length(CAST(json AS BLOB))),0) FROM evidence", { cursor ->
    cursor.next(); QueryResult.Value(cursor.getLong(0)!!)
}, 0).value
