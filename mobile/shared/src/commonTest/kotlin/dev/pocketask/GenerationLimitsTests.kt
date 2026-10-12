package dev.pocketask

import kotlin.test.*

class GenerationLimitsTests {
    @Test fun presetScriptsHaveRoomForTheirSourcesOnCpuAndGpu() {
        listOf(2 to 1024, 5 to 2560, 15 to 7680).forEach { (minutes, output) ->
            assertEquals(output, GenerationLimits.audioOutputTokens(minutes))
            listOf(4096, 8192).forEach { initial ->
                val context = GenerationLimits.contextTokens(output, initial)
                assertTrue(context >= initial)
                assertTrue(context - output >= GenerationLimits.inputReserveTokens)
                assertTrue(context <= GenerationLimits.maxContextTokens)
            }
        }
        assertEquals(11776, GenerationLimits.contextTokens(7680, 8192))
    }

    @Test fun ordinaryRepliesKeepTheirExistingBudgetAndContext() {
        assertEquals(512, GenerationLimits.defaultOutputTokens)
        listOf(4096, 8192).forEach { initial ->
            assertEquals(initial, GenerationLimits.contextTokens(GenerationLimits.defaultOutputTokens, initial))
        }
    }

    @Test fun invalidBudgetsFailInsteadOfOverflowingOrSilentlyShorteningAudio() {
        listOf(0, -1, Int.MAX_VALUE).forEach { minutes ->
            assertFailsWith<IllegalArgumentException> { GenerationLimits.audioOutputTokens(minutes) }
        }
        assertEquals(28672, GenerationLimits.audioOutputTokens(56))
        assertEquals(32768, GenerationLimits.contextTokens(28672, 4096))
        assertFailsWith<IllegalArgumentException> { GenerationLimits.audioOutputTokens(57) }
        listOf(0, -1, 28673, Int.MAX_VALUE).forEach { output ->
            assertFailsWith<IllegalArgumentException> { GenerationLimits.contextTokens(output, 4096) }
        }
    }
}
