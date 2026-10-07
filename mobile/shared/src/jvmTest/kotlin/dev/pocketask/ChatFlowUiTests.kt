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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ChatFlowUiTests {
    @Test fun bottomComposerSendsFollowUpsAndThePlusMenuInjectsMarkdown() = runComposeUiTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val root = Files.createTempDirectory("pocketask-chat-ui").toFile()
        val runtime = ChatRuntime()
        val controller = AppController(root.path, chatStore(root.path), runtime, ChatInputs())
        try {
            runOnIdle { controller.models.state(modelSpecs[1], ModelState(installed = true)) }
            setContent { Box(Modifier.width(390.dp).height(740.dp).testTag("chat.preview")) { PocketAskApp(controller) } }
            val input = onNodeWithTag("chat.input").fetchSemanticsNode().boundsInRoot
            val messages = onNodeWithTag("chat.messages").fetchSemanticsNode().boundsInRoot
            assertTrue(input.top >= messages.bottom)
            val navigation = onNodeWithTag("navigation").fetchSemanticsNode().boundsInRoot
            assertTrue(input.bottom < navigation.top)
            onNodeWithTag("chat.input").performTextInput("Hello")
            onNodeWithTag("chat.send").performClick()
            waitUntil(timeoutMillis = 10_000) { controller.state.value.result?.status == "Completed" }
            runOnIdle { assertEquals("", controller.state.value.draft.question) }
            onNodeWithTag("chat.input").performTextInput("Tell me more")
            onNodeWithTag("chat.send").performClick()
            waitUntil(timeoutMillis = 10_000) { controller.state.value.history.size == 2 }
            runOnIdle {
                assertEquals(2, conversationTurns(controller.state.value).size)
                assertContains(runtime.prompts.last(), "Hello")
            }
            val pixels = onNodeWithTag("chat.preview").captureToImage().toPixelMap()
            val screenshot = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) screenshot.setRGB(x, y, pixels[x, y].toArgb())
            ImageIO.write(screenshot, "png", File("/private/tmp/pocketask-chat-preview.png"))
            onNodeWithTag("chat.input").performTextInput("A draft to keep")
            onNodeWithTag("navigation.assets").performClick()
            onNodeWithText("Your assets").assertIsDisplayed()
            onNodeWithTag("navigation.history").performClick()
            onNodeWithText("Your history").assertIsDisplayed()
            onNodeWithTag("navigation.ask").performClick()
            onNodeWithTag("chat.input").assertTextContains("A draft to keep")
            onNodeWithTag("chat.input").performTextClearance()
            onNodeWithTag("chat.addSource").performClick()
            onNodeWithText("Add files").assertIsDisplayed()
            onNodeWithText("Add images").assertIsDisplayed()
            onNodeWithText("Write text").performClick()
            onNodeWithTag("chat.noteTitle").performTextInput("My source")
            onNodeWithTag("chat.noteText").performTextInput("# Markdown\nAn injected fact.")
            onNodeWithText("Add to sources").performClick()
            waitUntil(timeoutMillis = 10_000) { controller.state.value.library.size == 1 && !controller.state.value.importing }
            runOnIdle {
                val source = controller.state.value.library.single()
                assertEquals("My source.md", source.name)
                assertEquals("# Markdown\nAn injected fact.", File(source.path).readText())
                assertFalse(source.prepared)
            }
            onNodeWithTag("chat.manageSources").assertIsDisplayed()
            onNodeWithTag("chat.send").assertIsNotEnabled()
            onNodeWithText("New chat").performClick()
            onNodeWithTag("chat.manageSources").performClick()
            onNodeWithText("Your knowledge base").assertIsDisplayed()
            onNodeWithText("My source.md").assertIsDisplayed()
            onNodeWithText("Remove").performClick()
            onNodeWithText("Keep source").performClick()
            runOnIdle { assertEquals(1, controller.state.value.library.size) }
            onNodeWithText("Remove").performClick()
            onNodeWithTag("knowledge.remove").performClick()
            runOnIdle {
                assertTrue(controller.state.value.library.isEmpty())
                assertEquals(2, controller.state.value.history.size)
            }
        } finally { controller.close(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
}
