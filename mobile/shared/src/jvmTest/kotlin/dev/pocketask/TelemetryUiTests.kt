package dev.pocketask

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.nio.file.Files
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class TelemetryUiTests {
    @Test fun diagnosticTimelineFitsAPhoneWidth() = runComposeUiTest {
        val start = 1700000000000L
        val timings = listOf(
            RequestTiming(start, 0, "Request started", "Gemma 4 E2B IT · 2 searchable assets"),
            RequestTiming(start + 100, 100, "Loading search model", "EmbeddingGemma 2 · 740M"),
            RequestTiming(start + 2200, 2200, "Embedding question", "CPU · 4 threads · 256-value embeddings"),
            RequestTiming(start + 3300, 3300, "Searching saved passages", "2 searchable assets"),
            RequestTiming(start + 3500, 3500, "Sources selected", "6 matches · 5 supplied passages · 5800 evidence characters"),
            RequestTiming(start + 3600, 3600, "Releasing search model"),
            RequestTiming(start + 3900, 3900, "Loading answer model", "Gemma 4 E2B IT"),
            RequestTiming(start + 8100, 8100, "Waiting for first text", "GPU · 8192-token context · 512-token output limit · 6210 prompt characters · 330 instruction characters · 0 history characters"),
            RequestTiming(start + 48000, 48000, "First text displayed"),
            RequestTiming(start + 58400, 58400, "Answer finished", "820 answer characters"),
            RequestTiming(start + 58410, 58410, "Releasing models"),
            RequestTiming(start + 58700, 58700, "Completed")
        )
        val answer = Answer("preview", "Google Core Web Vitals", emptyList(), status = "Completed", timings = timings)
        setContent { MaterialTheme { Column(Modifier.width(390.dp).height(1200.dp).testTag("timing.preview")) { RequestTimingPanel(answer, false) } } }
        onNodeWithText("Waiting for first text · 39.900 s").assertIsDisplayed()
        val pixels = onNodeWithTag("timing.preview").captureToImage().toPixelMap()
        val screenshot = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) screenshot.setRGB(x, y, pixels[x, y].toArgb())
        ImageIO.write(screenshot, "png", File("/private/tmp/pocketask-telemetry-preview.png"))
    }

    @Test fun settingsToggleInsertsTimingsBetweenMessagesAndOffRestoresTheChat() = runComposeUiTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val root = Files.createTempDirectory("request-timing-ui").toFile()
        val store = chatStore(root.path)
        val now = System.currentTimeMillis()
        val reply = Answer("timed", "Google Core Web Vitals", emptyList(), text = "A saved reply.", status = "Completed", createdAt = now, completedAt = now + 3200,
            timings = listOf(RequestTiming(now, 0, "Loading answer model", "Gemma 4 E2B IT"),
                RequestTiming(now + 1200, 1200, "Waiting for first text", "GPU · 1800 prompt characters"),
                RequestTiming(now + 3200, 3200, "Completed")))
        store.save(reply); store.saveDraft(Draft(conversationId = reply.id))
        val controller = AppController(root.path, store, ChatRuntime(), ChatInputs())
        var clipboardText: AnnotatedString? = null
        val clipboard = object : ClipboardManager {
            override fun setText(annotatedString: AnnotatedString) { clipboardText = annotatedString }
            override fun getText() = clipboardText
        }
        try {
            setContent { CompositionLocalProvider(LocalClipboardManager provides clipboard) { Box(Modifier.width(390.dp).height(844.dp)) { PocketAskApp(controller) } } }
            onNodeWithTag("chat.timing.timed").assertDoesNotExist()
            onNodeWithTag("chat.reply.timed").assertIsDisplayed()
            onNodeWithTag("navigation.settings").performClick()
            onNodeWithTag("settings.telemetry").assertIsOff().performClick().assertIsOn()
            runOnIdle { assertEquals("yes", store.value("request-telemetry")) }
            onNodeWithTag("navigation.ask").performClick()
            onNodeWithTag("chat.timing.timed").performScrollTo().assertIsDisplayed()
            onNodeWithText("Waiting for first text · 2.000 s").assertExists()
            onNodeWithText("GPU · 1800 prompt characters").assertExists()
            onNodeWithTag("chat.timing.copy.timed").assertIsDisplayed().performClick()
            runOnIdle {
                val exported = assertNotNull(clipboardText).text
                assertContains(exported, "Locune · request telemetry")
                assertContains(exported, "GPU · 1800 prompt characters")
                assertContains(exported, "Total elapsed: 3.200 s")
            }
            onNodeWithTag("chat.timing.toggle.timed").performClick()
            onNodeWithTag("chat.timing.copy.timed").assertIsDisplayed()
            onNodeWithText("GPU · 1800 prompt characters").assertDoesNotExist()
            onNodeWithTag("chat.reply.timed").performScrollTo()
            val user = onNodeWithTag("chat.user.timed").fetchSemanticsNode().boundsInRoot
            val timing = onNodeWithTag("chat.timing.timed").fetchSemanticsNode().boundsInRoot
            val answer = onNodeWithTag("chat.reply.timed").fetchSemanticsNode().boundsInRoot
            assertTrue(user.bottom <= timing.top)
            assertTrue(timing.bottom <= answer.top)
            onNodeWithTag("navigation.settings").performClick()
            onNodeWithTag("settings.telemetry").performClick().assertIsOff()
            onNodeWithTag("navigation.ask").performClick()
            onNodeWithTag("chat.timing.timed").assertDoesNotExist()
            onNodeWithTag("chat.timing.copy.timed").assertDoesNotExist()
            onNodeWithTag("chat.reply.timed").assertIsDisplayed()
            runOnIdle { assertEquals(reply.timings, store.history().single().timings) }
        } finally { controller.close(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
}
