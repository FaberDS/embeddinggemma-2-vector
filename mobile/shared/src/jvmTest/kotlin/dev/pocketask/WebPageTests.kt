package dev.pocketask

import androidx.compose.ui.text.LinkAnnotation
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpResponseData
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WebPageTests {
    private val article = "<html><head><title>Garden &amp; flowers</title></head><body><nav>Do not index navigation</nav><article><h1>Planting</h1><p>Plant flowers near the fence. Water them regularly and keep their roots cool. Choose a sunny location for your garden.</p><script>doNotExecute()</script></article></body></html>"

    @Test fun validatesUrlsExtractsArticleAndRejectsAccessScreens() {
        assertEquals("https://example.com/article", webUrl(" https://example.com/article#section "))
        listOf("file:///tmp/private", "javascript:alert(1)", "example.com", "https://", "https://user:pass@example.com", "https://example.com/a b", "https://example.com\\login").forEach { assertFailsWith<IllegalArgumentException>(it) { webUrl(it) } }
        val page = extractWebPage(article, "https://example.com/article")
        assertEquals("Garden & flowers", page.title)
        assertContains(page.text, "Plant flowers"); assertFalse(page.text.contains("navigation")); assertFalse(page.text.contains("doNotExecute"))
        listOf("<title>Sign in</title><p>${"Please enter your credentials. ".repeat(12)}</p>", "<title>Article</title><input type=password><p>${"Content ".repeat(20)}</p>", "<title>Article</title><p>Enable JavaScript and verify you are human to continue.</p>", "<p></p>", "<title>Consent</title><p>${"Accept cookies to continue. ".repeat(10)}</p>").forEach { assertFailsWith<IllegalArgumentException> { extractWebPage(it, "https://example.com/article") } }
        assertFailsWith<IllegalArgumentException> { extractWebPage(article, "https://example.com/login") }
    }

    @Test fun linksOpenValidatedWebUrlsWithoutChangingLocalCitations() {
        val opened = mutableListOf<String>()
        val annotated = linkedAnswer("See [Garden](https://example.com/a_(b)) and https://example.org/path. Evidence [S1]. [unsafe](javascript:alert(1))", open = opened::add)
        assertEquals("See Garden and https://example.org/path. Evidence [S1]. [unsafe](javascript:alert(1))", annotated.text)
        val links = annotated.getLinkAnnotations(0, annotated.length).map { it.item as LinkAnnotation.Url }
        assertEquals(listOf("https://example.com/a_(b)", "https://example.org/path"), links.map { it.url })
        links.forEach { it.linkInteractionListener!!.onClick(it) }
        assertEquals(links.map { it.url }, opened)
    }

    @Test fun fetchChecksRedirectsStatusTypeEncodingAndActualBodySize() = runTest {
        suspend fun fetch(handler: MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> HttpResponseData) = fetchWebPage("https://example.com/article", HttpClient(MockEngine { request -> handler(request) }) { followRedirects = false })
        val page = fetch { request -> if (request.url.encodedPath == "/article") respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/final")) else respond(article, headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")) }
        assertEquals("https://example.com/final", page.url)
        for ((body, status, headers) in listOf(
            Triple("", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "text/html")),
            Triple("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/pdf")),
            Triple("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/login")),
            Triple("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "file:///tmp/private")),
            Triple("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://example.com/final")),
            Triple(article, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html; charset=windows-1252")),
            Triple("a".repeat(2_000_001), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
        )) assertFails { fetch { respond(body, status, headers) } }
        assertFails { fetch { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/loop")) } }
        val offline = assertFailsWith<IllegalStateException> { fetch { throw java.io.IOException("Engine detail") } }
        assertContains(offline.message!!, "Check your connection"); assertFalse(offline.message!!.contains("Engine detail"))
        assertFailsWith<CancellationException> { fetch { throw CancellationException("Cancelled") } }
    }

    @Test fun previewCancelRetrySaveDeduplicateAndRestoreUseExistingIndex() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("web-import").toFile()
        val db = chatStore(root.path)
        val runtime = MemoryRuntime()
        val inputs = ChatInputs()
        var fail = false
        val controller = AppController(root.path, db, runtime, inputs, worker = dispatcher, webFetcher = { url -> if (fail) error("Offline") else extractWebPage(article, url) })
        try {
            controller.models.state(modelSpecs[0], ModelState(installed = true))
            controller.question("Keep my draft")
            controller.insertLink("https://example.com/article"); controller.previewWeb(); runCurrent()
            assertNotNull(controller.state.value.web?.page); assertTrue(controller.state.value.library.isEmpty()); assertTrue(runtime.indexed.isEmpty())
            controller.cancelWeb(); assertNull(controller.state.value.web); assertTrue(db.library(root.path).isEmpty())
            fail = true; controller.insertLink("https://example.com/article"); controller.previewWeb(); runCurrent()
            assertContains(controller.state.value.web!!.error!!, "Offline"); assertFalse(controller.state.value.web!!.loading)
            assertTrue(controller.state.value.library.isEmpty())
            fail = false; controller.previewWeb(); runCurrent(); controller.importWeb(); advanceUntilIdle()
            val source = controller.state.value.library.single()
            assertTrue(source.isWebPage); assertTrue(source.prepared); assertFalse(source.needsImageDescriptions)
            assertEquals("https://example.com/article", source.sourceUrl); assertNotNull(source.createdAt)
            assertContains(java.io.File(source.path).readText(), "Plant flowers"); assertContains(runtime.indexed.joinToString(), "Plant flowers")
            assertEquals("Keep my draft", controller.state.value.draft.question)
            assertEquals(source, db.library(root.path).single())
            controller.insertLink("https://example.com/article"); controller.previewWeb(); runCurrent(); controller.importWeb(); advanceUntilIdle()
            assertEquals(1, controller.state.value.library.size)
            controller.open(source.path); controller.open(source.sourceUrl!!)
            assertEquals(listOf(source.path to 1, source.sourceUrl to 1), inputs.opened)
        } finally { controller.close(); Dispatchers.resetMain(); root.deleteRecursively() }
    }

    @Test fun cancelledFetchCannotPopulateAnotherPreviewOrCreateAnAsset() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("web-cancel").toFile()
        val controller = AppController(root.path, chatStore(root.path), ChatRuntime(), ChatInputs(), worker = dispatcher, webFetcher = { url -> delay(1000); extractWebPage(article, url) })
        try {
            controller.insertLink("https://example.com/old"); controller.previewWeb(); runCurrent()
            controller.cancelWeb(); controller.insertLink("https://example.com/new"); advanceUntilIdle()
            assertEquals("https://example.com/new", controller.state.value.web!!.url)
            assertNull(controller.state.value.web!!.page); assertTrue(controller.state.value.library.isEmpty())
        } finally { controller.close(); Dispatchers.resetMain(); root.deleteRecursively() }
    }

    @Test fun sharedUrlsWaitForPreviewAndReplayedFilesAreNotImportedAgain() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("web-share").toFile()
        val store = chatStore(root.path)
        val controller = AppController(root.path, store, ChatRuntime(), ChatInputs(), worker = dispatcher, initiallyActive = false)
        try {
            val completions = mutableListOf<Boolean>()
            controller.receiveShare("url-handoff", "https://example.com/article", emptyList(), completed = { completions += it }); runCurrent()
            assertTrue(completions.isEmpty())
            assertNull(controller.state.value.web)
            controller.foreground(); advanceUntilIdle()
            assertEquals(listOf(true), completions)
            assertEquals("https://example.com/article", controller.state.value.web!!.url)
            assertNull(controller.state.value.web!!.page); assertTrue(controller.state.value.library.isEmpty())
            val restored = AppController(root.path, store, ChatRuntime(), ChatInputs(), worker = dispatcher)
            assertEquals("https://example.com/article", restored.state.value.web!!.url)
            restored.close()
            controller.receiveShare("url-handoff", "https://example.com/article", emptyList(), completed = { completions += it }); advanceUntilIdle()
            assertEquals(listOf(true, true), completions)
            controller.cancelWeb()
            val staged = java.io.File(root, "staging/share.txt").apply { parentFile.mkdirs(); writeText("Shared content") }
            val files = listOf(SharedFile("shared.txt", staged.path, "text/plain"))
            controller.receiveShare("files-handoff", null, files); advanceUntilIdle()
            assertEquals(1, controller.state.value.library.size); assertFalse(staged.exists()); assertTrue(controller.receivedShare("files-handoff"))
            controller.receiveShare("files-handoff", null, files); advanceUntilIdle()
            assertEquals(1, controller.state.value.library.size); assertNull(controller.state.value.error)
            controller.receiveShare("invalid", "javascript:alert(1)", emptyList(), completed = { completions += it }); advanceUntilIdle()
            assertEquals(false, completions.last())
            assertNotNull(controller.state.value.error); assertNull(controller.state.value.web); assertFalse(controller.receivedShare("invalid"))
            controller.receiveShare("private", null, listOf(SharedFile("private.txt", "/outside/file", "text/plain"))); advanceUntilIdle()
            assertContains(controller.state.value.error!!, "safely"); assertEquals(1, controller.state.value.library.size)
        } finally { controller.close(); Dispatchers.resetMain(); root.deleteRecursively() }
    }
}
