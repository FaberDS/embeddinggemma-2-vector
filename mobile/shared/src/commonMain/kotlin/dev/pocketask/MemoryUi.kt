package dev.pocketask

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun MemoryDialog(controller: AppController, memory: MemoryDraft, state: UiState) {
    val speech by controller.speech.state.collectAsState()
    val recording = memory.status == "Recording"
    val saving = memory.status == "Saving"
    val saved = memory.status == "Saved"
    val indexed = state.library.firstOrNull { it.id == memory.sourceId }?.prepared == true
    AlertDialog(onDismissRequest = controller::closeMemory, modifier = Modifier.testTag("memory.dialog"),
        title = { Text(if (saved) "Memory saved" else "Add memory") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (recording) Row(verticalAlignment = Alignment.CenterVertically) {
                    MicrophoneIcon(true)
                    Text(speech.stage ?: "Listening…", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
                if (saving) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.stage ?: "Preparing memory…") }
                if (saved) {
                    Text(memory.title, style = MaterialTheme.typography.titleMedium)
                    Text(if (indexed) "Indexed · available in every chat" else "Saved in your knowledge base · waiting to index", style = MaterialTheme.typography.bodySmall)
                    if (!indexed) state.stage?.let { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(memory.transcript, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("Speak a thought to remember. Stop to save it with an automatic title in your knowledge base.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(memory.transcript, controller::memoryText, modifier = Modifier.fillMaxWidth().testTag("memory.transcript"),
                        label = { Text("Transcript") }, minLines = 4, maxLines = 8, readOnly = recording || saving)
                    if (!recording && !saving) TextButton(onClick = controller::startMemory, enabled = state.stage == null) { Text("Continue recording") }
                }
                memory.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when { saved -> controller.closeMemory(); recording -> controller.finishMemory(); else -> controller.saveMemory() }
            }, enabled = !saving && (recording || saved || (memory.transcript.isNotBlank() && state.stage == null)), modifier = Modifier.testTag("memory.save")) {
                Text(when { saved -> "Done"; saving -> "Saving…"; recording -> "Stop and save"; else -> "Save memory" })
            }
        },
        dismissButton = {
            if (!saved) TextButton(onClick = { if (saving) controller.stop() else controller.closeMemory() }, modifier = Modifier.testTag("memory.cancel")) {
                Text(if (saving) "Pause" else "Cancel")
            }
        })
}
