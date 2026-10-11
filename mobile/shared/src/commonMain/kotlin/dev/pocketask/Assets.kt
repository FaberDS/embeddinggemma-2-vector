package dev.pocketask

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    var memberships by remember { mutableStateOf<String?>(null) }
    val busy = state.stage != null || state.picking || state.importing
    val assets = state.library.filter { source ->
        when (filter) {
            "Images" -> source.isImage
            "Documents" -> !source.isImage && !source.isMemory && !source.isWebPage
            "Web pages" -> source.isWebPage
            "Memories" -> source.isMemory
            else -> true
        }
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp).testTag("assets.grid"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = navigationSpace + 12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Your assets", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                    Box {
                        FilledTonalButton(onClick = { adding = true }, enabled = !busy, modifier = Modifier.testTag("assets.add")) {
                            Icon(AddAssetIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add")
                        }
                        DropdownMenu(adding, { adding = false }) {
                            DropdownMenuItem(text = { Text("Add files") }, onClick = { adding = false; controller.pick(false) })
                            DropdownMenuItem(text = { Text("Add images") }, onClick = { adding = false; controller.pick(true) })
                            DropdownMenuItem(text = { Text("Write text") }, onClick = { adding = false; writing = true })
                            DropdownMenuItem(text = { Text("Insert link") }, onClick = { adding = false; controller.insertLink() })
                            DropdownMenuItem(text = { Text("Add memory") }, enabled = controller.speech.available, onClick = { adding = false; controller.startMemory() }, modifier = Modifier.testTag("memory.add"))
                        }
                    }
                }
                Text("${state.library.size} ${if (state.library.size == 1) "asset" else "assets"} · ${state.library.count { it.prepared || it.searchable }} searchable", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Assign items to projects to make them available to chats. Unassigned items stay out of project context.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("All", "Images", "Documents", "Memories", "Web pages")) { category ->
                        FilterChip(filter == category, { filter = category }, label = { Text(category, style = MaterialTheme.typography.labelMedium) }, modifier = Modifier.testTag("assets.filter.$category"))
                    }
                }
                if (state.picking && !state.importing) Text("Choose assets in the picker", style = MaterialTheme.typography.bodySmall)
                IndexingState(controller, state)
            }
        }
        if (assets.isEmpty()) item {
            Text(if (state.library.isEmpty()) "Add an image, document or memory to start your knowledge base." else "No ${filter.lowercase()} yet.",
                Modifier.padding(vertical = 24.dp).testTag("assets.empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(assets, key = { it.id }) { source ->
            AssetCard(source, busy, { controller.open(source.path) }, { removing = source }, { source.sourceUrl?.let { controller.open(it) } },
                state.projects.filter { it.id in source.projectIds }.map { it.name }.ifEmpty { listOf("Unassigned") }, { memberships = source.id })
        }
        state.error?.let { error -> item { ErrorCard(error, controller::dismissError) } }
    }
    removing?.let { source -> ConfirmRemoveSource(source, { controller.removeAttachment(source.id); removing = null }, { removing = null }) }
    if (writing) WriteTextDialog(controller, state) { writing = false }
    memberships?.let { ItemProjects(controller, state, it) { memberships = null } }
}

