import SwiftUI
import PocketAsk

@MainActor
final class AppHost {
    let runtime = SwiftRuntime()
    let inputs = IOSInputs()
    let app: IosApp
    init() {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("PocketAsk")
        try! FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var protected = root
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try? protected.setResourceValues(values)
        inputs.root = root
        app = IosApp(root: root.path, runtime: runtime, inputs: inputs)
        inputs.presenter = app.viewController
    }
}

struct SharedView: UIViewControllerRepresentable {
    let host: AppHost
    func makeUIViewController(context: Context) -> UIViewController { host.app.viewController }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@main
struct PocketAskApp: App {
    @Environment(\.scenePhase) private var phase
    private let host = AppHost()
    var body: some Scene {
        WindowGroup {
            SharedView(host: host).ignoresSafeArea()
                .onChange(of: phase) { _, newPhase in if newPhase == .background { host.app.background() } }
        }
    }
}
