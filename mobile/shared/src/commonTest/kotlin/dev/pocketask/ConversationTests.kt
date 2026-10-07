package dev.pocketask

import kotlinx.datetime.TimeZone
import kotlin.test.*
import kotlin.time.Instant

class ConversationTests {
    private fun time(value: String) = Instant.parse(value).toEpochMilliseconds()

    @Test fun timestampsUseRelativeTimeTodayAndAbsoluteTimeAcrossLocalMidnight() {
        val now = time("2026-10-07T10:00:00Z")
        assertEquals("Just now", messageTime(now - 30_000, now, TimeZone.UTC))
        assertEquals("5 min ago", messageTime(now - 300_000, now, TimeZone.UTC))
        assertEquals("2 hr ago", messageTime(now - 7_200_000, now, TimeZone.UTC))
        assertEquals("", messageTime(null, now, TimeZone.UTC))
        val midnight = time("2026-10-06T22:01:00Z")
        val yesterday = time("2026-10-06T21:59:00Z")
        assertEquals("2026-10-06 · 23:59", messageTime(yesterday, midnight, TimeZone.of("Europe/Vienna")))
        assertEquals("2 min ago", messageTime(yesterday, midnight, TimeZone.UTC))
        assertEquals("Just now", messageTime(now + 30_000, now, TimeZone.UTC))
    }

    @Test fun liveRepliesReplaceSavedCopiesAndOtherChatsStayOutOfTheConversation() {
        val first = Answer("one", "First", emptyList(), status = "Completed", conversationId = "chat", createdAt = 100)
        val second = first.copy(id = "two", question = "Second", createdAt = 200, text = "Saved partial")
        val other = first.copy(id = "other", conversationId = "other", createdAt = 300)
        val state = UiState(draft = Draft(conversationId = "chat"), history = listOf(second, other, first), result = second.copy(text = "Live text"))
        assertEquals(listOf("one", "two"), conversationTurns(state).map { it.id })
        assertEquals("Live text", conversationTurns(state).last().text)
        assertEquals(listOf("other", "two"), conversationSummaries(state.history).map { it.id })
        assertTrue(conversationTurns(state.copy(draft = Draft())).isEmpty())
    }

    @Test fun sourceLinksResolveOriginalDocumentsAndRetainAllCitationNumbers() {
        val pdf = Attachment("pdf", "Annual report.pdf", "/old-chat/report.pdf", "application/pdf")
        val photo = Attachment("photo", "Garden.jpg", "/old-chat/photo.jpg", "image/jpeg")
        val sources = listOf(
            Evidence("text", pdf.id, pdf.name, 3, "Passage", null, emptyList()),
            Evidence("page", pdf.id, pdf.name, 3, "", "/old-chat/page-2.jpg", emptyList()),
            Evidence("photo", photo.id, photo.name, null, "", photo.path, emptyList())
        )
        val links = sourceLinks(Answer("one", "Question", listOf(pdf, photo), sources = sources, text = "Evidence [S1] [S2] [S3].", status = "Completed"))
        assertEquals(2, links.size)
        assertEquals(listOf(1, 2), links.first().citations)
        assertEquals("[S1] [S2] Annual report.pdf · page 3", links.first().label)
        assertEquals(pdf.path, links.first().path)
        assertFalse(links.first().isImage)
        assertEquals(photo.path, links.last().path)
        assertTrue(links.last().isImage)
    }

    @Test fun onlyCitedCompletedSourcesHaveLinksAndTheirOriginalNumbersStayStable() {
        val sources = (1..3).map { Evidence("source-$it", "owner-$it", "Document $it.txt", null, "Passage", null, emptyList()) }
        val files = sources.map { Attachment(it.attachmentId, it.name, "/original/${it.name}", "text/plain") }
        val answer = Answer("reply", "Question", files, text = "Third passage [S3].", sources = sources, status = "Answering")
        listOf("Preparing", "Answering", "Stopped", "Failed", "Interrupted").forEach { status ->
            assertTrue(sourceLinks(answer.copy(status = status)).isEmpty())
        }
        val links = sourceLinks(answer.copy(status = "Completed"))
        assertEquals(1, links.size)
        assertEquals(listOf(3), links.single().citations)
        assertEquals("[S3] Document 3.txt", links.single().label)
        assertTrue(sourceLinks(answer.copy(status = "Completed", text = "No cited evidence.")).isEmpty())
    }

    @Test fun oldHistoryDecodesWithoutInventingCompletionTimesOrModelNames() {
        val legacy = json.decodeFromString<Answer>("""{"id":"1700000000000-42","question":"Old question","attachments":[],"status":"Completed"}""")
        assertEquals(legacy.id, legacy.conversationId)
        assertEquals(1700000000000, legacy.sentAt())
        assertNull(legacy.completedAt)
        assertNull(legacy.modelId)
    }

    @Test fun followUpContextIsBoundedAndCannotReuseCitationIdsFromEarlierEvidence() {
        val turns = List(8) { Answer("$it", "Question $it", emptyList(), text = "Answer $it [S1] " + "details ".repeat(50), status = "Completed") }
        val context = conversationContext(turns + turns.last().copy(id = "failed", text = "Failed content", status = "Failed"), budget = 1200)
        assertTrue(context.length <= 1200)
        assertContains(context, "Question 7")
        assertFalse(context.contains("Question 0"))
        assertFalse(context.contains("Failed content"))
        assertFalse(context.contains("[S1]"))
        assertTrue(context.indexOf("Question 6") < context.indexOf("Question 7"))
    }
}
