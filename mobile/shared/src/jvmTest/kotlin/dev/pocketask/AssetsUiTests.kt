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
import java.awt.Color
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
class AssetsUiTests {
    @Test fun assetsShowTheGlobalLibraryAndFilterOpenAndRemoveWithoutReindexing() = runComposeUiTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val root = Files.createTempDirectory("assets-ui").toFile()
        val db = chatStore(root.path)
        val inputs = ChatInputs()
        val runtime = MemoryRuntime()
        fun source(id: String, name: String, type: String, memory: Boolean = false, prepared: Boolean = true): Attachment {
            val file = File(root, name)
            if (type.startsWith("image/")) {
                val image = BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB)
                val graphics = image.createGraphics()
                graphics.color = Color(215, 231, 207); graphics.fillRect(0, 0, 400, 300)
                graphics.color = Color(84, 126, 85); graphics.fillRect(195, 145, 10, 155)
                graphics.color = if (id == "5-photo") Color(222, 162, 176) else Color(221, 191, 115)
                for ((x, y) in listOf(145 to 90, 205 to 90, 175 to 60, 175 to 120)) graphics.fillOval(x, y, 60, 60)
                graphics.color = Color(244, 219, 144); graphics.fillOval(185, 100, 40, 40)
                graphics.dispose(); ImageIO.write(image, "png", file)
            } else file.writeText("# Garden ideas\nPlant flowers near the fence.")
            val asset = Attachment(id, name, file.path, type, isMemory = memory)
            db.saveSource(asset)
            if (prepared) {
                db.addEvidence(Evidence("$id-text", id, name, null, "A saved passage", null, List(256) { if (it == 0) 1f else 0f }))
                db.markPrepared(asset)
            }
            return asset
        }
        val rose = source("5-photo", "Rose.png", "image/png")
        val tulip = source("4-photo", "Tulip.png", "image/png")
        val pdf = source("3-pdf", "Garden guide.pdf", "application/pdf")
        val memory = source("2-memory", "Garden ideas.md", "text/markdown", memory = true)
        val note = source("1-note", "Plans.md", "text/markdown", prepared = false)
        val controller = AppController(root.path, db, runtime, inputs)
        try {
            setContent { Box(Modifier.width(390.dp).height(844.dp).testTag("assets.preview")) { PocketAskApp(controller) } }
            onNodeWithTag("chat.input").performTextInput("A chat draft to keep")
            onNodeWithTag("navigation.assets").performClick().assertIsSelected()
            onNodeWithText("Your assets").assertIsDisplayed()
            onNodeWithText("5 assets · 4 searchable").assertIsDisplayed()
            onNodeWithTag("assets.filter.All").assertIsSelected()
            val first = onNodeWithTag("assets.item.${rose.id}").fetchSemanticsNode().boundsInRoot
            val second = onNodeWithTag("assets.item.${tulip.id}").fetchSemanticsNode().boundsInRoot
            assertEquals(first.top, second.top); assertTrue(first.right < second.left)
            val pixels = onNodeWithTag("assets.preview").captureToImage().toPixelMap()
            val screenshot = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) screenshot.setRGB(x, y, pixels[x, y].toArgb())
            ImageIO.write(screenshot, "png", File("/private/tmp/pocketask-assets-preview.png"))
            onNodeWithTag("assets.open.${rose.id}").performClick()
            runOnIdle { assertEquals(rose.path to 1, inputs.opened.last()) }
            onNodeWithTag("assets.filter.Documents").performScrollTo().performClick().assertIsSelected()
            onNodeWithTag("assets.item.${rose.id}").assertDoesNotExist()
            onNodeWithTag("assets.item.${memory.id}").assertDoesNotExist()
            onNodeWithText("Garden guide.pdf").assertIsDisplayed()
            onNodeWithText("Markdown · Waiting to index").assertIsDisplayed()
            onNodeWithTag("assets.open.${pdf.id}").performClick()
            runOnIdle { assertEquals(pdf.path to 1, inputs.opened.last()) }
            onNodeWithTag("assets.filter.Memories").performScrollTo().performClick().assertIsSelected()
            onNodeWithText("Garden ideas").assertIsDisplayed()
            onNodeWithText("Transcript · Indexed").assertIsDisplayed()
            onNodeWithTag("assets.item.${pdf.id}").assertDoesNotExist()
            onNodeWithTag("assets.item.${note.id}").assertDoesNotExist()
            onNodeWithTag("assets.open.${memory.id}").performClick()
            runOnIdle { assertEquals(memory.path to 1, inputs.opened.last()) }
            onNodeWithTag("assets.remove.${memory.id}").performClick()
            onNodeWithText("Keep source").performClick()
            runOnIdle { assertEquals(5, controller.state.value.library.size) }
            onNodeWithTag("assets.remove.${memory.id}").performClick()
            onNodeWithTag("knowledge.remove").performClick()
            onNodeWithTag("assets.empty").assertTextEquals("No memories yet.")
            runOnIdle { assertTrue(db.evidence(memory.id, 0).isEmpty()); assertEquals(4, controller.state.value.library.size) }
            onNodeWithTag("navigation.ask").performClick()
            onNodeWithTag("chat.input").assertTextContains("A chat draft to keep")
            runOnIdle { controller.newQuestion(); assertEquals(4, controller.state.value.library.size); assertTrue(runtime.loads.isEmpty()) }
        } finally { controller.close(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun emptyAssetsOfferExistingImportAndMarkdownInputs() = runComposeUiTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val root = Files.createTempDirectory("assets-empty").toFile()
        val controller = AppController(root.path, chatStore(root.path), ChatRuntime(), ChatInputs())
        try {
            setContent { Box(Modifier.width(390.dp).height(740.dp)) { PocketAskApp(controller) } }
            onNodeWithTag("navigation.assets").performClick()
            onNodeWithTag("assets.empty").assertIsDisplayed()
            onNodeWithTag("assets.add").performClick()
            onNodeWithText("Add files").assertIsDisplayed()
            onNodeWithText("Add images").assertIsDisplayed()
            onNodeWithTag("memory.add").assertIsNotEnabled()
            onNodeWithText("Write text").performClick()
            onNodeWithTag("chat.noteTitle").performTextInput("Ideas")
            onNodeWithTag("chat.noteText").performTextInput("# An idea\nRemember this for future chats.")
            onNodeWithText("Add to sources").performClick()
            waitUntil(timeoutMillis = 10_000) { controller.state.value.library.size == 1 && !controller.state.value.importing }
            onNodeWithText("Ideas.md").assertIsDisplayed()
            onNodeWithText("Markdown · Waiting to index").assertIsDisplayed()
            runOnIdle { assertEquals("# An idea\nRemember this for future chats.", File(controller.state.value.library.single().path).readText()) }
        } finally { controller.close(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
}
