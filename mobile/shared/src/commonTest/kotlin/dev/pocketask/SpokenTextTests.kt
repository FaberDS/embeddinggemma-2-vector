package dev.pocketask

import kotlin.test.Test
import kotlin.test.assertEquals

class SpokenTextTests {
    @Test fun voiceReadsProseWithoutCitationsMarkdownOrCode() {
        assertEquals("A clear answer. \nOpen the guide.\n Code omitted.", spokenText("# **A clear answer.** [S1]\nOpen [the guide](https://example.com).\n```kotlin\nprintln(\"hello\")\n```"))
    }
}
