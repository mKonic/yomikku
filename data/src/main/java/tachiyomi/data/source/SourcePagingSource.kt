package tachiyomi.data.source

import androidx.paging.PagingState
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import exh.log.xLogE
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.QuerySanitizer.sanitize
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.repository.SourcePagingSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SourceSearchPagingSource(
    source: suspend () -> Source,
    private val query: String,
    private val filters: FilterList,
) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source().getSearchManga(currentPage, query.sanitize(), filters)
    }
}

class SourcePopularPagingSource(source: suspend () -> Source) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source().getPopularManga(currentPage)
    }
}

class SourceLatestPagingSource(source: suspend () -> Source) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source().getLatestUpdates(currentPage)
    }
}

abstract class BaseSourcePagingSource(
    // KMK -->
    // Resolved lazily rather than handed over at construction: a source only exists once the
    // extensions have finished loading, and a paging source built before that would otherwise
    // capture a stub for its whole life.
    private val sourceProvider: suspend () -> Source,
    // KMK <--
    protected val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
) : SourcePagingSource() {

    // KMK -->
    constructor(
        source: Source,
        networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    ) : this({ source }, networkToLocalManga)

    protected suspend fun source(): Source = sourceProvider()
    // KMK <--

    protected val seenManga = hashSetOf<String>()

    abstract suspend fun requestNextPage(currentPage: Int): MangasPage

    override suspend fun load(
        params: LoadParams<Long>,
    ): LoadResult<Long, Manga> {
        val page = params.key ?: 1

        return try {
            // KMK -->
            val source = sourceProvider()
            // KMK <--
            val mangasPage = withIOContext {
                requestNextPage(page.toInt())
                    .takeIf { it.mangas.isNotEmpty() }
                    ?: throw NoResultsException()
            }

            // SY -->
            getPageLoadResult(source, params, mangasPage)
            // SY <--
        } catch (e: Exception) {
            xLogE("${this::class.simpleName}: Failed to load paging source", e)
            LoadResult.Error(e)
        }
    }

    // SY -->
    open suspend fun getPageLoadResult(
        // KMK -->
        source: Source,
        // KMK <--
        params: LoadParams<Long>,
        mangasPage: MangasPage,
    ): LoadResult.Page<Long, Manga> {
        val page = params.key ?: 1

        val manga = mangasPage.mangas
            .map { it.toDomainManga(source.id) }
            .filter { seenManga.add(it.url) }
            .let { networkToLocalManga(it) }

        val nextKey = if (mangasPage.hasNextPage) page + 1 else null

        return LoadResult.Page(
            data = manga,
            prevKey = null,
            nextKey = nextKey,
        )
    }
    // SY <--

    override fun getRefreshKey(
        state: PagingState<Long, Manga>,
    ): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey ?: anchorPage?.nextKey
        }
    }
}

class NoResultsException : Exception()
