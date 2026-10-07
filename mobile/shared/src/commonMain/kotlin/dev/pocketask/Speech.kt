package dev.pocketask

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

interface DictationResult {
    fun ready()
    /** Each update replaces the previous transcript; it is not an incremental token. */
    fun text(value: String, final: Boolean)
    fun failure(message: String)
}
interface PlaybackResult {
    fun stage(value: String)
    fun success()
    fun failure(message: String)
}
interface PlatformSpeech {
    fun listen(callback: DictationResult)
    fun finishListening()
    fun speak(text: String, voice: String, directory: String, callback: PlaybackResult)
    fun stop()
}

data class SpeechVoice(val id: String, val title: String)
val speechVoices = listOf("F", "M").flatMap { gender -> (1..5).map { SpeechVoice("$gender$it", "${if (gender == "F") "Female" else "Male"} $it") } }
private const val SpeechRevision = "aafc6e32416a594460b32413efc49d7fe4ce6d46"
val speechModelSpecs = listOf(
    ModelSpec("speech-onnx-duration_predictor.onnx", "Supertonic 3", "supertonic/onnx/duration_predictor.onnx",
        "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/$SpeechRevision/onnx/duration_predictor.onnx", 3700147, "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db"),
    ModelSpec("speech-onnx-text_encoder.onnx", "Supertonic 3", "supertonic/onnx/text_encoder.onnx",
        "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/$SpeechRevision/onnx/text_encoder.onnx", 36416150, "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff"),
    ModelSpec("speech-onnx-vector_estimator.onnx", "Supertonic 3", "supertonic/onnx/vector_estimator.onnx",
        "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/$SpeechRevision/onnx/vector_estimator.onnx", 256534781, "883ac868ea0275ef0e991524dc64f16b3c0376efd7c320af6b53f5b780d7c61c"),
    ModelSpec("speech-onnx-vocoder.onnx", "Supertonic 3", "supertonic/onnx/vocoder.onnx",
        "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/$SpeechRevision/onnx/vocoder.onnx", 101424195, "085de76dd8e8d5836d6ca66826601f615939218f90e519f70ee8a36ed2a4c4ba"),
    ModelSpec("speech-onnx-tts.json", "Supertonic 3", "supertonic/onnx/tts.json",
        "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/$SpeechRevision/onnx/tts.json", 8253, "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09"),
    ModelSpec("speech-onnx-unicode_indexer.json", "Supertonic 3", "supertonic/onnx/unicode_indexer.json",
        "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/$SpeechRevision/onnx/unicode_indexer.json", 277676, "9bf7346e43883a81f8645c81224f786d43c5b57f3641f6e7671a7d6c493cb24f"),
    ModelSpec("speech-voice_styles-F1.json", "Supertonic 3", "supertonic/voice_styles/F1.json",
        "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/$SpeechRevision/voice_styles/F1.json", 292046, "bbdec6ee00231c2c742ad05483df5334cab3b52fda3ba38e6a07059c4563dbc2"),
    ModelSpec("speech-voice_styles-F2.json", "Supertonic 3", "supertonic/voice_styles/F2.json",
        "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/$SpeechRevision/voice_styles/F2.json", 292423, "7c722c6a72707b1a77f035d67f0d1351ba187738e06f7683e8c72b1df3477fc6"),
    ModelSpec("speech-voice_styles-F3.json", "Supertonic 3", "supertonic/voice_styles/F3.json",
        "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/$SpeechRevision/voice_styles/F3.json", 290794, "12f6ef2573baa2defa1128069cb59f203e3ab67c92af77b42df8a0e3a2f7c6ab"),
    ModelSpec("speech-voice_styles-F4.json", "Supertonic 3", "supertonic/voice_styles/F4.json",
        "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/$SpeechRevision/voice_styles/F4.json", 291808, "c2fa764c1225a76dfc3e2c73e8aa4f70d9ee48793860eb34c295fff01c2e032b"),
    ModelSpec("speech-voice_styles-F5.json", "Supertonic 3", "supertonic/voice_styles/F5.json",
        "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/$SpeechRevision/voice_styles/F5.json", 291479, "45966e73316415626cf41a7d1c6f3b4c70dbc1ba2bee5c1978ef0ce33244fc8d"),
    ModelSpec("speech-voice_styles-M1.json", "Supertonic 3", "supertonic/voice_styles/M1.json",
        "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/$SpeechRevision/voice_styles/M1.json", 291748, "e35604687f5d23694b8e91593a93eec0e4eca6c0b02bb8ed69139ab2ea6b0a5b"),
    ModelSpec("speech-voice_styles-M2.json", "Supertonic 3", "supertonic/voice_styles/M2.json",
        "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/$SpeechRevision/voice_styles/M2.json", 292055, "b76cbf62bac707c710cf0ae5aba5e31eea1a6339a9734bfae33ab98499534a50"),
    ModelSpec("speech-voice_styles-M3.json", "Supertonic 3", "supertonic/voice_styles/M3.json",
        "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/$SpeechRevision/voice_styles/M3.json", 290198, "ea1ac35ccb91b0d7ecad533a2fbd0eec10c91513d8951e3b25fbba99954e159b"),
    ModelSpec("speech-voice_styles-M4.json", "Supertonic 3", "supertonic/voice_styles/M4.json",
        "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/$SpeechRevision/voice_styles/M4.json", 291522, "ca8eefad4fcd989c9379032ff3e50738adc547eeb5e221b82593a6d7b3bac303"),
    ModelSpec("speech-voice_styles-M5.json", "Supertonic 3", "supertonic/voice_styles/M5.json",
        "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/$SpeechRevision/voice_styles/M5.json", 291469, "dd22b92740314321f8ae11c5e87f8dd60d060f15dd3a632b5adf77f471f77af2")
)

