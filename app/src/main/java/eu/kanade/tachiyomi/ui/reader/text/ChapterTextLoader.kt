package eu.kanade.tachiyomi.ui.reader.text

import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Where a chapter's text comes from, cheapest first: the local source, a download, the chapter cache, the network.
 * Text fetched from the network is cached, so reopening a chapter or preloading the next one costs one request.
 */
class ChapterTextLoader(
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val chapterCache: ChapterCache = Injekt.get(),
) {

    suspend fun load(manga: Manga, chapter: Chapter, source: Source): ChapterDocument = withIOContext {
        val html = loadHtml(manga, chapter, source)
        val baseUrl = (source as? HttpSource)?.baseUrl.orEmpty()
        ChapterParser.parse(html, baseUrl)
    }

    /**
     * Fetches the chapter into the cache without parsing it, so the next chapter opens without a wait.
     */
    suspend fun preload(manga: Manga, chapter: Chapter, source: Source) = withIOContext {
        if (source.isLocal() || isDownloaded(manga, chapter, source)) return@withIOContext
        if (chapterCache.getChapterText(chapter) != null) return@withIOContext
        val text = source.getChapterText(chapter.toSChapter())
        chapterCache.putChapterText(chapter, text)
    }

    private suspend fun loadHtml(manga: Manga, chapter: Chapter, source: Source): String {
        if (source.isLocal()) return source.getChapterText(chapter.toSChapter())

        downloadProvider.findChapterDir(chapter.name, chapter.scanlator, chapter.url, manga.ogTitle, source)
            ?.takeIf { it.isFile }
            ?.openInputStream()
            ?.bufferedReader()
            ?.use { return it.readText() }

        chapterCache.getChapterText(chapter)?.let { return it }

        val text = source.getChapterText(chapter.toSChapter())
        chapterCache.putChapterText(chapter, text)
        return text
    }

    private fun isDownloaded(manga: Manga, chapter: Chapter, source: Source): Boolean {
        return downloadProvider.findChapterDir(chapter.name, chapter.scanlator, chapter.url, manga.ogTitle, source) !=
            null
    }
}
