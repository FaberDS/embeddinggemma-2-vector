import Foundation

enum VoiceActivityPhase: String, Codable, Hashable {
    case importing, recording, transcribing, preparingVoice, playback
    var title: String {
        switch self {
        case .importing: return "Importing assets"
        case .recording: return "Microphone active"
        case .transcribing: return "Finishing transcription"
        case .preparingVoice: return "Preparing voice"
        case .playback: return "Reading aloud"
        }
    }
    var symbol: String {
        switch self {
        case .importing: return "tray.and.arrow.down.fill"
        case .recording: return "mic.fill"
        case .transcribing: return "waveform"
        case .preparingVoice: return "speaker.wave.1"
        case .playback: return "speaker.wave.2.fill"
        }
    }
    var canStop: Bool { self != .transcribing && self != .importing }
    var actionTitle: String { self == .recording ? "Finish" : "Stop" }
}

struct VoiceActivityState: Codable, Hashable {
    let phase: VoiceActivityPhase
    let startedAt: Date?
    let expiresAt: Date
}

struct VoiceActivitySession: Equatable {
    let id: String
    let state: VoiceActivityState
}

/** Audio callbacks own the state; timers render locally without per-second updates. */
struct VoiceActivityLifecycle {
    private(set) var current: VoiceActivitySession?
    @discardableResult
    mutating func set(_ phase: VoiceActivityPhase?, now: Date = .now, duration: TimeInterval? = nil) -> Bool {
        guard phase != current?.state.phase else { return false }
        guard let phase else { current = nil; return true }
        let timeout: TimeInterval
        switch phase {
        case .importing: timeout = 300
        case .recording: timeout = 65 // Native dictation finishes after 55 seconds.
        case .transcribing: timeout = 15
        case .preparingVoice: timeout = 300
        case .playback: timeout = max(1, duration ?? 300) + 10
        }
        current = VoiceActivitySession(id: current?.id ?? UUID().uuidString,
            state: VoiceActivityState(phase: phase, startedAt: phase == .recording || phase == .playback ? now : nil,
                expiresAt: now.addingTimeInterval(timeout)))
        return true
    }
    func acceptsStop(_ id: String) -> Bool { current?.id == id && current?.state.phase.canStop == true }
}
