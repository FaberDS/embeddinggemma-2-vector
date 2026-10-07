import Foundation
import UIKit
import PocketAsk

/// URLSession's daemon owns transfers while the UI is suspended or the process is reclaimed.
final class IOSModelTransfers: NSObject, ModelTransfers, URLSessionDownloadDelegate, @unchecked Sendable {
    static let identifier = "dev.denisschule.pocketask.models"
    private let directory: URL
    private let lock = NSLock()
    private var tasks: [String: URLSessionDownloadTask] = [:]
    private var states: [String: TransferState] = [:]
    private var restoring = true
    private var waiting: [(ModelSpec, Bool)] = []
    private let preferences = UserDefaults.standard
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: Self.identifier)
        configuration.isDiscretionary = false
        configuration.sessionSendsLaunchEvents = true
        configuration.waitsForConnectivity = true
        configuration.allowsExpensiveNetworkAccess = true
        let queue = OperationQueue(); queue.maxConcurrentOperationCount = 1
        return URLSession(configuration: configuration, delegate: self, delegateQueue: queue)
    }()

    init(root: URL) {
        directory = root.appendingPathComponent("models")
        super.init()
        session.getAllTasks { [self] recovered in
            lock.lock()
            for case let task as URLSessionDownloadTask in recovered {
                guard let name = task.taskDescription else { continue }
                tasks[name] = task
                states[name] = state("Queued", bytes: task.countOfBytesReceived, active: true)
            }
            restoring = false
            let pending = waiting; waiting.removeAll()
            lock.unlock()
            pending.forEach { start(spec: $0.0, cellular: $0.1) }
        }
    }

    private func file(_ name: String, _ suffix: String) -> URL { directory.appendingPathComponent(name + suffix) }
    private func state(_ stage: String, bytes: Int64 = 0, path: String? = nil, error: String? = nil, active: Bool = false) -> TransferState {
        TransferState(stage: stage, downloaded: bytes, path: path, error: error, active: active)
    }

    func doCopyReserve(spec: ModelSpec) -> Int64 { 0 }

    func start(spec: ModelSpec, cellular: Bool) {
        lock.lock(); defer { lock.unlock() }
        let name = spec.filename
        if restoring { waiting.append((spec, cellular)); return }
        if tasks[name] != nil || FileManager.default.fileExists(atPath: file(name, ".transfer").path) { return }
        do {
            try FileManager.default.createDirectory(at: file(name, ".transfer").deletingLastPathComponent(), withIntermediateDirectories: true)
            let resume = file(name, ".resume")
            let data = preferences.bool(forKey: "cellular." + name) == cellular ? try? Data(contentsOf: resume) : nil
            let task: URLSessionDownloadTask
            if let data { task = session.downloadTask(withResumeData: data) }
            else {
                var request = URLRequest(url: URL(string: spec.url)!)
                request.allowsCellularAccess = cellular
                request.allowsExpensiveNetworkAccess = cellular
                task = session.downloadTask(with: request)
            }
            try? FileManager.default.removeItem(at: resume)
            task.taskDescription = name
            tasks[name] = task
            states[name] = state("Queued", active: true)
            preferences.set(true, forKey: "requested." + name)
            preferences.set(cellular, forKey: "cellular." + name)
            task.resume()
        } catch { states[name] = state("Failed", error: error.localizedDescription) }
    }

    func snapshot(spec: ModelSpec) -> TransferState {
        lock.lock(); defer { lock.unlock() }
        let name = spec.filename
        let completed = file(name, ".transfer")
        if FileManager.default.fileExists(atPath: completed.path) {
            return state("Downloaded", bytes: spec.bytes, path: completed.path)
        }
        if let result = states[name] { return result }
        if restoring && preferences.bool(forKey: "requested." + name) { return state("Restoring downloads", active: true) }
        if preferences.bool(forKey: "requested." + name) || FileManager.default.fileExists(atPath: file(name, ".resume").path) {
            return state("Paused", error: "Download interrupted. Retry to resume; iOS cancels transfers when the app is force-quit.")
        }
        return state("Idle")
    }

    func cancel(spec: ModelSpec) {
        lock.lock()
        let name = spec.filename
        waiting.removeAll { $0.0.filename == name }
        let task = tasks.removeValue(forKey: name)
        states[name] = state("Paused")
        preferences.removeObject(forKey: "requested." + name)
        try? FileManager.default.removeItem(at: file(name, ".transfer"))
        lock.unlock()
        if let task {
            task.cancel { [self] data in
                lock.lock(); defer { lock.unlock() }
                // A retry may already have created a new task. Never overwrite its state.
                if tasks[name] == nil, let data { try? data.write(to: file(name, ".resume"), options: .atomic) }
            }
        } else { try? FileManager.default.removeItem(at: file(name, ".resume")) }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let name = downloadTask.taskDescription else { return }
        lock.lock(); defer { lock.unlock() }
        guard tasks[name]?.taskIdentifier == downloadTask.taskIdentifier else { return }
        states[name] = state("Downloading", bytes: totalBytesWritten, active: true)
    }

    func urlSession(_ session: URLSession, taskIsWaitingForConnectivity task: URLSessionTask) {
        guard let name = task.taskDescription else { return }
        lock.lock(); defer { lock.unlock() }
        guard tasks[name]?.taskIdentifier == task.taskIdentifier else { return }
        states[name] = state("Waiting for permitted network", bytes: task.countOfBytesReceived, active: true)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let name = downloadTask.taskDescription else { return }
        lock.lock(); defer { lock.unlock() }
        guard tasks[name]?.taskIdentifier == downloadTask.taskIdentifier else { return }
        do {
            guard let response = downloadTask.response as? HTTPURLResponse, (200...299).contains(response.statusCode) else { throw URLError(.badServerResponse) }
            let target = file(name, ".transfer")
            try? FileManager.default.removeItem(at: target)
            try FileManager.default.moveItem(at: location, to: target)
            states[name] = state("Downloaded", bytes: downloadTask.countOfBytesReceived, path: target.path)
        } catch { states[name] = state("Failed", error: error.localizedDescription) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let name = task.taskDescription else { return }
        lock.lock(); defer { lock.unlock() }
        guard tasks[name]?.taskIdentifier == task.taskIdentifier else { return }
        tasks.removeValue(forKey: name)
        if let error {
            if let data = (error as NSError).userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
                try? data.write(to: file(name, ".resume"), options: .atomic)
            }
            states[name] = state("Failed", bytes: task.countOfBytesReceived, error: "\(error.localizedDescription) Retry to resume.")
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async { BackgroundDownloadEvents.finish() }
    }
}

/// Complete the OS wake-up after downloaded files have been moved into private storage.
enum BackgroundDownloadEvents {
    static var handler: (() -> Void)?
    static func register(_ handler: @escaping () -> Void) {
        self.handler = handler
    }
    static func finish() {
        guard let handler else { return }
        self.handler = nil; handler()
    }
}

final class DownloadAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        if identifier == IOSModelTransfers.identifier { BackgroundDownloadEvents.register(completionHandler) }
        else { completionHandler() }
    }
}
