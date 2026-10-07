package dev.pocketask

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
enum class ImportStageStatus { Pending, Active, Completed, Paused, Failed }

@Serializable
data class ImportStage(val assetId: String, val assetName: String, val phase: String, val completed: Int,
    val total: Int, val status: ImportStageStatus) {
    val id get() = "$assetId:$phase"
}

@Serializable
data class ImportReport(val stages: List<ImportStage> = emptyList(), val status: String = "Waiting to index")

/** Follow the actual text-first pipeline; completed work comes from committed checkpoints. */
internal fun importStages(assets: List<Attachment>, checkpoints: Map<String, IndexCheckpoint>,
    activeAsset: String? = null, activePhase: String? = null, failed: Set<String> = emptySet()): List<ImportStage> {
    val stages = assets.flatMap { asset ->
        val saved = checkpoints[asset.id]
        buildList {
            add(ImportStage(asset.id, asset.name, "Indexing text", saved?.text ?: 0, saved?.pages ?: 0, ImportStageStatus.Pending))
            if (asset.needsImageDescriptions) add(ImportStage(asset.id, asset.name, "Indexing recognized text", saved?.ocr ?: 0, saved?.pages ?: 0, ImportStageStatus.Pending))
        }
    } + assets.filter { it.needsImageDescriptions }.flatMap { asset ->
        val saved = checkpoints[asset.id]
        listOf("Indexing page images" to saved?.images, "Describing pages" to saved?.descriptions, "Indexing descriptions" to saved?.captions).map { (phase, count) ->
            ImportStage(asset.id, asset.name, phase, count ?: 0, saved?.pages ?: 0, ImportStageStatus.Pending)
        }
    }
    val failedStages = failed.mapNotNull { id -> stages.firstOrNull { it.assetId == id && (it.total == 0 || it.completed < it.total) }?.id }.toSet()
    return stages.map { stage -> stage.copy(status = when {
        stage.total > 0 && stage.completed >= stage.total -> ImportStageStatus.Completed
        stage.id in failedStages -> ImportStageStatus.Failed
        stage.assetId == activeAsset && stage.phase == activePhase -> ImportStageStatus.Active
        else -> ImportStageStatus.Pending
    }) }
}

internal fun Store.savedImportReport(): ImportReport? = value("import-report")?.let { saved ->
    runCatching { json.decodeFromString<ImportReport>(saved) }.getOrNull()?.let { report ->
        if (report.status != "Indexing" && report.stages.none { it.status == ImportStageStatus.Active }) report
        else report.copy(status = "Paused", stages = report.stages.map {
            if (it.status == ImportStageStatus.Active) it.copy(status = ImportStageStatus.Paused) else it
        })
    }
}

internal fun Store.saveImportReport(report: ImportReport) = put("import-report", value = json.encodeToString(report))
