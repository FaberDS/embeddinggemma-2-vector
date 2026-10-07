package dev.pocketask

/** A short transcript-derived title also works when the answer model is not installed. */
internal fun memoryTitle(text: String, fallback: String = "Recorded memory"): String = text
    .lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    .replace(Regex("\\[S\\d+\\]"), "")
    .trim().trim('#', '*', '`', '"', '\'', '“', '”')
    .replace(Regex("[/\\\\\\p{Cntrl}]"), " ").trim()
    .split(Regex("\\s+")).filter { it.isNotBlank() }.take(8).joinToString(" ").take(80)
    .trimEnd('.', '!', '?', ' ').ifBlank { fallback }
