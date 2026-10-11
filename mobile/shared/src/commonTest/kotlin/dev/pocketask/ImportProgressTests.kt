package dev.pocketask

import kotlin.test.*

class ImportProgressTests {
    private val pdf = Attachment("pdf", "Report.pdf", "/report.pdf", "application/pdf")
    private val note = Attachment("note", "Ideas.md", "/ideas.md", "text/markdown")

    @Test fun stagesFollowTheTextFirstPipelineWithoutAddingVisualWorkToNotes() {
        val stages = importStages(listOf(pdf, note), mapOf(pdf.id to IndexCheckpoint(100, text = 100, ocr = 50), note.id to IndexCheckpoint(1)), pdf.id, "Indexing recognized text")
        assertEquals(listOf("pdf:Indexing text", "pdf:Indexing recognized text", "note:Indexing text",
            "pdf:Indexing page images", "pdf:Describing pages", "pdf:Indexing descriptions"), stages.map { it.id })
        assertEquals(ImportStageStatus.Completed, stages[0].status)
        assertEquals(ImportStageStatus.Active, stages[1].status)
        assertEquals(50, stages[1].completed)
        assertTrue(stages.drop(2).all { it.status == ImportStageStatus.Pending })
    }

    @Test fun unknownPageCountsDoNotInventCompletedWork() {
        val stages = importStages(listOf(pdf, note), emptyMap())
        assertTrue(stages.all { it.total == 0 && it.completed == 0 && it.status == ImportStageStatus.Pending })
    }

    @Test fun failedAssetsKeepCompletedStagesAndOnlyFlagTheirFirstUnfinishedStage() {
        val stages = importStages(listOf(pdf), mapOf(pdf.id to IndexCheckpoint(100, text = 100, ocr = 100, images = 100, descriptions = 42)), failed = setOf(pdf.id))
        assertTrue(stages.take(3).all { it.status == ImportStageStatus.Completed })
        assertEquals(ImportStageStatus.Failed, stages[3].status)
        assertEquals(ImportStageStatus.Pending, stages[4].status)
    }

    @Test fun completedCheckpointArchivesEveryRealStage() {
        val stages = importStages(listOf(pdf, note), mapOf(pdf.id to IndexCheckpoint(100, 100, 100, 100, 100, 100), note.id to IndexCheckpoint(1, 1)))
        assertEquals(6, stages.size)
        assertTrue(stages.all { it.status == ImportStageStatus.Completed })
    }

    @Test fun photosOmitEmbeddedTextAndTextOnlySourcesOmitVisualStages() {
        val photo = Attachment("photo", "Photo.jpg", "/photo.jpg", "image/jpeg")
        assertEquals(listOf("Indexing recognized text", "Indexing page images", "Describing pages", "Indexing descriptions"),
            importStages(listOf(photo), emptyMap()).map { it.phase })
        for (type in listOf("text/plain", "text/markdown", "text/x-web-page")) {
            assertEquals(listOf("Indexing text"), importStages(listOf(note.copy(type = type)), emptyMap()).map { it.phase })
        }
    }

    @Test fun blankPdfTextAndUnneededOcrAreSkippedWithoutHidingVisualWork() {
        val stages = importStages(listOf(pdf), mapOf(pdf.id to IndexCheckpoint(100, text = 100, ocr = 100, skippedText = 100, skippedOcr = 100)))
        assertTrue(stages.take(2).all { it.status == ImportStageStatus.Skipped })
        assertTrue(stages.drop(2).all { it.status == ImportStageStatus.Pending })
    }

    @Test fun readingPagesIsActiveOnlyWhileExtractionIsRunning() {
        val stages = importStages(listOf(pdf), mapOf(pdf.id to IndexCheckpoint(100, text = 42)), pdf.id, "Reading pages")
        assertEquals("Reading pages", stages.single { it.status == ImportStageStatus.Active }.phase)
        assertEquals(42, stages.first().completed)
        assertEquals(ImportStageStatus.Pending, stages[1].status)
        assertTrue(importStages(listOf(pdf), emptyMap()).none { it.phase == "Reading pages" })
    }

    @Test fun olderCheckpointsAndReportsDecodeWithoutSkipMetadata() {
        assertEquals(IndexCheckpoint(1, text = 1), json.decodeFromString<IndexCheckpoint>("""{"pages":1,"text":1}"""))
        assertEquals(0, json.decodeFromString<ImportReport>("""{"stages":[{"assetId":"pdf","assetName":"Report.pdf","phase":"Indexing text","completed":1,"total":1,"status":"Completed"}]}""").stages.single().skipped)
    }

    @Test fun readingForAnOcrUpgradeUsesTheOcrPageRatherThanTheFinishedTextCount() {
        val stages = importStages(listOf(pdf), mapOf(pdf.id to IndexCheckpoint(100, text = 100)), pdf.id, "Reading pages", activeCompleted = 3)
        assertEquals(3, stages.single { it.status == ImportStageStatus.Active }.completed)
    }
}
