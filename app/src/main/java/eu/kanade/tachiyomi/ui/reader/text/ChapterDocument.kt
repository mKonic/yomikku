package eu.kanade.tachiyomi.ui.reader.text

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * A chapter reduced to what the reader draws: a flat list of blocks, each knowing where it starts in the chapter's
 * text. Offsets are what reading progress is measured in, so they only depend on the text, never on how it is laid
 * out.
 */
@Immutable
class ChapterDocument(
    val blocks: List<TextBlock>,
) {
    /** Length of the chapter's text, counting one character for each image and rule. */
    val length: Int = blocks.lastOrNull()?.let { it.start + it.length } ?: 0

    /** Index of the block holding [offset], clamped to the document. */
    fun blockIndexAt(offset: Int): Int {
        if (blocks.isEmpty()) return 0
        var low = 0
        var high = blocks.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (blocks[mid].start <= offset) low = mid else high = mid - 1
        }
        return low
    }

    fun isEmpty() = blocks.isEmpty()

    companion object {
        val EMPTY = ChapterDocument(emptyList())
    }
}

@Immutable
sealed interface TextBlock {
    /** Offset of the block's first character in the chapter. */
    val start: Int

    /** Characters the block takes up in the chapter, plus one for the break after it. */
    val length: Int

    @Immutable
    data class Paragraph(
        val text: AnnotatedString,
        val kind: Kind,
        override val start: Int,
    ) : TextBlock {
        override val length: Int get() = text.length + 1
    }

    @Immutable
    data class Image(
        val url: String,
        val description: String?,
        override val start: Int,
    ) : TextBlock {
        override val length: Int get() = 1
    }

    @Immutable
    data class Rule(override val start: Int) : TextBlock {
        override val length: Int get() = 1
    }

    enum class Kind {
        BODY,
        HEADING,
        SUBHEADING,
        QUOTE,
        PREFORMATTED,
    }
}

/**
 * Turns chapter HTML into a [ChapterDocument]. Only structure and inline emphasis survive: sites' own styling,
 * scripts and layout are dropped, and the reader's settings decide how the text looks.
 */
object ChapterParser {

    fun parse(html: String, baseUrl: String = ""): ChapterDocument {
        val body = Jsoup.parseBodyFragment(html, baseUrl).body()
        return Builder().apply { walkBlock(body) }.build()
    }

    /**
     * Plain text, one paragraph per blank-line separated block (single newlines inside a block are kept as line
     * breaks).
     */
    fun parsePlainText(text: String): ChapterDocument {
        val builder = Builder()
        text.replace("\r\n", "\n")
            .split(Regex("\n\\s*\n"))
            .map { it.trim('\n', ' ', '\t', ' ') }
            .filter { it.isNotBlank() }
            .forEach { builder.addParagraph(AnnotatedString(it), TextBlock.Kind.BODY) }
        return builder.build()
    }

    private class Builder {
        private val blocks = mutableListOf<TextBlock>()
        private var offset = 0

        private var current = AnnotatedString.Builder()
        private var currentKind = TextBlock.Kind.BODY

        // Whether the last character appended was whitespace, so runs collapse across text nodes.
        private var pendingSpace = false

        fun build(): ChapterDocument {
            flush()
            return ChapterDocument(blocks.toList())
        }

        fun addParagraph(text: AnnotatedString, kind: TextBlock.Kind) {
            blocks += TextBlock.Paragraph(text, kind, offset)
            offset += text.length + 1
        }

        private fun flush() {
            val text = current.toAnnotatedString()
            current = AnnotatedString.Builder()
            pendingSpace = false
            val trimmed = text.trimmed()
            if (trimmed.isNotEmpty()) addParagraph(trimmed, currentKind)
            currentKind = TextBlock.Kind.BODY
        }

        fun walkBlock(element: Element) {
            element.childNodes().forEach { node -> walkNode(node) }
        }

        private fun walkNode(node: Node) {
            when (node) {
                is TextNode -> appendText(node.wholeText, preformatted = currentKind == TextBlock.Kind.PREFORMATTED)
                is Element -> walkElement(node)
            }
        }

