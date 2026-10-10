import Foundation
import UIKit
import UniformTypeIdentifiers
import PhotosUI
import PDFKit
import ImageIO
import Network
import PocketAsk

final class IOSInputs: NSObject, PlatformInputs, DocumentInputs, UIDocumentPickerDelegate, PHPickerViewControllerDelegate, @unchecked Sendable {
    var root: URL!
    weak var presenter: UIViewController?
    private var pending: ImportResult?
    private let monitor = NWPathMonitor()
    private let work = DispatchQueue(label: "dev.pocketask.inputs", qos: .userInitiated)
    private let pathLock = NSLock()
    private var online = false
    private var expensive = true

    override init() {
        super.init()
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            self.pathLock.lock(); self.online = path.status == .satisfied; self.expensive = path.isExpensive; self.pathLock.unlock()
        }
        monitor.start(queue: DispatchQueue(label: "dev.pocketask.network"))
    }

    func pick(images: Bool, callback: ImportResult) {
        pending = callback
        DispatchQueue.main.async {
            if images {
                var config = PHPickerConfiguration(); config.filter = .images; config.selectionLimit = 0
                let picker = PHPickerViewController(configuration: config); picker.delegate = self
                self.presenter?.present(picker, animated: true)
            } else {
                let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.pdf, .plainText, UTType(filenameExtension: "md") ?? .text], asCopy: true)
                picker.allowsMultipleSelection = true; picker.delegate = self
                self.presenter?.present(picker, animated: true)
            }
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { pending?.success(); pending = nil }
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let callback = pending else { return }; pending = nil
        work.async {
            do {
                for url in urls {
                    let security = url.startAccessingSecurityScopedResource(); defer { if security { url.stopAccessingSecurityScopedResource() } }
                    let target = try self.stagePath()
                    try FileManager.default.copyItem(at: url, to: target)
                    let type = url.pathExtension.lowercased() == "pdf" ? "application/pdf" : url.pathExtension.lowercased() == "md" ? "text/markdown" : "text/plain"
                    callback.item(name: url.lastPathComponent, path: target.path, type: type)
                }
                callback.success()
            } catch { callback.failure(message: error.localizedDescription) }
        }
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard let callback = pending else { return }; pending = nil
        // Load one image at a time; never materialize the entire photo selection in RAM.
        func next(_ index: Int) {
            guard index < results.count else { callback.success(); return }
            let provider = results[index].itemProvider
            provider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { url, error in
                guard let url else { callback.failure(message: error?.localizedDescription ?? "This image could not be opened."); return }
                do {
                    let data = try ShareInbox.imageData(url)
                    let target = try self.stagePath(); try data.write(to: target, options: .atomic)
                    callback.item(name: provider.suggestedName ?? "Image \(index + 1)", path: target.path, type: "image/jpeg")
                    self.work.async { next(index + 1) }
                } catch { callback.failure(message: error.localizedDescription) }
            }
        }
        next(0)
    }

    private func stagePath() throws -> URL {
        let directory = root.appendingPathComponent("staging"); try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent(UUID().uuidString)
    }
    func pageCount(attachment: Attachment, callback: CountResult) {
        work.async {
            if attachment.type == "application/pdf" {
                guard let pdf = PDFDocument(url: URL(fileURLWithPath: attachment.path)), !pdf.isLocked else { callback.failure(message: "\(attachment.name): this PDF is damaged or encrypted."); return }
                callback.success(count: Int32(pdf.pageCount))
            } else { callback.success(count: 1) }
        }
    }
    func readPage(attachment: Attachment, page: Int32, callback: PageResult) {
        readPage(attachment: attachment, page: page, callback: callback, text: true, visual: true)
    }
    func readTextPage(attachment: Attachment, page: Int32, callback: PageResult) {
        readPage(attachment: attachment, page: page, callback: callback, text: true, visual: false)
    }
    func readImagePage(attachment: Attachment, page: Int32, callback: PageResult) {
        readPage(attachment: attachment, page: page, callback: callback, text: false, visual: true)
    }
    private func readPage(attachment: Attachment, page: Int32, callback: PageResult, text: Bool, visual: Bool) {
        work.async {
            do {
                let path = URL(fileURLWithPath: attachment.path)
                let result: PageInput
                if attachment.isImage { result = PageInput(text: "", imagePath: attachment.path, ocr: text ? try IOSOcr.recognize(path) : nil) }
                else if attachment.type == "application/pdf" {
                    guard let document = PDFDocument(url: path), !document.isLocked, let source = document.page(at: Int(page)) else { throw CocoaError(.fileReadCorruptFile) }
                    let imagePath = path.deletingLastPathComponent().appendingPathComponent("page-\(page).jpg")
                    let embedded = text ? (source.string ?? "") : ""
                    let needsOcr = text && OcrPolicy.shared.needsRecognition(text: embedded)
                    if (visual || needsOcr) && !FileManager.default.fileExists(atPath: imagePath.path) {
                        let bounds = source.bounds(for: .mediaBox)
                        let scale = 1400 / max(bounds.width, bounds.height)
                        let image = source.thumbnail(of: CGSize(width: bounds.width * scale, height: bounds.height * scale), for: .mediaBox)
                        guard let data = image.jpegData(compressionQuality: 0.9) else { throw CocoaError(.fileReadCorruptFile) }
                        try data.write(to: imagePath, options: .atomic)
                    }
                    let recognized = needsOcr ? try IOSOcr.recognize(imagePath) : ""
                    result = PageInput(text: embedded, imagePath: (visual || needsOcr) ? imagePath.path : nil,
                        ocr: text ? OcrPolicy.shared.additionalText(embedded: embedded, recognized: recognized) : nil)
                } else {
                    let size = try FileManager.default.attributesOfItem(atPath: attachment.path)[.size] as? NSNumber
                    guard (size?.intValue ?? 0) <= 20_000_000 else { callback.failure(message: "Text files larger than 20 MB must be split before import."); return }
                    result = PageInput(text: try String(contentsOf: path, encoding: .utf8), imagePath: nil, ocr: nil)
                }
                callback.success(page: result)
            } catch { callback.failure(message: "\(attachment.name): \(error.localizedDescription)") }
        }
    }
    func open(path: String, page: Int32) {
        DispatchQueue.main.async {
            if let url = URL(string: path), ["https", "http"].contains(url.scheme?.lowercased() ?? "") {
                UIApplication.shared.open(url)
                return
            }
            let controller = SourceController(path: path, page: Int(page))
            self.presenter?.present(UINavigationController(rootViewController: controller), animated: true)
        }
    }
    func freeBytes() -> Int64 {
        let values = try? root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage ?? 0
    }
    func canDownload(cellular: Bool) -> Bool { pathLock.lock(); defer { pathLock.unlock() }; return online && (cellular || !expensive) }
    func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

