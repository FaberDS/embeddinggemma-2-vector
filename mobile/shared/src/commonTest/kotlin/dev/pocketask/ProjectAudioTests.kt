package dev.pocketask

import kotlin.test.*

class ProjectAudioTests {
    @Test fun scriptsKeepSpeakerAssignmentsAndRequireEligibleSources() {
        val settings = AudioSettings(firstVoice = "M4", secondVoice = "F3")
        val script = """{"turns":[{"speaker":1,"text":"**First idea.**","sourceIds":[1],"voice":"invalid"},{"speaker":2,"text":"Second idea.","sourceIds":[2]}]}"""
        val turns = audioSection("```json\n$script\n```", settings, 2).turns
        assertEquals(listOf("M4", "F3"), turns.map { it.voice })
        assertEquals("First idea.", turns.first().text)
        assertFailsWith<IllegalArgumentException> { audioSection(script, settings, 1) }
        assertFailsWith<IllegalArgumentException> { audioSection(script, settings.copy(style = "Tutor"), 2) }
        listOf("""{"turns":[]}""", """{"turns":[{"speaker":1,"text":"Idea","sourceIds":[]}]}""",
            """{"turns":[{"speaker":1,"text":"Idea","sourceIds":[1]}]}""").forEach {
            assertFailsWith<IllegalArgumentException> { audioSection(it, settings, 2) }
        }
        assertFailsWith<AudioScriptFormatException> { audioSection(script.dropLast(8), settings, 2) }
        assertFailsWith<IllegalArgumentException> { audioSection("""{"turns":[{"speaker":1,"text":"${"x".repeat(2001)}","sourceIds":[1]}]}""", settings.copy(style = "Tutor"), 2) }
    }
    @Test fun independentSettingsUseTheCompleteExistingCatalog() {
        assertEquals(31, speechLanguages.size)
        assertEquals(31, speechLanguages.map { it.id }.toSet().size)
        speechLanguages.forEach { language -> AudioSettings(language = language.id).validate() }
        speechVoices.forEach { voice -> AudioSettings(style = "Tutor", firstVoice = voice.id).validate() }
        val settings = AudioSettings(length = "Long", detail = "Overview", language = "de", longMinutes = 12)
        assertContains(audioInstructions(settings), "12 minutes")
        assertContains(audioInstructions(settings), "German")
        assertContains(audioInstructions(settings), "Overview")
        assertEquals(settings.minutes, settings.copy(detail = "Detailed", style = "Tutor").minutes)
        assertFailsWith<IllegalArgumentException> { settings.copy(firstVoice = "../F1").validate() }
        assertFailsWith<IllegalArgumentException> { settings.copy(language = "na").validate() }
        assertFailsWith<IllegalArgumentException> { settings.copy(shortMinutes = 0).validate() }
        assertFailsWith<IllegalArgumentException> { settings.copy(secondVoice = settings.firstVoice).validate() }
    }
    @Test fun schemasBoundTheScriptAndKeepSourceAndSpeakerConstraints() {
        val tutor = audioSchema(AudioSettings(style = "Tutor"), 3)
        val schema = kotlinx.serialization.json.Json.parseToJsonElement(tutor).toString()
        assertContains(schema, "\"enum\":[1]")
        assertContains(schema, "\"maximum\":3")
        assertContains(schema, "\"maxItems\":15")
        assertContains(schema, "\"maxLength\":1000")
        assertContains(audioSchema(AudioSettings(), 2), "\"enum\":[1,2]")
        assertContains(audioInstructions(AudioSettings(), compact = true), "325 spoken words")
        assertContains(audioInstructions(AudioSettings()), "close the JSON within 2560 output tokens")
    }
}
