package dev.pocketask

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.math.sin
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SettingsUiTests {
    @Test fun settingsIsATabAndShowsSavedVectorsWithoutReadingSourcesOrLoadingModels() = runComposeUiTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val root = Files.createTempDirectory("settings-ui").toFile()
        val db = chatStore(root.path)
        val document = Attachment("doc", "Private.pdf", "/missing/document.pdf", "application/pdf")
        val memory = Attachment("memory", "Private.md", "/missing/memory.md", "text/markdown", isMemory = true)
        db.saveSource(document); db.saveSource(memory); db.markPrepared(document)
        val vector = normalize(List(256) { sin(it * 0.7).toFloat() })
        repeat(5) { index ->
            db.addEvidence(Evidence("v$index", if (index < 3) document.id else memory.id, "Saved evidence", index + 1,
                if (index == 2) "" else "A saved passage", if (index == 2) "/missing/page.jpg" else null, vector))
        }
        val runtime = MemoryRuntime()
        val controller = AppController(root.path, db, runtime, ChatInputs())
        try {
            setContent { Box(Modifier.width(390.dp).height(844.dp).testTag("settings.preview")) { PocketAskApp(controller) } }
            onNodeWithTag("chat.input").performTextInput("Keep my draft")
            onNodeWithTag("navigation.settings").performClick().assertIsSelected()
            waitUntil(timeoutMillis = 10_000) { controller.state.value.knowledge?.vectors == 5L && !controller.state.value.knowledgeLoading }
            onAllNodesWithText("Settings").assertCountEquals(2)
            onNodeWithTag("knowledge.vectors").assertTextEquals("5")
            onNodeWithTag("knowledge.searchable").assertTextEquals("3")
            onNodeWithText("4 text passages · 1 image vector").assertIsDisplayed()
            onNodeWithText("Private.pdf").assertDoesNotExist()
            onNodeWithText("Private.md").assertDoesNotExist()
            fun screenshot(path: String) {
                val pixels = onNodeWithTag("settings.preview").captureToImage().toPixelMap()
                val result = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) result.setRGB(x, y, pixels[x, y].toArgb())
                ImageIO.write(result, "png", File(path))
            }
            screenshot("/private/tmp/pocketask-settings-preview.png")
            onNodeWithTag("knowledge.structure").performScrollTo().performClick()
            onNodeWithTag("knowledge.shape").assertTextEquals("5 × 256")
            onNodeWithTag("knowledge.rawBytes").assertTextEquals("5.1 KB")
            onNodeWithTag("knowledge.embedding").performScrollTo().assertIsDisplayed()
            screenshot("/private/tmp/pocketask-embedding-preview.png")
            onNodeWithTag("navigation.ask").performClick()
            onNodeWithTag("chat.input").assertTextContains("Keep my draft")
            runOnIdle { controller.removeAttachment(memory.id) }
            onNodeWithTag("navigation.settings").performClick()
            waitUntil(timeoutMillis = 10_000) { controller.state.value.knowledge?.vectors == 3L && !controller.state.value.knowledgeLoading }
            onNodeWithTag("knowledge.vectors").assertTextEquals("3")
            onNodeWithTag("knowledge.refresh").performClick()
            waitUntil(timeoutMillis = 10_000) { !controller.state.value.knowledgeLoading }
            runOnIdle { assertTrue(runtime.loads.isEmpty()); assertTrue(runtime.indexed.isEmpty()) }
        } finally { controller.close(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
}
