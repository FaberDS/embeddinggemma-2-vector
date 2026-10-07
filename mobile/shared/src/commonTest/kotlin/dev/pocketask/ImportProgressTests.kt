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
}
