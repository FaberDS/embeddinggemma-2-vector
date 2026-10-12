package dev.pocketask

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.pocketask.db.AppDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.*

internal fun audioTestWav(path: String, samples: List<Short> = listOf(100, -100), rate: Int = 24000) {
    val buffer = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVEfmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples.size * 2)
    samples.forEach { buffer.putShort(it) }
    File(path).writeBytes(buffer.array())
}

internal class AudioRuntime : LocalRuntime by ChatRuntime() {
    val prompts = mutableListOf<String>()
    val instructions = mutableListOf<String>()
    val limits = mutableListOf<Int>()
    var loaded = false
    var malformed = false
    var malformedOnce = false
    val schemas = mutableListOf<String>()
    override fun answerStructured(instructions: String, prompt: String, schema: String, maxOutputTokens: Int, callback: StreamResult) {
        schemas += schema
        answer(instructions, prompt, emptyList(), callback, maxOutputTokens)
    }
    override fun load(search: Boolean, path: String, callback: Completion) { loaded = true; callback.success() }
    override fun release(callback: Completion) { loaded = false; callback.success() }
    override fun answer(instructions: String, prompt: String, images: List<String>, callback: StreamResult, maxOutputTokens: Int) {
        this.instructions += instructions; prompts += prompt; limits += maxOutputTokens
        val first = """{"speaker":1,"text":"A supported idea.","sourceIds":[1]}"""
        val second = """,{"speaker":2,"text":"A supported connection.","sourceIds":[1]}"""
        callback.token(if (malformed || malformedOnce) "{\"turns\":[" else "{\"turns\":[$first${if (instructions.contains("Use only speaker 1")) "" else second}]}")
        malformedOnce = false
        callback.success()
    }
}

