package dev.pocketask

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectAudioSheet(controller: AppController, state: UiState) {
    val project = state.projects.firstOrNull { it.id == state.audioProjectId } ?: return
    val settings = state.audioSettings
    val speech by controller.speech.state.collectAsState()
    var advanced by remember(project.id) { mutableStateOf(false) }
    var short by remember(project.id, settings.shortMinutes) { mutableStateOf(settings.shortMinutes.toString()) }
    var medium by remember(project.id, settings.mediumMinutes) { mutableStateOf(settings.mediumMinutes.toString()) }
    var long by remember(project.id, settings.longMinutes) { mutableStateOf(settings.longMinutes.toString()) }
    val busy = state.audioGenerating
    val validDurations = listOf(short, medium, long).all { (it.toIntOrNull() ?: 0) > 0 }
    ModalBottomSheet(onDismissRequest = controller::closeAudio, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("audio.sheet")) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Create audio", style = MaterialTheme.typography.headlineSmall)
            Text(project.name, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(state.draft.question, controller::question, label = { Text("Prompt") }, placeholder = { Text("What should this audio explore?") },
                enabled = !busy, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth().testTag("audio.prompt"))
            AudioChoices("Length", listOf("Short", "Medium", "Long"), settings.length, !busy) { controller.audioSettings(settings.copy(length = it)) }
            Text("Targets: ${settings.shortMinutes} / ${settings.mediumMinutes} / ${settings.longMinutes} minutes. Large projects use selected passages; sparse material may produce shorter audio.", style = MaterialTheme.typography.bodySmall)
            AudioChoices("Detail", listOf("Overview", "Balanced", "Detailed"), settings.detail, !busy) { controller.audioSettings(settings.copy(detail = it)) }
            AudioChoices("Style", listOf("Podcast", "Tutor"), settings.style, !busy) { style ->
                controller.audioSettings(settings.copy(style = style, secondVoice = if (style == "Podcast" && settings.firstVoice == settings.secondVoice) speechVoices.first { it.id != settings.firstVoice }.id else settings.secondVoice))
            }
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("audio.advanced")) { Text(if (advanced) "▴ Advanced settings" else "▾ Advanced settings") }
            if (advanced) {
                AudioOption("Language", speechLanguages.map { it.id to it.title }, settings.language, !busy) { controller.audioSettings(settings.copy(language = it)) }
                AudioOption(if (settings.style == "Podcast") "Speaker 1" else "Tutor voice", speechVoices.filter { settings.style != "Podcast" || it.id != settings.secondVoice }.map { it.id to it.title }, settings.firstVoice, !busy) { controller.audioSettings(settings.copy(firstVoice = it)) }
                if (settings.style == "Podcast") AudioOption("Speaker 2", speechVoices.filter { it.id != settings.firstVoice }.map { it.id to it.title }, settings.secondVoice, !busy) { controller.audioSettings(settings.copy(secondVoice = it)) }
                Text("Preset durations in minutes", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AudioMinutes("Short", short, !busy, Modifier.weight(1f)) { short = it; it.toIntOrNull()?.takeIf { value -> value > 0 }?.let { controller.audioSettings(settings.copy(shortMinutes = it)) } }
                    AudioMinutes("Medium", medium, !busy, Modifier.weight(1f)) { medium = it; it.toIntOrNull()?.takeIf { value -> value > 0 }?.let { controller.audioSettings(settings.copy(mediumMinutes = it)) } }
                    AudioMinutes("Long", long, !busy, Modifier.weight(1f)) { long = it; it.toIntOrNull()?.takeIf { value -> value > 0 }?.let { controller.audioSettings(settings.copy(longMinutes = it)) } }
                }
            }
            if (state.library.none { project.id in it.projectIds }) Text("This project has no context. Assign and index items in Assets first.", Modifier.testTag("audio.empty"))
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.stage ?: "Generating audio…", Modifier.testTag("audio.progress"))
                TextButton(onClick = controller::cancelAudio, modifier = Modifier.testTag("audio.cancel")) { Text("Cancel generation") }
            } else Button(onClick = controller::generateAudio, enabled = validDurations && state.stage == null && state.draft.question.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("audio.generate")) { Text("Generate") }
            state.audioError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("audio.error")) }
            speech.error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = controller.speech::dismissError) { Text("Dismiss playback error") } }
            if (!controller.speech.installed || !controller.models.states.collectAsState().value.getValue(state.answerModel).installed) TextButton(onClick = { controller.closeAudio(); controller.screen("settings") }) { Text("Download models in Settings") }
            Text("Saved audio", style = MaterialTheme.typography.titleMedium)
            val saved = state.audio.filter { it.projectId == project.id }
            if (saved.isEmpty()) Text("Generated audio will stay here for later listening.", style = MaterialTheme.typography.bodySmall)
            saved.forEach { audio ->
                Card(Modifier.fillMaxWidth().testTag("audio.saved.${audio.id}")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(audio.title)
                        val seconds = audio.durationSeconds.toInt()
                        Text("${speechLanguages.firstOrNull { it.id == audio.settings.language }?.title ?: audio.settings.language} · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} · ${messageTime(audio.createdAt, kotlin.time.Clock.System.now().toEpochMilliseconds())}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { controller.playAudio(audio.id) }, enabled = state.stage == null, modifier = Modifier.testTag("audio.play.${audio.id}")) { Text(if (speech.answerId == "audio:${audio.id}") "Stop" else "Play") }
                            TextButton(onClick = { controller.shareAudio(audio.id) }, enabled = state.stage == null, modifier = Modifier.testTag("audio.share.${audio.id}")) { Text("Share") }
                        }
                    }
                }
            }
            TextButton(onClick = controller::closeAudio) { Text(if (busy) "Cancel and close" else "Close") }
        }
    }
}

@Composable
private fun AudioChoices(label: String, values: List<String>, selected: String, enabled: Boolean, choose: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { value -> FilterChip(selected == value, { choose(value) }, label = { Text(value) }, enabled = enabled, modifier = Modifier.testTag("audio.$label.$value")) }
    }
}

@Composable
private fun AudioOption(label: String, values: List<Pair<String, String>>, selected: String, enabled: Boolean, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("audio.$label")) { Text("$label: ${values.firstOrNull { it.first == selected }?.second ?: selected}") }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 300.dp)) {
            values.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; choose(id) }, modifier = Modifier.testTag("audio.$label.$id")) }
        }
    }
}

@Composable
private fun AudioMinutes(label: String, value: String, enabled: Boolean, modifier: Modifier, write: (String) -> Unit) {
    OutlinedTextField(value, write, singleLine = true, enabled = enabled, label = { Text(label) }, isError = (value.toIntOrNull() ?: 0) <= 0,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier.testTag("audio.minutes.$label"))
}
