package dev.pocketask

import kotlinx.serialization.Serializable
import kotlin.math.ceil

@Serializable
data class IndexCheckpoint(val pages: Int, val text: Int = 0, val images: Int = 0, val descriptions: Int = 0, val captions: Int = 0, val ocr: Int = 0) {
    val completed get() = text + images + descriptions + captions + ocr
}

data class IndexingProgress(val phase: String, val completed: Int, val total: Int, val completedUnits: Long, val totalUnits: Long,
    val remainingSeconds: Long? = null, val background: Boolean = false) {
    val fraction get() = if (total == 0) 0f else (completed.toFloat() / total).coerceIn(0f, 1f)
}

/** Estimate only the current phase, using work completed during this run. */
internal class IndexEstimate {
    private var phase = ""
    private var start = 0L
    private var baseline = 0
    fun remaining(phase: String, completed: Int, total: Int, now: Long): Long? {
        if (this.phase != phase) { this.phase = phase; start = now; baseline = completed }
        val samples = completed - baseline
        if (samples < 2 || now <= start || completed >= total) return null
        return ceil((now - start).toDouble() / samples * (total - completed) / 1000).toLong()
    }
}

interface IndexingPermit { fun ready(background: Boolean); fun expired() }
interface IndexingTasks {
    fun start(userInitiated: Boolean, callback: IndexingPermit)
    fun progress(value: IndexingProgress)
    fun finish(success: Boolean)
    fun schedule()
}

/** Native PDF readers can extract text without rendering a page image. */
interface DocumentInputs {
    fun readTextPage(attachment: Attachment, page: Int, callback: PageResult)
    fun readImagePage(attachment: Attachment, page: Int, callback: PageResult)
}
