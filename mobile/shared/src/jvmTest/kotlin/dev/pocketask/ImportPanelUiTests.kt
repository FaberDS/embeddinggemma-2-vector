package dev.pocketask

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ImportPanelUiTests {
    private val asset = Attachment("report", "Research report.pdf", "/report.pdf", "application/pdf")
    private val report = ImportReport(importStages(listOf(asset), mapOf(asset.id to IndexCheckpoint(100, 100, ocr = 42)), asset.id, "Indexing recognized text"), "Indexing")

    @Test fun headerShowsCurrentStepAndPhaseEstimateAndTappingRevealsThePipeline() = runComposeUiTest {
        var pauses = 0
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF4C6758), secondaryContainer = Color(0xFFDFE7E0), onSecondaryContainer = Color(0xFF24402D))) {
                Box(Modifier.size(390.dp, 700.dp).testTag("import.preview")) {
                ImportPanel(report, IndexingProgress("Indexing recognized text", 42, 100, 142, 500, 120), "Indexing recognized text · Research report.pdf · 42/100", false, { pauses++ }, {})
            } }
        }
        onNodeWithTag("import.estimate", useUnmergedTree = true).assertTextEquals("About 2 min left in this step")
        onNodeWithTag("import.panel").assertDoesNotExist()
        onNodeWithTag("import.header").performClick()
        onNodeWithTag("import.panel").assertIsDisplayed()
        onAllNodesWithText(asset.name).assertCountEquals(1)
        onNodeWithTag("import.file.${asset.id}").assertIsDisplayed()
        onNodeWithText("Done").assertIsDisplayed()
        onNodeWithText("Active").assertIsDisplayed()
        onAllNodesWithText("Upcoming").assertCountEquals(3)
        onNodeWithText("42/100").assertIsDisplayed()
        onNodeWithTag("import.pause").performClick()
        runOnIdle { assertEquals(1, pauses) }
        val pixels = onNodeWithTag("import.preview").captureToImage().toPixelMap()
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
        ImageIO.write(image, "png", File("/private/tmp/pocketask-import-panel.png"))
        onNodeWithTag("import.header").performClick()
        onNodeWithTag("import.panel").assertDoesNotExist()
    }

    @Test fun pullingTheHeaderDownAndUpOpensAndClosesThePanel() = runComposeUiTest {
        setContent { MaterialTheme { ImportPanel(report, null, null, false, {}, {}) } }
        onNodeWithTag("import.header").performTouchInput { swipeDown(startY = height * .1f, endY = height * .95f) }
        onNodeWithTag("import.panel").assertIsDisplayed()
        onNodeWithTag("import.header").performTouchInput { swipeUp(startY = height * .95f, endY = height * .1f) }
        onNodeWithTag("import.panel").assertDoesNotExist()
    }

    @Test fun pausedReportsStayVisibleAllowResumeAndAvoidInventingAnEta() = runComposeUiTest {
        var resumed = 0
        val paused = report.copy(status = "Paused", stages = report.stages.map { if (it.status == ImportStageStatus.Active) it.copy(status = ImportStageStatus.Paused) else it })
        setContent { MaterialTheme { ImportPanel(paused, null, null, false, {}, { resumed++ }) } }
        onNodeWithText("Paused").assertIsDisplayed()
        onNodeWithTag("import.estimate", useUnmergedTree = true).assertTextEquals("Completed work is saved")
        onNodeWithTag("import.header").performClick()
        onNodeWithTag("import.resume").performClick()
        runOnIdle { assertEquals(1, resumed) }
    }

    @Test fun restoredProgressIsPausedAndCompletedArchiveSurvivesRelaunch() {
        val root = Files.createTempDirectory("import-report").toFile()
        val store = chatStore(root.path)
        try {
            store.saveImportReport(report)
            val restored = store.savedImportReport()!!
            assertEquals("Paused", restored.status)
            assertEquals(ImportStageStatus.Completed, restored.stages[0].status)
            assertEquals(ImportStageStatus.Paused, restored.stages[1].status)
            val done = ImportReport(report.stages.map { it.copy(completed = it.total, status = ImportStageStatus.Completed) }, "Completed")
            store.saveImportReport(done)
            assertEquals(done, store.savedImportReport())
            store.put("import-report", value = "invalid")
            assertNull(store.savedImportReport())
        } finally { root.deleteRecursively() }
    }

    @Test fun loadingAModelDoesNotReuseThePreviousStagesTimeEstimate() = runComposeUiTest {
        setContent { MaterialTheme { ImportPanel(report, IndexingProgress("Indexing recognized text", 42, 100, 142, 500, 120),
            "Loading answer model for indexing", false, {}, {}) } }
        onNodeWithTag("import.estimate", useUnmergedTree = true).assertTextEquals("Estimating this step…")
    }

    @Test fun completedImportsKeepTheirArchiveWithoutOfferingResume() = runComposeUiTest {
        val completed = report.copy(status = "Completed", stages = report.stages.map { it.copy(completed = it.total, status = ImportStageStatus.Completed) })
        setContent { MaterialTheme { ImportPanel(completed, null, null, false, {}, {}) } }
        onNodeWithTag("import.estimate", useUnmergedTree = true).assertTextEquals("Saved to your knowledge base")
        onNodeWithTag("import.header").performClick()
        onNodeWithTag("import.resume").assertDoesNotExist()
        onNodeWithTag("import.pause").assertDoesNotExist()
        onAllNodesWithText("Done").assertCountEquals(5)
    }

    @Test fun pausingAfterTheLastPageStillAllowsFinalizationToResume() = runComposeUiTest {
        var resumed = false
        val paused = report.copy(status = "Paused", stages = report.stages.map { it.copy(completed = it.total, status = ImportStageStatus.Completed) })
        setContent { MaterialTheme { ImportPanel(paused, null, null, false, {}, { resumed = true }) } }
        onNodeWithTag("import.header").performClick()
        onNodeWithTag("import.resume").performClick()
        runOnIdle { assertTrue(resumed) }
    }
}
