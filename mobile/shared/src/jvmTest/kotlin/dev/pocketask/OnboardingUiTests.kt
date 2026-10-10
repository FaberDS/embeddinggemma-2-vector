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
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class OnboardingUiTests {
    @Test fun welcomeLeadsToModelSelectionAndSetupCanBeDeferred() = runComposeUiTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val root = Files.createTempDirectory("onboarding-ui").toFile()
        val store = chatStore(root.path).also { it.put("onboarded", value = "no") }
        val controller = AppController(root.path, store, ChatRuntime(), ChatInputs())
        try {
            setContent { Box(Modifier.width(390.dp).height(740.dp).testTag("onboarding.preview")) { PocketAskApp(controller) } }
            onNodeWithContentDescription("Locune logo").assertIsDisplayed()
            onNodeWithText("Welcome to Locune").assertIsDisplayed()
            onNodeWithText("Answer model").assertDoesNotExist()
            val pixels = onNodeWithTag("onboarding.preview").captureToImage().toPixelMap()
            val screenshot = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) screenshot.setRGB(x, y, pixels[x, y].toArgb())
            ImageIO.write(screenshot, "png", File("/private/tmp/pocketask-welcome-preview.png"))
            onNodeWithText("Choose models").performClick()
            onNodeWithText("Set up your models").assertIsDisplayed()
            onNodeWithContentDescription("Locune logo").assertDoesNotExist()
            onNodeWithText("Answer model").assertIsDisplayed()
            onNode(hasText(modelSpecs[1].title) and hasClickAction()).performClick()
            onAllNodesWithText(modelSpecs[2].title)[0].performClick()
            runOnIdle { assertEquals(modelSpecs[2].id, controller.state.value.answerModel) }
            onNodeWithText("Set up later").performClick()
            onNodeWithTag("chat.input").assertIsDisplayed()
            runOnIdle { assertEquals("yes", store.value("onboarded")); assertEquals(2, controller.state.value.onboarding) }
        } finally { controller.close(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun compactScreenCanContinueWithInstalledModels() = runComposeUiTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val root = Files.createTempDirectory("onboarding-compact").toFile()
        val store = chatStore(root.path).also { it.put("onboarded", value = "no") }
        val controller = AppController(root.path, store, ChatRuntime(), ChatInputs())
        try {
            runOnIdle { controller.selectedModels().forEach { controller.models.state(it, ModelState(installed = true)) } }
            setContent { Box(Modifier.width(320.dp).height(568.dp)) { PocketAskApp(controller) } }
            onNodeWithText("Choose models").assertIsDisplayed().performClick()
            onNodeWithText("Start asking").performScrollTo().assertIsDisplayed().performClick()
            onNodeWithTag("chat.input").assertIsDisplayed()
            runOnIdle { assertEquals("yes", store.value("onboarded")) }
        } finally { controller.close(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
}
