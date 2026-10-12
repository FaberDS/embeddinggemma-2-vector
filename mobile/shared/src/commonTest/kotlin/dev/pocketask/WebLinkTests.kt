package dev.pocketask

import androidx.compose.ui.text.LinkAnnotation
import kotlin.test.*

class WebLinkTests {
    @Test fun markdownDestinationsAndTitlesKeepLabelsAndSectionAnchors() {
        val opened = mutableListOf<String>()
        val text = linkedAnswer("[Docs](<https://example.com/guide#setup>) and [Garden](https://example.org/a_(b)#flowers \"Planting guide\") and [HTTP](http://example.net 'Reference')", open = opened::add)
        assertEquals("Docs and Garden and HTTP", text.text)
        val links = text.getLinkAnnotations(0, text.length).map { it.item as LinkAnnotation.Url }
        assertEquals(listOf("https://example.com/guide#setup", "https://example.org/a_(b)#flowers", "http://example.net"), links.map { it.url })
        links.forEach { it.linkInteractionListener!!.onClick(it) }
        assertEquals(links.map { it.url }, opened)
    }

    @Test fun plainUrlsKeepAnchorsAndBalancedParenthesesWithoutTrailingPunctuation() {
        val original = "See (HTTPS://example.com/a_(b)#details), then http://example.org/help! Evidence [S1]."
        val text = linkedAnswer(original, open = {})
        assertEquals(original, text.text)
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(listOf("https://example.com/a_(b)#details", "http://example.org/help"), links.map { (it.item as LinkAnnotation.Url).url })
        assertEquals(listOf("HTTPS://example.com/a_(b)#details", "http://example.org/help"), links.map { text.text.substring(it.start, it.end) })
    }

    @Test fun localReferencesAndInvalidDestinationsStayUntouched() {
        val original = "[S1] [Report](/documents/report.pdf) [File](file:///documents/report.pdf) [unsafe](javascript:alert(1)) [login](https://user:pass@example.com) https:// https://example.com\\login"
        val text = linkedAnswer(original, open = { fail("Invalid or local destinations must not open as web links") })
        assertEquals(original, text.text)
        assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
    }
}
