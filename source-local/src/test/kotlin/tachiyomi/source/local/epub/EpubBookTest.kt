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
}
