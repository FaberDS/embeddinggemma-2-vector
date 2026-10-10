package dev.pocketask

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun WebImportDialog(controller: AppController, state: UiState, web: WebImport) {
    val clipboard = LocalClipboardManager.current
    val saving = state.importing
    AlertDialog(onDismissRequest = { if (!saving) controller.cancelWeb() }, title = { Text(if (web.page == null) "Insert link" else "Preview web page") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (web.page == null) {
                Text("Load a public page, review its extracted text, then choose whether to save and index it.")
                OutlinedTextField(web.url, controller::webInput, label = { Text("HTTP/HTTPS URL") }, enabled = !web.loading, modifier = Modifier.fillMaxWidth().testTag("web.url"))
                TextButton(onClick = { controller.webInput(clipboard.getText()?.text.orEmpty()) }, enabled = !web.loading, modifier = Modifier.testTag("web.paste")) { Text("Paste from clipboard") }
                if (web.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Loading page…") }
            } else {
                Text(web.page.title, style = MaterialTheme.typography.titleMedium)
                Text(web.page.url, style = MaterialTheme.typography.bodySmall)
                Text("Only the text below will be saved and indexed. Images and scripts are excluded.", style = MaterialTheme.typography.bodySmall)
                Text(web.page.text, Modifier.testTag("web.preview"), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { controller.webInput(web.url) }, enabled = !saving) { Text("Change URL") }
            }
            web.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("web.error")) }
        }
    }, confirmButton = {
        if (web.page == null) TextButton(onClick = controller::previewWeb, enabled = !web.loading && web.url.isNotBlank(), modifier = Modifier.testTag("web.load")) { Text("Preview") }
        else TextButton(onClick = controller::importWeb, enabled = !saving && state.stage == null && !state.picking, modifier = Modifier.testTag("web.import")) { Text(if (saving) "Saving…" else "Add and index") }
    }, dismissButton = { TextButton(onClick = controller::cancelWeb, enabled = !saving, modifier = Modifier.testTag("web.cancel")) { Text("Cancel") } })
}
