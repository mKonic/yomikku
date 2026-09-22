package eu.kanade.tachiyomi.ui.reader.text

import androidx.compose.ui.text.font.FontWeight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class ChapterParserTest {

    private fun ChapterDocument.paragraphs() = blocks.filterIsInstance<TextBlock.Paragraph>()

    @Test
    fun `paragraphs, headings, rules and images become blocks`() {
        val document = ChapterParser.parse(
            "<h2>Chapter 1</h2><p>First  line<br>second</p><hr><img src='https://x/a.png' alt='pic'><p>End</p>",
        )
        document.blocks.map { it::class } shouldBe listOf(
            TextBlock.Paragraph::class,
            TextBlock.Paragraph::class,
            TextBlock.Paragraph::class,
            TextBlock.Rule::class,
            TextBlock.Image::class,
            TextBlock.Paragraph::class,
        )
        val paragraphs = document.paragraphs()
        paragraphs[0].kind shouldBe TextBlock.Kind.HEADING
        paragraphs.map { it.text.text } shouldBe listOf("Chapter 1", "First line", "second", "End")
        document.blocks[4].shouldBeInstanceOf<TextBlock.Image>().description shouldBe "pic"
    }

    @Test
    fun `a line break inside bold splits the paragraph and keeps the style`() {
        // Used to throw "Nothing to pop": the break started a new paragraph while <b> was still open.
        val document = ChapterParser.parse("<p><b>one<br>two</b> plain</p>")
        val paragraphs = document.paragraphs()
        paragraphs.map { it.text.text } shouldBe listOf("one", "two plain")
        val second = paragraphs[1].text
        val bold = second.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        bold shouldHaveSize 1
        second.text.substring(bold[0].start, bold[0].end) shouldBe "two"
    }

    @Test
    fun `an image inside a link does not break the link`() {
        val document = ChapterParser.parse("<p><a href='https://x/'>see <img src='https://x/a.png'> here</a> end</p>")
        document.blocks.map { it::class } shouldBe listOf(
            TextBlock.Paragraph::class,
            TextBlock.Image::class,
            TextBlock.Paragraph::class,
        )
        document.paragraphs().map { it.text.text } shouldBe listOf("see", "here end")
    }

    @Test
    fun `tooltips and hidden elements are left out`() {
        val document = ChapterParser.parse(
            "<p>attacking<sup><a>1</a></sup><span role='tooltip'>the note</span>!</p>" +
                "<p hidden>gone</p><p style='display: none'>gone</p><p>kept</p>",
        )
        document.paragraphs().map { it.text.text } shouldBe listOf("attacking1!", "kept")
    }

    @Test
    fun `offsets count every block`() {
        val document = ChapterParser.parse("<p>abc</p><hr><p>de</p>")
        document.blocks.map { it.start } shouldBe listOf(0, 4, 5)
        document.length shouldBe 8
    }
}
