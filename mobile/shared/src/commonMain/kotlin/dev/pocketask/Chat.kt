package dev.pocketask

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.time.Clock

@Composable
internal fun rememberChatTime(): Long {
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) { while (true) { now = Clock.System.now().toEpochMilliseconds(); delay(30_000) } }
    return now
}

@Composable
internal fun Chat(controller: AppController, state: UiState, models: Map<String, ModelState>, modifier: Modifier, readOnly: Boolean = false) {
    val speech by controller.speech.state.collectAsState()
    val id = if (readOnly) state.result?.conversationId else state.draft.conversationId
    val turns = conversationTurns(state, id)
    val list = rememberLazyListState()
    val now = rememberChatTime()
    LaunchedEffect(id, turns.size) { if (turns.isNotEmpty()) list.animateScrollToItem(turns.size * 2 - 1) }
    Column(modifier.fillMaxSize()) {
        if (readOnly) TextButton(onClick = { controller.screen("history") }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Back to history") }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat.messages"), state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (turns.isEmpty()) item {
                Box(Modifier.fillParentMaxHeight(0.7f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("What would you like to know?", style = MaterialTheme.typography.headlineSmall)
                }
            }
            turns.forEach { answer ->
                item(key = "user-${answer.id}") { UserMessage(answer, now, controller::open) }
                item(key = "reply-${answer.id}") { ModelMessage(answer, now, if (!readOnly && state.result?.id == answer.id) state.stage else null, controller::open, controller::useAgain,
                    onRead = if (controller.speech.available) { { controller.speech.read(answer) } } else null,
                    playing = speech.answerId == answer.id, canRead = state.stage == null, playbackStage = speech.stage.takeIf { speech.answerId == answer.id }) }
            }
        }
        if (readOnly) {
            turns.lastOrNull()?.let { answer ->
                OutlinedButton(onClick = { controller.continueConversation(answer) }, enabled = state.stage == null,
                    modifier = Modifier.padding(16.dp).fillMaxWidth()) { Text("Continue conversation") }
            }
        } else ChatComposer(controller, state, models)
    }
}

