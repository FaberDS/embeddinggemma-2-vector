package dev.pocketask

import kotlin.test.*

private fun vector(index: Int) = List(256) { if (it == index) 1f else 0f }
private fun source(id: String, owner: String, index: Int = 0, image: String? = null) = Evidence(id, owner, "$owner.pdf", 1, "Evidence $id", image, vector(index))

/** Runs on the JVM and Kotlin/Native simulator, without models or a physical phone. */
class RetrievalCoreTests {
    @Test fun citationsRejectInventedIdsAndPreserveSourceIdentity() {
        val sources = listOf(source("one", "one"), source("two", "two"))
        val (answer, cited) = validCitations("Second [S2], first [S1], invented [S9].", sources)
        assertEquals("Second [S2], first [S1], invented .", answer)
        assertEquals(listOf("one", "two"), cited.map { it.id })
    }
    @Test fun savedDescriptionsUseOnlyTheHighestRankedPreviewWithoutVisionInputs() {
        val sources = List(8) { source("image-$it", "image-$it", image = "/private/image-$it.jpg") }
        val result = evidencePackage("What is in these pictures?", sources)
        assertTrue(result.images.isEmpty())
        assertEquals(sources.take(1).map { it.image }, result.sources.map { it.displayImage })
        assertContains(result.prompt, "Evidence image-0")
        assertEquals(sources.take(1).map { it.id }, result.sources.map { it.id })
        assertTrue(result.prompt.contains("[S1] image-0.pdf"))
        assertFalse(result.prompt.contains("[S2] image-1.pdf"))
        assertTrue(evidencePackage("No match", emptyList()).images.isEmpty())
    }
    @Test fun textDescriptionVectorsRetainPreviewLinksAndUncaptionedLegacyImagesAreSkipped() {
        val legacy = source("old-image", "old", image = "/never-opened/old.jpg").copy(text = "")
        val caption = source("caption", "flower").copy(page = null, text = "A yellow flower beside a fence.", previewImage = "/never-opened/flower.jpg")
        val result = evidencePackage("Where is the flower?", listOf(legacy, caption))
        assertTrue(result.images.isEmpty())
        assertEquals(listOf("caption"), result.sources.map { it.id })
        assertNull(result.sources.single().image)
        assertEquals(caption.previewImage, result.sources.single().displayImage)
        assertContains(result.prompt, caption.text)
        assertContains(result.prompt, "Saved AI-generated image description")
        assertFalse(result.prompt.contains("old.pdf"))
    }

    @Test fun chunksPreserveTheEndOfTextAndNormalizationRejectsBadVectors() {
        val text = "0123456789".repeat(400)
        val chunks = textChunks(text)
        assertTrue(chunks.last().endsWith(text.takeLast(200)))
        assertEquals(chunks[0].takeLast(160), chunks[1].take(160))
        assertFailsWith<IllegalArgumentException> { normalize(List(256) { 0f }) }
        assertFailsWith<IllegalArgumentException> { normalize(List(256) { Float.NaN }) }
    }
    @Test fun evidenceBudgetRetainsRankingAndDoesNotReadImageFiles() {
        val sources = listOf(source("large", "a").copy(text = "x".repeat(6001)), source("small", "b").copy(text = "Supported text"), source("image", "c", image = "/missing/on/purpose.jpg"))
        val result = evidencePackage("Question", sources)
        assertEquals(listOf("small", "image"), result.sources.map { it.id })
        assertTrue(result.images.isEmpty())
        assertEquals("/missing/on/purpose.jpg", result.sources.last().previewImage)
        assertTrue(result.prompt.contains("[S1] b.pdf"))
        assertTrue(result.prompt.contains("[S2] c.pdf"))
        assertFalse(result.prompt.contains("x".repeat(100)))
    }
    @Test fun duplicateRetrievedSourcesRetainTheirTextAndOneLinkedImage() {
        val image = source("image", "owner", image = "/same.jpg")
        val sibling = image.copy(id = "sibling", page = 2)
        val result = evidencePackage("Question", listOf(image, image, sibling))
        assertEquals(listOf("image", "sibling"), result.sources.map { it.id })
        assertTrue(result.images.isEmpty())
        assertEquals(listOf("/same.jpg"), result.sources.map { it.displayImage }.distinct())
    }
    @Test fun readablePdfPassagesAvoidLoadingTheirLowerRankedPageImage() {
        val passage = source("passage", "report").copy(text = "A matching paragraph.")
        val page = passage.copy(id = "page-image", text = "A saved chart description.", image = "/unopened/report-page.jpg")
        val photo = source("photo", "picture", image = "/best/photo.jpg").copy(page = null)
        val result = evidencePackage("Find the paragraph", listOf(passage, page, photo))
        assertEquals(listOf(passage.id, photo.id), result.sources.map { it.id })
        assertTrue(result.images.isEmpty())
        assertEquals(photo.image, result.sources.last().displayImage)
        val visual = evidencePackage("Describe the chart", listOf(page, passage))
        assertTrue(visual.images.isEmpty())
        assertContains(visual.prompt, "A saved chart description.")
        assertEquals(listOf(page.id, passage.id), visual.sources.map { it.id })
    }

    @Test fun emptyAndBoundarySizedTextChunksNeverDropContent() {
        assertTrue(textChunks(" \n\t ").isEmpty())
        listOf(1, 1199, 1200, 1201, 2240, 2241, 50_000).forEach { size ->
            val text = "a".repeat(size)
            val chunks = textChunks(text)
            assertTrue(chunks.all { it.length <= 1200 })
            val restored = chunks.first() + chunks.drop(1).joinToString("") { it.drop(160) }
            assertEquals(text, restored)
        }
        assertFailsWith<IllegalArgumentException> { textChunks("text", size = 10, overlap = 10) }
    }
    @Test fun invalidAndOverflowingCitationNumbersAreDiscarded() {
        val (answer, cited) = validCitations("[S0] [S99999999999999999999] [S1]", listOf(source("one", "owner")))
        assertEquals("[S1]", answer)
        assertEquals("one", cited.single().id)
    }
}
