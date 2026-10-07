package dev.pocketask

import kotlin.test.*

class OcrTests {
    @Test fun missingAndSparsePdfTextUseOcrWhileReadableTextAvoidsRendering() {
        assertTrue(OcrPolicy.needsRecognition(" \n"))
        assertTrue(OcrPolicy.needsRecognition("Page 17"))
        assertTrue(OcrPolicy.needsRecognition("!".repeat(500)))
        assertFalse(OcrPolicy.needsRecognition("A readable PDF passage containing an invoice number and its total amount."))
        assertFalse(OcrPolicy.needsRecognition("漢".repeat(40)))
    }
    @Test fun recognizedTextKeepsIdentifiersAndRemovesDuplicateEmbeddedLines() {
        assertEquals("INV-42\nTotal: €125.70", OcrPolicy.additionalText("Invoice\nCompany name", " INVOICE \nINV-42\nTotal: €125.70\nINV-42\ncompany  name"))
        assertEquals("", OcrPolicy.additionalText("", " \n\t"))
        assertEquals("INV-42", OcrPolicy.additionalText("INV-420", "INV-42"))
    }
    @Test fun ocrContextIsLabelledAsRecognitionAndNeverLoadsPixels() {
        val source = Evidence("receipt-ocr", "receipt", "Receipt.jpg", null, "INV-42 Total €125.70", null,
            List(256) { if (it == 0) 1f else 0f }, "/missing/receipt.jpg", "ocr")
        val result = evidencePackage("What is the amount?", listOf(source))
        assertContains(result.prompt, "On-device OCR transcription")
        assertFalse(result.prompt.contains("AI-generated image description"))
        assertContains(result.prompt, source.text)
        assertTrue(result.images.isEmpty()); assertEquals(source.previewImage, result.sources.single().displayImage)
    }
    @Test fun exactIdentifiersAreDistinctFromSimilarPrefixesAndMatchPunctuation() {
        val match = KeywordMatch("Find invoice INV-42")
        assertTrue(match.score("Number: inv-42. Total €125.") > 1f)
        assertEquals(0f, match.score("Number: INV-420"))
        assertEquals(0f, match.score("Number: AINV-42"))
        assertEquals(0f, match.score("Number: INV-42-A"))
        assertTrue(KeywordMatch("Order 1234").score("Order: 1234") > 1f)
        assertEquals(0f, KeywordMatch("1234").score("12345"))
    }
    @Test fun quotedPhrasesAndUnicodeWordsComplementSemanticSearch() {
        assertTrue(KeywordMatch("Find \"Project Orchid\"").score("Project Orchid status") > 2f)
        assertTrue(KeywordMatch("für München").score("Rechnung aus München") > 0f)
        assertEquals(0f, KeywordMatch("What is the and for?").score("What is the and for?"))
        assertEquals(0f, KeywordMatch("").score("Any passage"))
    }
    @Test fun oldSerializedIndexesRemainReadable() {
        val checkpoint = json.decodeFromString<IndexCheckpoint>("{\"pages\":1,\"text\":1,\"images\":1,\"descriptions\":1,\"captions\":1}")
        assertEquals(0, checkpoint.ocr)
        assertNull(json.decodeFromString<PageInput>("{\"text\":\"old\",\"imagePath\":null}").ocr)
    }
}
