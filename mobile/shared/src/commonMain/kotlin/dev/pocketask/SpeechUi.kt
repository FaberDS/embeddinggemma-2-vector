package dev.pocketask

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun MicrophoneIcon(recording: Boolean) {
    val color = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(22.dp)) {
        val w = size.width; val h = size.height; val stroke = Stroke(1.8.dp.toPx())
        if (recording) drawRoundRect(color, Offset(w * .23f, h * .23f), Size(w * .54f, h * .54f), CornerRadius(2.dp.toPx()))
        else {
            drawRoundRect(color, Offset(w * .36f, h * .08f), Size(w * .28f, h * .5f), CornerRadius(w * .14f), style = stroke)
            drawArc(color, 0f, 180f, false, Offset(w * .23f, h * .24f), Size(w * .54f, h * .49f), style = stroke)
            drawLine(color, Offset(w * .5f, h * .73f), Offset(w * .5f, h * .91f), stroke.width)
            drawLine(color, Offset(w * .35f, h * .91f), Offset(w * .65f, h * .91f), stroke.width)
        }
    }
}

@Composable
internal fun SpeechSettings(speech: SpeechController, busy: Boolean) {
    val state by speech.state.collectAsState()
    val models by speech.models.states.collectAsState()
    var choose by remember { mutableStateOf(false) }
    var cellular by remember { mutableStateOf(false) }
    val installed = models.values.all { it.installed }
    val downloading = models.values.any { it.busy }
    val total = speechModelSpecs.sumOf { it.bytes }
    val downloaded = speechModelSpecs.sumOf { spec -> models.getValue(spec.id).let { if (it.installed) spec.bytes else it.downloaded } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Speech", style = MaterialTheme.typography.titleMedium)
        Text("Dictate with the microphone beside Message. Review the text before sending. Dictation requires an on-device recognizer for your phone’s language.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Read answers aloud", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(state.automatic, speech::automatic, enabled = speech.available, modifier = Modifier.testTag("speech.automatic"))
        }
        Box {
            OutlinedButton(onClick = { choose = true }, modifier = Modifier.fillMaxWidth().testTag("speech.voice")) {
                Text("Supertonic 3 · ${speechVoices.first { it.id == state.voice }.title}")
            }
            DropdownMenu(choose, { choose = false }) { speechVoices.forEach { voice ->
                DropdownMenuItem(text = { Text(voice.title) }, onClick = { speech.voice(voice.id); choose = false })
            } }
        }
        if (installed) {
            Text("Supertonic 3 · 10 voices · ready offline", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { if (state.answerId == "preview") speech.stop() else speech.preview() }, enabled = !busy && speech.available) {
                Text(if (state.answerId == "preview") "Stop preview" else "Preview voice")
            }
        } else {
            Text("Download ${formatBytes(total)} once for all ten voices. Answers then play locally.", style = MaterialTheme.typography.bodySmall)
            if (downloading) {
                LinearProgressIndicator(progress = { (downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${formatBytes(downloaded)} / ${formatBytes(total)}", style = MaterialTheme.typography.bodySmall)
                val speed = models.values.filter { it.busy }.mapNotNull { it.bytesPerSecond }.sum()
                Text(if (speed > 0) formatRemaining(((total - downloaded) / speed).toLong()) else "Estimating download time…", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = speech::cancelDownload) { Text("Cancel voice download") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(cellular, { cellular = it }); Text("Allow cellular download", style = MaterialTheme.typography.bodySmall) }
                Button(onClick = { speech.download(cellular) }, enabled = speech.available) { Text("Download Supertonic voices") }
            }
            models.values.firstNotNullOfOrNull { it.error }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        state.stage?.let { Row(verticalAlignment = Alignment.CenterVertically) {
            Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = speech::stop) { Text("Stop") }
        } }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall); TextButton(onClick = speech::dismissError) { Text("Dismiss") } }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
    }
}