data class SpeechState(val voice: String = "F1", val automatic: Boolean = true, val listening: Boolean = false,
    val stage: String? = null, val answerId: String? = null, val error: String? = null, val memory: Boolean = false)

/** Owns speech lifetimes, without allowing late native callbacks to edit a new draft. */
class SpeechController(private val root: String, private val store: Store, inputs: PlatformInputs,
    transfers: ModelTransfers?, private val platform: PlatformSpeech?, private val scope: CoroutineScope,
    private val question: () -> String, private val write: (String) -> Unit, private val canStart: () -> Boolean) {
    val models = ModelManager(root, store, inputs, transfers, speechModelSpecs)
    private val mutable = MutableStateFlow(SpeechState(voice = store.value("speech-voice")?.takeIf { value -> speechVoices.any { it.id == value } } ?: "F1", automatic = store.value("speech-auto") != "no"))
    val state = mutable.asStateFlow()
    val available get() = platform != null
    val installed get() = models.states.value.values.all { it.installed }
    private var generation = 0
    private var downloadJob: Job? = null
    private var active = true
    init { scope.launch { models.observeTransfers() } }
    fun automatic(value: Boolean) { store.put("speech-auto", value = if (value) "yes" else "no"); mutable.update { it.copy(automatic = value) }; if (!value) stop() }
    fun voice(id: String) { require(speechVoices.any { it.id == id }); stop(); store.put("speech-voice", value = id); mutable.update { it.copy(voice = id) } }
    fun dismissError() = mutable.update { it.copy(error = null) }
    fun toggleListening() {
        if (state.value.listening) {
            finishListening()
            return
        }
        val prefix = question().trimEnd()
        capture(false, { value -> write(listOf(prefix, value.trim()).filter { it.isNotBlank() }.joinToString(" ")) }, {}, {})
    }
    fun recordMemory(onText: (String) -> Unit, onComplete: (String) -> Unit, onFailure: (String) -> Unit) = capture(true, onText, onComplete, onFailure)
    fun finishListening() {
        if (!state.value.listening) return
        mutable.update { it.copy(stage = "Finishing dictation…") }
        platform?.finishListening()
    }
    private fun capture(memory: Boolean, onText: (String) -> Unit, onComplete: (String) -> Unit, onFailure: (String) -> Unit) {
        if (!active || !canStart() || platform == null) return
        stop()
        val token = generation
        mutable.update { it.copy(listening = true, stage = "Preparing microphone…", error = null, memory = memory) }
        platform.listen(object : DictationResult {
            override fun ready() { scope.launch { if (token == generation) mutable.update { it.copy(stage = "Listening…") } } }
            override fun text(value: String, final: Boolean) { scope.launch {
                if (token != generation) return@launch
                if (value.isNotBlank()) onText(value.trim())
                if (final) { generation++; mutable.update { it.copy(listening = false, stage = null, memory = false) }; onComplete(value.trim()) }
            } }
            override fun failure(message: String) { scope.launch {
                if (token == generation) { generation++; mutable.update { it.copy(listening = false, stage = null, memory = false, error = if (memory) null else message) }; onFailure(message) }
            } }
        })
    }
    fun read(answer: Answer, automatic: Boolean = false) {
        if (automatic && !state.value.automatic) return
        if (answer.status != "Completed" || answer.text.isBlank() || !active || !canStart() || platform == null) return
        if (state.value.answerId == answer.id) { stop(); return }
        if (!installed) {
            if (!automatic) mutable.update { it.copy(error = "Download Supertonic voices in Settings to read answers aloud.") }
            return
        }
        play(answer.text, answer.id)
    }
    fun preview() {
        if (!installed || !active || !canStart() || platform == null) return
        play("Hello. This is your selected voice. Your answers stay on your device.", "preview")
    }
    private fun play(text: String, id: String) {
        stop()
        val token = generation
        mutable.update { it.copy(answerId = id, stage = "Loading Supertonic…", error = null) }
        platform!!.speak(spokenText(text), state.value.voice, "$root/models/supertonic", object : PlaybackResult {
            override fun stage(value: String) { scope.launch { if (token == generation) mutable.update { it.copy(stage = value) } } }
            override fun success() { scope.launch { if (token == generation) { generation++; mutable.update { it.copy(stage = null, answerId = null) } } } }
            override fun failure(message: String) { scope.launch { if (token == generation) { generation++; mutable.update { it.copy(stage = null, answerId = null, error = message) } } } }
        })
    }
    fun stop() { generation++; platform?.stop(); mutable.update { it.copy(listening = false, stage = null, answerId = null, memory = false) } }
    fun background() { active = false; stop() }
    fun foreground() { active = true }
    fun download(cellular: Boolean) {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            try {
                if (models.supportsBackground) models.startDownloads(speechModelSpecs, cellular)
                else speechModelSpecs.forEach { if (!models.states.value.getValue(it.id).installed) models.download(it, cellular) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "Voice download failed. Retry.") } }
        }
    }
    fun cancelDownload() { downloadJob?.cancel(); scope.launch { models.cancelDownloads() } }
}

/** Keep speech natural; avoid reading citation codes, Markdown syntax and long URLs. */
internal fun spokenText(text: String): String = text
    .replace(Regex("```[\\s\\S]*?```"), " Code omitted. ")
    .replace(Regex("\\[S[0-9]+]"), "")
    .replace(Regex("\\[([^]\\n]+)]\\([^)]*\\)"), "$1")
    .replace(Regex("https?://\\S+"), "link")
    .replace(Regex("(?m)^\\s{0,3}(?:#{1,6} |[-*] |[0-9]+\\. )"), "")
    .replace(Regex("[*_`~]"), "").trim()
