package dev.pocketask

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.pocketask.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.File
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ProjectUiTests {
    @Test fun usersCreateSelectProjectsAndShareOrUnassignAnItem() = runSkikoComposeUiTest(size = Size(390f, 740f)) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val root = Files.createTempDirectory("project-ui").toFile()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val store = Store(driver, root.path)
        store.put("onboarded", value = "yes")
        val file = File(root, "Ideas.txt").apply { writeText("Ideas for both projects") }
        val item = Attachment("ideas", file.name, file.path, "text/plain")
        store.saveSource(item); store.markPrepared(item)
        val controller = AppController(root.path, store, ChatRuntime(), ChatInputs())
        try {
            setContent { Box(Modifier.width(390.dp).height(740.dp).testTag("project.preview")) { PocketAskApp(controller) } }
            onNodeWithTag("chat.input").performTextInput("Before choosing")
            onNodeWithTag("chat.send").assertIsNotEnabled()
            fun create(name: String): String {
                onNodeWithTag("project.select").performClick()
                if (controller.state.value.projects.isEmpty()) {
                    onNodeWithText("Create a project to give your chats their own context.").assertIsDisplayed()
                    savePreview("project.picker", "empty")
                }
                onNodeWithTag("project.new").performClick()
                onNodeWithTag("project.create").assertIsNotEnabled()
                onNodeWithTag("project.name").performTextInput(name)
                onNodeWithTag("project.create").performClick()
                return controller.state.value.draft.projectId!!
            }
            val alpha = create("Alpha")
            val beta = create("Beta")
            savePreview("project.preview", "collapsed")
            onNodeWithTag("project.select").performClick()
            onNodeWithTag("project.select.$beta").assertIsSelected()
            onNodeWithTag("project.select.$alpha").assertIsNotSelected()
            savePreview("project.picker", "expanded")
            onNodeWithTag("project.close").performClick()
            onNodeWithTag("project.picker").assertDoesNotExist()
            runOnIdle { assertEquals(beta, controller.state.value.draft.projectId) }
            onNodeWithTag("navigation.assets").performClick()
            onNodeWithTag("assets.projects.ideas").performScrollTo().performClick()
            onNodeWithTag("assets.membership.$alpha").assertIsOff().performClick().assertIsOn()
            onNodeWithTag("assets.membership.$beta").assertIsOff().performClick().assertIsOn()
            runOnIdle { assertEquals(setOf(alpha, beta), store.library(root.path).single().projectIds) }
            onNodeWithTag("assets.membership.$alpha").performClick().assertIsOff()
            onNodeWithText("Done").performClick()
            onNodeWithTag("project.select").performClick()
            onNodeWithTag("project.select.$alpha").performClick()
            onNodeWithTag("project.picker").assertDoesNotExist()
            runOnIdle { assertEquals(alpha, controller.state.value.draft.projectId) }
            onNodeWithTag("navigation.ask").performClick()
            onNodeWithTag("chat.manageSources").performClick()
            onNodeWithText("No items assigned. Add items to this project from Assets.").assertIsDisplayed()
            onNodeWithText("Done").performClick()
            onNodeWithTag("project.select").performClick()
            onNodeWithTag("project.select.$beta").performClick()
            onNodeWithTag("chat.manageSources").performClick()
            onNodeWithText("Ideas.txt").assertIsDisplayed()
            onNodeWithText("Unassign").performClick()
            runOnIdle {
                assertTrue(store.library(root.path).single().projectIds.isEmpty())
                assertTrue(file.exists())
                assertEquals(2, store.projects().size)
            }
        } finally { controller.close(); driver.close(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun longProjectNamesAndScrollableSelectionStayUsable() = runSkikoComposeUiTest(size = Size(320f, 480f)) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val root = Files.createTempDirectory("project-ui-long").toFile()
        val controller = AppController(root.path, chatStore(root.path), ChatRuntime(), ChatInputs())
        try {
            runOnIdle { repeat(12) { controller.createProject("Project $it with a longer name for a narrow phone") } }
            val selected = controller.state.value.draft.projectId!!
            val first = controller.state.value.projects.first().id
            setContent {
                val state by controller.state.collectAsState()
                MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF4C6758), primaryContainer = Color(0xFFE0EBE2),
                    onPrimaryContainer = Color(0xFF1F3528), surface = Color(0xFFFAFBF8))) {
                    Surface(Modifier.fillMaxSize().testTag("project.preview")) {
                        Column { Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                            Text("Locune", style = MaterialTheme.typography.titleLarge)
                            ProjectSelector(controller, state, Modifier.weight(1f).padding(start = 12.dp))
                        } }
                    }
                }
            }
            savePreview("project.preview", "light-collapsed")
            onNodeWithTag("project.select").assertIsDisplayed().performClick()
            onNodeWithTag("project.list").performScrollToNode(hasTestTag("project.select.$selected"))
            onNodeWithTag("project.select.$selected").assertIsDisplayed().assertIsSelected()
            savePreview("project.picker", "long-names")
            onNodeWithTag("project.list").performScrollToNode(hasTestTag("project.select.$first"))
            onNodeWithTag("project.select.$first").performClick()
            onNodeWithTag("project.picker").assertDoesNotExist()
            runOnIdle { assertEquals(first, controller.state.value.draft.projectId) }
        } finally { controller.close(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.savePreview(tag: String, name: String) {
    waitForIdle()
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    if (tag == "project.picker") waitUntil(timeoutMillis = 5_000) {
        onNodeWithTag("project.new").fetchSemanticsNode().boundsInRoot.bottom <=
            onAllNodes(isRoot()).onLast().fetchSemanticsNode().boundsInRoot.bottom - 10f
    }
    val node = if (tag == "project.picker") onAllNodes(isRoot()).onLast() else onNodeWithTag(tag)
    val pixels = node.captureToImage().toPixelMap()
    val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
    ImageIO.write(image, "png", File("/private/tmp/sch-29-project-$name.png"))
}
