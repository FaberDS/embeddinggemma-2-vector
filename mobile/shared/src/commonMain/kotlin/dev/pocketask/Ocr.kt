package dev.pocketask

/** Keep the embedded PDF text authoritative; OCR fills pages with little readable text. */
object OcrPolicy {
    fun needsRecognition(text: String) = text.count { it.isLetterOrDigit() } < 40

    fun additionalText(embedded: String, recognized: String): String {
        fun key(text: String) = text.lowercase().filter { !it.isWhitespace() }
        val existing = embedded.lineSequence().map(::key).toSet()
        val seen = mutableSetOf<String>()
        return recognized.lineSequence().map(String::trim).filter { line ->
            val normalized = key(line)
            normalized.isNotBlank() && normalized !in existing && seen.add(normalized)
        }.joinToString("\n")
    }
}
