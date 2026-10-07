package dev.pocketask

import kotlin.test.*

class IndexEstimateTests {
    @Test fun estimateUsesOnlyNewWorkInTheCurrentPhase() {
        val estimate = IndexEstimate()
        assertNull(estimate.remaining("Text", 50, 100, 1000))
        assertNull(estimate.remaining("Text", 51, 100, 2000))
        assertEquals(48L, estimate.remaining("Text", 52, 100, 3000))
        assertNull(estimate.remaining("Descriptions", 0, 100, 4000))
        assertEquals(196L, estimate.remaining("Descriptions", 2, 100, 8000))
        assertNull(estimate.remaining("Descriptions", 100, 100, 9000))
    }
}
