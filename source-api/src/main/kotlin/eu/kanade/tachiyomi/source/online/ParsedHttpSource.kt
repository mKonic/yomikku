package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * A [HttpSource] for sites that render their pages server side: every listing is a CSS selector over the page, and
 * the chapter text is the content of one element.
 */
abstract class ParsedHttpSource : HttpSource() {

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector()).map(::popularMangaFromElement)
        val hasNextPage = popularMangaNextPageSelector()?.let { document.selectFirst(it) } != null
        return MangasPage(mangas, hasNextPage)
    }

    protected abstract fun popularMangaSelector(): String

    protected abstract fun popularMangaFromElement(element: Element): SManga

    protected abstract fun popularMangaNextPageSelector(): String?

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(searchMangaSelector()).map(::searchMangaFromElement)
        val hasNextPage = searchMangaNextPageSelector()?.let { document.selectFirst(it) } != null
        return MangasPage(mangas, hasNextPage)
    }

    protected open fun searchMangaSelector(): String = popularMangaSelector()

    protected open fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    protected open fun searchMangaNextPageSelector(): String? = popularMangaNextPageSelector()

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(latestUpdatesSelector()).map(::latestUpdatesFromElement)
        val hasNextPage = latestUpdatesNextPageSelector()?.let { document.selectFirst(it) } != null
        return MangasPage(mangas, hasNextPage)
    }

    protected open fun latestUpdatesSelector(): String = popularMangaSelector()

    protected open fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    protected open fun latestUpdatesNextPageSelector(): String? = popularMangaNextPageSelector()

    override fun mangaDetailsParse(response: Response): SManga = mangaDetailsParse(response.asJsoup())

    protected abstract fun mangaDetailsParse(document: Document): SManga

    // KMK -->
    override fun relatedMangaListParse(response: Response): List<SManga> {
        return response.asJsoup().select(relatedMangaListSelector()).map(::relatedMangaFromElement)
    }

    protected open fun relatedMangaListSelector(): String = popularMangaSelector()

    protected open fun relatedMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
    // KMK <--

    override fun chapterListParse(response: Response): List<SChapter> {
        return response.asJsoup().select(chapterListSelector()).map(::chapterFromElement)
    }

    protected abstract fun chapterListSelector(): String

    protected abstract fun chapterFromElement(element: Element): SChapter

    override fun chapterTextParse(response: Response): String = chapterTextParse(response.asJsoup())

    /**
     * The chapter body: the inner HTML of the first element matching [chapterTextSelector], with the elements
     * matching [chapterTextRemoveSelector] taken out first.
     */
    protected open fun chapterTextParse(document: Document): String {
        val content = document.selectFirst(chapterTextSelector())
            ?: throw Exception("No chapter text found")
        chapterTextRemoveSelector()?.let { content.select(it).remove() }
        content.select("img[src]").forEach { it.attr("src", it.absUrl("src")) }
        return content.html()
    }

    protected abstract fun chapterTextSelector(): String

    /**
     * Elements inside the chapter body that are not part of the text: ads, share buttons, "next chapter" links.
     */
    protected open fun chapterTextRemoveSelector(): String? = "script, style, iframe, ins, noscript"
}
