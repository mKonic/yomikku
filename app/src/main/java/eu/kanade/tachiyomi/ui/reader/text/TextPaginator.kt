package eu.kanade.tachiyomi.ui.reader.text

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * One screen of a chapter in paged mode.
 *
 * A paragraph that crosses a page break is laid out once and drawn on both pages, each showing its own lines, so
 * the line breaks and justification are exactly what the whole paragraph would get.
 */
@Immutable
data class TextPage(
    val items: List<PageItem>,
    /** Offset of the first character on the page, for progress. */
    val startOffset: Int,
)

@Immutable
sealed interface PageItem {
    /** Where the item's top sits on the page. */
    val y: Float

    @Immutable
    data class Lines(
        val layout: TextLayoutResult,
        val firstLine: Int,
        val lastLine: Int,
        override val y: Float,
    ) : PageItem {
        val top: Float get() = layout.getLineTop(firstLine)
        val height: Float get() = layout.getLineBottom(lastLine) - top
    }

    @Immutable
    data class Image(val block: TextBlock.Image, override val y: Float) : PageItem

    @Immutable
    data class Rule(override val y: Float) : PageItem
}

object TextPaginator {

    /**
     * Splits [document] into pages of [width] by [height] pixels.
     *
     * @param paragraphSpacing space between blocks, in pixels. Dropped at the top of a page.
     * @param ruleHeight height a horizontal rule takes, in pixels.
     */
    suspend fun paginate(
        document: ChapterDocument,
        style: ReaderTextStyle,
        measurer: TextMeasurer,
        width: Int,
        height: Int,
        paragraphSpacing: Float,
        ruleHeight: Float,
    ): List<TextPage> {
        if (width <= 0 || height <= 0) return emptyList()

        val pages = mutableListOf<TextPage>()
        var items = mutableListOf<PageItem>()
        var pageStart = 0
        var y = 0f

        fun finishPage(nextStart: Int) {
            if (items.isNotEmpty()) pages += TextPage(items, pageStart)
            items = mutableListOf()
            pageStart = nextStart
            y = 0f
        }

        for (block in document.blocks) {
            coroutineContext.ensureActive()
            when (block) {
                is TextBlock.Paragraph -> {
                    val layout = measurer.measure(
                        text = block.text,
                        style = style.forKind(block.kind),
                        constraints = Constraints.fixedWidth(width),
                    )
                    if (items.isNotEmpty()) y += paragraphSpacing
                    var line = 0
                    while (line < layout.lineCount) {
                        // Fit as many lines as the rest of the page has room for.
                        val sliceTop = layout.getLineTop(line)
                        var last = line
                        while (last + 1 < layout.lineCount && y + layout.getLineBottom(last + 1) - sliceTop <= height) {
                            last++
                        }
                        val fits = y + layout.getLineBottom(last) - sliceTop <= height
                        if (!fits && items.isNotEmpty()) {
                            // Not even one line fits under what is already on the page: start a new one.
                            finishPage(block.start + layout.getLineStart(line))
                            continue
                        }
                        items += PageItem.Lines(layout, line, last, y)
                        y += layout.getLineBottom(last) - sliceTop
                        line = last + 1
                        if (line < layout.lineCount) finishPage(block.start + layout.getLineStart(line))
                    }
                }
                is TextBlock.Image -> {
                    if (!style.showImages) continue
                    // An image gets a page of its own, scaled to fit it.
                    finishPage(block.start)
                    items += PageItem.Image(block, 0f)
                    finishPage(block.start + block.length)
                }
                is TextBlock.Rule -> {
                    if (items.isNotEmpty()) y += paragraphSpacing
                    if (y + ruleHeight > height) finishPage(block.start)
                    items += PageItem.Rule(y)
                    y += ruleHeight
                }
            }
        }
        finishPage(document.length)
        return pages
    }
}
