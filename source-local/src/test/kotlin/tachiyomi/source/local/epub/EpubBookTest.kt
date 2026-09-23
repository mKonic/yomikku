package tachiyomi.source.local.epub

import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class EpubBookTest {

    private fun unwrap(body: String) = Jsoup.parse(body).also(EpubBook::unwrapImageSvgs).body()

    @Test
    fun `an svg wrapping an image becomes the image, titled by its description`() {
        val body = unwrap(
            """<svg viewBox="0 0 600 800"><title>Map of the Empire</title><image xlink:href="../images/map.jpg"/></svg>""",
        )
        body.select("svg").size shouldBe 0
        body.selectFirst("image")!!.attr("xlink:href") shouldBe "../images/map.jpg"
        body.selectFirst("image")!!.attr("alt") shouldBe "Map of the Empire"
        body.text() shouldBe ""
    }

    @Test
    fun `a plain href works as well as xlink href`() {
        val body = unwrap("""<svg><image href="cover.png"/></svg>""")
        body.selectFirst("image")!!.attr("xlink:href") shouldBe "cover.png"
    }

    @Test
    fun `an svg that draws something else is left alone`() {
        val body = unwrap("""<svg><image xlink:href="a.png"/><rect width="10" height="10"/></svg>""")
        body.select("svg").size shouldBe 1
    }

    private fun slice(body: String, from: String?, until: String?) =
        Jsoup.parse(body).also { EpubBook.slice(it, from, until) }.body()

    private val book = """
        <p>Title page</p>
        <h2 id="c1">I</h2><p>One.</p>
        <div><a id="c2"></a><h2>II</h2><p>Two.</p></div>
        <h2 id="c3">III</h2><p>Three.</p>
    """

    @Test
    fun `a chapter runs from its anchor to the next one`() {
        slice(book, "c1", "c2").text() shouldBe "I One."
    }

    @Test
    fun `an anchor inside a wrapper keeps the wrapper and what follows the anchor in it`() {
        slice(book, "c2", "c3").text() shouldBe "II Two."
    }

    @Test
    fun `the last chapter runs to the end and the first from the start`() {
        slice(book, "c3", null).text() shouldBe "III Three."
        slice(book, null, "c1").text() shouldBe "Title page"
    }

    @Test
    fun `a missing anchor leaves that end open`() {
        slice(book, "c3", "nowhere").text() shouldBe "III Three."
    }

    @Test
    fun `a self-closing anchor in xhtml does not wrap the text after it in a link`() {
        val document = EpubBook.parseXhtml(
            """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>""" +
                """<a id="c1"/><h2>I</h2><p>It is a truth&#160;universally acknowledged.</p></body></html>""",
        )
        document.selectFirst("a")!!.childNodeSize() shouldBe 0
        document.body().text().replace('\u00a0', ' ') shouldBe "I It is a truth universally acknowledged."
        document.getElementById("c1") shouldBe document.selectFirst("a")
    }
}
