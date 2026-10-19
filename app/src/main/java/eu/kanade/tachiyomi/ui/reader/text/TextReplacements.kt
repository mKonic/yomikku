package eu.kanade.tachiyomi.ui.reader.text

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A change the reader makes to the text of every chapter: a name a machine translation keeps getting wrong, or a
 * site's "read this at ..." line, which an empty [replace] removes. A paragraph left empty is dropped.
 */
@Serializable
data class TextReplacement(
    val find: String,
    val replace: String = "",
    /** [find] is a regular expression, and [replace] may refer to its groups as $1. */
    val regex: Boolean = false,
    val enabled: Boolean = true,
)

object TextReplacements {

    private val json = Json { ignoreUnknownKeys = true }

    fun decode(value: String): List<TextReplacement> =
        runCatching { json.decodeFromString<List<TextReplacement>>(value) }.getOrDefault(emptyList())

    fun encode(rules: List<TextReplacement>): String = json.encodeToString(rules)

    /** Why [pattern] can't be used as a regular expression, or null when it can. */
    fun regexError(pattern: String): String? = try {
        Regex(pattern)
        null
    } catch (e: IllegalArgumentException) {
        e.message?.lineSequence()?.firstOrNull() ?: e.toString()
    }

    /**
     * The enabled rules as one function over a paragraph, applied in order; null when there are none. Text between
     * matches keeps its styling. A rule whose pattern doesn't compile is skipped.
     */
    fun compile(rules: List<TextReplacement>): ((AnnotatedString) -> AnnotatedString)? {
        val steps = rules.filter { it.enabled && it.find.isNotEmpty() }.mapNotNull { rule ->
            val pattern = if (rule.regex) {
                runCatching { Regex(rule.find) }.getOrNull() ?: return@mapNotNull null
            } else {
                Regex(Regex.escape(rule.find))
            }
            { text: AnnotatedString -> text.replace(pattern, rule) }
        }
        if (steps.isEmpty()) return null
        return { text -> steps.fold(text) { acc, step -> step(acc) } }
    }

    private fun AnnotatedString.replace(pattern: Regex, rule: TextReplacement): AnnotatedString {
        val matches = pattern.findAll(text).filter { it.value.isNotEmpty() }.toList()
        if (matches.isEmpty()) return this
        val source = this
        return buildAnnotatedString {
            var last = 0
            matches.forEach { match ->
                append(source.subSequence(last, match.range.first))
                append(if (rule.regex) expand(rule.replace, match) else rule.replace)
                last = match.range.last + 1
            }
            append(source.subSequence(last, source.length))
        }
    }

    /** [replacement] with $1, $2... filled from [match]; \$ is a literal dollar sign. */
    private fun expand(replacement: String, match: MatchResult): String = GROUP.replace(replacement) { ref ->
        if (ref.value == "\\$") "$" else match.groupValues.getOrNull(ref.groupValues[1].toInt()).orEmpty()
    }

    private val GROUP = Regex("""\\\$|\$(\d+)""")
}
