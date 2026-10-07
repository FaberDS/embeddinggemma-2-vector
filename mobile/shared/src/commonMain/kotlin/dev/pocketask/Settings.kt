package dev.pocketask

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun Settings(controller: AppController, state: UiState, models: Map<String, ModelState>, modifier: Modifier, navigationSpace: Dp) {
    LaunchedEffect(state.library, state.stage == null, state.history.size) { controller.refreshKnowledge() }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp).testTag("settings.list"),
        verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(top = 12.dp, bottom = navigationSpace + 12.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }
        item { KnowledgeOverview(state, controller::refreshKnowledge) }
        item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Models", style = MaterialTheme.typography.titleMedium); ModelSetup(controller, state, models) } }
        item { SpeechSettings(controller.speech, state.stage != null) }
        item { Text("Everything stays in private app storage. Removing a model keeps your knowledge base and history.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun KnowledgeOverview(state: UiState, refresh: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val stats = state.knowledge
    OutlinedCard(Modifier.fillMaxWidth().testTag("knowledge.overview")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Knowledge index", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = refresh, enabled = !state.knowledgeLoading, modifier = Modifier.testTag("knowledge.refresh")) { Text("Refresh") }
            }
            if (state.knowledgeLoading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Reading index details…", style = MaterialTheme.typography.bodySmall) }
            state.knowledgeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (stats != null) {
                Metric("Stored vectors", stats.vectors.toString(), "knowledge.vectors")
                val passages = if (stats.textVectors == 1L) "text passage" else "text passages"
                val images = if (stats.imageVectors == 1L) "image vector" else "image vectors"
                Text("${stats.textVectors} $passages · ${stats.imageVectors} $images", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Metric("Searchable now", stats.searchableVectors.toString(), "knowledge.searchable")
                Metric("Index payload", storageSize(stats.indexBytes), "knowledge.indexBytes")
                Metric(if (stats.databaseOnDisk) "Database on disk" else "Database allocation", storageSize(stats.databaseBytes), "knowledge.databaseBytes")
                Text("Payload includes saved vectors, passage text and source references. Database size includes SQLite overhead, history and the asset catalog. Original files and models are separate.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("knowledge.structure"), contentPadding = PaddingValues(0.dp)) {
                    Text("Embedding structure ${if (expanded) "↑" else "↓"}")
                }
                if (expanded) {
                    HorizontalDivider()
                    Text(modelSpecs[0].title, style = MaterialTheme.typography.titleSmall)
                    Metric("Stored shape", stats.shape, "knowledge.shape")
                    Text("Current encoder: 256 Float32 values, normalized to length 1. Exact cosine search retrieves up to 6 matching passages or images.", style = MaterialTheme.typography.bodySmall)
                    Text("Text is split into 1,200-character passages with 160 characters of overlap. Photos and rendered PDF pages each get an image vector plus searchable passages from a saved AI description. Answers use those descriptions; originals are linked for viewing. Memory transcripts use the same text passages.", style = MaterialTheme.typography.bodySmall)
                    Metric("Raw Float32 equivalent", storageSize(stats.rawVectorBytes), "knowledge.rawBytes")
                    Text("Vectors are stored as JSON in SQLite, so their disk use is larger than this raw numeric size.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (stats.sample.size == 256) {
                        Text("One saved embedding", style = MaterialTheme.typography.titleSmall)
                        EmbeddingPreview(stats.sample)
                        Text("Each square is one of its 256 values. Green is positive; purple is negative. Stronger color means greater magnitude.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stats.sample.take(6).joinToString(prefix = "[", postfix = ", …]") { ((it * 1000).roundToInt() / 1000.0).toString() }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    } else Text(if (stats.vectors == 0L) "Index an asset to inspect a saved embedding." else "No 256-value sample is saved in this index.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, tag: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun EmbeddingPreview(vector: List<Float>) {
    val positive = MaterialTheme.colorScheme.primary
    val negative = MaterialTheme.colorScheme.secondary
    val base = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(160.dp).testTag("knowledge.embedding").semantics { contentDescription = "256 values from one saved embedding; color indicates sign and magnitude" }) {
        val cell = minOf(size.width, size.height) / 16f
        val left = (size.width - cell * 16) / 2
        val peak = vector.maxOf { abs(it) }.coerceAtLeast(0.0001f)
        vector.forEachIndexed { index, value ->
            val color = androidx.compose.ui.graphics.lerp(base, if (value >= 0) positive else negative, (abs(value) / peak).coerceIn(0f, 1f))
            drawRect(color, Offset(left + index % 16 * cell, index / 16 * cell), Size(cell - 1, cell - 1))
        }
    }
}
