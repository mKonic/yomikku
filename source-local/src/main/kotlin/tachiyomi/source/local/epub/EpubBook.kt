package tachiyomi.source.local.epub

import mihon.core.archive.EpubReader
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.io.InputStream
import java.net.URLDecoder

/**
 * An EPUB read as a novel: the reading order (spine) is the chapter list, and the table of contents gives the
 * chapters their names.
 */
class EpubBook(private val reader: EpubReader) {

    private val packageHref: String = reader.getPackageHref()
    private val packageDocument: Document = reader.getPackageDocument(packageHref)
    private val packageDir: String = packageHref.substringBeforeLast('/', "")

    private val manifest: Map<String, ManifestItem> = packageDocument.select("manifest > item")
        .associate { item ->
            item.attr("id") to ManifestItem(
                id = item.attr("id"),
                path = resolve(packageDir, item.attr("href")),
                mediaType = item.attr("media-type"),
                properties = item.attr("properties"),
            )
        }

    val metadata: Metadata by lazy {
        fun text(tag: String) = packageDocument.getElementsByTag(tag).map { it.text().trim() }.filter { it.isNotEmpty() }
        Metadata(
            title = text("dc:title").firstOrNull(),
            authors = text("dc:creator"),
            description = text("dc:description").firstOrNull()?.let { Jsoup.parse(it).text() },
            subjects = text("dc:subject"),
            publisher = text("dc:publisher").firstOrNull(),
        )
    }

    /**
     * The chapters in reading order. Documents the book marks as not part of the reading order (linear="no") are
     * left out, as are ones with no text at all, like a page holding only the cover.
     */
    val chapters: List<Chapter> by lazy {
        val titles = tableOfContents()
        packageDocument.select("spine > itemref")
            .filterNot { it.attr("linear") == "no" }
            .mapNotNull { manifest[it.attr("idref")] }
            .filter { it.mediaType == XHTML || it.mediaType == HTML }
            .mapIndexed { index, item ->
                Chapter(
                    path = item.path,
                    title = titles[item.path] ?: item.path.substringAfterLast('/').substringBeforeLast('.'),
                    index = index,
                )
            }
    }

    /** Path of the cover image inside the book, if it declares one. */
    val coverPath: String? by lazy {
        manifest.values.firstOrNull { "cover-image" in it.properties.split(' ') }?.path
            ?: packageDocument.select("metadata > meta[name=cover]").attr("content")
                .takeIf { it.isNotEmpty() }
                ?.let { manifest[it]?.path }
            ?: manifest.values.firstOrNull { it.mediaType.startsWith("image/") && "cover" in it.id.lowercase() }?.path
    }

    fun open(path: String): InputStream? = reader.getInputStream(path)

    /**
     * The body of the chapter at [path], with every image pointed at a file [extractImage] provides.
     */
    fun readChapter(path: String, extractImage: (entryPath: String) -> String?): String {
        val document = open(path)?.use { Jsoup.parse(it, null, "") } ?: error("Missing $path in the book")
        unwrapImageSvgs(document)
        val dir = path.substringBeforeLast('/', "")
        document.select("img[src], image").forEach { element ->
            val href = if (element.normalName() == "image") element.imageHref() else element.attr("src")
            val entry = resolve(dir, href)
            val file = extractImage(entry)
            if (file != null) {
                element.tagName("img")
                element.removeAttr("xlink:href").removeAttr("href")
                element.attr("src", file)
            } else {
                element.remove()
            }
        }
        return document.body().html()
    }

    /**
     * Chapter titles keyed by document path, from the EPUB 3 navigation document or the EPUB 2 NCX. A document the
     * table of contents lists more than once (sections of one file) takes its first entry.
     */
    private fun tableOfContents(): Map<String, String> {
        val titles = mutableMapOf<String, String>()
        fun add(dir: String, href: String, title: String) {
            val path = resolve(dir, href.substringBefore('#'))
            if (title.isNotBlank() && path !in titles) titles[path] = title.trim()
        }

        manifest.values.firstOrNull { "nav" in it.properties.split(' ') }?.let { nav ->
            val document = open(nav.path)?.use { Jsoup.parse(it, null, "") } ?: return@let
            val dir = nav.path.substringBeforeLast('/', "")
            val toc = document.select("nav").firstOrNull { it.attr("epub:type") == "toc" } ?: document
            toc.select("a[href]").forEach { add(dir, it.attr("href"), it.text()) }
        }
        if (titles.isNotEmpty()) return titles

        val ncxId = packageDocument.selectFirst("spine")?.attr("toc")
        val ncx = manifest[ncxId] ?: manifest.values.firstOrNull { it.mediaType == NCX }
        if (ncx != null) {
            val document = open(ncx.path)?.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
            val dir = ncx.path.substringBeforeLast('/', "")
            document?.select("navPoint")?.forEach { point: Element ->
                val label = point.selectFirst("navLabel > text")?.text().orEmpty()
                val src = point.selectFirst("content")?.attr("src").orEmpty()
                if (src.isNotEmpty()) add(dir, src, label)
            }
        }
        return titles
    }

    data class Chapter(val path: String, val title: String, val index: Int)

    data class Metadata(
        val title: String?,
        val authors: List<String>,
        val description: String?,
        val subjects: List<String>,
        val publisher: String?,
    )

    private data class ManifestItem(val id: String, val path: String, val mediaType: String, val properties: String)

    companion object {
        private const val XHTML = "application/xhtml+xml"
        private const val HTML = "text/html"
        private const val NCX = "application/x-dtbncx+xml"

        /**
         * Replaces each `<svg>` that only wraps raster images (full-page illustrations often come this way) with
         * plain `<image>` elements, taking the svg's title or description as their alt text. The svg's own text would
         * otherwise show up in the chapter. Svgs that draw anything else are left alone.
         */
        internal fun unwrapImageSvgs(document: Document) {
            document.getElementsByTag("svg").toList().forEach { svg ->
                val children = svg.children()
                val images = children.filter { it.normalName() == "image" && it.imageHref().isNotEmpty() }
                if (images.isEmpty() || children.any { it.normalName() !in SVG_WRAPPER_TAGS }) return@forEach
                val description = children.firstOrNull { it.normalName() == "title" || it.normalName() == "desc" }
                    ?.text()?.trim().orEmpty()
                images.forEach { image ->
                    svg.before(
                        Element("image")
                            .attr("xlink:href", image.imageHref())
                            .apply { if (description.isNotEmpty()) attr("alt", description) },
                    )
                }
                svg.remove()
            }
        }

        private fun Element.imageHref() = attr("xlink:href").ifEmpty { attr("href") }

        private val SVG_WRAPPER_TAGS = setOf("image", "title", "desc")

        /**
         * Resolves [href] against [dir], both paths inside the book. Hrefs are URL-encoded and may climb with "..";
         * the result has no leading slash, the way zip entries are named.
         */
        fun resolve(dir: String, href: String): String {
            val decoded = try {
                URLDecoder.decode(href.substringBefore('#'), "UTF-8")
            } catch (_: IllegalArgumentException) {
                href.substringBefore('#')
            }
            if (decoded.startsWith('/')) return decoded.trimStart('/')
            val base = if (dir.isEmpty()) File("/") else File("/", dir)
            return File(base, decoded).normalize().path.replace(File.separatorChar, '/').trimStart('/')
        }
    }
}
