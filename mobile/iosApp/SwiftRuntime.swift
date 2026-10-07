import Foundation
import LiteRTLM
import PocketAsk

/// All model operations run through one actor; cancellation never releases a live engine.
private actor ModelWorker {
    var embedding: EmbeddingEngine?
    var engine: Engine?
    var conversation: Conversation?
    private var cancelled = false
    private let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].path

    func load(search: Bool, path: String, cpu: Bool) async throws {
        await release(); cancelled = false
        if search {
            let model = EmbeddingEngine(config: EmbeddingEngineConfig(modelPath: path, backend: .cpu(threadCount: 4), visionBackend: .cpu(threadCount: 4), cacheDir: cache))
            embedding = model
            try await model.initialize()
        } else {
            #if targetEnvironment(simulator)
            let backend = Backend.cpu(threadCount: 4)
            #else
            let backend = cpu ? Backend.cpu(threadCount: 4) : Backend.gpu
            #endif
            let model = Engine(engineConfig: try EngineConfig(modelPath: path, backend: backend, visionBackend: .cpu(threadCount: 4), maxNumTokens: cpu ? 4096 : 8192, cacheDir: cache))
            engine = model
            try await model.initialize()
        }
        if cancelled { throw CancellationError() }
    }

    func embed(text: String, image: String?, query: Bool) async throws -> [Float] {
        guard let embedding, !cancelled else { throw CancellationError() }
        let content: LiteRTLM.Content = try image.map { try imageContent($0) } ?? .text(query ? "task: search result | query: \(text)" : "title: none | text: \(text)")
        return try await embedding.computeEmbedding(contents: [content], options: EmbeddingOptions(normalize: true, outputSize: 256)).embedding
    }

    func answer(instructions: String, prompt: String, images: [String], callback: StreamResult) async throws {
        guard let engine, !cancelled else { throw CancellationError() }
        let config = ConversationConfig(systemMessage: Message(instructions), samplerConfig: try SamplerConfig(topK: 40, topP: 0.9, temperature: 0.2), thinkingConfig: ThinkingConfig(enableThinking: false))
        let current = try await engine.createConversation(with: config)
        conversation = current
        defer { conversation = nil }
        let contents: [LiteRTLM.Content] = [.text(prompt)] + (try images.map { try imageContent($0) })
        let stream = await current.sendMessageStream(Message(contents: contents), maxOutputTokens: 512)
        for try await chunk in stream {
            if cancelled { throw CancellationError() }
            callback.token(text: chunk.toString)
        }
    }

    private func imageContent(_ path: String) throws -> LiteRTLM.Content {
        do {
            let data = try Data(contentsOf: URL(fileURLWithPath: path), options: .mappedIfSafe)
            guard !data.isEmpty else { throw CocoaError(.fileReadCorruptFile) }
            return .imageData(data)
        } catch {
            throw NSError(domain: "PocketAsk.Image", code: 1, userInfo: [NSLocalizedDescriptionKey: "An attached image is missing or unreadable. Remove it and add it again, then retry your question."])
        }
    }

    func cancel() async {
        cancelled = true
        try? await conversation?.cancel()
    }
    func release() async {
        // Embedding operations are serialized by the SDK actor; close waits for an in-flight call.
        await embedding?.close(); embedding = nil
        conversation = nil
        engine = nil
    }
}

final class SwiftRuntime: LocalRuntime, @unchecked Sendable {
    private let worker = ModelWorker()
    private let queueLock = NSLock()
    private var previous: Task<Void, Never>?
    private var indexingCPU = false
    private var details = "No model loaded"
    func setIndexingCPU(_ value: Bool) { queueLock.lock(); indexingCPU = value; queueLock.unlock() }
    private func cpuPreference() -> Bool { queueLock.lock(); defer { queueLock.unlock() }; return indexingCPU }
    private func setDetails(_ value: String) { queueLock.lock(); details = value; queueLock.unlock() }
    func inferenceDetails() -> String { queueLock.lock(); defer { queueLock.unlock() }; return details }
    private func enqueue(_ work: @escaping () async -> Void) {
        queueLock.lock()
        let predecessor = previous
        previous = Task { await predecessor?.value; await work() }
        queueLock.unlock()
    }
    func load(search: Bool, path: String, callback: Completion) {
        enqueue {
            do {
                let cpu = self.cpuPreference()
                try await self.worker.load(search: search, path: path, cpu: cpu)
                #if targetEnvironment(simulator)
                let backend = "CPU · 4 threads"
                #else
                let backend = cpu ? "CPU · 4 threads" : "GPU"
                #endif
                self.setDetails(search ? "CPU · 4 threads · 256-value embeddings" : "\(backend) · \(cpu ? 4096 : 8192)-token context · 512-token output limit")
                callback.success()
            } catch { callback.failure(message: error.localizedDescription) }
        }
    }
    func embed(text: String, imagePath: String?, query: Bool, callback: VectorResult) {
        enqueue { do { let values = try await self.worker.embed(text: text, image: imagePath, query: query); callback.success(values: values.map { KotlinFloat(float: $0) }) } catch { callback.failure(message: error.localizedDescription) } }
    }
    func answer(instructions: String, prompt: String, images: [String], callback: StreamResult) {
        enqueue { do { try await self.worker.answer(instructions: instructions, prompt: prompt, images: images, callback: callback); callback.success() } catch { callback.failure(message: error.localizedDescription) } }
    }
    func release(callback: Completion) { enqueue { await self.worker.release(); self.setDetails("No model loaded"); callback.success() } }
    func cancel() { Task { await worker.cancel() } }
}
