package dev.pocketask

import kotlinx.datetime.TimeZone
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class RequestTimingTests {
    @Test fun elapsedTimeDoesNotFollowWallClockChanges() {
        var wallTime = 100_000L
        val clock = TestTimeSource()
        val trace = RequestTrace({ wallTime }, clock)
        assertEquals(0L, trace.event("Started").elapsedMs)
        clock += 1234.milliseconds
        wallTime -= 30_000
        val event = trace.event("First text", "GPU")
        assertEquals(1234L, event.elapsedMs)
        assertEquals(70_000L, event.timestamp)
        assertEquals("GPU", event.detail)
        assertEquals("1.234 s", timingDuration(event.elapsedMs))
        assertEquals("00:00:01.234", timingTimestamp(1234, TimeZone.UTC))
        assertEquals("02:00:01.234", timingTimestamp(1234, TimeZone.of("+02:00")))
    }

    @Test fun olderRepliesHaveNoInventedTimings() {
        val answer = json.decodeFromString<Answer>("""{"id":"old","question":"Hello","attachments":[],"status":"Completed"}""")
        assertTrue(answer.timings.isEmpty())
    }

    @Test fun copiedTelemetryIncludesDatesAndDurationsAndAnUnfinishedSnapshotIsLabelled() {
        val answer = Answer("request-1", "Private question", emptyList(), text = "Private answer", status = "Answering", modelId = "answer", timings = listOf(
            RequestTiming(0, 0, "Loading answer model", "Gemma 4 E2B IT"),
            RequestTiming(1200, 1200, "Waiting for first text", "GPU · 1800 prompt characters"),
            RequestTiming(3200, 3200, "First text displayed")))
        val running = telemetryText(answer, 5000, true, TimeZone.UTC)
        assertContains(running, "Status: Answering (in progress)")
        assertContains(running, "Time zone: UTC")
        assertContains(running, "Total elapsed: 5.000 s")
        assertContains(running, "First text: 3.200 s since request started")
        assertContains(running, "1970-01-01 00:00:01.200 | +1.200 s | Waiting for first text | duration: 2.000 s")
        assertContains(running, "First text displayed | duration: 1.800 s (running)")
        assertContains(running, "GPU · 1800 prompt characters")
        assertFalse(running.contains(answer.question)); assertFalse(running.contains(answer.text))
        val completed = telemetryText(answer.copy(status = "Completed", timings = answer.timings + RequestTiming(5000, 5000, "Completed")), 5000, false, TimeZone.UTC)
        assertContains(completed, "Status: Completed")
        assertFalse(completed.contains("(running)"))
        assertTrue(completed.endsWith("1970-01-01 00:00:05.000 | +5.000 s | Completed"))
    }
}
