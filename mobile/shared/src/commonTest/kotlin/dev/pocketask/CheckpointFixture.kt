package dev.pocketask

import kotlin.test.*

internal fun seedCheckpoint(store: Store, root: String): Attachment {
    val pdf = Attachment("checkpoint-pdf", "Long PDF.pdf", "$root/inputs/checkpoint-pdf/original.pdf", "application/pdf")
    store.saveSource(pdf); store.beginIndex(pdf, 100)
    store.savePageInput(pdf, 0, PageInput("Completed passage", "$root/inputs/checkpoint-pdf/page-0.jpg"))
    val vector = List(256) { if (it == 0) 1f else 0f }
    val text = Evidence("checkpoint-pdf-0-t0", pdf.id, pdf.name, 1, "Completed passage", null, vector)
    store.commitPage(pdf, IndexCheckpoint(100, text = 42, images = 6, descriptions = 2), listOf(text))
    // Unfinished image vectors must not displace the searchable text in the top six.
    repeat(6) { page -> store.addEvidence(text.copy(id = "checkpoint-pdf-$page-image", text = "", image = "$root/inputs/checkpoint-pdf/page-$page.jpg")) }
    return pdf
}

internal suspend fun verifyCheckpoint(store: Store, root: String) {
    val pdf = store.library(root).single()
    assertFalse(pdf.prepared); assertTrue(pdf.searchable)
    assertEquals(IndexCheckpoint(100, text = 42, images = 6, descriptions = 2), store.checkpoint(pdf))
    assertEquals("$root/inputs/checkpoint-pdf/page-0.jpg", store.pageInput(pdf, 0)!!.imagePath)
    assertEquals(listOf("Completed passage"), retrieve(store, setOf(pdf.id), List(256) { if (it == 0) 1f else 0f }).map { it.text })
    assertEquals(1L, store.knowledgeStats().searchableVectors)
    store.removeSource(pdf)
    assertNull(store.checkpoint(pdf)); assertNull(store.pageInput(pdf, 0)); assertFalse(store.searchable(pdf))
    assertTrue(store.evidence(pdf.id, 0).isEmpty())
}
