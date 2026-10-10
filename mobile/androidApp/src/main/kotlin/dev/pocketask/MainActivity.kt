package dev.pocketask

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.pocketask.db.AppDatabase

class PocketViewModel(application: Application) : AndroidViewModel(application) {
    val inputs = AndroidInputs(application)
    val speech = AndroidSpeech(application)
    val controller = AppController(application.filesDir.absolutePath,
        Store(AndroidSqliteDriver(AppDatabase.Schema, application, "pocketask.db"), application.filesDir.absolutePath), AndroidRuntime(application), inputs, AndroidModelTransfers(application), platformSpeech = speech)
    override fun onCleared() { controller.close(); inputs.close(); speech.close() }
}

class MainActivity : ComponentActivity() {
    private lateinit var model: PocketViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[PocketViewModel::class.java]
        model.inputs.attach(this)
        model.speech.attach(this)
        setContent { PocketAskApp(model.controller) }
        model.inputs.receiveShare(intent, model.controller)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); model.inputs.receiveShare(intent, model.controller) }
    override fun onStart() { super.onStart(); model.controller.foreground() }
    override fun onStop() { super.onStop(); if (!isChangingConfigurations) model.controller.background() }
}
