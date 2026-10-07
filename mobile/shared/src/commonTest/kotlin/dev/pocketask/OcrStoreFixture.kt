package dev.pocketask

import kotlin.test.*

internal fun seedOcr(store: Store, root: String) {
    val image = Attachment("ocr-photo", "Receipt.jpg", "$root/inputs/ocr-photo/original.jpg", "image/jpeg")
    val vector = List(256) { if (it == 1) 1f else 0f }
    val source = Evidence("ocr-photo-0-ocr0", image.id, image.name, null, "Invoice INV-42 total EUR 125.70", null,
        vector, image.path, "ocr")
    store.saveSource(image); store.beginIndex(image, 1)
    store.savePageInput(image, 0, PageInput("", image.path, source.text))
    store.commitPage(image, IndexCheckpoint(1, text = 1, ocr = 1), listOf(source))
}

internal suspend fun verifyOcr(store: Store, root: String) {
    val image = store.library(root).single()
    assertFalse(image.prepared); assertTrue(image.searchable)
    assertEquals(1, store.checkpoint(image)!!.ocr)
    assertContains(store.pageInput(image, 0)!!.ocr!!, "INV-42")
    val query = List(256) { if (it == 0) 1f else 0f } // Orthogonal to the saved vector.
    val result = retrieve(store, setOf(image.id), query, queryText = "Find INV-42")
    assertEquals("ocr", result.single().kind)
    assertEquals("$root/inputs/ocr-photo/original.jpg", result.single().displayImage)
    assertContains(evidencePackage("Find INV-42", result).prompt, "On-device OCR transcription")
    assertEquals(1L, store.knowledgeStats().searchableVectors)
    store.removeSource(image)
    assertNull(store.pageInput(image, 0)); assertNull(store.checkpoint(image))
    assertTrue(retrieve(store, setOf(image.id), query, queryText = "INV-42").isEmpty())
}
