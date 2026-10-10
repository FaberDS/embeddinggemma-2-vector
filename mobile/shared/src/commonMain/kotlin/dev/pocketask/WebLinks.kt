package dev.pocketask

import androidx.compose.ui.text.*

private val answerLinks = Regex("\\[([^]\\n]+)]\\((https?://(?:[^\\s()]+|\\([^\\s()]*\\))+)\\)|https?://[^\\s<>]+", RegexOption.IGNORE_CASE)

internal fun linkedAnswer(text: String, styles: TextLinkStyles = TextLinkStyles(), open: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in answerLinks.findAll(text)) {
        append(text.substring(cursor, match.range.first))
        val markdown = match.groupValues[2].isNotEmpty()
        var target = if (markdown) match.groupValues[2] else match.value.trimEnd('.', ',', ';', ':', '!', '?', '"', '\'', ']', '}')
        if (!markdown) while (target.endsWith(')') && target.count { it == ')' } > target.count { it == '(' }) target = target.dropLast(1)
        val valid = runCatching { webUrl(target) }.getOrNull()
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
