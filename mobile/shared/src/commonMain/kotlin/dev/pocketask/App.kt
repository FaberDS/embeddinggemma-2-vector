package dev.pocketask

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private val Accent = Color(0xFF4C6758)

@Composable
fun PocketAskApp(controller: AppController) {
    val state by controller.state.collectAsState()
    val models by controller.models.states.collectAsState()
    val haze = rememberHazeState()
    val showNavigation = WindowInsets.ime.getBottom(LocalDensity.current) == 0
    val navigationSpace = if (showNavigation) 88.dp else 0.dp
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFADD0B7), primaryContainer = Color(0xFF304437), onPrimaryContainer = Color(0xFFD5EAD8), secondaryContainer = Color(0xFF304437), onSecondaryContainer = Color(0xFFD5EAD8))
        else lightColorScheme(primary = Accent, primaryContainer = Color(0xFFE0EBE2), onPrimaryContainer = Color(0xFF1F3528), secondaryContainer = Color(0xFFDFE7E0), onSecondaryContainer = Color(0xFF24402D), surface = Color(0xFFFAFBF8), background = Color(0xFFFAFBF8))
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) {
            if (state.onboarding < 2) Onboarding(controller, state, models)
            else Box(Modifier.fillMaxSize().imePadding()) {
                Scaffold(modifier = Modifier.fillMaxSize().hazeSource(haze),
                    topBar = {
                        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Pocket Ask", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (state.screen == "ask" && state.draft.conversationId != null) TextButton(onClick = controller::newQuestion, enabled = state.stage == null && !state.picking && !state.importing) { Text("New chat") }
                        }
                    }
                ) { padding ->
                    when (state.screen) {
                        "assets" -> Assets(controller, state, Modifier.padding(padding), navigationSpace)
                        "settings" -> Settings(controller, state, models, Modifier.padding(padding), navigationSpace)
                        "history" -> History(controller, state, Modifier.padding(padding), navigationSpace)
                        "detail" -> Chat(controller, state, models, Modifier.padding(padding).padding(bottom = navigationSpace), readOnly = true)
                        else -> Chat(controller, state, models, Modifier.padding(padding).padding(bottom = navigationSpace))
                    }
                }
                if (showNavigation) GlassNavigation(state.screen, controller::screen, haze,
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp))
            }
            state.memory?.let { MemoryDialog(controller, it, state) }
        }
    }
}

@Composable
internal fun IndexingState(controller: AppController, state: UiState) {
    state.stage?.let { stage ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stage, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = controller::stop) { Text("Stop") }
            }
        }
    }
    val pending = state.library.count { !it.prepared }
    if (pending > 0 && state.stage == null && !state.picking && !state.importing) {
        Text("$pending ${if (pending == 1) "asset" else "assets"} waiting to index", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { if (controller.models.states.value.getValue("search").installed) controller.indexAttachments() else controller.settings() }) {
            Text(if (controller.models.states.value.getValue("search").installed) "Index assets" else "Set up search model")
        }
    }
}

