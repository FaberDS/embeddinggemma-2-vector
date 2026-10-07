import AppIntents

/// LiveActivityIntent executes in the app process; no shared files or app group are needed.
@MainActor
enum VoiceActivityActions {
    static var stop: ((String) -> Void)?
}

struct StopVoiceIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Stop voice activity"
    static var isDiscoverable = false
    @Parameter(title: "Session") var sessionID: String
    init() { }
    init(sessionID: String) { self.sessionID = sessionID }
    func perform() async throws -> some IntentResult {
        await MainActor.run { VoiceActivityActions.stop?(sessionID) }
        return .result()
    }
}