private final class SourceController: UIViewController {
    let path: String
    let page: Int
    init(path: String, page: Int) { self.path = path; self.page = page; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }
    override func viewDidLoad() {
        super.viewDidLoad(); view.backgroundColor = .systemBackground
        navigationItem.rightBarButtonItem = UIBarButtonItem(systemItem: .done, primaryAction: UIAction { [weak self] _ in self?.dismiss(animated: true) })
        let url = URL(fileURLWithPath: path)
        let content: UIView
        if url.pathExtension == "pdf", let document = PDFDocument(url: url) {
            let pdf = PDFView(); pdf.document = document; pdf.autoScales = true
            if let target = document.page(at: max(0, page - 1)) { pdf.go(to: target) }
            content = pdf
        } else if url.pathExtension == "jpg" {
            let image = UIImageView(image: UIImage(contentsOfFile: path)); image.contentMode = .scaleAspectFit; content = image
        } else {
            let text = UITextView(); text.isEditable = false; text.font = .preferredFont(forTextStyle: .body); text.text = try? String(contentsOf: url, encoding: .utf8); content = text
        }
        content.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(content)
        NSLayoutConstraint.activate([content.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor), content.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor), content.leadingAnchor.constraint(equalTo: view.leadingAnchor), content.trailingAnchor.constraint(equalTo: view.trailingAnchor)])
    }
}
