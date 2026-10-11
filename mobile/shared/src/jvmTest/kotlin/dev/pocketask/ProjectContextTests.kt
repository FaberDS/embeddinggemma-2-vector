package dev.pocketask

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.pocketask.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectContextTests {
    @Test fun projectEvidenceHistoryAndMembershipsStayIsolatedAcrossRestart() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("project-context").toFile()
        var driver = JdbcSqliteDriver("jdbc:sqlite:${root.path}/app.db")
        AppDatabase.Schema.create(driver)
        var store = Store(driver, root.path)
        store.put("library-migrated-v1", value = "yes")
        val prompts = mutableListOf<String>()
        val answerInstructions = mutableListOf<String>()
        var queries = 0
        val runtime = object : LocalRuntime {
            override fun load(search: Boolean, path: String, callback: Completion) = callback.success()
            override fun embed(text: String, imagePath: String?, query: Boolean, callback: VectorResult) {
                queries++
                callback.success(List(256) { if (it == 0) 1f else 0f })
            }
            override fun answer(instructions: String, prompt: String, images: List<String>, callback: StreamResult) {
                answerInstructions += instructions
                prompts += prompt
                callback.token("Saved reply [S1].")
                callback.success()
            }
            override fun release(callback: Completion) = callback.success()
            override fun cancel() {}
        }
        fun source(id: String, text: String) {
            val file = File(root, "inputs/$id/original.txt").apply { parentFile.mkdirs(); writeText(text) }
            val source = Attachment(id, "$id.txt", file.path, "text/plain")
            store.saveSource(source)
            store.addEvidence(Evidence(id, id, source.name, null, text, null, List(256) { if (it == 0) 1f else 0f }))
            store.markPrepared(source)
        }
        source("unassigned", "Unassigned private fact")
        source("a", "Alpha private fact")
        source("b", "Beta private fact")
        source("shared", "Shared flower fact")
        var controller = AppController(root.path, store, runtime, ChatInputs(), worker = dispatcher)
        try {
            controller.question("Needs a project"); controller.ask(); advanceUntilIdle()
            assertTrue(store.history().isEmpty())
            assertTrue(prompts.isEmpty())
            assertContains(controller.state.value.error!!, "select a project")
            controller.createProject(" Alpha ")
            val alpha = controller.state.value.draft.projectId!!
            controller.createProject("Beta")
            val beta = controller.state.value.draft.projectId!!
            controller.setMembership("a", alpha, true)
            controller.setMembership("b", beta, true)
            controller.setMembership("shared", alpha, true)
            controller.setMembership("shared", beta, true)
            modelSpecs.take(2).forEach { controller.models.state(it, ModelState(installed = true)) }
            advanceUntilIdle()

            controller.selectProject(alpha)
            controller.question("Alpha first question"); controller.ask(); advanceUntilIdle()
            val first = controller.state.value.result!!
            assertEquals(alpha, first.projectId)
            assertEquals(setOf("a", "shared"), first.sources.map { it.attachmentId }.toSet())
            assertContains(prompts.last(), "Alpha private fact")
            assertContains(prompts.last(), "Shared flower fact")
            assertFalse(prompts.last().contains("Beta private fact"))
            assertFalse(prompts.last().contains("Unassigned private fact"))

            controller.selectProject(beta)
            assertNull(controller.state.value.draft.conversationId)
            assertNull(controller.state.value.result)
            assertTrue(controller.state.value.projectHistory.isEmpty())
            controller.question("Beta first question"); controller.ask(); advanceUntilIdle()
            val betaAnswer = controller.state.value.result!!
            assertEquals(setOf("b", "shared"), betaAnswer.sources.map { it.attachmentId }.toSet())
            assertFalse(prompts.last().contains("Alpha"))
            controller.question("Beta follow-up"); controller.ask(); advanceUntilIdle()
            assertContains(prompts.last(), "Beta first question")
            assertEquals(betaAnswer.conversationId, controller.state.value.result!!.conversationId)

            controller.continueConversation(first)
            assertEquals(alpha, controller.state.value.draft.projectId)
            controller.question("Alpha follow-up"); controller.ask(); advanceUntilIdle()
            assertContains(prompts.last(), "Alpha first question")
            assertFalse(prompts.last().contains("Beta"))
            controller.setMembership("shared", alpha, false)
            controller.question("After removal"); controller.ask(); advanceUntilIdle()
            assertEquals(listOf("a"), controller.state.value.result!!.sources.map { it.attachmentId })
            assertFalse(prompts.last().contains("Shared flower fact"))
            assertFalse(prompts.last().contains("Earlier conversation"))
            assertEquals(setOf(beta), controller.state.value.library.first { it.id == "shared" }.projectIds)
            assertEquals(1, store.evidence("shared", 0).size)
            assertTrue(File(root, "inputs/shared/original.txt").exists())

            controller.setMembership("a", alpha, false)
            val queriesBefore = queries
            controller.question("Empty project"); controller.ask(); advanceUntilIdle()
            assertTrue(controller.state.value.result!!.sources.isEmpty())
            assertEquals(queriesBefore, queries)
            assertEquals("Empty project", prompts.last())
            assertContains(answerInstructions.last(), "If they do not support an answer, say so.")
            assertFailsWith<IllegalArgumentException> { store.save(first.copy(projectId = beta)) }
            assertFailsWith<IllegalArgumentException> { store.setMembership("shared", "missing", true) }

            controller.close(); advanceUntilIdle(); driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:${root.path}/app.db")
            store = Store(driver, root.path)
            controller = AppController(root.path, store, runtime, ChatInputs(), worker = dispatcher)
            assertEquals(alpha, controller.state.value.draft.projectId)
            assertEquals("Alpha", controller.state.value.projects.first { it.id == alpha }.name)
            assertEquals(setOf(beta), controller.state.value.library.first { it.id == "shared" }.projectIds)
            assertTrue(controller.state.value.library.first { it.id == "unassigned" }.projectIds.isEmpty())
            assertTrue(controller.state.value.library.first { it.id == "a" }.projectIds.isEmpty())
            assertEquals(1, store.evidence("shared", 0).size)
            assertTrue(store.history().all { it.projectId in setOf(alpha, beta) })
            controller.clearHistory()
            assertTrue(store.history().all { it.projectId == beta })
            assertEquals(2, store.history().size)
            controller.useAgain(betaAnswer)
            modelSpecs.take(2).forEach { controller.models.state(it, ModelState(installed = true)) }
            controller.ask(); advanceUntilIdle()
            assertEquals(beta, controller.state.value.result!!.projectId)
            assertEquals(setOf("b", "shared"), controller.state.value.result!!.sources.map { it.attachmentId }.toSet())
            assertFalse(prompts.last().contains("Alpha"))
            assertFalse(prompts.last().contains("Unassigned private fact"))
        } finally { controller.close(); advanceUntilIdle(); driver.close(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
}
