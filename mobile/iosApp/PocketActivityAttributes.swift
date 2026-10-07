import ActivityKit

struct PocketActivityAttributes: ActivityAttributes {
    typealias ContentState = VoiceActivityState
    let sessionID: String
}
