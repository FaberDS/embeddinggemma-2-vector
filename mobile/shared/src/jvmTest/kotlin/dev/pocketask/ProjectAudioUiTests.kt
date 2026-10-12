package dev.pocketask

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
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
class ProjectAudioUiTests {
    @Test fun composerPromptSettingsAndSavedAudioStayUsableOnNarrowPhones() {
        listOf(Size(390f, 740f), Size(320f, 480f)).forEach { size -> runSkikoComposeUiTest(size = size) {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            val root = Files.createTempDirectory("audio-ui").toFile()
            val store = chatStore(root.path)
            val file = File(root, "source.txt").apply { writeText("A source idea") }
            val item = Attachment("source", file.name, file.path, "text/plain", projectIds = setOf("test-project"))
            store.saveSource(item); store.markPrepared(item)
            store.addEvidence(Evidence("s1", item.id, item.name, null, "A source idea", null, List(256) { 1f }))
            val runtime = AudioRuntime()
            val controller = AppController(root.path, store, runtime, ChatInputs(), worker = UnconfinedTestDispatcher(), platformSpeech = AudioSpeech(runtime))
            try {
                controller.models.state(modelSpecs[1], ModelState(installed = true))
                speechModelSpecs.forEach { controller.speech.models.state(it, ModelState(installed = true)) }
                setContent { PocketAskApp(controller) }
                onNodeWithTag("chat.createAudio").assertIsNotEnabled()
                onNodeWithTag("chat.input").performTextInput("Explain how the source idea connects to the project")
                onNodeWithTag("chat.createAudio").assertIsEnabled().assertIsDisplayed()
                onNodeWithTag("chat.addSource").assertIsDisplayed()
                val composer = onAllNodes(isRoot()).onFirst().captureToImage().toPixelMap()
                val composerImage = BufferedImage(composer.width, composer.height, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until composer.height) for (x in 0 until composer.width) composerImage.setRGB(x, y, composer[x, y].toArgb())
                ImageIO.write(composerImage, "png", File("/private/tmp/sch-27-prompt-composer-${size.width.toInt()}.png"))
                fun open() {
                    onNodeWithTag("project.actions").performClick()
                    onNodeWithTag("project.audio").performClick()
                    mainClock.advanceTimeBy(1000); waitForIdle()
                }
                onNodeWithTag("chat.createAudio").performClick()
                mainClock.advanceTimeBy(1000); waitForIdle()
                onNodeWithTag("audio.prompt").assertTextContains("Explain how the source idea connects to the project")
                onNodeWithTag("audio.prompt").performTextReplacement("")
                onNodeWithTag("audio.generate").performScrollTo().assertIsNotEnabled()
                onNodeWithTag("audio.prompt").performScrollTo().performTextReplacement("Focus on practical connections")
                onNodeWithTag("audio.Length.Medium").assertIsSelected()
                onNodeWithTag("audio.Language").assertDoesNotExist()
                onNodeWithTag("audio.Detail.Detailed").performScrollTo().performClick()
                onNodeWithTag("audio.Length.Medium").assertIsSelected()
                onNodeWithTag("audio.Style.Tutor").performScrollTo().performClick()
                onNodeWithTag("audio.advanced").performScrollTo().performClick()
                onNodeWithTag("audio.Language").performScrollTo().performClick()
                onNodeWithTag("audio.Language.de").performScrollTo().performClick()
                onNodeWithTag("audio.Tutor voice").performScrollTo().performClick()
                onNodeWithTag("audio.Tutor voice.M3").performScrollTo().performClick()
                onNodeWithTag("audio.minutes.Short").performScrollTo().performTextReplacement("3")
                onNodeWithTag("audio.minutes.Medium").performTextReplacement("0")
                onNodeWithTag("audio.generate").performScrollTo().assertIsNotEnabled()
                onNodeWithTag("audio.minutes.Medium").performScrollTo().performTextReplacement("4")
                onNodeWithTag("audio.generate").performScrollTo().assertIsEnabled()
                runOnIdle { assertEquals(AudioSettings(detail = "Detailed", style = "Tutor", language = "de", firstVoice = "M3", shortMinutes = 3, mediumMinutes = 4), controller.state.value.audioSettings) }
                runOnIdle { controller.closeAudio() }; mainClock.advanceTimeBy(1000)
                open()
                onNodeWithTag("audio.Language").assertDoesNotExist()
                onNodeWithTag("audio.Style.Tutor").assertIsSelected()
                onNodeWithTag("audio.generate").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5000) { !controller.state.value.audioGenerating }
                val audio = controller.state.value.audio.single()
                assertEquals("Focus on practical connections", audio.prompt)
                assertContains(runtime.prompts.single(), "Audio request: Focus on practical connections")
                onNodeWithTag("audio.play.${audio.id}").performScrollTo().assertIsDisplayed().performClick()
                onNodeWithTag("audio.share.${audio.id}").assertIsDisplayed()
                val pixels = onAllNodes(isRoot()).onLast().captureToImage().toPixelMap()
                val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
                ImageIO.write(image, "png", File("/private/tmp/sch-27-audio-${size.width.toInt()}.png"))
            } finally { controller.close(); root.deleteRecursively(); Dispatchers.resetMain() }
        } }
    }
}