@Composable
private fun AssetCard(source: Attachment, busy: Boolean, open: () -> Unit, remove: () -> Unit, openUrl: () -> Unit, projects: List<String>, memberships: () -> Unit) {
    var actions by remember { mutableStateOf(false) }
    val kind = when {
        source.isMemory -> "Transcript"
        source.isImage -> "Image"
        source.isWebPage -> "Web page"
        source.type == "application/pdf" -> "PDF"
        source.type == "text/markdown" -> "Markdown"
        source.type == "text/plain" -> "Text"
        else -> "Document"
    }
    Card(onClick = open, modifier = Modifier.fillMaxWidth().testTag("assets.item.${source.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (source.isImage) AsyncImage(source.path, contentDescription = null, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                else Surface(Modifier.size(64.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Box(contentAlignment = Alignment.Center) {
                        if (source.isMemory) MicrophoneIcon(false) else Icon(DocumentIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (source.isMemory) source.name.removeSuffix(".md") else source.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("$kind · ${if (source.prepared) "Indexed" else if (source.searchable) "Text searchable · visual indexing pending" else "Waiting to index"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { actions = true }, modifier = Modifier.size(48.dp).testTag("assets.actions.${source.id}")) {
                        Icon(AssetActionsIcon, contentDescription = "Actions for ${source.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(actions, { actions = false }) {
                        DropdownMenuItem(text = { Text(if (source.isWebPage) "Saved content" else "Open") },
                            leadingIcon = { Icon(DocumentIcon, contentDescription = null) },
                            onClick = { actions = false; open() }, modifier = Modifier.testTag("assets.open.${source.id}"))
                        if (source.isWebPage) DropdownMenuItem(text = { Text("Open original URL") },
                            leadingIcon = { Icon(OpenAssetIcon, contentDescription = null) },
                            onClick = { actions = false; openUrl() }, modifier = Modifier.testTag("assets.url.${source.id}"))
                        DropdownMenuItem(text = { Text("Remove", color = if (busy) LocalContentColor.current else MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(RemoveAssetIcon, contentDescription = null) }, enabled = !busy,
                            onClick = { actions = false; remove() }, modifier = Modifier.testTag("assets.remove.${source.id}"))
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                projects.forEachIndexed { index, name ->
                    AssistChip(onClick = memberships, enabled = !busy, shape = CircleShape,
                        modifier = Modifier.testTag(if (index == 0) "assets.projects.${source.id}" else "assets.projects.${source.id}.$index"),
                        label = { Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        border = null,
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant))
                }
            }
            if (source.isWebPage) {
                source.sourceUrl?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                source.createdAt?.let { Text("Imported ${timingTimestamp(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

private val AssetActionsIcon = ImageVector.Builder("AssetActions", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        listOf(5f, 12f, 19f).forEach { x ->
            moveTo(x - 1.5f, 12f); arcTo(1.5f, 1.5f, 0f, true, true, x + 1.5f, 12f)
            arcTo(1.5f, 1.5f, 0f, true, true, x - 1.5f, 12f); close()
        }
    }
}.build()

private val AddAssetIcon = lineIcon("AddAsset") {
    moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f)
}
private val OpenAssetIcon = lineIcon("OpenAsset") {
    moveTo(14f, 3f); lineTo(21f, 3f); lineTo(21f, 10f); moveTo(21f, 3f); lineTo(10f, 14f)
    moveTo(10f, 3f); lineTo(5f, 3f); quadTo(3f, 3f, 3f, 5f); lineTo(3f, 19f)
    quadTo(3f, 21f, 5f, 21f); lineTo(19f, 21f); quadTo(21f, 21f, 21f, 19f); lineTo(21f, 14f)
}
private val RemoveAssetIcon = lineIcon("RemoveAsset") {
    moveTo(3f, 6f); lineTo(21f, 6f); moveTo(9f, 6f); lineTo(9f, 3f); lineTo(15f, 3f); lineTo(15f, 6f)
    moveTo(5f, 6f); lineTo(6f, 21f); lineTo(18f, 21f); lineTo(19f, 6f)
    moveTo(10f, 10f); lineTo(10f, 17f); moveTo(14f, 10f); lineTo(14f, 17f)
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
            Text("Save as Markdown, then assign it to projects from Assets.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(title, { title = it }, label = { Text("Title (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("chat.noteTitle"))
            OutlinedTextField(text, { text = it }, label = { Text("Text or Markdown") }, minLines = 6, maxLines = 12, modifier = Modifier.fillMaxWidth().testTag("chat.noteText"))
        }
    }, confirmButton = { TextButton(onClick = { keyboard?.hide(); controller.addText(title, text); dismiss() }, enabled = text.isNotBlank() && !busy) { Text("Add to sources") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
