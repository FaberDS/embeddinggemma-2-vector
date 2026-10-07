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
import kotlinx.coroutines.test.*
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SpeechUiTests {
    @Test fun plusMenuStartsMemoryRecordingAndStopSavesAnIndexedSource() = runComposeUiTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val root = Files.createTempDirectory("memory-ui").toFile()
        val platform = FakeSpeech()
        val controller = AppController(root.path, chatStore(root.path), MemoryRuntime(), ChatInputs(), platformSpeech = platform)
        try {
            modelSpecs.take(2).forEach { controller.models.state(it, ModelState(installed = true)) }
            setContent { Box(Modifier.width(390.dp).height(740.dp).testTag("memory.preview")) { PocketAskApp(controller) } }
            onNodeWithTag("chat.input").performTextInput("Keep my chat draft")
            onNodeWithTag("chat.addSource").performClick()
            onNodeWithTag("memory.add").performClick()
            onNodeWithText("Add memory").assertIsDisplayed()
            onNodeWithText("Listening…").assertIsDisplayed()
            runOnIdle { platform.dictation!!.text("Plant flowers near the fence", false) }
            onNodeWithTag("memory.transcript").assertTextContains("Plant flowers near the fence")
            onNodeWithTag("memory.save").assertTextContains("Stop and save").performClick()
            runOnIdle { assertEquals(1, platform.finished); platform.dictation!!.text("Plant flowers near the fence", true) }
            waitUntil(timeoutMillis = 10_000) { controller.state.value.library.singleOrNull()?.prepared == true }
            onNodeWithText("Memory saved").assertIsDisplayed()
            onNodeWithText("Garden layout ideas").assertIsDisplayed()
            onNodeWithText("Indexed · available in every chat").assertIsDisplayed()
            val pixels = onNodeWithTag("memory.dialog").captureToImage().toPixelMap()
            val screenshot = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) screenshot.setRGB(x, y, pixels[x, y].toArgb())
            ImageIO.write(screenshot, "png", File("/private/tmp/pocketask-memory-preview.png"))
            onNodeWithTag("memory.save").assertTextContains("Done").performClick()
            onNodeWithTag("chat.input").assertTextContains("Keep my chat draft")
            onNodeWithTag("chat.manageSources").performClick()
            onNodeWithText("Garden layout ideas.md").assertIsDisplayed()
            onNodeWithText("Memory · Indexed").assertIsDisplayed()
        } finally { controller.close(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun microphoneCreatesEditableDraftAndRepliesHaveStopAndReplay() = runComposeUiTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val root = Files.createTempDirectory("speech-ui").toFile()
        val platform = FakeSpeech()
        val controller = AppController(root.path, chatStore(root.path), ChatRuntime(), ChatInputs(), platformSpeech = platform)
        try {
            controller.models.state(modelSpecs[1], ModelState(installed = true))
            speechModelSpecs.forEach { controller.speech.models.state(it, ModelState(installed = true)) }
            setContent { Box(Modifier.width(390.dp).height(740.dp).testTag("speech.preview")) { PocketAskApp(controller) } }
            onNodeWithTag("chat.input").performTextInput("Explain")
            onNodeWithTag("speech.microphone").assertContentDescriptionEquals("Dictate message").performClick()
            onNodeWithText("Listening…").assertIsDisplayed()
            onNodeWithTag("chat.send").assertIsNotEnabled()
            runOnIdle { platform.dictation!!.text("this document", false) }
            onNodeWithTag("speech.microphone").assertContentDescriptionEquals("Finish dictation").performClick()
            runOnIdle { assertEquals(1, platform.finished); platform.dictation!!.text("this document", true) }
            onNodeWithTag("chat.input").assertIsEnabled().assertTextContains("Explain this document")
            onNodeWithTag("chat.send").performClick()
            waitUntil(timeoutMillis = 10_000) { controller.state.value.result?.status == "Completed" && controller.speech.state.value.answerId != null }
            val id = controller.state.value.result!!.id
            onNodeWithTag("speech.read.$id").assertTextContains("Stop").performClick()
            onNodeWithTag("speech.read.$id").assertTextContains("Read").performClick()
            runOnIdle { assertEquals(2, platform.spoken.size); platform.playback!!.stage("Reading aloud…") }
            val pixels = onNodeWithTag("speech.preview").captureToImage().toPixelMap()
            val screenshot = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) screenshot.setRGB(x, y, pixels[x, y].toArgb())
            ImageIO.write(screenshot, "png", File("/private/tmp/pocketask-speech-preview.png"))
            onNodeWithTag("navigation.settings").performClick()
            waitUntil(timeoutMillis = 10_000) { controller.state.value.knowledge != null && !controller.state.value.knowledgeLoading }
            onNodeWithTag("settings.list").performScrollToNode(hasTestTag("speech.voice"))
            // The list scrolls behind the floating bar; move controls clear of it.
            onNodeWithTag("settings.list").performTouchInput { swipeUp() }
            onNodeWithTag("speech.voice").performClick()
            onNodeWithText("Male 3").performScrollTo().performClick()
            runOnIdle { assertEquals("M3", controller.speech.state.value.voice); assertNull(controller.speech.state.value.answerId) }
            onNodeWithTag("speech.automatic").performClick()
            runOnIdle { assertFalse(controller.speech.state.value.automatic) }
        } finally { controller.close(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
}