internal class AudioSpeech(private val runtime: AudioRuntime) : PlatformSpeech by FakeSpeech() {
    val rendered = mutableListOf<Pair<List<AudioTurn>, String>>()
    val played = mutableListOf<String>()
    var held: PlaybackResult? = null
    var hold = false
    var fail = false
    override fun renderAudio(turns: List<AudioTurn>, language: String, directory: String, outputDirectory: String, callback: PlaybackResult) {
        check(!runtime.loaded) { "Text model must be released before speech synthesis." }
        rendered += turns to language
        turns.indices.forEach { audioTestWav("$outputDirectory/$it.wav") }
        callback.stage("Preparing audio…")
        if (hold) held = callback else if (fail) callback.failure("Synthesis failed; try again.") else callback.success()
    }
    override fun playFile(path: String, callback: PlaybackResult) { check(File(path).isFile); played += path; callback.stage("Playing saved audio…") }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectAudioControllerTests {
    private class Fixture(val dispatcher: TestDispatcher) {
        val root = Files.createTempDirectory("project-audio").toFile()
        var driver = JdbcSqliteDriver("jdbc:sqlite:${root.path}/app.db").also { AppDatabase.Schema.create(it) }
        var store = Store(driver, root.path)
        val runtime = AudioRuntime()
        val speech = AudioSpeech(runtime)
        val shared = mutableListOf<String>()
        var sharingFails = false
        val inputs = object : PlatformInputs by ChatInputs() {
            override fun shareAudio(path: String, callback: Completion) {
                check(File(path).isFile); shared += path
                if (sharingFails) callback.failure("Sharing failed; retry.") else callback.success()
            }
        }
        var controller: AppController
        init {
            Dispatchers.setMain(dispatcher)
            store.put("library-migrated-v1", value = "yes")
            store.saveProject(Project("alpha", "Alpha")); store.saveProject(Project("beta", "Beta"))
            store.saveDraft(Draft(projectId = "alpha"))
            fun source(id: String, projects: Set<String>, text: String) {
                val file = File(root, "inputs/$id/original.txt").apply { parentFile.mkdirs(); writeText(text) }
                val item = Attachment(id, "$id.txt", file.path, "text/plain", projectIds = projects)
                store.saveSource(item); store.markPrepared(item)
                store.addEvidence(Evidence(id, id, item.name, null, text, null, List(256) { if (it == 0) 1f else 0f }))
            }
            source("alpha-only", setOf("alpha"), "Alpha private idea")
            source("beta-only", setOf("beta"), "Beta private idea")
            source("shared", setOf("alpha", "beta"), "Shared idea")
            source("unassigned", emptySet(), "Unassigned private idea")
            controller = newController()
        }
        fun newController() = AppController(root.path, store, runtime, inputs, worker = dispatcher, platformSpeech = speech)
        fun ready() {
            controller.question("Explore the ideas and their connections")
            controller.models.state(modelSpecs[1], ModelState(installed = true))
            speechModelSpecs.forEach { controller.speech.models.state(it, ModelState(installed = true)) }
        }
        fun close() { controller.close(); driver.close(); root.deleteRecursively(); Dispatchers.resetMain() }
    }
    @Test fun bothStylesUseCurrentMembershipAndSaveRealFilesAcrossRestart() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            f.ready(); advanceUntilIdle()
            f.controller.openAudio("alpha")
            assertEquals(AudioSettings(), f.controller.state.value.audioSettings)
            f.controller.generateAudio(); advanceUntilIdle()
            val podcast = f.controller.state.value.audio.single()
            assertEquals("alpha", podcast.projectId)
            assertEquals(setOf("alpha-only", "shared"), podcast.sources.map { it.attachmentId }.toSet())
            assertEquals(listOf("F1", "M1"), podcast.turns.map { it.voice })
            assertEquals(listOf(2560), f.runtime.limits)
            assertContains(f.runtime.prompts.single(), "Audio request: Explore the ideas and their connections")
            assertEquals("Explore the ideas and their connections", podcast.prompt)
            assertEquals(audioSchema(AudioSettings(), 2), f.runtime.schemas.single())
            assertFalse(f.runtime.prompts.last().contains("Beta private")); assertFalse(f.runtime.prompts.last().contains("Unassigned"))
            val podcastFile = File(f.root, "audio/${podcast.id}.wav")
            assertEquals(52L, podcastFile.length())
            assertEquals(4.0 / 24000, podcast.durationSeconds)

            val settings = AudioSettings(length = "Short", detail = "Detailed", style = "Tutor", language = "de", firstVoice = "M3", shortMinutes = 3)
            f.controller.audioSettings(settings)
            f.controller.closeAudio(); f.controller.selectProject("beta"); f.controller.openAudio("beta")
            f.controller.question("Explain the Beta project")
            assertEquals(AudioSettings(), f.controller.state.value.audioSettings)
            f.controller.generateAudio(); advanceUntilIdle()
            val beta = f.controller.state.value.audio.first { it.projectId == "beta" }
            assertEquals(setOf("beta-only", "shared"), beta.sources.map { it.attachmentId }.toSet())
            assertFalse(f.runtime.prompts.last().contains("Alpha private"))
            f.controller.playAudio(podcast.id); assertTrue(f.speech.played.isEmpty())
            f.controller.shareAudio(podcast.id); assertTrue(f.shared.isEmpty())
            f.controller.closeAudio(); f.controller.selectProject("alpha"); f.controller.openAudio("alpha")
            assertEquals(settings, f.controller.state.value.audioSettings)
            f.controller.selectProject("beta"); assertNull(f.controller.state.value.audioProjectId)
            f.controller.selectProject("alpha"); f.controller.openAudio("alpha")
            f.controller.question("Explain the Alpha project")
            f.controller.setMembership("shared", "alpha", false)
            f.controller.generateAudio(); advanceUntilIdle()
            val tutor = f.controller.state.value.audio.first { it.settings.style == "Tutor" }
            assertEquals(listOf("alpha-only"), tutor.sources.map { it.attachmentId })
            assertEquals(listOf("M3"), tutor.turns.map { it.voice })
            assertEquals("de", f.speech.rendered.last().second)
            assertEquals(1536, f.runtime.limits.last())
            assertContains(f.runtime.instructions.last(), "3 minutes"); assertContains(f.runtime.instructions.last(), "Detailed")
            assertFalse(f.runtime.prompts.last().contains("Shared idea"))
            f.controller.close(); advanceUntilIdle(); f.driver.close()
            f.driver = JdbcSqliteDriver("jdbc:sqlite:${f.root.path}/app.db"); f.store = Store(f.driver, f.root.path)
            f.controller = f.newController()
            advanceUntilIdle(); f.controller.openAudio("alpha")
            assertEquals(settings, f.controller.state.value.audioSettings)
            assertEquals(3, f.controller.state.value.audio.size)
            assertEquals(podcast.prompt, f.controller.state.value.audio.first { it.id == podcast.id }.prompt)
            // Playback and sharing work without the answer or voice models installed.
            f.controller.playAudio(podcast.id); runCurrent()
            assertEquals(podcastFile.path, f.speech.played.single())
            f.controller.shareAudio(podcast.id); assertEquals(podcastFile.path, f.shared.single())
            f.sharingFails = true; f.controller.shareAudio(podcast.id); runCurrent()
            assertContains(f.controller.state.value.audioError!!, "Sharing failed")
            assertTrue(podcastFile.exists())
        } finally { f.controller.close(); advanceUntilIdle(); f.close() }
    }
    @Test fun emptyFailedAndCancelledGenerationsNeverSavePartialAudioAndCanRetry() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            f.ready(); advanceUntilIdle(); f.controller.openAudio("alpha")
            f.runtime.malformed = true; f.controller.generateAudio(); advanceUntilIdle()
            assertContains(f.controller.state.value.audioError!!, "after retrying"); assertTrue(f.store.audio().isEmpty())
            assertEquals(2, f.runtime.prompts.size)
            f.runtime.malformed = false; f.speech.fail = true
            f.controller.generateAudio(); advanceUntilIdle()
            assertContains(f.controller.state.value.audioError!!, "Synthesis failed"); assertTrue(f.store.audio().isEmpty())
            f.speech.fail = false; f.speech.hold = true
            f.controller.generateAudio(); runCurrent()
            assertTrue(f.controller.state.value.audioGenerating)
            f.controller.cancelAudio(); advanceUntilIdle()
            assertContains(f.controller.state.value.audioError!!, "stopped")
            assertTrue(f.store.audio().isEmpty()); assertNull(f.controller.state.value.stage)
            f.speech.held!!.success(); f.speech.held!!.stage("Late stage"); runCurrent()
            assertNull(f.controller.state.value.stage); assertTrue(f.store.audio().isEmpty())
            assertTrue(File(f.root, "audio").walk().none { it.isFile })
            f.speech.hold = false; f.controller.generateAudio(); advanceUntilIdle()
            assertEquals(1, f.store.audio().size); assertNull(f.controller.state.value.audioError)
            f.controller.setMembership("alpha-only", "alpha", false); f.controller.setMembership("shared", "alpha", false)
            val count = f.runtime.prompts.size
            f.controller.generateAudio(); advanceUntilIdle()
            assertContains(f.controller.state.value.audioError!!, "no context"); assertEquals(count, f.runtime.prompts.size)
            assertEquals(1, f.store.audio().size)
        } finally { f.controller.close(); advanceUntilIdle(); f.close() }
    }
    @Test fun wavAssemblyPreservesSamplesAndRejectsDamagedOrIncompatibleParts() {
        val root = Files.createTempDirectory("audio-join").toFile()
        try {
            val first = File(root, "1.wav"); val second = File(root, "2.wav"); val output = File(root, "complete.wav")
            audioTestWav(first.path, listOf(Short.MIN_VALUE, 0)); audioTestWav(second.path, listOf(Short.MAX_VALUE))
            assertEquals(3.0 / 24000, joinAudio(listOf(first.path, second.path), output.path))
            val bytes = ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(42, bytes.getInt(4)); assertEquals(6, bytes.getInt(40))
            assertEquals(Short.MIN_VALUE, bytes.getShort(44)); assertEquals(0, bytes.getShort(46).toInt()); assertEquals(Short.MAX_VALUE, bytes.getShort(48))
            audioTestWav(second.path, rate = 44100)
            assertFailsWith<IllegalArgumentException> { joinAudio(listOf(first.path, second.path), output.path) }
            second.writeBytes(first.readBytes().dropLast(1).toByteArray())
            assertFailsWith<IllegalArgumentException> { joinAudio(listOf(second.path), output.path) }
            assertFailsWith<IllegalArgumentException> { joinAudio(emptyList(), output.path) }
        } finally { root.deleteRecursively() }
    }
    @Test fun truncatedStructuredScriptRetriesOnceWithoutSavingOrSpeakingTheFragment() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            f.ready(); advanceUntilIdle(); f.controller.openAudio("alpha")
            f.runtime.malformedOnce = true
            f.controller.generateAudio(); advanceUntilIdle()
            assertEquals(2, f.runtime.prompts.size)
            assertEquals(f.runtime.prompts.first(), f.runtime.prompts.last())
            assertContains(f.runtime.instructions.last(), "shorter, complete script")
            assertEquals(1, f.speech.rendered.size)
            assertEquals(1, f.store.audio().size)
            assertNull(f.controller.state.value.audioError)
            assertEquals(listOf(2560, 2560), f.runtime.limits)
        } finally { f.controller.close(); advanceUntilIdle(); f.close() }
    }
    @Test fun projectSamplingIncludesLaterPassagesAndMultipleImageDescriptionsWithinBudget() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            repeat(150) { index ->
                f.store.addEvidence(Evidence("large-${index.toString().padStart(3, '0')}", "alpha-only", "Large source", index + 1,
                    "Passage $index " + "detail ".repeat(180), null, emptyList()))
            }
            repeat(2) { index ->
                f.store.addEvidence(Evidence("image-$index", "shared", "Image $index", null, "Image description $index",
                    "${f.root}/inputs/shared/$index.jpg", emptyList(), kind = "description"))
            }
            val pack = audioEvidence(f.store, f.controller.state.value.projectLibrary)
            assertEquals(setOf("alpha-only", "shared"), pack.sources.map { it.attachmentId }.toSet())
            assertContains(pack.prompt, "Passage 149")
            assertContains(pack.prompt, "Image description 0"); assertContains(pack.prompt, "Image description 1")
            assertTrue(pack.sources.sumOf { it.text.length } <= 6000)
            assertTrue(pack.sources.all { it.vector.isEmpty() }); assertTrue(pack.images.isEmpty())
            assertFalse(pack.prompt.contains("Beta private")); assertFalse(pack.prompt.contains("Unassigned"))
        } finally { f.controller.close(); advanceUntilIdle(); f.close() }
    }
}
