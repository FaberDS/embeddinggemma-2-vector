package dev.pocketask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ImportPanel(report: ImportReport, progress: IndexingProgress?, step: String?, copying: Boolean,
    onPause: () -> Unit, onResume: () -> Unit, canResume: Boolean = true) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { 28.dp.toPx() }
    val active = progress != null || copying
    val listState = rememberLazyListState()
    val groups = remember(report.stages) { report.stages.groupBy { it.assetId }.values.toList() }
    val focusedAsset = report.stages.firstOrNull { it.status == ImportStageStatus.Active || it.status == ImportStageStatus.Paused || it.status == ImportStageStatus.Failed }?.assetId
    LaunchedEffect(expanded, focusedAsset) {
        val group = groups.indexOfFirst { it.first().assetId == focusedAsset }
        if (expanded && group >= 0) listState.scrollToItem(groups.take(group).sumOf { it.size + 1 })
    }
    val current = when {
        copying -> "Saving assets"
        progress != null -> step?.substringBefore(" · ") ?: progress.phase
        else -> report.status
    }
    val estimate = when {
        progress?.remainingSeconds != null && current == progress.phase -> "${formatRemaining(progress.remainingSeconds)} in this step"
        active -> "Estimating this step…"
        report.status == "Completed" -> "Saved to your knowledge base"
        else -> "Completed work is saved"
    }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("import.header")
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .pointerInput(threshold) {
                    var distance = 0f
                    detectVerticalDragGestures(onDragStart = { distance = 0f }, onDragEnd = {
                        if (distance > threshold) expanded = true else if (distance < -threshold) expanded = false
                    }, onVerticalDrag = { change, amount -> change.consume(); distance += amount })
                }
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse import progress" else "Expand import progress") { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (active) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(if (report.status == "Completed") "✓" else "↓", color = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(current, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(estimate, Modifier.testTag("import.estimate"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(if (expanded) "⌃" else "⌄", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.testTag("import.panel")) {
                    HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Import progress", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        if (progress != null) TextButton(onClick = onPause, modifier = Modifier.testTag("import.pause")) { Text("Pause") }
                        else if (!copying && (report.status != "Completed" || report.stages.any { it.status != ImportStageStatus.Completed }))
                            TextButton(onClick = onResume, enabled = canResume, modifier = Modifier.testTag("import.resume")) {
                                Text(if (report.status == "Waiting for answer model") "Set up model" else "Resume")
                            }
                    }
                    if (progress?.background == true) Text("Can continue in the background", Modifier.padding(horizontal = 14.dp), style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp).testTag("import.stages"), state = listState, contentPadding = PaddingValues(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (report.stages.isEmpty()) item { Text("Preparing your assets…", style = MaterialTheme.typography.bodySmall) }
                        groups.forEach { stages ->
                            val asset = stages.first()
                            item(key = "asset:${asset.assetId}") {
                                Text(asset.assetName, Modifier.fillMaxWidth().testTag("import.file.${asset.assetId}"),
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            items(stages, key = { it.id }) { stage -> ImportStageRow(stage) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportStageRow(stage: ImportStage) {
    val color = when (stage.status) {
        ImportStageStatus.Active, ImportStageStatus.Completed -> MaterialTheme.colorScheme.primary
        ImportStageStatus.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().testTag("import.stage.${stage.id}"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (stage.status == ImportStageStatus.Active) CircularProgressIndicator(Modifier.padding(top = 3.dp).size(14.dp), strokeWidth = 2.dp)
        else Text(when (stage.status) {
            ImportStageStatus.Completed -> "✓"
            ImportStageStatus.Paused -> "Ⅱ"
            ImportStageStatus.Failed -> "!"
            else -> "○"
        }, Modifier.width(14.dp), color = color)
        Text(stage.phase, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = color)
        Column(horizontalAlignment = Alignment.End) {
            Text(when (stage.status) {
                ImportStageStatus.Pending -> "Upcoming"
                ImportStageStatus.Active -> "Active"
                ImportStageStatus.Completed -> "Done"
                ImportStageStatus.Paused -> "Paused"
                ImportStageStatus.Failed -> "Retry"
            }, style = MaterialTheme.typography.labelSmall, color = color)
            if (stage.total > 0) Text("${stage.completed}/${stage.total}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ImportHeader(controller: AppController, state: UiState) {
    val saved = state.importReport
    val assetIds = state.library.mapTo(mutableSetOf()) { it.id }
    val visibleStages = saved?.stages.orEmpty().filter { it.assetId in assetIds }
    val reportedIds = visibleStages.mapTo(mutableSetOf()) { it.assetId }
    val pending = state.library.filterNot { it.prepared }
    if (!state.importing && state.indexing == null && visibleStages.isEmpty() && pending.isEmpty()) return
    val newPending = pending.filter { it.id !in reportedIds }
    val report = if (visibleStages.isNotEmpty()) saved!!.copy(stages = visibleStages + importStages(newPending, emptyMap()),
        status = if (newPending.isNotEmpty() && state.indexing == null) "Waiting to index" else saved.status)
        else ImportReport(importStages(pending, emptyMap()))
    ImportPanel(report, state.indexing, state.stage, state.importing, controller::stop, {
        if (report.status != "Waiting for answer model" && controller.models.states.value.getValue("search").installed) controller.indexAttachments(true) else controller.settings()
    }, canResume = state.stage == null && !state.picking && !state.importing)
}
