package dev.pocketask

import androidx.compose.ui.text.*

private val answerLinks = Regex("""\[([^]\n]+)]\(\s*(?:<(https?://[^\s<>]+)>|(https?://(?:[^\s()<>]+|\([^\s()]*\))+))(?:\s+(?:"[^"\n]*"|'[^'\n]*'|\([^\n)]*\)))?\s*\)|https?://[^\s<>]+""", RegexOption.IGNORE_CASE)

internal fun linkedAnswer(text: String, styles: TextLinkStyles = TextLinkStyles(), open: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in answerLinks.findAll(text)) {
        append(text.substring(cursor, match.range.first))
        val destination = match.groupValues[2].ifEmpty { match.groupValues[3] }
        val markdown = destination.isNotEmpty()
        var target = if (markdown) destination else match.value.trimEnd('.', ',', ';', ':', '!', '?', '"', '\'', ']', '}')
        if (!markdown) while (target.endsWith(')') && target.count { it == ')' } > target.count { it == '(' }) target = target.dropLast(1)
        // Web imports drop fragments for deduplication; answer links must keep their destination.
        val valid = runCatching { webUrl(target) }.getOrNull()?.let { url ->
            if ('#' in target) "$url#${target.substringAfter('#')}" else url
        }
        if (valid == null) append(match.value)
        else {
            withLink(LinkAnnotation.Url(valid, styles, LinkInteractionListener { open(valid) })) {
                append(if (markdown) match.groupValues[1] else target)
            }
            if (!markdown) append(match.value.substring(target.length))
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}
