package dev.pocketask

object GenerationLimits {
    const val defaultOutputTokens = 512
    const val inputReserveTokens = 4096
    const val maxContextTokens = 32768

    // shortcut: 512 tokens/minute is an initial multilingual script budget; calibrate against synthesized durations.
    fun audioOutputTokens(minutes: Int): Int {
        require(minutes > 0 && minutes <= (maxContextTokens - inputReserveTokens) / 512) {
            "Choose an audio target from 1 to 56 minutes."
        }
        return minutes * 512
    }

    @Throws(IllegalArgumentException::class)
    fun contextTokens(outputTokens: Int, minimum: Int): Int {
        require(outputTokens in 1..(maxContextTokens - inputReserveTokens)) { "Output token budget exceeds the model context. Use shorter script sections." }
        require(minimum in 4096..maxContextTokens) { "Invalid model context size." }
        return if (outputTokens <= defaultOutputTokens) minimum else maxOf(minimum, outputTokens + inputReserveTokens)
    }
}
