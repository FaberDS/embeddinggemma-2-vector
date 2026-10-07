package dev.pocketask

internal data class AndroidInferenceProfile(val cpu: Boolean, val contextTokens: Int)

internal fun androidInferenceProfile(totalMemoryBytes: Long, lowRam: Boolean, model: String): AndroidInferenceProfile {
    val limitedMemory = lowRam || totalMemoryBytes <= 6L * 1024 * 1024 * 1024
    // Some S21/FE Adreno drivers fail to compile this model's Vulkan shaders.
    val s21 = model.startsWith("SM-G99", ignoreCase = true)
    return AndroidInferenceProfile(cpu = limitedMemory || s21, contextTokens = if (limitedMemory) 4096 else 8192)
}
