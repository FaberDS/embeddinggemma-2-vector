package dev.pocketask

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.time.TimeSource

@Serializable
data class RequestTiming(val timestamp: Long, val elapsedMs: Long, val status: String, val detail: String = "")

/** One event per request phase, never per token. Elapsed times use a monotonic clock. */
internal class RequestTrace(private val now: () -> Long, clock: TimeSource = TimeSource.Monotonic) {
    private val start = clock.markNow()
    fun event(status: String, detail: String = "") = RequestTiming(now(), start.elapsedNow().inWholeMilliseconds, status, detail)
}

internal fun timingTimestamp(timestamp: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val time = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(zone)
    return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}:${time.second.toString().padStart(2, '0')}.${(time.nanosecond / 1_000_000).toString().padStart(3, '0')}"
}

internal fun timingDuration(ms: Long): String {
    val duration = ms.coerceAtLeast(0)
    return "${duration / 1000}.${(duration % 1000).toString().padStart(3, '0')} s"
}

internal fun telemetryText(answer: Answer, totalMs: Long, active: Boolean, zone: TimeZone = TimeZone.currentSystemDefault()): String = buildString {
    appendLine("Locune · request telemetry")
    appendLine("Request: ${answer.id}")
    appendLine("Status: ${answer.status}${if (active) " (in progress)" else ""}")
    appendLine("Model: ${modelSpecs.firstOrNull { it.id == answer.modelId }?.title ?: answer.modelId ?: "Not recorded"}")
    appendLine("Time zone: ${zone.id}")
    appendLine("Total elapsed: ${timingDuration(totalMs)}")
    answer.timings.firstOrNull { it.status == "First text displayed" }?.let { appendLine("First text: ${timingDuration(it.elapsedMs)} since request started") }
    answer.error?.let { appendLine("Error: $it") }
    appendLine()
    answer.timings.forEachIndexed { index, event ->
        val date = Instant.fromEpochMilliseconds(event.timestamp).toLocalDateTime(zone).date
        append("$date ${timingTimestamp(event.timestamp, zone)} | +${timingDuration(event.elapsedMs)} | ${event.status}")
        val next = answer.timings.getOrNull(index + 1)
        if (next != null || active) {
            append(" | duration: ${timingDuration((next?.elapsedMs ?: totalMs) - event.elapsedMs)}")
            if (next == null) append(" (running)")
        }
        appendLine()
        if (event.detail.isNotBlank()) appendLine("  ${event.detail}")
    }
}.trimEnd()
