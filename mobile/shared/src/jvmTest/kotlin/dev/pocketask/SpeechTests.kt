package dev.pocketask

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import java.nio.file.Files
import kotlin.test.*

internal class FakeSpeech : PlatformSpeech {
    var dictation: DictationResult? = null
    var playback: PlaybackResult? = null
    val spoken = mutableListOf<Pair<String, String>>()
    var stopped = 0
    var finished = 0
    override fun listen(callback: DictationResult) { dictation = callback; callback.ready() }
    override fun finishListening() { finished++ }
    override fun speak(text: String, voice: String, directory: String, callback: PlaybackResult) { spoken += text to voice; playback = callback }
    override fun stop() { stopped++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechTests {
    private fun ready(controller: AppController) {
        controller.models.state(modelSpecs[1], ModelState(installed = true))
        speechModelSpecs.forEach { controller.speech.models.state(it, ModelState(installed = true)) }
    }
    @Test fun partialDictationReplacesItsTranscriptAndPreservesTypedPrefix() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("speech-draft").toFile()
        val platform = FakeSpeech()
        val controller = AppController(root.path, chatStore(root.path), ChatRuntime(), ChatInputs(), worker = dispatcher, platformSpeech = platform)
        try {
            controller.question("Please explain"); controller.speech.toggleListening(); runCurrent()
            assertTrue(controller.speech.state.value.listening)
            platform.dictation!!.text("my", false); runCurrent()
            platform.dictation!!.text("my document", false); runCurrent()
            assertEquals("Please explain my document", controller.state.value.draft.question)
            controller.speech.toggleListening(); assertEquals(1, platform.finished)
            platform.dictation!!.text("my document clearly", true); runCurrent()
            assertEquals("Please explain my document clearly", controller.state.value.draft.question)
            assertFalse(controller.speech.state.value.listening)
            assertTrue(controller.state.value.history.isEmpty())
        } finally { controller.close(); runCurrent(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
    @Test fun newChatAndBackgroundRejectLateMicrophoneAndPlaybackCallbacks() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("speech-lifetime").toFile()
        val platform = FakeSpeech()
        val controller = AppController(root.path, chatStore(root.path), ChatRuntime(), ChatInputs(), worker = dispatcher, platformSpeech = platform)
        try {
            ready(controller)
            controller.speech.toggleListening(); runCurrent(); val old = platform.dictation!!
            controller.newQuestion(); controller.question("Fresh draft")
            old.text("late speech", true); runCurrent()
            assertEquals("Fresh draft", controller.state.value.draft.question)
            controller.speech.preview(); val playing = platform.playback!!
            controller.background(); playing.stage("Reading aloud"); playing.failure("Old error"); runCurrent()
            assertNull(controller.speech.state.value.stage); assertNull(controller.speech.state.value.error)
            val count = platform.spoken.size
            controller.speech.preview(); assertEquals(count, platform.spoken.size)
        } finally { controller.close(); runCurrent(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
    @Test fun completedAnswersAutoplayOnceButOpeningHistoryAndRestartDoNot() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("speech-answer").toFile(); val db = chatStore(root.path)
        val platform = FakeSpeech()
        val controller = AppController(root.path, db, ChatRuntime(), ChatInputs(), worker = dispatcher, platformSpeech = platform)
        var restored: AppController? = null
        try {
            ready(controller)
            controller.question("Question"); controller.ask(); advanceUntilIdle()
            assertEquals(listOf("A concise reply." to "F1"), platform.spoken)
            val answer = controller.state.value.result!!
            controller.showHistory(answer); runCurrent(); assertEquals(1, platform.spoken.size)
            controller.speech.read(answer); assertNull(controller.speech.state.value.answerId)
            controller.speech.voice("M3"); controller.speech.automatic(false)
            controller.question("Second"); controller.ask(); advanceUntilIdle(); assertEquals(1, platform.spoken.size)
            controller.speech.read(controller.state.value.result!!); assertEquals("M3", platform.spoken.last().second)
            controller.close(); runCurrent()
            restored = AppController(root.path, db, ChatRuntime(), ChatInputs(), worker = dispatcher, platformSpeech = platform)
            assertEquals("M3", restored.speech.state.value.voice); assertFalse(restored.speech.state.value.automatic)
            runCurrent(); assertEquals(2, platform.spoken.size)
        } finally { restored?.close(); controller.close(); runCurrent(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
    @Test fun missingVoicesAndDeniedPermissionLeaveTheDraftUsable() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("speech-errors").toFile(); val platform = FakeSpeech()
        val controller = AppController(root.path, chatStore(root.path), ChatRuntime(), ChatInputs(), worker = dispatcher, platformSpeech = platform)
        try {
            controller.question("Typed draft"); controller.speech.toggleListening(); runCurrent()
            platform.dictation!!.failure("Microphone permission denied"); runCurrent()
            assertFalse(controller.speech.state.value.listening); assertEquals("Typed draft", controller.state.value.draft.question)
            val answer = Answer("a", "q", emptyList(), text = "Answer", status = "Completed")
            controller.speech.read(answer, automatic = true); assertTrue(platform.spoken.isEmpty())
            controller.speech.read(answer); assertContains(controller.speech.state.value.error!!, "Download Supertonic")
            assertNull(controller.speech.state.value.answerId)
        } finally { controller.close(); runCurrent(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
    @Test fun failedAndStoppedAnswersNeverAutoplay() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("speech-failed").toFile(); val platform = FakeSpeech()
        val controller = AppController(root.path, chatStore(root.path), ChatRuntime(), ChatInputs(), worker = dispatcher, platformSpeech = platform)
        try {
            ready(controller)
            listOf("Failed", "Stopped", "Interrupted").forEach { status -> controller.speech.read(Answer(status, "q", emptyList(), text = "Partial answer", status = status), automatic = true) }
            assertTrue(platform.spoken.isEmpty())
        } finally { controller.close(); runCurrent(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
}
