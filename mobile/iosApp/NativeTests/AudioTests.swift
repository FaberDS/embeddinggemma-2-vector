import XCTest
import UIKit
import PocketAsk
@testable import PocketAskIOS

private final class AudioPlayback: NSObject, PlaybackResult {
    var stages: [String] = []
    var errors: [String] = []
    var done: (() -> Void)?
    func stage(value: String) { stages.append(value) }
    func success() { done?() }
    func failure(message: String) { errors.append(message); done?() }
}
private final class AudioCompletion: NSObject, Completion {
    var error: String?
    let done: () -> Void
    init(_ done: @escaping () -> Void) { self.done = done }
    func success() { done() }
    func failure(message: String) { error = message; done() }
}
private final class AudioPresenter: UIViewController {
    var shared: UIActivityViewController?
    var shown: (() -> Void)?
    override func present(_ controller: UIViewController, animated: Bool, completion: (() -> Void)? = nil) {
        shared = controller as? UIActivityViewController
        shown?(); completion?()
    }
}

final class AudioTests: XCTestCase {
    @MainActor func testSavedWavPlaysAndSurvivesStopAndMissingFileIsRecoverable() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".wav")
        defer { try? FileManager.default.removeItem(at: file) }
        try writeWavFile(file.path, (0..<24000).map { sin(Float($0) * 0.1) * 0.1 }, 24000)
        let speech = IOSSpeech()
        let callback = AudioPlayback()
        speech.playFile(path: file.path, callback: callback)
        XCTAssertEqual(callback.stages, ["Playing saved audio…"])
        XCTAssertTrue(callback.errors.isEmpty)
        speech.stop()
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        speech.playFile(path: file.path + ".missing", callback: callback)
        XCTAssertEqual(callback.errors.count, 1)
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
    }
    @MainActor func testSharePresentsNativeControllerForSavedFileAndReportsErrors() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let audio = root.appendingPathComponent("audio/test.wav")
        try FileManager.default.createDirectory(at: audio.deletingLastPathComponent(), withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try writeWavFile(audio.path, (0..<24000).map { sin(Float($0) * 0.1) * 0.1 }, 24000)
        let inputs = IOSInputs(); inputs.root = root
        let presenter = AudioPresenter()
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = presenter; window.makeKeyAndVisible()
        defer { window.isHidden = true }
        inputs.presenter = presenter
        let presented = expectation(description: "Native share sheet")
        presenter.shown = { presented.fulfill() }
        let shared = expectation(description: "Share completion")
        let callback = AudioCompletion { shared.fulfill() }
        inputs.shareAudio(path: audio.path, callback: callback)
        await fulfillment(of: [presented], timeout: 5)
        let sheet = try XCTUnwrap(presenter.shared)
        if let popover = sheet.popoverPresentationController { XCTAssertTrue(popover.sourceView === presenter.view) }
        sheet.completionWithItemsHandler?(nil, false, nil, nil)
        await fulfillment(of: [shared], timeout: 5)
        XCTAssertNil(callback.error)
        XCTAssertTrue(FileManager.default.fileExists(atPath: audio.path))
        let failed = expectation(description: "Missing file failure")
        let missing = AudioCompletion { failed.fulfill() }
        inputs.shareAudio(path: audio.path + ".missing", callback: missing)
        await fulfillment(of: [failed], timeout: 5)
        XCTAssertNotNil(missing.error)
        let rejected = expectation(description: "Non-audio path failure")
        let outside = AudioCompletion { rejected.fulfill() }
        let other = root.appendingPathComponent("other.wav")
        try FileManager.default.copyItem(at: audio, to: other)
        inputs.shareAudio(path: other.path, callback: outside)
        await fulfillment(of: [rejected], timeout: 5)
        XCTAssertNotNil(outside.error)
    }
    @MainActor func testInvalidRenderFailsBeforeLoadingModels() async {
        let speech = IOSSpeech()
        let callback = AudioPlayback()
        let failed = expectation(description: "Render failure")
        callback.done = { failed.fulfill() }
        speech.renderAudio(turns: [], language: "invalid", directory: "/missing", outputDirectory: "/missing", callback: callback)
        await fulfillment(of: [failed], timeout: 5)
        XCTAssertEqual(callback.errors.count, 1)
        let idle = expectation(description: "Speech released")
        let completion = AudioCompletion { idle.fulfill() }
        speech.release(callback: completion)
        await fulfillment(of: [idle], timeout: 5)
    }
}
