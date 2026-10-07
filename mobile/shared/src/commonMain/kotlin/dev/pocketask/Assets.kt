package dev.pocketask

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
internal fun Assets(controller: AppController, state: UiState, modifier: Modifier, navigationSpace: Dp) {
    var filter by rememberSaveable { mutableStateOf("All") }
    var adding by remember { mutableStateOf(false) }
    var writing by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Attachment?>(null) }
    val busy = state.stage != null || state.picking || state.importing
    val assets = state.library.filter { source ->
        when (filter) {
            "Images" -> source.isImage
            "Documents" -> !source.isImage && !source.isMemory
            "Memories" -> source.isMemory
            else -> true
        }
    }
    LazyVerticalGrid(GridCells.Adaptive(150.dp), modifier.fillMaxSize().padding(horizontal = 20.dp).testTag("assets.grid"),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = navigationSpace + 12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Your assets", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                    Box {
                        OutlinedButton(onClick = { adding = true }, enabled = !busy, modifier = Modifier.testTag("assets.add")) { Text("+ Add") }
                        DropdownMenu(adding, { adding = false }) {
                            DropdownMenuItem(text = { Text("Add files") }, onClick = { adding = false; controller.pick(false) })
                            DropdownMenuItem(text = { Text("Add images") }, onClick = { adding = false; controller.pick(true) })
                            DropdownMenuItem(text = { Text("Write text") }, onClick = { adding = false; writing = true })
                            DropdownMenuItem(text = { Text("Add memory") }, enabled = controller.speech.available, onClick = { adding = false; controller.startMemory() }, modifier = Modifier.testTag("memory.add"))
                        }
                    }
                }
                Text("${state.library.size} ${if (state.library.size == 1) "asset" else "assets"} · ${state.library.count { it.prepared || it.searchable }} searchable", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Available to every chat.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("All", "Images", "Documents", "Memories")) { category ->
                        FilterChip(filter == category, { filter = category }, label = { Text(category, style = MaterialTheme.typography.labelMedium) }, modifier = Modifier.testTag("assets.filter.$category"))
                    }
                }
                if (state.picking && !state.importing) Text("Choose assets in the picker", style = MaterialTheme.typography.bodySmall)
                IndexingState(controller, state)
            }
        }
        if (assets.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(if (state.library.isEmpty()) "Add an image, document or memory to start your knowledge base." else "No ${filter.lowercase()} yet.",
                Modifier.padding(vertical = 24.dp).testTag("assets.empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(assets, key = { it.id }, span = { if (it.isImage) GridItemSpan(1) else GridItemSpan(maxLineSpan) }) { source ->
            AssetCard(source, busy, { controller.open(source.path) }, { removing = source })
        }
        state.error?.let { error -> item(span = { GridItemSpan(maxLineSpan) }) { ErrorCard(error, controller::dismissError) } }
    }
    removing?.let { source -> ConfirmRemoveSource(source, { controller.removeAttachment(source.id); removing = null }, { removing = null }) }
    if (writing) WriteTextDialog(controller, state) { writing = false }
}

@Composable
private fun AssetCard(source: Attachment, busy: Boolean, open: () -> Unit, remove: () -> Unit) {
    val kind = when {
        source.isMemory -> "Transcript"
        source.isImage -> "Image"
        source.type == "application/pdf" -> "PDF"
        source.type == "text/markdown" -> "Markdown"
        source.type == "text/plain" -> "Text"
        else -> "Document"
    }
    OutlinedCard(onClick = open, modifier = Modifier.fillMaxWidth().testTag("assets.item.${source.id}")) {
        if (source.isImage) AsyncImage(source.path, contentDescription = source.name, modifier = Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Crop)
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!source.isImage) {
                    if (source.isMemory) MicrophoneIcon(false) else Icon(DocumentIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (source.isMemory) source.name.removeSuffix(".md") else source.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("$kind · ${if (source.prepared) "Indexed" else if (source.searchable) "Text searchable · visual indexing pending" else "Waiting to index"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = open, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("assets.open.${source.id}")) { Text("Open") }
                TextButton(onClick = remove, enabled = !busy, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("assets.remove.${source.id}")) { Text("Remove") }
            }
        }
    }
}

private val DocumentIcon = ImageVector.Builder("Document", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 19f); quadTo(19f, 21f, 17f, 21f)
        lineTo(7f, 21f); quadTo(5f, 21f, 5f, 19f); lineTo(5f, 5f); quadTo(5f, 3f, 7f, 3f); lineTo(14f, 3f); close()
        moveTo(14f, 3f); lineTo(14f, 8f); lineTo(19f, 8f)
        moveTo(9f, 12f); lineTo(15f, 12f); moveTo(9f, 16f); lineTo(15f, 16f)
    }
}.build()

@Composable
internal fun WriteTextDialog(controller: AppController, state: UiState, dismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val busy = state.stage != null || state.picking || state.importing
    AlertDialog(onDismissRequest = dismiss, title = { Text("Write a source") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Save as Markdown in your knowledge base. Every chat can search it.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(title, { title = it }, label = { Text("Title (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("chat.noteTitle"))
            OutlinedTextField(text, { text = it }, label = { Text("Text or Markdown") }, minLines = 6, maxLines = 12, modifier = Modifier.fillMaxWidth().testTag("chat.noteText"))
        }
    }, confirmButton = { TextButton(onClick = { keyboard?.hide(); controller.addText(title, text); dismiss() }, enabled = text.isNotBlank() && !busy) { Text("Add to sources") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
