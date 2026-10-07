import AppKit
import Foundation

@main
struct OcrSmoke {
    static func main() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        func image(_ lines: [String], name: String) throws -> URL {
            let size = NSSize(width: 1600, height: 900)
            let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size.width), pixelsHigh: Int(size.height), bitsPerSample: 8,
                samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
            NSGraphicsContext.saveGraphicsState()
            NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
            NSColor.white.setFill(); NSRect(origin: .zero, size: size).fill()
            for (index, line) in lines.enumerated() {
                (line as NSString).draw(at: NSPoint(x: 100, y: 740 - index * 110), withAttributes: [.font: NSFont.monospacedSystemFont(ofSize: 48, weight: .medium), .foregroundColor: NSColor.black])
            }
            NSGraphicsContext.restoreGraphicsState()
            let path = root.appendingPathComponent(name + ".png")
            try bitmap.representation(using: .png, properties: [:])!.write(to: path)
            return path
        }
        let invoice = try image(["Invoice INV-42", "Total EUR 125.70", "Rechnung München"], name: "invoice")
        let text = try IOSOcr.recognize(invoice)
        guard text.contains("INV-42"), text.contains("125.70"), text.contains("München") else {
            throw NSError(domain: "OcrSmoke", code: 1, userInfo: [NSLocalizedDescriptionKey: "Missing expected synthetic text: \(text)"])
        }
        let blank = try image([], name: "blank")
        guard try IOSOcr.recognize(blank).isEmpty else { throw NSError(domain: "OcrSmoke", code: 2) }
        do {
            _ = try IOSOcr.recognize(root.appendingPathComponent("missing.jpg"))
            throw NSError(domain: "OcrSmoke", code: 3)
        } catch let error as NSError where error.domain != "OcrSmoke" { }
        print("OCR passed: exact identifier, amount, German text, blank image and missing-file failure.")
    }
}
