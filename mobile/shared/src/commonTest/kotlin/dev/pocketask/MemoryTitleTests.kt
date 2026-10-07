package dev.pocketask

import kotlin.test.*

class MemoryTitleTests {
    @Test fun generatedTitlesKeepTheLanguageAndAreSafeForMarkdownFiles() {
        assertEquals("Gartenideen für nächste Woche", memoryTitle("## Gartenideen für nächste Woche [S1]\nAn explanation to omit"))
        assertEquals("Plans for spring", memoryTitle("\"Plans/for\\spring\""))
        assertTrue(memoryTitle("word ".repeat(20)).split(" ").size <= 8)
        assertTrue(memoryTitle("a".repeat(200)).length <= 80)
    }
    @Test fun blankTitlesFallBackWithoutLosingTheTranscript() {
        assertEquals("Recorded memory", memoryTitle(" \n"))
        assertEquals("My backup title", memoryTitle("[S1]", "My backup title"))
    }
}
