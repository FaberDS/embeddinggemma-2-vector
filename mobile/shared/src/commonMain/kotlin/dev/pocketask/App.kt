package dev.pocketask

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

private val Accent = Color(0xFF4C6758)

@Composable
fun PocketAskApp(controller: AppController) {
    val state by controller.state.collectAsState()
    val models by controller.models.states.collectAsState()
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFADD0B7)) else lightColorScheme(primary = Accent, surface = Color(0xFFFAFBF8), background = Color(0xFFFAFBF8))
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) {
            if (state.onboarding < 2) Onboarding(controller, state, models)
            else Scaffold(
                topBar = {
                    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Pocket Ask", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { controller.settings(true) }) { Text("Settings") }
                    }
                },
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(selected = state.screen == "ask", onClick = { controller.screen("ask") }, icon = { Text("＋") }, label = { Text("Ask") })
                        NavigationBarItem(selected = state.screen != "ask", onClick = { controller.screen("history") }, icon = { Text("≡") }, label = { Text("History") })
                    }
                }
            ) { padding ->
                when (state.screen) {
                    "history" -> History(controller, state, Modifier.padding(padding))
                    "detail" -> LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item { TextButton(onClick = { controller.screen("history") }) { Text("Back to history") } }
                        state.result?.let { answer ->
                            item { Text(answer.question, style = MaterialTheme.typography.headlineSmall) }
                            item { ResultCard(controller, answer, state.sourcesExpanded) }
                            item { Button(onClick = { controller.useAgain(answer) }, enabled = state.stage == null) { Text("Use again") } }
                        }
                    }
                    else -> Ask(controller, state, models, Modifier.padding(padding))
                }
            }
            if (state.settings) Settings(controller, state, models)
        }
    }
}

@Composable
private fun Ask(controller: AppController, state: UiState, models: Map<String, ModelState>, modifier: Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    var allAttachments by remember { mutableStateOf(false) }
    val needsSetup = !models.getValue("answer").installed || (state.draft.attachments.isNotEmpty() && !models.getValue("search").installed)
    val busy = state.stage != null || state.picking || state.importing
    LazyColumn(modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        item {
            Text("A little help,\nkept on your device.", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("Ask a question. Add files or images when you need them.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedTextField(state.draft.question, controller::question, modifier = Modifier.fillMaxWidth(), placeholder = { Text("What would you like to know?") }, minLines = 3, maxLines = 8,
                enabled = !busy, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { controller.pick(false) }, enabled = !busy) { Text("Add files") }
                OutlinedButton(onClick = { controller.pick(true) }, enabled = !busy) { Text("Add images") }
            }
            if (state.picking || state.importing) {
                Spacer(Modifier.height(12.dp)); LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (state.importing) "Copying your selection…" else "Choose items in the picker", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.draft.attachments.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val images = state.draft.attachments.count { it.isImage }
                    Text("${state.draft.attachments.size - images} files · $images images", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    if (state.draft.attachments.size > 3) TextButton(onClick = { allAttachments = !allAttachments }) { Text(if (allAttachments) "Show less" else "Show all") }
                }
            }
            items(if (allAttachments) state.draft.attachments else state.draft.attachments.take(3), key = { it.id }) { attachment ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (attachment.isImage) AsyncImage(attachment.path, contentDescription = attachment.name, modifier = Modifier.size(48.dp))
                        else Text("FILE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(attachment.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            Text(if (attachment.prepared) "Prepared" else "Ready to prepare", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { controller.removeAttachment(attachment.id) }, enabled = !busy) { Text("Remove") }
                    }
                }
            }
        }
        item {
            Button(onClick = { keyboard?.hide(); if (needsSetup) controller.settings(true) else controller.ask() }, enabled = !busy && state.draft.question.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (needsSetup) "Set up models" else "Ask")
            }
            if (state.draft.attachments.isEmpty()) Text("No attachments · general answer", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.stage?.let { stage -> item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stage, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = controller::stop) { Text("Stop") }
                }
            }
        } }
        state.error?.let { error -> item { ErrorCard(error, controller::dismissError) } }
        state.result?.let { answer ->
            if (answer.text.isNotEmpty() || answer.status !in listOf("Preparing", "Answering")) {
                item { ResultCard(controller, answer, state.sourcesExpanded) }
                if (state.stage == null) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = controller::newQuestion) { Text("New question") }
                        if (answer.status == "Completed") TextButton(onClick = { controller.ask(expand = true) }) { Text("Expand answer") }
                        else TextButton(onClick = { controller.useAgain(answer) }) { Text("Use again") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(controller: AppController, answer: Answer, expanded: Boolean) {
    val clipboard = LocalClipboardManager.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (answer.status == "Completed") "Answer" else answer.status, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (answer.text.isNotEmpty()) TextButton(onClick = { clipboard.setText(AnnotatedString(answer.text)) }) { Text("Copy") }
            }
            Text(answer.text.ifBlank { answer.error ?: "This request did not finish. Use again to retry." }, style = MaterialTheme.typography.bodyLarge)
            if (answer.sources.isNotEmpty()) {
                TextButton(onClick = controller::toggleSources) { Text("${if (expanded) "Hide" else "Show"} sources · ${answer.sources.size}") }
                if (expanded) answer.sources.forEachIndexed { index, source ->
                    TextButton(onClick = { controller.open(source) }) { Text("[S${index + 1}] ${source.label}") }
                }
            }
        }
    }
}

