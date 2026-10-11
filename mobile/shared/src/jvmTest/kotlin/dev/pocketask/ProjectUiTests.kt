package dev.pocketask

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.pocketask.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ProjectUiTests {
    @Test fun usersCreateSelectProjectsAndShareOrUnassignAnItem() = runComposeUiTest {
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
            setContent { Box(Modifier.width(390.dp).height(740.dp)) { PocketAskApp(controller) } }
            onNodeWithTag("chat.input").performTextInput("Before choosing")
            onNodeWithTag("chat.send").assertIsNotEnabled()
            fun create(name: String): String {
                onNodeWithTag("project.select").performClick()
                onNodeWithTag("project.new").performClick()
                onNodeWithTag("project.create").assertIsNotEnabled()
                onNodeWithTag("project.name").performTextInput(name)
                onNodeWithTag("project.create").performClick()
                return controller.state.value.draft.projectId!!
            }
            val alpha = create("Alpha")
            val beta = create("Beta")
            onNodeWithTag("navigation.assets").performClick()
            onNodeWithTag("assets.projects.ideas").performScrollTo().performClick()
            onNodeWithTag("assets.membership.$alpha").assertIsOff().performClick().assertIsOn()
            onNodeWithTag("assets.membership.$beta").assertIsOff().performClick().assertIsOn()
            runOnIdle { assertEquals(setOf(alpha, beta), store.library(root.path).single().projectIds) }
            onNodeWithTag("assets.membership.$alpha").performClick().assertIsOff()
            onNodeWithText("Done").performClick()
            onNodeWithTag("project.select").performClick()
            onNodeWithTag("project.select.$alpha").performClick()
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
}
