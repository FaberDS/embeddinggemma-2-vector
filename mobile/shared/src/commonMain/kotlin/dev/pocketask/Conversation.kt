package dev.pocketask

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

fun Answer.sentAt(): Long? = createdAt ?: id.substringBefore('-').toLongOrNull()

fun messageTime(timestamp: Long?, now: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    if (timestamp == null) return ""
    val date = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(zone)
    val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
    if (date.date != today) return "${date.date} · ${date.hour.toString().padStart(2, '0')}:${date.minute.toString().padStart(2, '0')}"
    val minutes = ((now - timestamp).coerceAtLeast(0) / 60_000)
    return when {
        minutes == 0L -> "Just now"
        minutes < 60 -> "$minutes min ago"
        else -> "${minutes / 60} hr ago"
    }
}

fun conversationTurns(state: UiState, id: String? = state.draft.conversationId): List<Answer> {
    if (id == null) return emptyList()
    return (state.history + listOfNotNull(state.result)).filter { it.conversationId == id }
        .associateBy { it.id }.values.sortedWith(compareBy<Answer> { it.sentAt() ?: 0 }.thenBy { it.id })
}

fun conversationSummaries(history: List<Answer>): List<Answer> = history.groupBy { it.conversationId }.values
    .map { turns -> turns.maxWith(compareBy<Answer> { it.sentAt() ?: 0 }.thenBy { it.id }) }
    .sortedWith(compareByDescending<Answer> { it.sentAt() ?: 0 }.thenByDescending { it.id })

/** Earlier replies are bounded context, and their citation IDs are never reused. */
fun conversationContext(turns: List<Answer>, budget: Int = 2400): String {
    var remaining = budget
    val blocks = mutableListOf<String>()
    turns.filter { it.status == "Completed" && it.text.isNotBlank() }.takeLast(4).asReversed().forEach { turn ->
        val reply = turn.text.replace(Regex("\\[S\\d+\\]"), "").take(1500)
        val block = "User: ${turn.question.take(500)}\nAssistant: $reply"
        if (block.length <= remaining) { blocks += block; remaining -= block.length + 2 }
    }
    return blocks.asReversed().joinToString("\n\n")
}

data class SourceLink(val citations: List<Int>, val title: String, val path: String?, val page: Int?, val isImage: Boolean) {
    val label get() = citations.joinToString(" ") { "[S$it]" } + " " + title + (page?.let { " · page $it" } ?: "")
}

/** PDF page pixels remain document links; each reply resolves its own attachments. */
fun sourceLinks(answer: Answer): List<SourceLink> {
    if (answer.status != "Completed") return emptyList()
    val cited = validCitations(answer.text, answer.sources).second.map { it.id }.toSet()
    val links = mutableListOf<SourceLink>()
    answer.sources.forEachIndexed { index, source ->
        if (source.id !in cited) return@forEachIndexed
        val attachment = answer.attachments.firstOrNull { it.id == source.attachmentId }
        val image = attachment?.isImage ?: (source.displayImage != null)
        val path = if (image) source.displayImage ?: attachment?.path else attachment?.path
        val existing = links.indexOfFirst { it.path == path && it.title == source.name && it.page == source.page && it.isImage == image }
        if (existing >= 0) links[existing] = links[existing].copy(citations = links[existing].citations + (index + 1))
        else links += SourceLink(listOf(index + 1), source.name, path, source.page, image)
    }
    return links
}
