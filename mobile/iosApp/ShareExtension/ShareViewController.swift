import UIKit

final class ShareViewController: UIViewController {
    private let message = UILabel()
    private let save = UIButton(type: .system)
    private let cancel = UIButton(type: .system)
    private var task: Task<Void, Never>?
    private var saved = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        let title = UILabel(); title.text = "Save to Locune"; title.font = .preferredFont(forTextStyle: .title2)
        message.text = "Save shared PDFs, images, and page URLs. Open Locune afterward to import files or preview links."
        message.font = .preferredFont(forTextStyle: .body); message.numberOfLines = 0
        title.adjustsFontForContentSizeCategory = true; message.adjustsFontForContentSizeCategory = true
        save.setTitle("Save", for: .normal); save.addTarget(self, action: #selector(saveShare), for: .touchUpInside)
        cancel.setTitle("Cancel", for: .normal); cancel.addTarget(self, action: #selector(close), for: .touchUpInside)
        let stack = UIStackView(arrangedSubviews: [title, message, save, cancel]); stack.axis = .vertical; stack.spacing = 20
        stack.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(stack)
        NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 24), stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -24), stack.centerYAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerYAnchor)])
    }

    @objc private func saveShare() {
        guard task == nil else { return }
        save.isEnabled = false; message.text = "Saving shared content…"
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        task = Task {
            do {
                _ = try await ShareInbox.save(providers, into: ShareInbox.directory())
                saved = true; message.text = "Saved. Open Locune to import files and preview shared links."
                cancel.setTitle("Done", for: .normal); save.isHidden = true
            } catch is CancellationError { }
            catch { message.text = error.localizedDescription; save.isEnabled = true }
            task = nil
        }
    }

    @objc private func close() {
        task?.cancel()
        if saved { extensionContext?.completeRequest(returningItems: nil) }
        else { extensionContext?.cancelRequest(withError: CocoaError(.userCancelled)) }
    }
}