@Composable
private fun History(controller: AppController, state: UiState, modifier: Modifier) {
    var clear by remember { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Your history", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                if (state.history.isNotEmpty()) TextButton(onClick = { clear = true }) { Text("Clear") }
            }
        }
        if (controller.canUndo()) item { TextButton(onClick = controller::undoDelete) { Text("Entry deleted · Undo") } }
        if (state.history.isEmpty()) item { Text("Your questions and answers will appear here. Everything stays on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.history, key = { it.id }) { answer ->
            OutlinedCard(onClick = { controller.showHistory(answer) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(answer.question, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text("${answer.attachments.size} attachments · ${answer.status}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (answer.text.isNotBlank()) Text(answer.text, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { controller.deleteHistory(answer) }, enabled = state.stage == null) { Text("Delete") }
                }
            }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Clear history?") }, text = { Text("Saved answers and attachments used only by these entries will be removed.") }, confirmButton = { TextButton(onClick = { controller.clearHistory(); clear = false }) { Text("Clear history") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("Keep history") } })
}

@Composable
private fun Onboarding(controller: AppController, state: UiState, models: Map<String, ModelState>) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Spacer(Modifier.height(32.dp))
        Text("Pocket Ask", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (state.onboarding == 0) {
            Text("Ask privately.\nStay on your device.", style = MaterialTheme.typography.headlineLarge)
            Text("Select documents, add images, or simply ask a question. Get a short answer with sources when you provide material.", style = MaterialTheme.typography.bodyLarge)
            Text("After model setup, questions, attachments and history work offline.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Button(onClick = controller::introNext, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Continue") }
        } else {
            Text("Prepare offline models", style = MaterialTheme.typography.headlineMedium)
            Text("Two local models, about ${formatBytes(modelSpecs.sumOf { it.bytes })} total. Download once; use offline afterward.")
            Column(Modifier.weight(1f).fillMaxWidth()) { ModelSetup(controller, state, models) }
            TextButton(onClick = controller::finishOnboarding, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Later · keep preparing a draft") }
        }
    }
}

@Composable
private fun Settings(controller: AppController, state: UiState, models: Map<String, ModelState>) {
    AlertDialog(onDismissRequest = { controller.settings(false) }, title = { Text("Offline models") }, text = {
        Column { ModelSetup(controller, state, models); Spacer(Modifier.height(12.dp)); Text("Attachments and answers stay in private app storage. Models can be removed without deleting history.", style = MaterialTheme.typography.bodySmall) }
    }, confirmButton = { TextButton(onClick = { controller.settings(false) }) { Text("Done") } })
}

@Composable
private fun ModelSetup(controller: AppController, state: UiState, models: Map<String, ModelState>) {
    var cellular by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<ModelSpec?>(null) }
    val busy = models.values.any { it.busy }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        modelSpecs.forEach { spec ->
            val status = models.getValue(spec.id)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(spec.title, style = MaterialTheme.typography.titleSmall)
                    Text("${formatBytes(spec.bytes)} · ${status.stage}", style = MaterialTheme.typography.bodySmall)
                    if (status.busy) {
                        if (status.stage == "Downloading") {
                            LinearProgressIndicator(progress = { (status.downloaded.toDouble() / spec.bytes).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text("${formatBytes(status.downloaded)} / ${formatBytes(spec.bytes)}", style = MaterialTheme.typography.bodySmall)
                        } else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (status.installed) TextButton(onClick = { remove = spec }, enabled = !busy && state.stage == null) { Text("Remove model") }
                }
            }
        }
        if (models.values.any { !it.installed }) {
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(cellular, { cellular = it }, enabled = !busy); Text("Allow cellular download", style = MaterialTheme.typography.bodySmall) }
            if (busy) OutlinedButton(onClick = controller::cancelDownloads, modifier = Modifier.fillMaxWidth()) { Text("Cancel download") }
            else Button(onClick = { controller.downloadModels(cellular) }, enabled = state.stage == null, modifier = Modifier.fillMaxWidth()) { Text("Download missing models") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    remove?.let { spec -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove ${spec.title.lowercase()}?") }, text = { Text("You will need to download it again before using it. Your history stays available.") }, confirmButton = { TextButton(onClick = { controller.removeModel(spec); remove = null }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { remove = null }) { Text("Keep") } }) }
}

@Composable
private fun ErrorCard(message: String, dismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) { Text(message); TextButton(onClick = dismiss) { Text("Dismiss") } }
    }
}
