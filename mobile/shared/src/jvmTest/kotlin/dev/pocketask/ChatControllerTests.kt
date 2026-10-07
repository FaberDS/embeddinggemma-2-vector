package dev.pocketask

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.pocketask.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import java.nio.file.Files
import kotlin.test.*

internal class ChatInputs : PlatformInputs {
    var time = System.currentTimeMillis()
    val opened = mutableListOf<Pair<String, Int>>()
    override fun pick(images: Boolean, callback: ImportResult) = callback.success()
    override fun pageCount(attachment: Attachment, callback: CountResult) = callback.success(1)
    override fun readPage(attachment: Attachment, page: Int, callback: PageResult) = callback.success(PageInput(java.io.File(attachment.path).readText(), null))
    override fun open(path: String, page: Int) { opened += path to page }
    override fun freeBytes() = Long.MAX_VALUE
    override fun canDownload(cellular: Boolean) = true
    override fun now() = time++
}

internal class ChatRuntime : LocalRuntime {
    val prompts = mutableListOf<String>()
    override fun load(search: Boolean, path: String, callback: Completion) = callback.success()
    override fun embed(text: String, imagePath: String?, query: Boolean, callback: VectorResult) = callback.success(List(256) { if (it == 0) 1f else 0f })
    override fun answer(instructions: String, prompt: String, images: List<String>, callback: StreamResult) {
        prompts += prompt
        callback.token("A concise reply."); callback.success()
    }
    override fun release(callback: Completion) = callback.success()
    override fun cancel() {}
}

internal fun chatStore(root: String): Store {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    AppDatabase.Schema.create(driver)
    return Store(driver, root).also { it.put("onboarded", value = "yes") }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerTests {
    @Test fun followUpsKeepTheThreadAndContextAndNewChatStartsClean() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("pocketask-chat").toFile()
        val db = chatStore(root.path)
        val runtime = ChatRuntime()
        val controller = AppController(root.path, db, runtime, ChatInputs(), worker = dispatcher)
        try {
            controller.models.state(modelSpecs[1], ModelState(installed = true))
            controller.question("First question"); controller.ask(); advanceUntilIdle()
            val first = controller.state.value.result!!
            assertEquals("", controller.state.value.draft.question)
            assertEquals(first.id, db.draft().conversationId)
            assertEquals("answer", first.modelId)
            assertNotNull(first.createdAt)
            assertTrue(first.completedAt!! >= first.createdAt)
            controller.question("Tell me more"); controller.ask(); advanceUntilIdle()
            val second = controller.state.value.result!!
            assertEquals(first.conversationId, second.conversationId)
            assertEquals(2, conversationTurns(controller.state.value).size)
            assertContains(runtime.prompts.last(), "First question")
            assertContains(runtime.prompts.last(), "A concise reply.")
            assertEquals(1, conversationSummaries(db.history()).size)
            controller.newQuestion(); controller.question("Unrelated question"); controller.ask(); advanceUntilIdle()
            assertNotEquals(first.conversationId, controller.state.value.result!!.conversationId)
            assertFalse(runtime.prompts.last().contains("First question"))
            assertEquals(2, conversationSummaries(db.history()).size)
        } finally { controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }

    @Test fun conversationsSurviveRestartAndCanBeDeletedAndRestoredAsAGroup() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("pocketask-chat-history").toFile()
        val db = chatStore(root.path)
        val controller = AppController(root.path, db, ChatRuntime(), ChatInputs(), worker = dispatcher)
        var restored: AppController? = null
        try {
            controller.models.state(modelSpecs[1], ModelState(installed = true))
            repeat(2) { controller.question("Question $it"); controller.ask(); advanceUntilIdle() }
            val latest = controller.state.value.result!!
            controller.close(); advanceUntilIdle()
            restored = AppController(root.path, db, ChatRuntime(), ChatInputs(), worker = dispatcher)
            assertEquals(2, conversationTurns(restored.state.value).size)
            restored.newQuestion()
            assertTrue(conversationTurns(restored.state.value).isEmpty())
            restored.showHistory(latest)
            restored.continueConversation(latest)
            assertEquals("", restored.state.value.draft.question)
            assertEquals(2, conversationTurns(restored.state.value).size)
            restored.deleteConversation(latest.conversationId)
            assertTrue(db.history().isEmpty())
            assertTrue(restored.canUndo())
            restored.undoDelete()
            assertEquals(2, db.history().size)
            assertFalse(restored.canUndo())
            assertEquals(2, conversationTurns(restored.state.value).size)
        } finally { restored?.close(); controller.close(); advanceUntilIdle(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
}
