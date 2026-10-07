import BackgroundTasks
import UIKit
import PocketAsk

/// The OS owns execution time; the shared indexer owns durable work checkpoints.
@MainActor
final class IOSIndexing: IndexingTasks {
    private static let deferredID = "dev.denisschule.pocketask.index.deferred"
    private let runtime: SwiftRuntime
    private var task: BGTask?
    private var identifier: String?
    private var permit: IndexingPermit?
    private var pending: IndexingPermit?
    private var expired = false
    var resume: (() -> Bool)?
    var hasPending: (() -> Bool)?
    var isIndexing: (() -> Bool)?

    init(runtime: SwiftRuntime) {
        self.runtime = runtime
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.deferredID, using: .main) { [weak self] task in
            MainActor.assumeIsolated { self?.runDeferred(task) }
        }
    }

    nonisolated func start(userInitiated: Bool, callback: IndexingPermit) {
        if Thread.isMainThread { MainActor.assumeIsolated { request(userInitiated: userInitiated, callback: callback) } }
        else { Task { @MainActor in request(userInitiated: userInitiated, callback: callback) } }
    }

    private func request(userInitiated: Bool, callback: IndexingPermit) {
        permit = callback
        if expired { callback.ready(background: false); return }
        if task is BGProcessingTask {
            runtime.setIndexingCPU(true)
            callback.ready(background: true)
            return
        }
        guard userInitiated, UIApplication.shared.applicationState != .background else {
            callback.ready(background: false)
            return
        }
        if #available(iOS 26.0, *) {
            let id = "dev.denisschule.pocketask.index.continued.\(UUID().uuidString)"
            pending = callback; identifier = id
            let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: .main) { [weak self] task in
                MainActor.assumeIsolated {
                    guard let self, let callback = self.pending, self.identifier == id else {
                        task.setTaskCompleted(success: false); return
                    }
                    self.pending = nil; self.task = task; self.expired = false
                    task.expirationHandler = { [weak self, weak task] in
                        Task { @MainActor in if let task { self?.expire(task) } }
                    }
                    callback.ready(background: true)
                }
            }
            guard registered else { foregroundOnly(callback); return }
            let request = BGContinuedProcessingTaskRequest(identifier: id, title: "Indexing your knowledge base", subtitle: "Preparing documents")
            request.strategy = .fail
            let gpu = Bundle.main.object(forInfoDictionaryKey: "PocketAskBackgroundGPU") as? Bool == true && BGTaskScheduler.supportedResources.contains(.gpu)
            if gpu { request.requiredResources = .gpu }
            // Never issue Metal work in the background without the GPU resource grant.
            runtime.setIndexingCPU(!gpu)
            do { try BGTaskScheduler.shared.submit(request) }
            catch { foregroundOnly(callback) }
        } else { foregroundOnly(callback) }
    }

    private func foregroundOnly(_ callback: IndexingPermit) {
        pending = nil; identifier = nil
        runtime.setIndexingCPU(false)
        callback.ready(background: false)
    }

    nonisolated func progress(value: IndexingProgress) {
        // Kotlin may report progress from its worker dispatcher.
        Task { @MainActor [weak self] in
            guard let self else { return }
            if #available(iOS 26.0, *), let task = self.task as? BGContinuedProcessingTask {
                task.progress.totalUnitCount = max(1, value.totalUnits)
                task.progress.completedUnitCount = value.completedUnits
                let eta: String
                if let seconds = value.remainingSeconds?.int64Value, seconds > 0 {
                    eta = seconds < 60 ? " · ~\(seconds)s this phase" : " · ~\((seconds + 59) / 60)m this phase"
                } else { eta = "" }
                task.updateTitle("Indexing your knowledge base", subtitle: "\(value.phase) · \(value.completed)/\(value.total)\(eta)")
            }
        }
    }

    nonisolated func finish(success: Bool) {
        if Thread.isMainThread { MainActor.assumeIsolated { complete(success: success) } }
        else { Task { @MainActor in complete(success: success) } }
    }

    private func complete(success: Bool) {
        let retry = task == nil || expired
        var continuedInterrupted = false
        if #available(iOS 26.0, *) { continuedInterrupted = task is BGContinuedProcessingTask && !success }
        if let identifier { BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier) }
        pending = nil; permit = nil; identifier = nil
        task?.setTaskCompleted(success: success); task = nil
        expired = false
        runtime.setIndexingCPU(false)
        if UIApplication.shared.applicationState == .background && !continuedInterrupted && (success || retry) { scheduleDeferred() }
        else if hasPending?() != true { BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.deferredID) }
    }

    func scheduleDeferred() {
        guard task == nil, hasPending?() == true else { return }
        let request = BGProcessingTaskRequest(identifier: Self.deferredID)
        request.requiresExternalPower = true
        request.requiresNetworkConnectivity = false
        request.earliestBeginDate = Date(timeIntervalSinceNow: 60)
        // Replaces our previous pending request; never accumulates duplicate jobs.
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.deferredID)
        do { try BGTaskScheduler.shared.submit(request) }
        catch { /* Checkpoints remain available for the next foreground resume. */ }
    }

    nonisolated func schedule() {
        if Thread.isMainThread { MainActor.assumeIsolated { scheduleDeferred() } }
        else { Task { @MainActor in scheduleDeferred() } }
    }

    private func runDeferred(_ task: BGTask) {
        guard hasPending?() == true, isIndexing?() != true else {
            task.setTaskCompleted(success: true); return
        }
        self.task = task
        expired = false
        runtime.setIndexingCPU(true)
        task.expirationHandler = { [weak self, weak task] in
            Task { @MainActor in if let task { self?.expire(task) } }
        }
        if resume?() != true {
            task.setTaskCompleted(success: false); self.task = nil
            runtime.setIndexingCPU(false)
        }
    }

    private func expire(_ task: BGTask) {
        guard self.task === task else { return }
        expired = true; permit?.expired()
    }
}
