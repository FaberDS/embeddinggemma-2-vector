package dev.pocketask

import kotlin.math.roundToLong

data class KnowledgeStats(val vectors: Long, val textVectors: Long, val imageVectors: Long, val searchableVectors: Long,
    val indexBytes: Long, val rawVectorBytes: Long, val minDimensions: Long, val maxDimensions: Long,
    val databaseBytes: Long, val databaseOnDisk: Boolean, val sample: List<Float>) {
    val shape get() = when {
        vectors == 0L -> "0 × 256"
        minDimensions == maxDimensions -> "$vectors × $minDimensions"
        else -> "$vectors vectors · $minDimensions–$maxDimensions values"
    }
}

internal fun storageSize(bytes: Long): String {
    val unit = when {
        bytes >= 1_000_000_000 -> 1_000_000_000L to "GB"
        bytes >= 1_000_000 -> 1_000_000L to "MB"
        bytes >= 1_000 -> 1_000L to "KB"
        else -> return "$bytes B"
    }
    return "${(bytes.toDouble() / unit.first * 10).roundToLong() / 10.0} ${unit.second}"
}
