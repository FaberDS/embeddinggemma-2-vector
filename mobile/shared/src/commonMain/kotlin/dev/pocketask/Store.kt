package dev.pocketask

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.QueryResult
import dev.pocketask.db.AppDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal val json = Json { ignoreUnknownKeys = true }

class Store(private val driver: SqlDriver, root: String? = null) {
    private val database = AppDatabase(driver)
    private val queries = database.storeQueries
    private val assets = AssetPaths(root)
    private fun stored(attachment: Attachment) = attachment.copy(path = assets.relative(attachment.path, attachment.id))
    private fun loaded(attachment: Attachment) = attachment.copy(path = assets.resolve(attachment.path, attachment.id), prepared = prepared(attachment), searchable = searchable(attachment))
    private fun stored(evidence: Evidence) = evidence.copy(image = evidence.image?.let { assets.relative(it, evidence.attachmentId) }, previewImage = evidence.previewImage?.let { assets.relative(it, evidence.attachmentId) })
    private fun loaded(evidence: Evidence) = evidence.copy(image = evidence.image?.let { assets.resolve(it, evidence.attachmentId) }, previewImage = evidence.previewImage?.let { assets.resolve(it, evidence.attachmentId) })
    private fun stored(answer: Answer) = answer.copy(attachments = answer.attachments.map(::stored), sources = answer.sources.map(::stored))
    private fun loaded(answer: Answer) = answer.copy(attachments = answer.attachments.map(::loaded), sources = answer.sources.map(::loaded))
    fun value(kind: String, id: String = "value") = queries.get(kind, id).executeAsOneOrNull()
    fun put(kind: String, id: String = "value", value: String) = queries.put(kind, id, value)
    fun draft() = value("draft")?.let { json.decodeFromString<Draft>(it) }?.let { it.copy(attachments = it.attachments.map(::loaded)) } ?: Draft()
    fun saveDraft(draft: Draft) = put("draft", value = json.encodeToString(draft.copy(attachments = draft.attachments.map(::stored))))
    fun memoryDraft() = value("memory-draft")?.let { json.decodeFromString<MemoryDraft>(it).copy(status = "Ready", error = null) }
    fun saveMemoryDraft(draft: MemoryDraft?) {
        if (draft == null || draft.status == "Saved") queries.remove("memory-draft", "value")
        else put("memory-draft", value = json.encodeToString(draft))
    }
    fun saveSource(attachment: Attachment) = put("library", attachment.id, json.encodeToString(stored(attachment)))
    fun removeSource(attachment: Attachment) { queries.remove("library", attachment.id); discardEvidence(attachment); queries.removeKind("image-description:${attachment.id}") }
    fun imageDescription(source: Evidence): ImageDescription? = value("image-description:${source.attachmentId}", source.id)?.let { json.decodeFromString<ImageDescription>(it) }
    fun saveImageDescription(source: Evidence, description: ImageDescription) = put("image-description:${source.attachmentId}", source.id, json.encodeToString(description))
    fun library(root: String): List<Attachment> {
        if (value("library-migrated-v1") != "yes") {
            val known = (draft().attachments + history().flatMap { it.attachments }).distinctBy { it.id }
            val fs = FileSystem.SYSTEM
            val inputs = "$root/inputs".toPath()
            // Recover imports lost by the earlier New chat flow, including ones never used in a reply.
            val recovered = if (fs.exists(inputs)) fs.list(inputs).mapNotNull { directory ->
                val id = directory.name
                if (id.length != 64 || id.any { it !in "0123456789abcdef" } || !fs.metadata(directory).isDirectory) return@mapNotNull null
                val original = fs.list(directory).firstOrNull { it.name.startsWith("original.") } ?: return@mapNotNull null
                val type = when (original.name) {
                    "original.pdf" -> "application/pdf"
                    "original.md" -> "text/markdown"
                    "original.txt" -> "text/plain"
                    "original.jpg" -> "image/jpeg"
                    else -> return@mapNotNull null
                }
                Attachment(id, evidence(id, 0).firstOrNull()?.name ?: "Recovered source.${original.name.substringAfter('.')}", original.toString(), type)
            } else emptyList()
            (known + recovered).distinctBy { it.id }.forEach(::saveSource)
            put("library-migrated-v1", value = "yes")
        }
        return queries.list("library").executeAsList().map { loaded(json.decodeFromString<Attachment>(it)) }.map { source ->
            // Older visual indexes need descriptions once, during ingestion, never during a question.
            if (source.needsImageDescriptions && value("prepared-v1-256", source.id) == "yes" && value("described-v1", source.id) != "yes") {
                queries.remove("prepared-v1-256", source.id)
                source.copy(prepared = false)
            } else if (source.needsImageDescriptions && value("prepared-v1-256", source.id) == "yes" && value("ocr-v1", source.id) != "yes") {
                // Keep existing vectors searchable while the one-time OCR upgrade runs.
                source.copy(prepared = false, searchable = true)
            } else source
        }
    }
    fun history() = queries.list("history").executeAsList().map { loaded(json.decodeFromString<Answer>(it)) }.map {
        if (it.status in listOf("Preparing", "Answering")) it.copy(status = "Interrupted", error = "The app stopped. Use again to resume.") else it
    }
    fun save(answer: Answer) = put("history", answer.id, json.encodeToString(stored(answer)))
    fun remove(answer: Answer) = queries.remove("history", answer.id)
    fun addEvidence(evidence: Evidence) {
        val encoded = json.encodeToString(stored(evidence))
        queries.putEvidence(evidence.attachmentId, evidence.id, encoded, if (evidence.image == null) "text" else "image", evidence.vector.size.toLong(), encoded.encodeToByteArray().size.toLong())
        if (evidence.image != null && evidence.text.isNotBlank()) put("searchable-image:${evidence.attachmentId}", evidence.id, "yes")
    }
    fun evidence(attachment: String, offset: Long) = queries.evidencePage(attachment, offset).executeAsList().map { loaded(json.decodeFromString<Evidence>(it)) }
    fun markPrepared(attachment: Attachment) {
        queries.transaction {
            if (attachment.needsImageDescriptions) {
                put("described-v1", attachment.id, "yes")
                put("ocr-v1", attachment.id, "yes")
            }
            put("prepared-v1-256", attachment.id, "yes")
            queries.removeKind("index-pages:${attachment.id}")
        }
    }
    fun prepared(attachment: Attachment) = value("prepared-v1-256", attachment.id) == "yes" &&
        (!attachment.needsImageDescriptions || value("ocr-v1", attachment.id) == "yes")
    fun searchable(attachment: Attachment) = value("prepared-v1-256", attachment.id) == "yes" || value("searchable-v1", attachment.id) == "yes"
    fun checkpoint(attachment: Attachment): IndexCheckpoint? = value("index-checkpoint-v1", attachment.id)?.let { json.decodeFromString<IndexCheckpoint>(it) }
    fun beginIndex(attachment: Attachment, pages: Int): IndexCheckpoint {
        checkpoint(attachment)?.let { return it }
        val upgrade = attachment.needsImageDescriptions && value("prepared-v1-256", attachment.id) == "yes" && value("described-v1", attachment.id) == "yes"
        val checkpoint = if (upgrade) IndexCheckpoint(pages, pages, pages, pages, pages) else IndexCheckpoint(pages)
        queries.transaction { if (!upgrade) discardEvidence(attachment); saveCheckpoint(attachment, checkpoint) }
        return checkpoint
    }
    fun saveCheckpoint(attachment: Attachment, checkpoint: IndexCheckpoint) = put("index-checkpoint-v1", attachment.id, json.encodeToString(checkpoint))
    fun commitPage(attachment: Attachment, checkpoint: IndexCheckpoint, evidence: List<Evidence>) {
        queries.transaction {
            evidence.forEach(::addEvidence)
            if (evidence.any { it.text.isNotBlank() }) put("searchable-v1", attachment.id, "yes")
            saveCheckpoint(attachment, checkpoint)
        }
    }
    fun pageInput(attachment: Attachment, page: Int): PageInput? = value("index-pages:${attachment.id}", page.toString())?.let {
        json.decodeFromString<PageInput>(it).let { input -> input.copy(imagePath = input.imagePath?.let { path -> assets.resolve(path, attachment.id) }) }
    }
    fun savePageInput(attachment: Attachment, page: Int, input: PageInput) = put("index-pages:${attachment.id}", page.toString(),
        json.encodeToString(input.copy(imagePath = input.imagePath?.let { assets.relative(it, attachment.id) })))
    fun imageEvidence(attachment: Attachment, page: Int): Evidence? = queries.getEvidence("${attachment.id}-$page-image").executeAsOneOrNull()?.let { loaded(json.decodeFromString<Evidence>(it)) }
    fun discardEvidence(attachment: Attachment) {
        queries.removeEvidence(attachment.id)
        queries.remove("prepared-v1-256", attachment.id)
        queries.remove("described-v1", attachment.id)
        queries.remove("ocr-v1", attachment.id)
        queries.remove("searchable-v1", attachment.id)
        queries.remove("index-checkpoint-v1", attachment.id)
        queries.removeKind("index-pages:${attachment.id}")
        queries.removeKind("searchable-image:${attachment.id}")
    }
    suspend fun knowledgeStats(): KnowledgeStats {
        // Older indexes get small metadata fields once, without loading models or source files.
        var after = ""
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = queries.legacyEvidence(after).executeAsList()
            if (batch.isEmpty()) break
            queries.transaction {
                batch.forEach { row ->
                    val evidence = json.decodeFromString<Evidence>(row.json)
                    queries.updateEvidenceMetadata(if (evidence.image == null) "text" else "image", evidence.vector.size.toLong(), row.json.encodeToByteArray().size.toLong(), row.id)
                }
            }
            after = batch.last().id
        }
        currentCoroutineContext().ensureActive()
        val counts = queries.knowledgeStats().executeAsOne()
        val sample = queries.embeddingSample().executeAsOneOrNull()?.let { json.decodeFromString<Evidence>(it).vector }.orEmpty()
        val file = driver.executeQuery(null, "PRAGMA database_list", { cursor ->
            var main: String? = null
            while (cursor.next().value) if (cursor.getString(1) == "main") main = cursor.getString(2)
            QueryResult.Value(main?.takeIf { it.isNotBlank() })
        }, 0).value
        val fs = FileSystem.SYSTEM
        val onDisk = file?.let { fs.metadataOrNull(it.toPath())?.size } != null
        val databaseBytes = if (onDisk) listOf(file, "$file-wal", "$file-shm", "$file-journal").sumOf { fs.metadataOrNull(it.toPath())?.size ?: 0 }
            else databasePragma("page_count") * databasePragma("page_size")
        return KnowledgeStats(counts.vectors, counts.text_vectors, counts.image_vectors, counts.searchable_vectors,
            counts.index_bytes, counts.raw_vector_bytes, counts.min_dimensions, counts.max_dimensions, databaseBytes, onDisk, sample)
    }
    private fun databasePragma(name: String): Long = driver.executeQuery(null, "PRAGMA $name", { cursor ->
        QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0 else 0)
    }, 0).value
    fun clean(attachments: List<Attachment>, keep: Set<String>, root: String) {
        attachments.filter { it.id !in keep }.distinctBy { it.id }.forEach {
            discardEvidence(it)
            queries.removeKind("image-description:${it.id}")
            val fs = FileSystem.SYSTEM
            val directory = "$root/inputs/${it.id}".toPath()
            if (fs.exists(directory)) fs.deleteRecursively(directory)
        }
    }
}

/** Only app-owned input files are rebased; iOS container UUIDs are not durable. */
internal class AssetPaths(private val root: String?) {
    private fun relativeInput(path: String, owner: String): String? {
        if (root == null) return null
        require(owner.isNotBlank() && owner !in listOf(".", "..") && '/' !in owner && '\\' !in owner)
        val prefix = "inputs/$owner/"
        val file = when {
            path.startsWith(prefix) -> path.removePrefix(prefix)
            path.startsWith('/') && "/$prefix" in path -> path.substringAfterLast("/$prefix")
            else -> return null
        }
        require(file.isNotBlank() && file !in listOf(".", "..") && '/' !in file && '\\' !in file) { "Invalid saved attachment path." }
        return prefix + file
    }
    fun relative(path: String, owner: String) = relativeInput(path, owner) ?: path
    fun resolve(path: String, owner: String) = relativeInput(path, owner)?.let { "${root!!.trimEnd('/')}/$it" } ?: path
}
