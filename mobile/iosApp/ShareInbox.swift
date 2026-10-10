import Foundation
import UniformTypeIdentifiers
import UIKit
import ImageIO
import PDFKit

struct ShareInbox: Codable {
    struct File: Codable {
        let name: String
        let path: String
        let type: String
    }
    let files: [File]
    let urls: [String]
    static let group = "group.dev.denisschule.pocketask"
    static let maximumFileSize = 100_000_000

    static func directory() throws -> URL {
        guard let group = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else {
            throw ShareError("Sharing is unavailable. Enable the shared App Group for the app and Share extension in Xcode.")
        }
        let inbox = group.appendingPathComponent("ShareInbox", isDirectory: true)
        try FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        var privateInbox = inbox
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try privateInbox.setResourceValues(values)
        return inbox
    }

    static func webURL(_ text: String) -> String? {
        guard let url = URL(string: text), ["http", "https"].contains(url.scheme?.lowercased() ?? ""),
              let host = url.host, !host.isEmpty, url.user == nil, url.password == nil,
              !text.contains("\\"), !text.unicodeScalars.contains(where: { CharacterSet.whitespacesAndNewlines.union(.controlCharacters).contains($0) }) else { return nil }
        return url.absoluteString
    }

    static func imageData(_ url: URL) throws -> Data {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: 1600, kCGImageSourceCreateThumbnailWithTransform: true] as CFDictionary),
              let data = UIImage(cgImage: image).jpegData(compressionQuality: 0.9) else {
            throw ShareError("This image is damaged or cannot be opened.")
        }
        return data
    }

    // The provider's temporary file disappears on return: copy/convert inside its callback.
    private static func loadFile(_ provider: NSItemProvider, type: UTType, into directory: URL) async throws -> File {
        let name = provider.suggestedName ?? (type == .image ? "Shared image.jpg" : "Shared document.pdf")
        return try await withCheckedThrowingContinuation { continuation in
            provider.loadFileRepresentation(forTypeIdentifier: type.identifier) { source, error in
                do {
                    guard let source else { throw error ?? ShareError("The shared file could not be read.") }
                    let size = try source.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                    guard size > 0, size <= maximumFileSize else { throw ShareError("Share a nonempty PDF or image smaller than 100 MB.") }
                    let image = type == .image
                    let path = UUID().uuidString + (image ? ".jpg" : ".pdf")
                    let target = directory.appendingPathComponent(path)
                    if image { try imageData(source).write(to: target, options: .atomic) }
                    else {
                        guard let pdf = PDFDocument(url: source), !pdf.isLocked, pdf.pageCount > 0 else { throw ShareError("This PDF is damaged or encrypted.") }
                        try FileManager.default.copyItem(at: source, to: target)
                    }
                    continuation.resume(returning: File(name: name, path: path, type: image ? "image/jpeg" : "application/pdf"))
                } catch { continuation.resume(throwing: error) }
            }
        }
    }

    private static func loadURL(_ provider: NSItemProvider) async throws -> String {
        let type = provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) ? UTType.url : .plainText
        let text: String = try await withCheckedThrowingContinuation { continuation in
            let loaded: (Any?, Error?) -> Void = { item, error in
                if let url = item as? URL { continuation.resume(returning: url.absoluteString) }
                else if let string = item as? String { continuation.resume(returning: string) }
                else if let data = item as? Data, let string = String(data: data, encoding: .utf8) { continuation.resume(returning: string) }
                else { continuation.resume(throwing: error ?? ShareError("The shared link could not be read.")) }
            }
            let reader: NSItemProviderReading.Type = type == .url ? NSURL.self : NSString.self
            if provider.canLoadObject(ofClass: reader) {
                _ = provider.loadObject(ofClass: reader) { loaded($0, $1) }
            } else {
                provider.loadItem(forTypeIdentifier: type.identifier, options: nil) { loaded($0, $1) }
            }
        }
        if let valid = webURL(text) { return valid }
        if type == .plainText, text.utf8.count <= 100_000 {
            let detector = try NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
            for match in detector.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
                if let url = match.url, let valid = webURL(url.absoluteString) { return valid }
            }
        }
        throw ShareError("Share an HTTP or HTTPS page URL, PDF, or image.")
    }

    static func save(_ providers: [NSItemProvider], into inbox: URL) async throws -> URL {
        guard !providers.isEmpty, providers.count <= 50 else { throw ShareError("Share between 1 and 50 PDFs, images, or page URLs.") }
        let id = UUID().uuidString
        let pending = inbox.appendingPathComponent(".\(id)", isDirectory: true)
        let ready = inbox.appendingPathComponent(id, isDirectory: true)
        try FileManager.default.createDirectory(at: pending, withIntermediateDirectories: true)
        do {
            var files: [File] = []; var urls: [String] = []
            for provider in providers {
                try Task.checkCancellation()
                if provider.hasItemConformingToTypeIdentifier(UTType.pdf.identifier) { files.append(try await loadFile(provider, type: .pdf, into: pending)) }
                else if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) { files.append(try await loadFile(provider, type: .image, into: pending)) }
                else if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) || provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) { urls.append(try await loadURL(provider)) }
                else { throw ShareError("This item is unsupported. Share a PDF, image, or HTTP/HTTPS page URL.") }
            }
            var seen = Set<String>()
            let manifest = ShareInbox(files: files, urls: urls.filter { seen.insert($0).inserted })
            let data = try JSONEncoder().encode(manifest)
            guard data.count <= 64_000 else { throw ShareError("The shared names or URLs are too long.") }
            try data.write(to: pending.appendingPathComponent("manifest.json"), options: .atomic)
            try Task.checkCancellation()
            // Only complete directories are visible to the app, even if the extension is killed.
            try FileManager.default.moveItem(at: pending, to: ready)
            return ready
        } catch { try? FileManager.default.removeItem(at: pending); throw error }
    }

    static func read(_ directory: URL) throws -> ShareInbox {
        guard UUID(uuidString: directory.lastPathComponent) != nil,
              try directory.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink != true else { throw ShareError("The shared handoff is invalid.") }
        let manifestURL = directory.appendingPathComponent("manifest.json")
        let properties = try manifestURL.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey, .isSymbolicLinkKey])
        guard properties.isRegularFile == true, properties.isSymbolicLink != true, (properties.fileSize ?? 0) <= 64_000 else { throw ShareError("The shared handoff is invalid.") }
        let manifest = try JSONDecoder().decode(Self.self, from: Data(contentsOf: manifestURL))
        guard !manifest.files.isEmpty || !manifest.urls.isEmpty, manifest.files.count + manifest.urls.count <= 50,
              manifest.urls.allSatisfy({ webURL($0) != nil }) else { throw ShareError("The shared handoff is invalid.") }
        for file in manifest.files {
            guard file.path == URL(fileURLWithPath: file.path).lastPathComponent, !file.path.contains("/"), !file.path.contains("\\"),
                  UUID(uuidString: String(file.path.split(separator: ".").first ?? "")) != nil,
                  ["image/jpeg", "application/pdf"].contains(file.type) else { throw ShareError("The shared file is invalid.") }
            let values = try directory.appendingPathComponent(file.path).resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
            guard values.isRegularFile == true, values.isSymbolicLink != true, (values.fileSize ?? 0) > 0, (values.fileSize ?? 0) <= maximumFileSize else { throw ShareError("The shared file is missing or unreadable.") }
        }
        return manifest
    }
}

struct ShareError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
