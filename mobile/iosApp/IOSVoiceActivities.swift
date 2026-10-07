import ActivityKit
import UIKit
import PocketAsk

@MainActor
final class IOSVoiceActivities: ImportActivityStatus {
    private var lifecycle = VoiceActivityLifecycle()
    private var activity: Activity<PocketActivityAttributes>?
    private var updates: Task<Void, Never>?
    private let enabled: () -> Bool
    private var importing = false
    var stopSpeech: (() -> Void)?

    init(enabled: @escaping () -> Bool = { ActivityAuthorizationInfo().areActivitiesEnabled }) {
        self.enabled = enabled
        // A relaunched app has no active audio session; clear orphaned voice activities.
        let previous = Activity<PocketActivityAttributes>.activities
        updates = Task { for activity in previous { await activity.end(nil, dismissalPolicy: .immediate) } }
        VoiceActivityActions.stop = { [weak self] id in
            guard let self, self.lifecycle.acceptsStop(id) else { return }
            self.stopSpeech?()
        }
    }

    func flushUpdates() async { await updates?.value }

    nonisolated func changed(importing: Bool) {
        Task { @MainActor in updateImport(importing) }
    }

    func updateImport(_ value: Bool) {
        importing = value
        if lifecycle.current == nil || lifecycle.current?.state.phase == .importing {
            set(value ? .importing : nil)
        }
    }

    func set(_ phase: VoiceActivityPhase?, duration: TimeInterval? = nil) {
        let phase = phase ?? (importing ? .importing : nil)
        guard lifecycle.set(phase, duration: duration) else { return }
        let previous = updates
        updates = Task { [weak self] in
            await previous?.value
            await self?.synchronize()
        }
    }

    private func synchronize() async {
        if activity?.attributes.sessionID != lifecycle.current?.id || !enabled() {
            let previous = activity; activity = nil
            await previous?.end(nil, dismissalPolicy: .immediate)
        }
        guard let session = lifecycle.current, enabled() else { return }
        let content = ActivityContent(state: session.state, staleDate: session.state.expiresAt, relevanceScore: 100)
        if let activity {
            await activity.update(content)
        } else if UIApplication.shared.applicationState == .active {
            // Failure/disabled activities leave the existing in-app controls available.
            activity = try? Activity.request(attributes: PocketActivityAttributes(sessionID: session.id), content: content, pushType: nil)
        }
    }
}
