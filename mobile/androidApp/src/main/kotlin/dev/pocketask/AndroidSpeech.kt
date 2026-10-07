package dev.pocketask

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import ai.onnxruntime.OrtEnvironment
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class AndroidSpeech(private val application: Application) : PlatformSpeech {
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private val worker = Executors.newSingleThreadExecutor()
    private var permission: ActivityResultLauncher<String>? = null
    private var permissionToken = -1
    private var recognition: DictationResult? = null
    private var recognizer: SpeechRecognizer? = null
    private var transcript = ""
    private var finishing: Runnable? = null
    @Volatile private var audio: AudioTrack? = null
    private val audioManager = application.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    fun attach(activity: ComponentActivity) {
        permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            val id = permissionToken
            if (id != generation.get()) return@registerForActivityResult
            if (allowed) startRecognition(id)
            else failRecognition("Enable microphone access for Pocket Ask in Android Settings.")
        }
    }
    override fun listen(callback: DictationResult) {
        stop(); recognition = callback
        val id = generation.get()
        if (application.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecognition(id)
        else { permissionToken = id; permission?.launch(Manifest.permission.RECORD_AUDIO) ?: failRecognition("Microphone permission is unavailable. Reopen the app.") }
    }
    private fun startRecognition(id: Int) {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(application)) {
            failRecognition("Offline dictation is unavailable. Install on-device speech and language support in Android Settings, or type your message."); return
        }
        try {
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(application)
            this.recognizer = recognizer; transcript = ""
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (id == generation.get()) recognition?.ready() }
                override fun onPartialResults(results: Bundle?) { updateTranscript(results, false) }
                override fun onResults(results: Bundle?) { updateTranscript(results, true) }
                private fun updateTranscript(results: Bundle?, final: Boolean) {
                    if (id != generation.get()) return
                    val value = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (value.isNotBlank()) transcript = value
                    if (final) finishRecognition() else recognition?.text(transcript, false)
                }
                override fun onError(error: Int) {
                    if (id != generation.get()) return
                    if (transcript.isNotBlank() && error in listOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) finishRecognition()
                    else failRecognition(when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Enable microphone access in Android Settings."
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Download offline speech support for your phone’s language in Android Settings."
                        else -> "No speech recognized. Check offline language support and try again."
                    })
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
            val timeout = Runnable { if (id == generation.get() && recognition != null) finishListening() }
            finishing = timeout; main.postDelayed(timeout, 55_000)
        } catch (e: Exception) { failRecognition("Could not start offline dictation. ${e.message.orEmpty()}") }
    }
    override fun finishListening() {
        recognizer?.stopListening(); finishing?.let(main::removeCallbacks)
        val id = generation.get()
        val timeout = Runnable { if (id == generation.get() && recognition != null) finishRecognition() }
        finishing = timeout; main.postDelayed(timeout, 2_000)
    }
    private fun finishRecognition() { val callback = recognition; val text = transcript; stop(); callback?.text(text, true) }
    private fun failRecognition(message: String) { val callback = recognition; stop(); callback?.failure(message) }
    override fun speak(text: String, voice: String, directory: String, callback: PlaybackResult) {
        stop()
        val id = generation.get()
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
                if (change < 0 && id == generation.get()) { stop(); callback.failure("Audio interrupted. Tap Read to resume.") }
            }, main).build()
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { callback.failure("Audio is unavailable while another app is using it."); return }
        this.focus = focus
        worker.execute {
            var tts: TextToSpeech? = null
            var style: Style? = null
            var track: AudioTrack? = null
            try {
                val env = OrtEnvironment.getEnvironment()
                if (id != generation.get()) return@execute
                tts = Helper.loadTextToSpeech("$directory/onnx", false, env)
                tts.checkCancellation = Runnable { check(id == generation.get()) { "Cancelled" } }
                style = Helper.loadVoiceStyle(listOf("$directory/voice_styles/$voice.json"), false, env)
                main.post { if (id == generation.get()) callback.stage("Preparing voice…") }
                val result = tts.call(text, "na", style, 8, 1.05f, 0.3f, env)
                val rate = tts.sampleRate
                style.close(); style = null; tts.close(); tts = null
                if (id != generation.get()) return@execute
                val length = min(result.wav.size, (result.duration[0] * rate).toInt())
                val buffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT).coerceAtLeast(16_384)
                track = AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(buffer).build()
                audio = track
                if (id != generation.get()) return@execute
                track.play(); main.post { if (id == generation.get()) callback.stage("Reading aloud…") }
                var offset = 0
                while (offset < length && id == generation.get()) {
                    val count = track.write(result.wav, offset, min(4096, length - offset), AudioTrack.WRITE_BLOCKING)
                    check(count > 0) { "Audio output is unavailable." }; offset += count
                }
                while (id == generation.get() && track.playbackHeadPosition < length) Thread.sleep(20)
                main.post { if (id == generation.get()) { stop(); callback.success() } }
            } catch (e: Exception) {
                main.post { if (id == generation.get()) { stop(); callback.failure("Supertonic could not play audio. ${e.message.orEmpty()}") } }
            } finally {
                runCatching { style?.close() }; runCatching { tts?.close() }
                if (audio === track) audio = null
                runCatching { track?.release() }
            }
        }
    }
    override fun stop() {
        generation.incrementAndGet()
        finishing?.let(main::removeCallbacks); finishing = null
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null; recognition = null
        runCatching { audio?.pause(); audio?.flush() }
        focus?.let(audioManager::abandonAudioFocusRequest); focus = null
    }
    fun close() { stop(); worker.shutdown() }
}
