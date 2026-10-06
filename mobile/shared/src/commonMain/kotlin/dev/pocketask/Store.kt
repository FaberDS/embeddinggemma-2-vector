package dev.pocketask

import app.cash.sqldelight.db.SqlDriver
import dev.pocketask.db.AppDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

internal val json = Json { ignoreUnknownKeys = true }

class Store(driver: SqlDriver) {
    private val database = AppDatabase(driver)
    private val queries = database.storeQueries
    fun value(kind: String, id: String = "value") = queries.get(kind, id).executeAsOneOrNull()
    fun put(kind: String, id: String = "value", value: String) = queries.put(kind, id, value)
    fun draft() = value("draft")?.let { json.decodeFromString<Draft>(it) } ?: Draft()
    fun saveDraft(draft: Draft) = put("draft", value = json.encodeToString(draft))
    fun history() = queries.list("history").executeAsList().map { json.decodeFromString<Answer>(it) }.map {
        if (it.status in listOf("Preparing", "Answering")) it.copy(status = "Interrupted", error = "The app stopped. Use again to resume.") else it
    }
    fun save(answer: Answer) = put("history", answer.id, json.encodeToString(answer))
    fun remove(answer: Answer) = queries.remove("history", answer.id)
    fun addEvidence(evidence: Evidence) = queries.putEvidence(evidence.attachmentId, evidence.id, json.encodeToString(evidence))
    fun evidence(attachment: String, offset: Long) = queries.evidencePage(attachment, offset).executeAsList().map { json.decodeFromString<Evidence>(it) }
    fun markPrepared(attachment: Attachment) = put("prepared-v1-256", attachment.id, "yes")
    fun prepared(attachment: Attachment) = value("prepared-v1-256", attachment.id) == "yes"
    fun discardEvidence(attachment: Attachment) {
        queries.removeEvidence(attachment.id)
        queries.remove("prepared-v1-256", attachment.id)
    }
    fun clean(attachments: List<Attachment>, keep: Set<String>, root: String) {
        attachments.filter { it.id !in keep }.distinctBy { it.id }.forEach {
            discardEvidence(it)
            val fs = FileSystem.SYSTEM
            val directory = "$root/inputs/${it.id}".toPath()
            if (fs.exists(directory)) fs.deleteRecursively(directory)
        }
    }
}
