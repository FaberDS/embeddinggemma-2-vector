package dev.pocketask

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectSelector(controller: AppController, state: UiState, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    val selected = state.projects.firstOrNull { it.id == state.draft.projectId }
    var actions by remember(selected?.id) { mutableStateOf(false) }
    Surface(onClick = { expanded = true }, enabled = controller.canEditProjects,
        shape = RoundedCornerShape(18.dp), color = colors.surfaceContainerLow,
        modifier = modifier.testTag("project.select").semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text("Project", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Text(selected?.name ?: "Select project", style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(ProjectChevron, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
            if (selected != null) Box {
                IconButton(onClick = { actions = true }, enabled = controller.canEditProjects, modifier = Modifier.testTag("project.actions")) { Text("⋯", modifier = Modifier.semantics { contentDescription = "Project actions" }) }
                DropdownMenu(actions, { actions = false }) {
                    DropdownMenuItem(text = { Text("Create audio") }, onClick = { actions = false; controller.openAudio(selected.id) }, modifier = Modifier.testTag("project.audio"))
                }
            }
        }
    }
    if (expanded) ModalBottomSheet(onDismissRequest = { expanded = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), containerColor = colors.surface,
        modifier = Modifier.testTag("project.picker")) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your projects", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { expanded = false }, modifier = Modifier.testTag("project.close")) { Text("Close") }
            }
            Text(if (state.projects.isEmpty()) "Create a project to give your chats their own context." else "Choose the context for your chats.",
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (state.projects.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 360.dp).selectableGroup().testTag("project.list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.projects, key = { it.id }) { project ->
                    val active = project.id == state.draft.projectId
                    Surface(color = if (active) colors.primaryContainer else colors.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .selectable(selected = active, enabled = controller.canEditProjects, role = Role.RadioButton,
                            onClick = { controller.selectProject(project.id); expanded = false })
                        .testTag("project.select.${project.id}")) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(project.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                                color = if (active) colors.onPrimaryContainer else colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (active) Text("✓", color = colors.primary, style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.clearAndSetSemantics {})
                        }
                    }
                }
            }
            Button(onClick = { expanded = false; name = ""; creating = true }, enabled = controller.canEditProjects,
                colors = ButtonDefaults.buttonColors(contentColor = colors.surface),
                shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("project.new")) { Text("+  New project") }
        }
    }
    if (creating) AlertDialog(onDismissRequest = { creating = false }, shape = RoundedCornerShape(28.dp),
        containerColor = colors.surface, title = { Text("New project", fontWeight = FontWeight.SemiBold) }, text = {
        OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true,
            shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().testTag("project.name"))
    }, confirmButton = {
        Button(onClick = { controller.createProject(name); creating = false }, enabled = name.isNotBlank() && controller.canEditProjects,
            colors = ButtonDefaults.buttonColors(contentColor = colors.surface),
            modifier = Modifier.testTag("project.create")) { Text("Create") }
    }, dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } })
    if (state.audioProjectId != null) ProjectAudioSheet(controller, state)
}

private val ProjectChevron = ImageVector.Builder("ProjectChevron", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f)
    }
}.build()

@Composable
internal fun ItemProjects(controller: AppController, state: UiState, sourceId: String, dismiss: () -> Unit) {
    val source = state.library.firstOrNull { it.id == sourceId } ?: return
    AlertDialog(onDismissRequest = dismiss, title = { Text("Projects for ${source.name}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose the projects that can use this item. Removing a membership keeps the item.")
            if (state.projects.isEmpty()) Text("Create a project using the project selector above.")
            state.projects.forEach { project ->
                Row(Modifier.fillMaxWidth().testTag("assets.membership.${project.id}").toggleable(
                    value = project.id in source.projectIds, enabled = controller.canEditProjects, role = Role.Checkbox,
                    onValueChange = { controller.setMembership(source.id, project.id, it) }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(project.id in source.projectIds, null, enabled = controller.canEditProjects)
                    Text(project.name)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Done") } })
}
