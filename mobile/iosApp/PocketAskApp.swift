import SwiftUI
import PocketAsk

@MainActor
final class AppHost {
    let runtime = SwiftRuntime()
    let inputs = IOSInputs()
    let speech = IOSSpeech()
    let voiceActivities = IOSVoiceActivities()
    let indexing: IOSIndexing
    let app: IosApp
    private var consumingShares = false
    private var scanSharesAgain = false
    init() {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("PocketAsk")
        try! FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var protected = root
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try? protected.setResourceValues(values)
        inputs.root = root
        indexing = IOSIndexing(runtime: runtime)
        app = IosApp(root: root.path, runtime: runtime, inputs: inputs, transfers: IOSModelTransfers(root: root), speech: speech, indexing: indexing, initiallyActive: false)
        inputs.presenter = app.viewController
        speech.liveActivities = voiceActivities
        voiceActivities.stopSpeech = { [weak self] in self?.app.stopSpeech() }
        app.observeImports(listener: voiceActivities)
        indexing.resume = { [weak self] in self?.app.resumeIndexingInBackground() == true }
        indexing.hasPending = { [weak self] in self?.app.hasPendingIndexing() == true }
        indexing.isIndexing = { [weak self] in self?.app.isIndexing() == true }
    }
    func foreground() {
        app.foreground()
        if consumingShares { scanSharesAgain = true; return }
        guard let inbox = try? ShareInbox.directory() else { return }
        consumingShares = true
        Task {
            defer {
                consumingShares = false
                if scanSharesAgain { scanSharesAgain = false; foreground() }
            }
            do {
                let handoffs = try FileManager.default.contentsOfDirectory(at: inbox, includingPropertiesForKeys: nil)
                    .filter { UUID(uuidString: $0.lastPathComponent) != nil }.sorted { $0.lastPathComponent < $1.lastPathComponent }
                for handoff in handoffs {
                    do {
                        let manifest = try await Task.detached { try ShareInbox.read(handoff) }.value
                        for index in 0..<max(1, manifest.urls.count) {
                            let id = handoff.lastPathComponent + "-\(index)"
                            if app.receivedShare(id: id) { continue }
                            var files: [SharedFile] = []
                            if index == 0 {
                                files = try await Task.detached { [root = inputs.root!] in
                                    let staging = root.appendingPathComponent("staging")
                                    try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
                                    return try manifest.files.map { file in
                                        let target = staging.appendingPathComponent("share-" + handoff.lastPathComponent + "-" + file.path)
                                        if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
                                        try FileManager.default.copyItem(at: handoff.appendingPathComponent(file.path), to: target)
                                        return SharedFile(name: file.name, path: target.path, type: file.type)
                                    }
                                }.value
                            }
                            let url = index < manifest.urls.count ? manifest.urls[index] : nil
                            let success = await receive(id: id, url: url, files: files)
                            // Keep the durable inbox for retry if import fails or the app is killed.
                            guard success else { return }
                        }
                        try FileManager.default.removeItem(at: handoff)
                    } catch {
                        _ = await receive(id: handoff.lastPathComponent, error: error.localizedDescription)
                    }
                }
            } catch { _ = await receive(id: UUID().uuidString, error: error.localizedDescription) }
        }
    }

    private func receive(id: String, url: String? = nil, files: [SharedFile] = [], error: String? = nil) async -> Bool {
        await withCheckedContinuation { continuation in
            app.receiveShare(id: id, url: url, files: files, error: error, result: ShareCompletion { continuation.resume(returning: $0) })
        }
    }

}

private final class ShareCompletion: ShareImportResult {
    let completion: (Bool) -> Void
    init(_ completion: @escaping (Bool) -> Void) { self.completion = completion }
    func completed(success: Bool) { completion(success) }
}

struct SharedView: UIViewControllerRepresentable {
    let host: AppHost
    func makeUIViewController(context: Context) -> UIViewController { host.app.viewController }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@main
struct PocketAskApp: App {
    @UIApplicationDelegateAdaptor(DownloadAppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var phase
    private let host = AppHost()
    var body: some Scene {
        WindowGroup {
            SharedView(host: host).ignoresSafeArea()
                .onOpenURL { _ in } // Keep the current screen; scenePhase handles resume.
                .onAppear { if phase == .active { host.foreground() } }
                .onChange(of: phase) { _, newPhase in
                    if newPhase == .background { host.app.background(); host.indexing.scheduleDeferred() }
                    else if newPhase == .active { host.foreground() }
                }
        }
    }
}
