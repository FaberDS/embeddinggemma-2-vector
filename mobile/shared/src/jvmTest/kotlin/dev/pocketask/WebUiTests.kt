package dev.pocketask

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class WebUiTests {
    @Test fun explicitPastePreviewAndAssetActionsKeepSavedAndOriginalContentSeparate() = runComposeUiTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val root = Files.createTempDirectory("web-ui").toFile()
        val inputs = ChatInputs()
        var reads = 0
        val clipboard = object : ClipboardManager {
            override fun getText(): AnnotatedString { reads++; return AnnotatedString("https://example.com/garden") }
            override fun setText(annotatedString: AnnotatedString) {}
        }
        val controller = AppController(root.path, chatStore(root.path), ChatRuntime(), inputs, worker = Dispatchers.Unconfined,
            webFetcher = { WebPage("Garden guide", it, "Plant flowers near the fence. Water them regularly and keep their roots cool. Choose a sunny location for your garden.") })
        try {
            setContent { CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                Box(Modifier.width(430.dp).height(844.dp).testTag("web.screen")) { PocketAskApp(controller) }
            } }
            onNodeWithTag("chat.addSource").performClick(); onNodeWithText("Insert link").performClick()
            runOnIdle { assertEquals(0, reads) }
            onNodeWithTag("web.paste").performClick(); onNodeWithTag("web.url").assertTextContains("https://example.com/garden")
            onNodeWithTag("web.load").performClick()
            onNodeWithText("Garden guide").assertIsDisplayed(); onNodeWithTag("web.preview").assertIsDisplayed()
            runOnIdle { assertEquals(1, reads); assertTrue(controller.state.value.library.isEmpty()) }
            val pixels = onNodeWithTag("web.preview").captureToImage().toPixelMap()
            val screenshot = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) screenshot.setRGB(x, y, pixels[x, y].toArgb())
            ImageIO.write(screenshot, "png", File("/private/tmp/pocketask-web-preview.png"))
            onNodeWithTag("web.import").performClick()
            onNodeWithTag("web.preview").assertDoesNotExist()
            onNodeWithTag("navigation.assets").performClick()
            onNodeWithTag("assets.filter.Web pages").performScrollTo().performClick().assertIsSelected()
            onNodeWithText("Web page · Waiting to index").assertIsDisplayed()
            val source = controller.state.value.library.single()
            onNodeWithTag("assets.url.${source.id}").performClick()
            runOnIdle { assertEquals(source.sourceUrl to 1, inputs.opened.last()) }
            onNodeWithTag("assets.open.${source.id}").performClick()
            runOnIdle { assertEquals(source.path to 1, inputs.opened.last()) }
        } finally { controller.close(); Dispatchers.resetMain(); root.deleteRecursively() }
    }
}
