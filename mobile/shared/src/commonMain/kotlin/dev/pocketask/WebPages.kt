package dev.pocketask

import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import okio.Buffer

data class WebPage(val title: String, val url: String, val text: String)
data class WebImport(val url: String = "", val loading: Boolean = false, val page: WebPage? = null, val error: String? = null)

internal fun webUrl(value: String): String {
    val text = value.trim()
    require(text.startsWith("http://", true) || text.startsWith("https://", true)) { "Enter an HTTP or HTTPS page URL." }
    val authority = text.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    require(authority.isNotBlank() && !authority.startsWith(':')) { "Enter a page URL with a hostname." }
    require(text.none { it.isWhitespace() || it.isISOControl() } && '\\' !in text) { "The URL contains invalid characters." }
    val url = try { Url(text) } catch (_: Exception) { throw IllegalArgumentException("Enter a valid page URL.") }
    require(url.host.isNotBlank() && url.host.none { it in "<>\"" } && url.user == null && url.password == null) { "Enter a page URL without login credentials." }
    return URLBuilder(url).apply { fragment = "" }.buildString()
}

private val gatePath = Regex("(^|/)(login|log-in|signin|sign-in|auth|oauth|consent|captcha)(/|$)", RegexOption.IGNORE_CASE)
private val gateTitle = Regex("^(sign[ -]?in|log[ -]?in|access denied|forbidden|just a moment|verify you are human|cookie consent|authentication required|403|404|page not found)(\\b|$)", RegexOption.IGNORE_CASE)

internal fun extractWebPage(html: String, sourceUrl: String): WebPage {
    val url = webUrl(sourceUrl)
    require(!gatePath.containsMatchIn(Url(url).encodedPath)) { "This URL leads to a login or access screen. Open the article in your browser instead." }
    val doc = Ksoup.parse(html = html)
    val declaredCharset = doc.selectFirst("meta[charset]")?.attr("charset")?.lowercase()
    require(declaredCharset == null || declaredCharset in listOf("utf-8", "utf8", "us-ascii")) { "This page uses an unsupported text encoding. Try another version of the article." }
    val title = doc.title().trim().ifBlank { doc.selectFirst("h1")?.text().orEmpty() }.ifBlank { Url(url).host }
    require(!gateTitle.containsMatchIn(title)) { "The site returned a login, consent, or access screen instead of the page." }
    require(doc.select("input[type=password], iframe[src*=captcha], form[action*=login], form[action*=signin]").isEmpty()) { "This page requires login or verification. Open it in your browser." }
    doc.select("script, style, noscript, template, svg, nav, header, footer, aside, form, dialog, [hidden], [aria-hidden=true], [role=dialog], [role=navigation]").remove()
    // ponytail: static article/main/body extraction; add a readability scorer if real pages need it.
    val content = doc.selectFirst("article") ?: doc.selectFirst("main, [role=main]") ?: doc.body()
    content.select("p, h1, h2, h3, h4, h5, h6, li, blockquote, pre, div, section, tr, br").forEach { it.before("\n"); it.after("\n") }
    val text = content.wholeText().lines().map { it.trim().replace(Regex("[\\t ]+"), " ") }.filter { it.isNotBlank() }.joinToString("\n\n")
    val beginning = text.take(500).lowercase()
    require(!gateTitle.containsMatchIn(text) && !(text.length < 1500 && listOf("enable javascript", "sign in to continue", "log in to continue", "accept cookies to continue", "verify you are human", "checking your browser", "access denied", "subscribe to continue").any { it in beginning })) { "The site returned an access screen instead of readable page content." }
    require(text.count { it.isLetterOrDigit() } >= 80) { "No readable article was extracted. The site may need JavaScript or login. Try another URL." }
    return WebPage(title.take(200), url, text)
}

/** Fetch without cookies, scripts, subresources or credentials; bound redirects, time and body size. */
internal suspend fun fetchWebPage(value: String, client: HttpClient = HttpClient {
    followRedirects = false
    install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 10_000; socketTimeoutMillis = 10_000 }
}): WebPage {
    try {
        var url = webUrl(value)
        repeat(6) {
            var redirect: String? = null
            var page: WebPage? = null
            client.prepareGet(url) { headers.append(HttpHeaders.Accept, "text/html, application/xhtml+xml") }.execute { response ->
                if (response.status.value in listOf(301, 302, 303, 307, 308)) {
                    val location = response.headers[HttpHeaders.Location] ?: error("The site returned a redirect without a destination.")
                    val destination = webUrl(URLBuilder(url).takeFrom(location).buildString())
                    require(!gatePath.containsMatchIn(Url(destination).encodedPath)) { "The site redirected to a login or consent screen. Open the article in your browser." }
                    require(!(Url(url).protocol == URLProtocol.HTTPS && Url(destination).protocol == URLProtocol.HTTP)) { "The site redirected to an insecure connection. Try its HTTPS article URL." }
                    redirect = destination
                } else {
                    require(response.status.value in 200..299) { "The page is unavailable (HTTP ${response.status.value}). Try another URL or retry later." }
                    val type = response.contentType()?.withoutParameters()
                    require(type == ContentType.Text.Html || type == ContentType("application", "xhtml+xml")) { "This URL is not an HTML web page. Import PDFs or other files using Add files." }
                    val maximum = 2_000_000
                    require((response.contentLength() ?: 0) <= maximum) { "This page is too large to import (2 MB limit)." }
                    val buffer = Buffer()
                    val bytes = ByteArray(8192)
                    val channel = response.bodyAsChannel()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = channel.readAvailable(bytes)
                        if (read < 0) break
                        require(buffer.size + read <= maximum) { "This page is too large to import (2 MB limit)." }
                        buffer.write(bytes, 0, read)
                    }
                    val charset = response.contentType()?.parameter("charset")?.lowercase()
                    require(charset == null || charset in listOf("utf-8", "utf8", "us-ascii")) { "This page uses an unsupported text encoding. Try another version of the article." }
                    page = extractWebPage(buffer.readUtf8(), url)
                }
            }
            page?.let { return it }
            url = redirect ?: error("The page could not be loaded.")
        }
        error("The site redirected too many times. Try the final article URL.")
    } catch (e: CancellationException) { throw e }
    catch (e: IllegalArgumentException) { throw e }
    catch (e: IllegalStateException) { throw e }
    catch (e: Exception) { throw IllegalStateException("Could not load this page. Check your connection, try HTTPS, or retry later.", e) }
    finally { client.close() }
}
