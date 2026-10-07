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
                .onAppear { if phase == .active { host.app.foreground() } }
                .onChange(of: phase) { _, newPhase in
                    if newPhase == .background { host.app.background(); host.indexing.scheduleDeferred() }
                    else if newPhase == .active { host.app.foreground() }
                }
        }
    }
}
