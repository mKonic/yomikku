package eu.kanade.tachiyomi.data.export

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a novel as an EPUB 3 book (with an EPUB 2 table of contents for older readers). Chapters are HTML as the
 * app stores them; images embedded as data URIs become files in the book, remote ones are left out as a reader
 * offline could not show them.
 *
 * Modelled on the exporter of LNReader's nitro-epub module.
 */
class EpubWriter(
    private val title: String,
    private val authors: List<String>,
    private val description: String?,
    private val language: String,
    private val identifier: String,
    private val cover: Image? = null,
) {

    class Chapter(val title: String, val html: String)

    class Image(val bytes: ByteArray, val mediaType: String)

    private class Resource(val path: String, val mediaType: String, val id: String, val properties: String? = null)

    fun write(chapters: List<Chapter>, out: OutputStream, onChapter: (done: Int) -> Unit = {}) {
        ZipOutputStream(out).use { zip ->
            // The mimetype must be the first entry, stored uncompressed, so readers can identify the file.
            zip.putStored("mimetype", "application/epub+zip".toByteArray())
            zip.putText("META-INF/container.xml", CONTAINER)

            val resources = mutableListOf<Resource>()
            cover?.let {
                val path = "images/cover.${extension(it.mediaType)}"
                zip.putBytes("OEBPS/$path", it.bytes)
                resources += Resource(path, it.mediaType, "cover-image", "cover-image")
            }

            var imageCount = 0
            val chapterPaths = chapters.mapIndexed { index, chapter ->
                val path = "text/chapter-${index + 1}.xhtml"
                val document = Jsoup.parseBodyFragment(chapter.html)
                document.select("script, style, iframe, form, input, button").remove()
                document.select("img").forEach { image ->
                    val source = image.attr("src")
                    val data = DATA_URI.matchEntire(source)
                    if (data == null) {
                        image.remove()
                        return@forEach
                    }
                    val mediaType = data.groupValues[1]
                    val imagePath = "images/image-${++imageCount}.${extension(mediaType)}"
                    zip.putBytes("OEBPS/$imagePath", Base64.getMimeDecoder().decode(data.groupValues[2]))
                    resources += Resource(imagePath, mediaType, "image-$imageCount")
                    image.attr("src", "../$imagePath")
                    image.removeAttr("srcset")
                    if (!image.hasAttr("alt")) image.attr("alt", "")
                }
                zip.putText("OEBPS/$path", chapterXhtml(chapter.title, document))
                onChapter(index + 1)
                path
            }

            zip.putText("OEBPS/style.css", STYLE)
            zip.putText("OEBPS/nav.xhtml", navigation(chapters, chapterPaths))
            zip.putText("OEBPS/toc.ncx", ncx(chapters, chapterPaths))
            zip.putText("OEBPS/content.opf", packageDocument(chapterPaths, resources))
        }
    }

    private fun chapterXhtml(chapterTitle: String, body: Document): String {
        body.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .prettyPrint(false)
        val heading = if (body.body().text().trimStart().startsWith(chapterTitle.trim(), ignoreCase = true)) "" else "<h2>${escape(chapterTitle)}</h2>"
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE html>
            |<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="$language">
            |<head><title>${escape(chapterTitle)}</title><link rel="stylesheet" type="text/css" href="../style.css"/></head>
            |<body>$heading${body.body().html()}</body>
            |</html>
        """.trimMargin()
    }

    private fun navigation(chapters: List<Chapter>, paths: List<String>) = """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<!DOCTYPE html>
        |<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="$language">
        |<head><title>${escape(title)}</title></head>
        |<body><nav epub:type="toc" id="toc"><h1>${escape(title)}</h1><ol>
        |${chapters.indices.joinToString("\n") { "<li><a href=\"${paths[it]}\">${escape(chapters[it].title)}</a></li>" }}
        |</ol></nav></body>
        |</html>
    """.trimMargin()

    private fun ncx(chapters: List<Chapter>, paths: List<String>): String {
        val points = chapters.indices.joinToString("\n") { i ->
            "<navPoint id=\"nav-${i + 1}\" playOrder=\"${i + 1}\"><navLabel><text>${escape(chapters[i].title)}" +
                "</text></navLabel><content src=\"${paths[i]}\"/></navPoint>"
        }
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
            |<head><meta name="dtb:uid" content="${escape(identifier)}"/></head>
            |<docTitle><text>${escape(title)}</text></docTitle>
            |<navMap>
            |$points
            |</navMap>
            |</ncx>
        """.trimMargin()
    }

    private fun packageDocument(chapterPaths: List<String>, resources: List<Resource>): String {
        val modified = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        val manifest = buildList {
            add("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
            add("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>")
            add("<item id=\"style\" href=\"style.css\" media-type=\"text/css\"/>")
            chapterPaths.forEachIndexed { index, path ->
                add("<item id=\"chapter-${index + 1}\" href=\"$path\" media-type=\"application/xhtml+xml\"/>")
            }
            resources.forEach { resource ->
                val properties = resource.properties?.let { " properties=\"$it\"" }.orEmpty()
                add("<item id=\"${resource.id}\" href=\"${resource.path}\" media-type=\"${resource.mediaType}\"$properties/>")
            }
        }
        val metadata = buildList {
            add("<dc:identifier id=\"book-id\">${escape(identifier)}</dc:identifier>")
            add("<dc:title>${escape(title)}</dc:title>")
            add("<dc:language>${escape(language)}</dc:language>")
            authors.forEach { add("<dc:creator>${escape(it)}</dc:creator>") }
            description?.takeIf { it.isNotBlank() }?.let { add("<dc:description>${escape(it)}</dc:description>") }
            add("<meta property=\"dcterms:modified\">$modified</meta>")
            if (cover != null) add("<meta name=\"cover\" content=\"cover-image\"/>")
        }
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id" xml:lang="$language">
            |<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            |${metadata.joinToString("\n")}
            |</metadata>
            |<manifest>
            |${manifest.joinToString("\n")}
            |</manifest>
            |<spine toc="ncx">
            |${chapterPaths.indices.joinToString("\n") { "<itemref idref=\"chapter-${it + 1}\"/>" }}
            |</spine>
            |</package>
        """.trimMargin()
    }

    private fun ZipOutputStream.putStored(name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    private fun ZipOutputStream.putBytes(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun ZipOutputStream.putText(name: String, text: String) = putBytes(name, text.toByteArray())

    companion object {
        private val DATA_URI = Regex("""data:([\w.+-]+/[\w.+-]+);base64,(.*)""", RegexOption.DOT_MATCHES_ALL)

        private fun escape(text: String) = Entities.escape(text)

        private fun extension(mediaType: String) = when (mediaType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/svg+xml" -> "svg"
            else -> mediaType.substringAfter('/').substringBefore('+')
        }

        private val CONTAINER = """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            |<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            |</container>
        """.trimMargin()

        private val STYLE = """
            |body { line-height: 1.5; }
            |img { max-width: 100%; height: auto; display: block; margin: 1em auto; }
            |h2 { text-align: center; }
        """.trimMargin()
    }
}
