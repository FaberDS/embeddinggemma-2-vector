package dev.pocketask

import androidx.compose.ui.window.ComposeUIViewController
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import dev.pocketask.db.AppDatabase
import platform.UIKit.UIViewController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

interface ImportActivityStatus { fun changed(importing: Boolean) }
interface ShareImportResult { fun completed(success: Boolean) }

class IosApp(root: String, runtime: LocalRuntime, inputs: PlatformInputs, transfers: ModelTransfers, speech: PlatformSpeech, indexing: IndexingTasks, initiallyActive: Boolean) {
    private val controller = AppController(root, Store(NativeSqliteDriver(AppDatabase.Schema, "pocketask.db", onConfiguration = {
        it.copy(extendedConfig = it.extendedConfig.copy(basePath = root))
    }), root), runtime, inputs, transfers, platformSpeech = speech, indexingTasks = indexing, initiallyActive = initiallyActive)
    val viewController: UIViewController = ComposeUIViewController { PocketAskApp(controller) }
    private val observers = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var importObserver: Job? = null
    fun observeImports(listener: ImportActivityStatus) {
        importObserver?.cancel()
        importObserver = observers.launch { controller.state.map { it.importing }.distinctUntilChanged().collect { listener.changed(it) } }
    }
    fun hasPendingIndexing() = controller.canIndexInBackground()
    fun isIndexing() = controller.state.value.indexing != null
    fun resumeIndexingInBackground() = controller.resumeIndexingInBackground()
    fun foreground() = controller.foreground()
    fun receivedShare(id: String) = controller.receivedShare(id)
    fun receiveShare(id: String, url: String?, files: List<SharedFile>, error: String?, result: ShareImportResult) =
        controller.receiveShare(id, url, files, error) { result.completed(it) }
    fun background() = controller.background()
    fun stopSpeech() { if (controller.speech.state.value.listening) controller.speech.finishListening() else controller.speech.stop() }
}
