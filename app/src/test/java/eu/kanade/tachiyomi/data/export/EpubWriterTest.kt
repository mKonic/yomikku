package eu.kanade.tachiyomi.data.export

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class EpubWriterTest {

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    private fun book(): Map<String, Pair<ZipEntry, ByteArray>> {
        val image = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
        val out = ByteArrayOutputStream()
        EpubWriter(
            title = "A <Novel> & Co",
            authors = listOf("Someone"),
            description = "About it",
            language = "en",
            identifier = "urn:yomikku:1",
            cover = EpubWriter.Image(png, "image/png"),
        ).write(
            listOf(
                EpubWriter.Chapter("Chapter 1", "<p>One &nbsp; <br> two</p><img src='$image'><img src='https://x/y.png'>"),
                EpubWriter.Chapter("Chapter 2", "<p>Two</p><script>bad()</script>"),
            ),
            out,
        )
        val entries = linkedMapOf<String, Pair<ZipEntry, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = it to zip.readBytes() }
        }
        return entries
    }

    private fun isWellFormedXml(bytes: ByteArray) = runCatching {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }.isSuccess

    @Test
    fun `the mimetype comes first and uncompressed`() {
        val (name, entry) = book().entries.first().let { it.key to it.value }
        name shouldBe "mimetype"
        entry.first.method shouldBe ZipEntry.STORED
        String(entry.second) shouldBe "application/epub+zip"
    }

    @Test
    fun `every xml file in the book is well formed`() {
        val entries = book()
        entries.keys shouldContainAll listOf(
            "META-INF/container.xml",
            "OEBPS/content.opf",
            "OEBPS/nav.xhtml",
            "OEBPS/toc.ncx",
            "OEBPS/text/chapter-1.xhtml",
            "OEBPS/text/chapter-2.xhtml",
        )
        entries.filterKeys { it.endsWith(".xml") || it.endsWith(".xhtml") || it.endsWith(".opf") || it.endsWith(".ncx") }
            .forEach { (name, entry) -> (name to isWellFormedXml(entry.second)) shouldBe (name to true) }
    }

    @Test
    fun `embedded images become files, remote images and scripts are dropped`() {
        val entries = book()
        entries["OEBPS/images/image-1.png"]!!.second.toList() shouldBe png.toList()
        entries["OEBPS/images/cover.png"]!!.second.toList() shouldBe png.toList()
        val chapter1 = String(entries["OEBPS/text/chapter-1.xhtml"]!!.second)
        chapter1 shouldContain "src=\"../images/image-1.png\""
        chapter1 shouldNotContain "https://x/y.png"
        String(entries["OEBPS/text/chapter-2.xhtml"]!!.second) shouldNotContain "bad()"
        val opf = String(entries["OEBPS/content.opf"]!!.second)
        opf shouldContain "href=\"images/image-1.png\""
        opf shouldContain "properties=\"cover-image\""
        opf shouldContain "<dc:title>A &lt;Novel&gt; &amp; Co</dc:title>"
    }
}