@Composable
private fun History(controller: AppController, state: UiState, modifier: Modifier, navigationSpace: Dp) {
    var clear by remember { mutableStateOf(false) }
    val now = rememberChatTime()
    val conversations = conversationSummaries(state.history)
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 12.dp, bottom = navigationSpace + 12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Your history", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                if (state.history.isNotEmpty()) TextButton(onClick = { clear = true }) { Text("Clear") }
            }
        }
        if (controller.canUndo()) item { TextButton(onClick = controller::undoDelete) { Text("Entry deleted · Undo") } }
        if (state.history.isEmpty()) item { Text("Your questions and answers will appear here. Everything stays on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(conversations, key = { it.conversationId }) { answer ->
            OutlinedCard(onClick = { controller.showHistory(answer) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(conversationTurns(state, answer.conversationId).firstOrNull()?.question ?: answer.question, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text("${state.history.count { it.conversationId == answer.conversationId }} questions · ${messageTime(answer.completedAt ?: answer.sentAt(), now)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (answer.text.isNotBlank()) Text(answer.text, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { controller.deleteConversation(answer.conversationId) }, enabled = state.stage == null) { Text("Delete") }
                }
            }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Clear history?") }, text = { Text("Saved conversations will be removed. Your knowledge base is kept.") }, confirmButton = { TextButton(onClick = { controller.clearHistory(); clear = false }) { Text("Clear history") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("Keep history") } })
}

@Composable
private fun Onboarding(controller: AppController, state: UiState, models: Map<String, ModelState>) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Spacer(Modifier.height(32.dp))
        Text("Pocket Ask", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (state.onboarding == 0) {
            Text("Ask privately.\nStay on your device.", style = MaterialTheme.typography.headlineLarge)
            Text("Build your knowledge base with documents, images and notes. Every chat searches your saved sources and gives a short answer with citations.", style = MaterialTheme.typography.bodyLarge)
            Text("After model setup, questions, attachments and history work offline.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Button(onClick = controller::introNext, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Continue") }
        } else {
            Text("Prepare offline models", style = MaterialTheme.typography.headlineMedium)
            Text("Two local models, about ${formatBytes(controller.selectedModels().sumOf { it.bytes })} total. Download once; use offline afterward.")
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) { ModelSetup(controller, state, models) }
            TextButton(onClick = controller::finishOnboarding, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Later · keep preparing a draft") }
        }
    }
}

@Composable
internal fun ModelSetup(controller: AppController, state: UiState, models: Map<String, ModelState>) {
    var cellular by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<ModelSpec?>(null) }
    val busy = models.values.any { it.busy }
    var choose by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Answer model", style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(onClick = { choose = true }, enabled = !busy && state.stage == null, modifier = Modifier.fillMaxWidth()) {
                Text(modelSpecs.first { it.id == state.answerModel }.title)
            }
            DropdownMenu(expanded = choose, onDismissRequest = { choose = false }) {
                modelSpecs.filter { it.id != "search" }.forEach { option ->
                    DropdownMenuItem(text = { Column {
                        Text(option.title)
                        Text("${formatBytes(option.bytes)} · ${option.detail}", style = MaterialTheme.typography.bodySmall)
                    } }, onClick = { controller.selectAnswerModel(option.id); choose = false })
                }
            }
        }
        Text("Both choices support text and images on iOS and Android. E4B uses more memory.", style = MaterialTheme.typography.bodySmall)
        controller.selectedModels().forEach { spec ->
            val status = models.getValue(spec.id)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(spec.title, style = MaterialTheme.typography.titleSmall)
                    Text(spec.filename.removeSuffix(".litertlm"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (spec.id == "search") "Search · text and images" else spec.detail, style = MaterialTheme.typography.bodySmall)
                    Text("${formatBytes(spec.bytes)} · ${status.stage}", style = MaterialTheme.typography.bodySmall)
                    if (status.busy) {
                        if (status.stage == "Downloading" || status.stage == "Queued" || status.stage.startsWith("Waiting")) {
                            LinearProgressIndicator(progress = { (status.downloaded.toDouble() / spec.bytes).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text("${formatBytes(status.downloaded)} / ${formatBytes(spec.bytes)}", style = MaterialTheme.typography.bodySmall)
                            Text(status.remainingSeconds?.let(::formatRemaining) ?: if (status.stage == "Downloading") "Estimating time remaining…" else "Time estimate appears when downloading resumes", style = MaterialTheme.typography.bodySmall)
                        } else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (status.installed) TextButton(onClick = { remove = spec }, enabled = !busy && state.stage == null) { Text("Remove model") }
                }
            }
        }
        if (controller.selectedModels().any { !models.getValue(it.id).installed }) {
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(cellular, { cellular = it }, enabled = !busy); Text("Allow cellular download", style = MaterialTheme.typography.bodySmall) }
            if (busy) OutlinedButton(onClick = controller::cancelDownloads, modifier = Modifier.fillMaxWidth()) { Text("Cancel download") }
            else Button(onClick = { controller.downloadModels(cellular) }, enabled = state.stage == null, modifier = Modifier.fillMaxWidth()) { Text("Download missing models") }
        }
        Text("Downloads continue in the background. The system may pause for Wi-Fi or battery. Reopen to verify completed models; on iOS, avoid force-quitting the app.", style = MaterialTheme.typography.bodySmall)
        if (controller.selectedModels().all { models.getValue(it.id).installed } && state.onboarding < 2) {
            Button(onClick = controller::finishOnboarding, modifier = Modifier.fillMaxWidth()) { Text("Start asking") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    remove?.let { spec -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove ${spec.title.lowercase()}?") }, text = { Text("You will need to download it again before using it. Your history stays available.") }, confirmButton = { TextButton(onClick = { controller.removeModel(spec); remove = null }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { remove = null }) { Text("Keep") } }) }
}

@Composable
internal fun ErrorCard(message: String, dismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) { Text(message); TextButton(onClick = dismiss) { Text("Dismiss") } }
    }
}

@Composable
internal fun ConfirmRemoveSource(source: Attachment, remove: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Remove from knowledge base?") },
        text = { Text("${source.name} will no longer be searched. Earlier answers keep their linked files.") },
        confirmButton = { TextButton(onClick = remove, modifier = Modifier.testTag("knowledge.remove")) { Text("Remove") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Keep source") } })
}
