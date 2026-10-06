package dev.pocketask

import androidx.compose.ui.window.ComposeUIViewController
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import dev.pocketask.db.AppDatabase
import platform.UIKit.UIViewController

class IosApp(root: String, runtime: LocalRuntime, inputs: PlatformInputs) {
    private val controller = AppController(root, Store(NativeSqliteDriver(AppDatabase.Schema, "pocketask.db", onConfiguration = {
        it.copy(extendedConfig = it.extendedConfig.copy(basePath = root))
    })), runtime, inputs)
    val viewController: UIViewController = ComposeUIViewController { PocketAskApp(controller) }
    fun background() = controller.background()
}
