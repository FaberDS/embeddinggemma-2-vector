package dev.pocketask

import kotlinx.serialization.Serializable

@Serializable
data class Attachment(val id: String, val name: String, val path: String, val type: String, val prepared: Boolean = false) {
    val isImage get() = type.startsWith("image/")
}

@Serializable
data class Evidence(val id: String, val attachmentId: String, val name: String, val page: Int?, val text: String, val image: String?, val vector: List<Float>) {
    val label get() = if (page == null) name else "$name · page $page"
}

@Serializable
data class Answer(val id: String, val question: String, val attachments: List<Attachment>, val text: String = "", val sources: List<Evidence> = emptyList(), val status: String = "Preparing", val error: String? = null)

@Serializable
data class Draft(val question: String = "", val attachments: List<Attachment> = emptyList())

data class PageInput(val text: String, val imagePath: String?)
data class ModelSpec(val id: String, val title: String, val filename: String, val url: String, val bytes: Long, val sha256: String)

val modelSpecs = listOf(
    ModelSpec("search", "Search model", "embeddinggemma-2-740m.litertlm",
        "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/24d962e906c7d332c6428e71c9676855024569e2/embeddinggemma-2-740m.litertlm",
        484622336, "e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c"),
    ModelSpec("answer", "Answer model", "gemma-4-E2B-it.litertlm",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm",
        2588147712, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c")
)

data class ModelState(val installed: Boolean = false, val stage: String = "Not installed", val downloaded: Long = 0, val busy: Boolean = false, val error: String? = null)
data class UiState(val draft: Draft = Draft(), val history: List<Answer> = emptyList(), val result: Answer? = null,
    val onboarding: Int = 0, val settings: Boolean = false, val screen: String = "ask", val stage: String? = null,
    val error: String? = null, val picking: Boolean = false, val importing: Boolean = false, val sourcesExpanded: Boolean = false)

interface Completion { fun success(); fun failure(message: String) }
interface VectorResult { fun success(values: List<Float>); fun failure(message: String) }
interface StreamResult { fun token(text: String); fun success(); fun failure(message: String) }
interface ImportResult { fun item(name: String, path: String, type: String); fun success(); fun failure(message: String) }
interface CountResult { fun success(count: Int); fun failure(message: String) }
interface PageResult { fun success(page: PageInput); fun failure(message: String) }

/** Callback boundary keeps native Swift APIs outside the shared UI and request logic. */
interface LocalRuntime {
    fun load(search: Boolean, path: String, callback: Completion)
    fun embed(text: String, imagePath: String?, query: Boolean, callback: VectorResult)
    fun answer(instructions: String, prompt: String, images: List<String>, callback: StreamResult)
    fun release(callback: Completion)
    fun cancel()
}

interface PlatformInputs {
    fun pick(images: Boolean, callback: ImportResult)
    fun pageCount(attachment: Attachment, callback: CountResult)
    fun readPage(attachment: Attachment, page: Int, callback: PageResult)
    fun open(path: String, page: Int)
    fun freeBytes(): Long
    fun canDownload(cellular: Boolean): Boolean
    fun now(): Long
}