@Composable
internal fun UserMessage(answer: Answer, now: Long, onOpen: (String, Int) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(Modifier.fillMaxWidth(0.86f).testTag("chat.user.${answer.id}"), shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(answer.question, style = MaterialTheme.typography.bodyLarge)
                if (!answer.usesKnowledgeBase && answer.attachments.isNotEmpty()) {
                    Text("${answer.attachments.count { !it.isImage }} files · ${answer.attachments.count { it.isImage }} images", style = MaterialTheme.typography.labelSmall)
                    answer.attachments.filterNot { it.isImage }.forEach { attachment ->
                        TextButton(onClick = { onOpen(attachment.path, 1) }, contentPadding = PaddingValues(0.dp)) { Text(attachment.name, maxLines = 2) }
                    }
                }
                Text(messageTime(answer.sentAt(), now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ModelMessage(answer: Answer, now: Long, stage: String?, onOpen: (String, Int) -> Unit, onRetry: (Answer) -> Unit, onRead: (() -> Unit)? = null, playing: Boolean = false, canRead: Boolean = true, playbackStage: String? = null) {
    val clipboard = LocalClipboardManager.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(Modifier.fillMaxWidth(0.94f).testTag("chat.reply.${answer.id}"), shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(modelSpecs.firstOrNull { it.id == answer.modelId }?.title ?: answer.modelId ?: "Local model", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(answer.text.ifBlank { answer.error ?: stage ?: if (answer.status == "Completed") "No answer was returned." else answer.status }, style = MaterialTheme.typography.bodyLarge)
                if (answer.error != null && answer.text.isNotBlank()) Text(answer.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (answer.status == "Completed") ReplySources(answer, onOpen)
                playbackStage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val time = answer.completedAt?.let { messageTime(it, now) }
                    Text(if (answer.status == "Completed") time.orEmpty() else listOfNotNull(answer.status, time).joinToString(" · "),
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (onRead != null && answer.status == "Completed" && answer.text.isNotBlank()) TextButton(onClick = onRead, enabled = canRead, modifier = Modifier.testTag("speech.read.${answer.id}"), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (playing) "Stop" else "Read") }
                    if (answer.text.isNotBlank()) TextButton(onClick = { clipboard.setText(AnnotatedString(answer.text)) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Copy") }
                    if (answer.status in listOf("Failed", "Stopped", "Interrupted")) TextButton(onClick = { onRetry(answer) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
private fun ReplySources(answer: Answer, onOpen: (String, Int) -> Unit) {
    val links = sourceLinks(answer)
    val images = links.filter { it.isImage }
    if (images.size == 1) {
        val image = images.single()
        OutlinedCard(Modifier.fillMaxWidth().testTag("chat.source.image").clickable(enabled = image.path != null) { image.path?.let { onOpen(it, 1) } }) {
            AsyncImage(image.path, contentDescription = image.title, modifier = Modifier.fillMaxWidth().height(190.dp), contentScale = ContentScale.Fit)
            TextButton(onClick = { image.path?.let { onOpen(it, 1) } }, enabled = image.path != null) { Text("${image.label} · Open", maxLines = 2) }
        }
    } else if (images.isNotEmpty()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(images, key = { it.citations.first() }) { image ->
                OutlinedCard(Modifier.width(132.dp).testTag("chat.source.thumbnail").clickable(enabled = image.path != null) { image.path?.let { onOpen(it, 1) } }) {
                    AsyncImage(image.path, contentDescription = image.title, modifier = Modifier.fillMaxWidth().height(100.dp), contentScale = ContentScale.Fit)
                    Text(image.label, modifier = Modifier.padding(horizontal = 8.dp), maxLines = 2, style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { image.path?.let { onOpen(it, 1) } }, enabled = image.path != null) { Text("Open") }
                }
            }
        }
    }
    links.filterNot { it.isImage }.forEach { document ->
        TextButton(onClick = { document.path?.let { onOpen(it, document.page ?: 1) } }, enabled = document.path != null,
            modifier = Modifier.fillMaxWidth().testTag("chat.source.document"), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(document.label, style = MaterialTheme.typography.labelMedium)
                Text("Open document", style = MaterialTheme.typography.labelSmall)
            }
            Text("↗")
        }
    }
}

@Composable
private fun ChatComposer(controller: AppController, state: UiState, models: Map<String, ModelState>) {
    val speech by controller.speech.state.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    var actions by remember { mutableStateOf(false) }
    var sources by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Attachment?>(null) }
    var writing by rememberSaveable { mutableStateOf(false) }
    val busy = state.stage != null || state.picking || state.importing
    val needsSetup = !models.getValue(state.answerModel).installed || (state.library.isNotEmpty() && !models.getValue("search").installed)
    val canSend = !speech.listening && !busy && state.draft.question.isNotBlank() && (needsSetup || state.library.isEmpty() || state.library.any { it.prepared })
    fun send() { if (canSend) { keyboard?.hide(); if (needsSetup) controller.settings() else controller.ask() } }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.library.isNotEmpty()) TextButton(onClick = { sources = true }, modifier = Modifier.testTag("chat.manageSources"), contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("Knowledge base · ${state.library.size} sources · ${state.library.count { it.prepared }} indexed", style = MaterialTheme.typography.labelMedium)
            }
            state.stage?.let { stage -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stage, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                TextButton(onClick = controller::stop) { Text("Stop") }
            } }
            if (state.picking || state.importing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(if (state.importing) "Importing sources…" else "Choose sources", style = MaterialTheme.typography.bodySmall) }
            if (!busy && state.library.any { !it.prepared }) TextButton(onClick = { if (models.getValue("search").installed) controller.indexAttachments() else controller.settings() }) {
                Text(if (models.getValue("search").installed) "Index pending sources" else "Set up search model")
            }
            state.error?.let { error -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = controller::dismissError) { Text("Dismiss") }
            } }
            if (speech.listening && !speech.memory) Text(speech.stage ?: "Listening…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            speech.error?.let { Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = controller.speech::dismissError) { Text("Dismiss") }
            } }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box {
                    FilledIconButton(onClick = { actions = true }, enabled = !busy, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF303833), contentColor = Color.White), modifier = Modifier.testTag("chat.addSource").semantics { contentDescription = "Add source" }) { Text("+", style = MaterialTheme.typography.titleLarge) }
                    DropdownMenu(actions, { actions = false }) {
                        DropdownMenuItem(text = { Text("Add files") }, onClick = { actions = false; controller.pick(false) })
                        DropdownMenuItem(text = { Text("Add images") }, onClick = { actions = false; controller.pick(true) })
                        DropdownMenuItem(text = { Text("Write text") }, onClick = { actions = false; writing = true })
                        DropdownMenuItem(text = { Text("Add memory") }, enabled = controller.speech.available, onClick = { actions = false; keyboard?.hide(); controller.startMemory() }, modifier = Modifier.testTag("memory.add"))
                    }
                }
                OutlinedTextField(state.draft.question, controller::question, modifier = Modifier.weight(1f).testTag("chat.input"), placeholder = { Text("Message") }, minLines = 1, maxLines = 6, enabled = !busy && !speech.listening,
                    shape = RoundedCornerShape(24.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
                if (controller.speech.available) IconButton(onClick = { keyboard?.hide(); controller.speech.toggleListening() }, enabled = !busy,
                    modifier = Modifier.testTag("speech.microphone").semantics { contentDescription = if (speech.listening) "Finish dictation" else "Dictate message" }) { MicrophoneIcon(speech.listening) }
                FilledIconButton(onClick = { send() }, enabled = canSend, modifier = Modifier.testTag("chat.send").semantics { contentDescription = "Send message" }) { Text("↑", style = MaterialTheme.typography.titleLarge) }
            }
            if (needsSetup && !busy) TextButton(onClick = { controller.settings() }) { Text("Set up models") }
        }
    }
    if (sources) AlertDialog(onDismissRequest = { sources = false }, title = { Text("Your knowledge base") }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.library, key = { it.id }) { attachment ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        TextButton(onClick = { controller.open(attachment.path) }, contentPadding = PaddingValues(0.dp)) { Text(attachment.name, maxLines = 2) }
                        Text(listOfNotNull(if (attachment.isMemory) "Memory" else null, if (attachment.prepared) "Indexed" else "Waiting to index").joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { removing = attachment }, enabled = !busy) { Text("Remove") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { sources = false }) { Text("Done") } })
    removing?.let { source -> ConfirmRemoveSource(source, { controller.removeAttachment(source.id); removing = null }, { removing = null }) }
    if (writing) WriteTextDialog(controller, state) { writing = false }
}
