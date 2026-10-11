package dev.pocketask

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ProjectSelector(controller: AppController, state: UiState, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, enabled = controller.canEditProjects,
            modifier = Modifier.fillMaxWidth().testTag("project.select")) {
            Text(state.projects.firstOrNull { it.id == state.draft.projectId }?.name ?: "Select project", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded, { expanded = false }) {
            state.projects.forEach { project ->
                DropdownMenuItem(text = { Text(project.name) }, onClick = { controller.selectProject(project.id); expanded = false },
                    modifier = Modifier.testTag("project.select.${project.id}"))
            }
            DropdownMenuItem(text = { Text("New project") }, onClick = { expanded = false; name = ""; creating = true }, modifier = Modifier.testTag("project.new"))
        }
    }
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text("New project") }, text = {
        OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true, modifier = Modifier.testTag("project.name"))
    }, confirmButton = {
        TextButton(onClick = { controller.createProject(name); creating = false }, enabled = name.isNotBlank() && controller.canEditProjects,
            modifier = Modifier.testTag("project.create")) { Text("Create") }
    }, dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } })
}

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
