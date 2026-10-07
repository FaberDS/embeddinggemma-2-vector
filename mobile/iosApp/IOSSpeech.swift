import Foundation
import AVFoundation
import Speech
import PocketAsk
import OnnxRuntimeBindings

/// Recognition and playback are local. A generation token invalidates permission and audio callbacks.
final class IOSSpeech: NSObject, PlatformSpeech, AVAudioPlayerDelegate {
    private let queue = DispatchQueue(label: "pocketask.speech", qos: .userInitiated)
    private let lock = NSLock()
    private var generation = 0
    private var engine: AVAudioEngine?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var speechRecognizer: SFSpeechRecognizer?
    private var recognition: DictationResult?
    private var transcript = ""
    private var timeout: DispatchWorkItem?
    private var player: AVAudioPlayer?
    private var playback: PlaybackResult?
    private var audioURL: URL?
    private var interruption: NSObjectProtocol?
    override init() {
        super.init()
        interruption = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self else { return }
            let callback = self.recognition; let playback = self.playback
            self.stop(); callback?.failure(message: "Dictation interrupted. Tap the microphone to try again.")
            playback?.failure(message: "Audio interrupted. Tap Read to resume.")
        }
    }
    private func current(_ token: Int) -> Bool { lock.lock(); defer { lock.unlock() }; return token == generation }
    private func token() -> Int { lock.lock(); defer { lock.unlock() }; return generation }
    private func advance() { lock.lock(); generation += 1; lock.unlock() }
    func listen(callback: DictationResult) {
        dispatchPrecondition(condition: .onQueue(.main))
        stop()
        recognition = callback
        let id = token()
        SFSpeechRecognizer.requestAuthorization { [weak self] status in
            DispatchQueue.main.async {
                guard let self, self.current(id) else { return }
                guard status == .authorized else { self.failDictation("Enable Speech Recognition for Pocket Ask in iOS Settings."); return }
                AVAudioApplication.requestRecordPermission { allowed in
                    DispatchQueue.main.async {
                        guard self.current(id) else { return }
                        guard allowed else { self.failDictation("Enable microphone access for Pocket Ask in iOS Settings."); return }
                        self.startRecognition(id)
                    }
                }
            }
        }
    }
    private func startRecognition(_ id: Int) {
        guard let recognizer = SFSpeechRecognizer(locale: .current), recognizer.isAvailable, recognizer.supportsOnDeviceRecognition else {
            failDictation("Offline dictation is unavailable for your phone’s language. Install its language support in iOS Settings, or type your message."); return
        }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: [.duckOthers])
            try session.setActive(true)
            let engine = AVAudioEngine()
            let request = SFSpeechAudioBufferRecognitionRequest()
            request.requiresOnDeviceRecognition = true
            request.shouldReportPartialResults = true
            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            guard format.sampleRate > 0 && format.channelCount > 0 else { throw NSError(domain: "speech", code: 1, userInfo: [NSLocalizedDescriptionKey: "Microphone is unavailable."]) }
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in request.append(buffer) }
            self.speechRecognizer = recognizer
            self.engine = engine; self.request = request; transcript = ""
            task = recognizer.recognitionTask(with: request) { [weak self] result, error in
                DispatchQueue.main.async {
                    guard let self, self.current(id) else { return }
                    if let result {
                        self.transcript = result.bestTranscription.formattedString
                        if result.isFinal { self.finishDictation() }
                        else { self.recognition?.text(value: self.transcript, final: false) }
                    } else if error != nil {
                        if !self.transcript.isEmpty { self.finishDictation() }
                        else { self.failDictation("No speech recognized. Check on-device language support and try again.") }
                    }
                }
            }
            engine.prepare(); try engine.start(); recognition?.ready()
            let timeout = DispatchWorkItem { [weak self] in if let self, self.current(id) { self.finishListening() } }
            self.timeout = timeout
            DispatchQueue.main.asyncAfter(deadline: .now() + 55, execute: timeout)
        } catch { failDictation(error.localizedDescription) }
    }
    func finishListening() {
        engine?.stop(); request?.endAudio(); timeout?.cancel()
        let id = token()
        let work = DispatchWorkItem { [weak self] in if let self, self.current(id), self.recognition != nil { self.finishDictation() } }
        timeout = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 2, execute: work)
    }
    private func finishDictation() {
        let callback = recognition; let text = transcript
        stop(); callback?.text(value: text, final: true)
    }
    private func failDictation(_ message: String) { let callback = recognition; stop(); callback?.failure(message: message) }
    func speak(text: String, voice: String, directory: String, callback: PlaybackResult) {
        stop()
        let id = token()
        playback = callback
        queue.async { [weak self] in
            guard let self else { return }
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("pocketask-speech-\(UUID().uuidString).wav")
            do {
                let env = try ORTEnv(loggingLevel: .warning)
                let tts = try loadTextToSpeech(directory + "/onnx", false, env)
                tts.checkCancellation = { if !self.current(id) { throw CancellationError() } }
                let style = try loadVoiceStyle([directory + "/voice_styles/" + voice + ".json"], verbose: false)
                guard self.current(id) else { return }
                DispatchQueue.main.async { if self.current(id) { callback.stage(value: "Preparing voice…") } }
                let (wav, duration) = try tts.call(text, "na", style, 8)
                guard self.current(id) else { return }
                try writeWavFile(url.path, Array(wav.prefix(min(wav.count, Int(Float(tts.sampleRate) * duration)))), tts.sampleRate)
                // ONNX sessions are released when this worker exits, before other local models are loaded.
                DispatchQueue.main.async {
                    guard self.current(id) else { try? FileManager.default.removeItem(at: url); return }
                    do {
                        let session = AVAudioSession.sharedInstance()
                        try session.setCategory(.playback, mode: .spokenAudio)
                        try session.setActive(true)
                        let player = try AVAudioPlayer(contentsOf: url)
                        player.delegate = self; self.player = player; self.audioURL = url
                        guard player.play() else { throw NSError(domain: "speech", code: 2, userInfo: [NSLocalizedDescriptionKey: "Could not play this answer."]) }
                        callback.stage(value: "Reading aloud…")
                    } catch { self.failPlayback(error.localizedDescription) }
                }
            } catch {
                try? FileManager.default.removeItem(at: url)
                DispatchQueue.main.async { if self.current(id) { self.failPlayback("Supertonic could not prepare audio. " + error.localizedDescription) } }
            }
        }
    }
    private func failPlayback(_ message: String) { let callback = playback; stop(); callback?.failure(message: message) }
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        guard self.player === player else { return }
        let callback = playback; stop()
        if flag { callback?.success() } else { callback?.failure(message: "Audio playback was interrupted.") }
    }
    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) { guard self.player === player else { return }; failPlayback("Could not play the generated audio.") }
    func stop() {
        advance(); timeout?.cancel(); timeout = nil
        if let engine { engine.stop(); engine.inputNode.removeTap(onBus: 0) }
        engine = nil; request?.endAudio(); request = nil; task?.cancel(); task = nil; speechRecognizer = nil; recognition = nil
        player?.stop(); player = nil; playback = nil
        if let url = audioURL { try? FileManager.default.removeItem(at: url) }; audioURL = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
    deinit { if let interruption { NotificationCenter.default.removeObserver(interruption) } }
}
