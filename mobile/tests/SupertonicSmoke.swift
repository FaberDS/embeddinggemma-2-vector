import Foundation
import OnnxRuntimeBindings
let root = CommandLine.arguments[1]
let env = try ORTEnv(loggingLevel: .warning)
let tts = try loadTextToSpeech(root + "/onnx", false, env)
for voice in ["F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5"] {
    let style = try loadVoiceStyle([root + "/voice_styles/" + voice + ".json"], verbose: false)
    let start = Date()
    let (samples, duration) = try tts.call("Hello. This is a local voice test.", "na", style, 8)
    precondition(duration > 0 && duration < 30 && !samples.isEmpty)
    precondition(samples.allSatisfy { $0.isFinite } && samples.contains { abs($0) > 0.001 })
    try writeWavFile(FileManager.default.temporaryDirectory.appendingPathComponent("pocketask-voice-" + voice + ".wav").path, Array(samples.prefix(Int(duration * Float(tts.sampleRate)))), tts.sampleRate)
    print("PASS \(voice): \(duration) seconds of audio, generated in \(Date().timeIntervalSince(start)) seconds")
}
var checks = 0
tts.checkCancellation = { checks += 1; if checks == 3 { throw CancellationError() } }
do {
    let style = try loadVoiceStyle([root + "/voice_styles/F1.json"], verbose: false)
    _ = try tts.call("Cancellation test.", "na", style, 8)
    fatalError("Cancellation was ignored")
} catch is CancellationError { precondition(checks == 3); print("PASS cancellation") }
