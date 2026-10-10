import XCTest
import UIKit
import UniformTypeIdentifiers
import PDFKit
@testable import PocketAskIOS

final class ShareInboxTests: XCTestCase {
    private func temporaryDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
    private func fileProvider(_ url: URL, type: UTType) -> NSItemProvider {
        let provider = NSItemProvider()
        provider.suggestedName = url.lastPathComponent
        provider.registerFileRepresentation(forTypeIdentifier: type.identifier, fileOptions: [], visibility: .all) { completion in
            completion(url, false, nil); return nil
        }
        return provider
    }

    func testURLInboxSurvivesRelaunchAndUnsupportedOrCancelledSharesLeaveNoReadyHandoff() async throws {
        let inbox = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: inbox) }
        let url = NSItemProvider(object: URL(string: "https://example.com/article")! as NSURL)
        let text = NSItemProvider(object: "Read https://example.com/article" as NSString)
        let legacyURL = NSItemProvider(item: URL(string: "https://example.com/article")! as NSURL, typeIdentifier: UTType.url.identifier)
        let ready = try await ShareInbox.save([url, text, legacyURL], into: inbox)
        XCTAssertEqual(try ShareInbox.read(ready).urls, ["https://example.com/article"])
        XCTAssertEqual(try ShareInbox.read(ready).urls, ["https://example.com/article"], "A new consumer can replay the persisted handoff.")
        for provider in [NSItemProvider(object: "No URL here" as NSString), NSItemProvider(object: URL(string: "file:///private/secret")! as NSURL), NSItemProvider()] {
            do { _ = try await ShareInbox.save([provider], into: inbox); XCTFail("Unsupported input was accepted") }
            catch { XCTAssertFalse(error.localizedDescription.isEmpty) }
        }
        let cancelled = Task { try await ShareInbox.save([url], into: inbox) }; cancelled.cancel()
        do { _ = try await cancelled.value; XCTFail("Cancelled share was committed") } catch is CancellationError { }
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: inbox.path), [ready.lastPathComponent])
        XCTAssertNil(ShareInbox.webURL("javascript:alert(1)")); XCTAssertNil(ShareInbox.webURL("https://user:pass@example.com"))
    }

    @MainActor
    func testPDFAndImageProvidersAreCopiedAndNormalizedBeforeTheirTemporaryFilesDisappear() async throws {
        let inbox = try temporaryDirectory(); let source = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: inbox); try? FileManager.default.removeItem(at: source) }
        let pdf = source.appendingPathComponent("Shared.pdf")
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 200, height: 200))
        try renderer.writePDF(to: pdf) { context in context.beginPage(); ("Shared test PDF" as NSString).draw(at: CGPoint(x: 20, y: 20), withAttributes: nil) }
        let image = source.appendingPathComponent("Shared.png")
        let picture = UIGraphicsImageRenderer(size: CGSize(width: 2000, height: 1000)).image { context in UIColor.red.setFill(); context.fill(CGRect(x: 0, y: 0, width: 2000, height: 1000)) }
        try XCTUnwrap(picture.pngData()).write(to: image)
        let ready = try await ShareInbox.save([fileProvider(pdf, type: .pdf), fileProvider(image, type: .image)], into: inbox)
        try FileManager.default.removeItem(at: source)
        let manifest = try ShareInbox.read(ready)
        XCTAssertEqual(manifest.files.map(\.type), ["application/pdf", "image/jpeg"])
        XCTAssertEqual(PDFDocument(url: ready.appendingPathComponent(manifest.files[0].path))?.pageCount, 1)
        let normalized = try XCTUnwrap(UIImage(contentsOfFile: ready.appendingPathComponent(manifest.files[1].path).path))
        XCTAssertLessThanOrEqual(max(normalized.size.width, normalized.size.height), 1600)
        let unreadable = fileProvider(source.appendingPathComponent("Missing.pdf"), type: .pdf)
        do { _ = try await ShareInbox.save([unreadable], into: inbox); XCTFail("Missing file was accepted") } catch { }
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: inbox.path), [ready.lastPathComponent])
    }

    func testConfiguredAppGroupIsAvailableToTheApp() throws {
        let inbox = try ShareInbox.directory()
        XCTAssertTrue(FileManager.default.fileExists(atPath: inbox.path))
    }

    func testInboxRejectsTraversalAndSymlinks() throws {
        let inbox = try temporaryDirectory(); defer { try? FileManager.default.removeItem(at: inbox) }
        let directory = inbox.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let outside = inbox.appendingPathComponent("secret.pdf"); try Data("private".utf8).write(to: outside)
        func write(_ path: String) throws {
            try JSONEncoder().encode(ShareInbox(files: [.init(name: "PDF", path: path, type: "application/pdf")], urls: [])).write(to: directory.appendingPathComponent("manifest.json"))
        }
        try write("../secret.pdf"); XCTAssertThrowsError(try ShareInbox.read(directory))
        let path = UUID().uuidString + ".pdf"
        try FileManager.default.createSymbolicLink(at: directory.appendingPathComponent(path), withDestinationURL: outside)
        try write(path); XCTAssertThrowsError(try ShareInbox.read(directory))
        XCTAssertTrue(FileManager.default.fileExists(atPath: outside.path))
    }
}
