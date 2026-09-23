package tachiyomi.source.local.epub

import mihon.core.archive.EpubReader
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import java.io.File
import java.io.InputStream
import java.net.URLDecoder

/**
 * An EPUB read as a novel. The table of contents is the chapter list: each entry runs from where it points to where
 * the next one does, so a file holding several chapters is split and a chapter spread over several files is joined.
 * A book without one falls back to its reading order (spine), a chapter per file.
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

    /** The documents in reading order, leaving out those the book marks as not part of it (linear="no"). */
    private val spine: List<String> by lazy {
        packageDocument.select("spine > itemref")
            .filterNot { it.attr("linear") == "no" }
            .mapNotNull { manifest[it.attr("idref")] }
            .filter { it.mediaType == XHTML || it.mediaType == HTML }
            .map { it.path }
            .distinct()
    }

    /**
     * The chapters in reading order. Documents before the first table of contents entry, like a title page, are
     * chapters of their own when they have any text; ones with none, like a page holding only the cover, are left out.
     */
    val chapters: List<Chapter> by lazy {
        val spineIndex = spine.withIndex().associate { (index, path) -> path to index }
        // Entries in reading order; one pointing back into the book (a second listing of a section) is dropped, and
        // of entries at the same place the last, most specific one names it.
        val entries = mutableListOf<TocEntry>()
        tableOfContents().forEach { entry ->
            val index = spineIndex[entry.path] ?: return@forEach
            val last = entries.lastOrNull()
            val lastIndex = last?.let { spineIndex.getValue(it.path) } ?: -1
            when {
                last != null && last.path == entry.path && last.fragment == entry.fragment -> {
                    entries[entries.lastIndex] = entry
                }
                index > lastIndex || (index == lastIndex && entry.fragment != null) -> entries += entry
            }
        }

        val chapters = mutableListOf<Chapter>()
        val firstIndex = entries.firstOrNull()?.let { spineIndex.getValue(it.path) } ?: spine.size
        spine.take(firstIndex).forEach { path ->
            val document = parse(path) ?: return@forEach
            if (document.body().text().isBlank()) return@forEach
            val title = document.selectFirst("h1, h2, h3")?.text()?.takeIf { it.isNotBlank() }
                ?: document.title().takeIf { it.isNotBlank() }
                ?: path.substringAfterLast('/').substringBeforeLast('.')
            chapters += Chapter(ref = path, title = title.trim(), index = chapters.size, end = null)
        }
        entries.forEachIndexed { i, entry ->
            val next = entries.getOrNull(i + 1)
            chapters += Chapter(
                ref = entry.ref,
                title = entry.title,
                index = chapters.size,
                end = next?.ref,
            )
        }
        chapters
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
     * The text of the chapter starting at [ref] (a document path, with the anchor it starts at if any), up to where
     * the next chapter starts, with every image pointed at a file [extractImage] provides.
     */
    fun readChapter(ref: String, extractImage: (entryPath: String) -> String?): String {
        val chapter = chapters.firstOrNull { it.ref == ref }
        val (startPath, startFragment) = split(ref)
        val (endPath, endFragment) = chapter?.end?.let(::split) ?: (null to null)
        val first = spine.indexOf(startPath)
        val last = when {
            chapter == null || first < 0 -> first
            endPath == null -> spine.lastIndex
            // Up to the end of the document before the next chapter's, or into it up to the next chapter's anchor.
            endFragment == null -> spine.indexOf(endPath) - 1
            else -> spine.indexOf(endPath)
        }
        val paths = if (first < 0) listOf(startPath) else spine.subList(first, maxOf(first, last) + 1)
        return paths.mapIndexed { i, path ->
            val document = parse(path) ?: error("Missing $path in the book")
            slice(
                document,
                from = startFragment.takeIf { i == 0 },
                until = endFragment.takeIf { path == endPath },
            )
            bodyHtml(document, path, extractImage)
        }.joinToString("\n")
    }

    private fun bodyHtml(document: Document, path: String, extractImage: (entryPath: String) -> String?): String {
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

    private fun parse(path: String): Document? = open(path)?.use { Jsoup.parse(it, null, "") }

    /**
     * The table of contents in its own order, flattened, from the EPUB 3 navigation document or else the EPUB 2 NCX.
     */
    private fun tableOfContents(): List<TocEntry> {
        val titles = mutableListOf<TocEntry>()
        fun add(dir: String, href: String, title: String) {
            if (title.isBlank()) return
            val fragment = href.substringAfter('#', "").takeIf { it.isNotEmpty() }
            titles += TocEntry(resolve(dir, href), fragment, title.trim())
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

    /**
     * A chapter: [ref] is the document it starts in, with "#anchor" when it starts partway, and [end] the same for
     * where the next chapter starts, or null when it runs to the end of its document or the book.
     */
    data class Chapter(val ref: String, val title: String, val index: Int, val end: String?)

    private data class TocEntry(val path: String, val fragment: String?, val title: String) {
        val ref get() = if (fragment == null) path else "$path#$fragment"
    }

    data class Metadata(
        val title: String?,
        val authors: List<String>,
        val description: String?,
        val subjects: List<String>,
        val publisher: String?,
    )

    private data class ManifestItem(val id: String, val path: String, val mediaType: String, val properties: String)

    companion object {
        private fun split(ref: String): Pair<String, String?> =
            ref.substringBefore('#') to ref.substringAfter('#', "").takeIf { it.isNotEmpty() }

        /**
         * Cuts [document] down to what lies from the element with id [from] (or the start) up to, not including, the
         * element with id [until] (or the end). The elements holding the two stay, as the text's frame.
         */
        internal fun slice(document: Document, from: String?, until: String?) {
            val body = document.body()
            val start = from?.let { anchor(body, it) }
            val end = until?.let { anchor(body, it) }
            if (start == null && end == null) return
            val nodes = mutableListOf<Node>()
            NodeTraversor.traverse({ node, _ -> nodes += node }, body)
            if (end != null) {
                val at = nodes.indexOf(end)
                nodes.drop(at).forEach { if (it.parent() != null) it.remove() }
            }
            if (start != null) {
                val ancestors = generateSequence<Node>(start) { it.parent() }.toSet()
                nodes.takeWhile { it !== start }
                    .filter { it !in ancestors && it !== body }
                    .forEach { if (it.parent() != null) it.remove() }
            }
        }

        private fun anchor(body: Element, id: String): Element? =
            body.getElementById(id) ?: body.getElementsByAttributeValue("name", id).firstOrNull()

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
