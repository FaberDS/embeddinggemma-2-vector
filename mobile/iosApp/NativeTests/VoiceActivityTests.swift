import XCTest
import ActivityKit
import UIKit
@testable import PocketAskIOS

final class VoiceActivityTests: XCTestCase {
    private enum WaitError: Error { case timedOut }
    private func content(_ activity: Activity<PocketActivityAttributes>, phase: VoiceActivityPhase) async throws -> VoiceActivityState {
        try await withThrowingTaskGroup(of: VoiceActivityState.self) { group in
            group.addTask {
                for await content in activity.contentUpdates {
                    if content.state.phase == phase { return content.state }
                }
                throw WaitError.timedOut
            }
            group.addTask { try await Task.sleep(nanoseconds: 3_000_000_000); throw WaitError.timedOut }
            defer { group.cancelAll() }
            let result = try await group.next()
            return try XCTUnwrap(result)
        }
    }
    @MainActor
    private func start(_ manager: IOSVoiceActivities, phase: VoiceActivityPhase) async throws -> Activity<PocketActivityAttributes> {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { throw XCTSkip("Live Activities are disabled on this simulator.") }
        manager.set(phase)
        await manager.flushUpdates()
        return try XCTUnwrap(Activity<PocketActivityAttributes>.activities.first, "The system should register the activity with our embedded widget.")
    }

    @MainActor
    func testRecordingTransitionsToTranscriptionAndEnds() async throws {
        let manager = IOSVoiceActivities()
        let activity = try await start(manager, phase: .recording)
        XCTAssertEqual(activity.content.state.phase, .recording)
        XCTAssertNotNil(activity.content.state.startedAt)
        XCTAssertNotNil(activity.content.staleDate)
        manager.set(.transcribing); await manager.flushUpdates()
        let updated = try await content(activity, phase: .transcribing)
        XCTAssertNil(updated.startedAt)
        XCTAssertFalse(updated.phase.canStop)
        manager.set(nil); await manager.flushUpdates()
        XCTAssertTrue(Activity<PocketActivityAttributes>.activities.isEmpty)
    }

    @MainActor
    func testPlaybackStartsItsTimerOnlyAfterPreparation() async throws {
        let manager = IOSVoiceActivities()
        let activity = try await start(manager, phase: .preparingVoice)
        XCTAssertNil(activity.content.state.startedAt)
        manager.set(.playback, duration: 25); await manager.flushUpdates()
        let state = try await content(activity, phase: .playback)
        XCTAssertEqual(state.phase, .playback)
        XCTAssertEqual(state.expiresAt.timeIntervalSince(try XCTUnwrap(state.startedAt)), 35, accuracy: 0.1)
        manager.set(nil); await manager.flushUpdates()
    }

    @MainActor
    func testStopIntentStopsOnlyItsCurrentSession() async throws {
        let manager = IOSVoiceActivities()
        var stopped = 0
        manager.stopSpeech = { stopped += 1; manager.set(nil) }
        let old = try await start(manager, phase: .recording)
        manager.set(nil); await manager.flushUpdates()
        let current = try await start(manager, phase: .playback)
        _ = try await StopVoiceIntent(sessionID: old.attributes.sessionID).perform()
        XCTAssertEqual(stopped, 0)
        _ = try await StopVoiceIntent(sessionID: current.attributes.sessionID).perform()
        await manager.flushUpdates()
        XCTAssertEqual(stopped, 1)
        XCTAssertTrue(Activity<PocketActivityAttributes>.activities.isEmpty)
        _ = try await StopVoiceIntent(sessionID: current.attributes.sessionID).perform()
        XCTAssertEqual(stopped, 1)
        manager.stopSpeech = nil
    }

    @MainActor
    func testDisabledActivitiesAndRapidCancellationKeepNoOrphans() async {
        let disabled = IOSVoiceActivities(enabled: { false })
        disabled.set(.recording); await disabled.flushUpdates()
        XCTAssertTrue(Activity<PocketActivityAttributes>.activities.isEmpty)
        let manager = IOSVoiceActivities()
        manager.set(.preparingVoice); manager.set(.playback); manager.set(nil)
        await manager.flushUpdates()
        XCTAssertTrue(Activity<PocketActivityAttributes>.activities.isEmpty)
    }

    @MainActor
    func testRelaunchCleanupEndsAbandonedActivities() async throws {
        let old = IOSVoiceActivities()
        _ = try await start(old, phase: .recording)
        let restored = IOSVoiceActivities()
        await restored.flushUpdates()
        XCTAssertTrue(Activity<PocketActivityAttributes>.activities.isEmpty)
        old.set(nil); await old.flushUpdates()
    }

    func testDuplicateCallbacksPreserveTimersAndOldControlsCannotStopNewSessions() throws {
        var lifecycle = VoiceActivityLifecycle()
        let date = Date(timeIntervalSince1970: 100)
        XCTAssertFalse(lifecycle.set(nil))
        XCTAssertTrue(lifecycle.set(.recording, now: date))
        let first = try XCTUnwrap(lifecycle.current)
        XCTAssertTrue(lifecycle.acceptsStop(first.id))
        XCTAssertFalse(lifecycle.set(.recording, now: date.addingTimeInterval(10)))
        XCTAssertEqual(lifecycle.current, first)
        lifecycle.set(.transcribing, now: date.addingTimeInterval(12))
        XCTAssertEqual(lifecycle.current?.id, first.id)
        XCTAssertFalse(lifecycle.acceptsStop(first.id))
        lifecycle.set(nil); lifecycle.set(.playback, now: date, duration: 30)
        XCTAssertFalse(lifecycle.acceptsStop(first.id))
        XCTAssertEqual(lifecycle.current?.state.expiresAt, date.addingTimeInterval(40))
    }

    @MainActor
    func testVoiceActivityPayloadContainsOnlyStatusNotPrivateContent() async throws {
        let manager = IOSVoiceActivities()
        let activity = try await start(manager, phase: .recording)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(activity.content.state)) as? [String: Any])
        XCTAssertEqual(Set(object.keys), ["phase", "startedAt", "expiresAt"])
        manager.set(nil); await manager.flushUpdates()
    }

    @MainActor
    func testImportProgressYieldsToActualAudioAndReturnsUntilImportCompletes() async throws {
        let manager = IOSVoiceActivities()
        manager.updateImport(true); await manager.flushUpdates()
        let activity = try XCTUnwrap(Activity<PocketActivityAttributes>.activities.first)
        XCTAssertEqual(activity.content.state.phase, .importing)
        XCTAssertFalse(activity.content.state.phase.canStop)
        manager.set(.recording); await manager.flushUpdates()
        _ = try await content(activity, phase: .recording)
        manager.updateImport(false); await manager.flushUpdates()
        XCTAssertEqual(activity.content.state.phase, .recording)
        manager.updateImport(true); manager.set(nil); await manager.flushUpdates()
        _ = try await content(activity, phase: .importing)
        manager.updateImport(false); await manager.flushUpdates()
        XCTAssertTrue(Activity<PocketActivityAttributes>.activities.isEmpty)
    }

    @MainActor
    func testZWidgetPresentationSmoke() async throws {
        let manager = IOSVoiceActivities()
        _ = try await start(manager, phase: .playback)
        NSLog("PocketAskLiveActivityPreviewReady")
        // Keep a synthetic activity available briefly for checking the system-rendered widget.
        try await Task.sleep(nanoseconds: 20_000_000_000)
        manager.set(nil); await manager.flushUpdates()
        XCTAssertTrue(Activity<PocketActivityAttributes>.activities.isEmpty)
    }
}