        private fun walkElement(element: Element) {
            val tag = element.normalName()
            when (tag) {
                in IGNORED -> Unit
                "br" -> flush()
                "hr" -> {
                    flush()
                    blocks += TextBlock.Rule(offset)
                    offset += 1
                }
                "img", "image" -> {
                    val url = element.absUrl("src").ifBlank { element.absUrl("data-src") }
                        .ifBlank { element.absUrl("xlink:href") }
                        .ifBlank { element.attr("src") }
                    if (url.isNotBlank()) {
                        flush()
                        blocks += TextBlock.Image(url, element.attr("alt").ifBlank { null }, offset)
                        offset += 1
                    }
                }
                "h1", "h2" -> block(element, TextBlock.Kind.HEADING)
                "h3", "h4", "h5", "h6" -> block(element, TextBlock.Kind.SUBHEADING)
                "blockquote" -> block(element, TextBlock.Kind.QUOTE)
                "pre" -> block(element, TextBlock.Kind.PREFORMATTED)
                "li" -> {
                    flush()
                    current.append("• ")
                    walkBlock(element)
                    flush()
                }
                in BLOCKS -> {
                    flush()
                    walkBlock(element)
                    flush()
                }
                "rt", "rp" -> Unit // Ruby annotations would be read inline with the text they annotate.
                else -> {
                    val style = inlineStyle(element)
                    val link = if (tag == "a") element.absUrl("href").takeIf { it.startsWith("http") } else null
                    if (link != null) current.pushLink(LinkAnnotation.Url(link))
                    if (style != null) current.pushStyle(style)
                    walkBlock(element)
                    if (style != null) current.pop()
                    if (link != null) current.pop()
                }
            }
        }

        private fun block(element: Element, kind: TextBlock.Kind) {
            flush()
            currentKind = kind
            walkBlock(element)
            flush()
        }

        private fun appendText(raw: String, preformatted: Boolean) {
            if (preformatted) {
                current.append(raw)
                return
            }
            for (char in raw) {
                if (char.isWhitespace() || char == ' ') {
                    pendingSpace = true
                } else {
                    if (pendingSpace && current.length > 0) current.append(' ')
                    pendingSpace = false
                    current.append(char)
                }
            }
        }
    }

    private fun inlineStyle(element: Element): SpanStyle? = when (element.normalName()) {
        "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
        "i", "em", "cite", "dfn", "var" -> SpanStyle(fontStyle = FontStyle.Italic)
        "u", "ins" -> SpanStyle(textDecoration = TextDecoration.Underline)
        "s", "strike", "del" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        "sup" -> SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.75.em)
        "sub" -> SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.75.em)
        "small" -> SpanStyle(fontSize = 0.85.em)
        "big" -> SpanStyle(fontSize = 1.15.em)
        "code", "kbd", "samp", "tt" -> SpanStyle(fontFamily = FontFamily.Monospace)
        "a" -> SpanStyle(textDecoration = TextDecoration.Underline)
        else -> element.styleOrNull()
    }

    /** The few inline CSS properties that carry meaning in novel text; everything else is the site's look. */
    private fun Element.styleOrNull(): SpanStyle? {
        val style = attr("style").lowercase()
        if (style.isBlank()) return null
        val bold = BOLD.containsMatchIn(style)
        val italic = ITALIC.containsMatchIn(style)
        if (!bold && !italic) return null
        return SpanStyle(
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
        )
    }

    private fun AnnotatedString.trimmed(): AnnotatedString {
        var start = 0
        var end = length
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return if (start == 0 && end == length) this else subSequence(start, end)
    }

    private val BOLD = Regex("font-weight\\s*:\\s*(bold|[6-9]00)")
    private val ITALIC = Regex("font-style\\s*:\\s*italic")

    private val IGNORED = setOf(
        "script", "style", "noscript", "iframe", "object", "embed", "form", "button", "input", "select", "textarea",
        "nav", "svg", "head", "template",
    )

    private val BLOCKS = setOf(
        "p", "div", "section", "article", "main", "header", "footer", "aside", "figure", "figcaption", "ul", "ol",
        "dl", "dt", "dd", "table", "thead", "tbody", "tr", "td", "th", "center", "address", "details", "summary",
        "body", "html",
    )
}
