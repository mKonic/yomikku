package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import exh.util.DataSaver
import exh.util.DataSaver.Companion.getImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.util.concurrent.PriorityBlockingQueue
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.math.min

/**
 * Loader used to load chapters from an online source.
 */
@OptIn(DelicateCoroutinesApi::class)
internal class HttpPageLoader(
    private val chapter: ReaderChapter,
    private val source: HttpSource,
    private val chapterCache: ChapterCache = Injekt.get(),
    // SY -->
    private val readerPreferences: ReaderPreferences = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    // SY <--
) : PageLoader() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * A queue used to manage requests one by one while allowing priorities.
     */
    private val queue = PriorityBlockingQueue<PriorityPage>()

    private val preloadSize = /* SY --> */ readerPreferences.preloadSize().get() // SY <--

    // SY -->
    private val dataSaver = DataSaver(source, sourcePreferences)
    // SY <--

    // KMK -->
    private val parallelDownloader = if (readerPreferences.parallelImageDownload().get()) {
        ParallelImageDownloader(
            client = source.client,
            tmpDir = File(Injekt.get<Application>().cacheDir, "parallel_image_download"),
        )
    } else {
        null
    }
    // KMK <--

    init {
        // KMK -->
        parallelDownloader?.let { downloader -> scope.launchIO { downloader.sweepOrphans() } }
        // KMK <--
        // EXH -->
        repeat(readerPreferences.readerThreads().get().coerceAtLeast(1)) {
            // EXH <--
            scope.launchIO {
                flow {
                    while (true) {
                        emit(runInterruptible { queue.take() })
                    }
                }
                    .filter { it.page.status == Page.State.Queue }
                    .collect {
                        internalLoadPage(
                            page = it.page,
                            force = it.priority == PriorityPage.RETRY,
                        )
                    }
            }
            // EXH -->
        }
        // EXH <--
    }

    override var isLocal: Boolean = false

    /**
     * Returns the page list for a chapter. It tries to return the page list from the local cache,
     * otherwise fallbacks to network.
     */
    override suspend fun getPages(): List<ReaderPage> {
        val pages = try {
            chapterCache.getPageListFromCache(chapter.chapter.toDomainChapter()!!)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            source.getPageList(chapter.chapter)
        }
        // SY -->
        val rp = pages.mapIndexed { index, page ->
            // Don't trust sources and use our own indexing
            ReaderPage(index, page.url, page.imageUrl)
        }
        if (readerPreferences.aggressivePageLoading().get()) {
            rp.forEach {
                if (it.status == Page.State.Queue) {
                    queue.offer(PriorityPage(it, PriorityPage.ADJACENT))
                }
            }
        }
        return rp
        // SY <--
    }

    /**
     * Loads a page through the queue. Handles re-enqueueing pages if they were evicted from the cache.
     */
    override suspend fun loadPage(page: ReaderPage) = loadPageInternal(page, PriorityPage.DEFAULT)

    // KMK -->
    private suspend fun loadPageInternal(page: ReaderPage, priority: Int): Unit = withIOContext {
        // KMK <--
        val imageUrl = page.imageUrl

        // Check if the image has been deleted
        if (page.status == Page.State.Ready && imageUrl != null && !chapterCache.isImageInCache(imageUrl)) {
            page.status = Page.State.Queue
        }

        // Automatically retry failed pages when subscribed to this page
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }

        val queuedPages = mutableListOf<PriorityPage>()
        if (page.status == Page.State.Queue) {
            queuedPages += PriorityPage(page, /* KMK --> */ priority /* KMK <-- */).also { queue.offer(it) }
        }
        queuedPages += preloadNextPages(page, preloadSize)

        suspendCancellableCoroutine<Nothing> { continuation ->
            continuation.invokeOnCancellation {
                queuedPages.forEach {
                    if (it.page.status == Page.State.Queue) {
                        queue.remove(it)
                    }
                }
            }
        }
    }

    /**
     * Retries a page. This method is only called from user interaction on the viewer.
     */
    override fun retryPage(page: ReaderPage) {
        // Whatever the state: a page that downloaded fine can still fail to decode, and only a page
        // back in Queue gets loaded again.
        page.status = Page.State.Queue
        if (readerPreferences.readerInstantRetry().get()) { // EXH <--
            boostPage(page)
        } else {
            // EXH <--
            queue.offer(PriorityPage(page, PriorityPage.RETRY))
        }
    }

    override fun recycle() {
        super.recycle()
        scope.cancel()
        queue.clear()

        // Cache current page list progress for online chapters to allow a faster reopen
        chapter.pages?.let { pages ->
            launchIO {
                try {
                    // Convert to pages without reader information
                    val pagesToSave = pages.map { Page(it.index, it.url, it.imageUrl) }
                    chapterCache.putPageListToCache(chapter.chapter.toDomainChapter()!!, pagesToSave)
                } catch (e: Throwable) {
                    if (e is CancellationException) {
                        throw e
                    }
                }
            }
        }
    }

    /**
     * Preloads the given [amount] of pages after the [currentPage] with a lower priority.
     *
     * @return a list of [PriorityPage] that were added to the [queue]
     */
    private fun preloadNextPages(currentPage: ReaderPage, amount: Int): List<PriorityPage> {
        val pageIndex = currentPage.index
        val pages = currentPage.chapter.pages ?: return emptyList()
        if (pageIndex == pages.lastIndex) return emptyList()

        return pages
            .subList(pageIndex + 1, min(pageIndex + 1 + amount, pages.size))
            .mapNotNull {
                if (it.status == Page.State.Queue) {
                    PriorityPage(it, PriorityPage.ADJACENT).apply { queue.offer(this) }
                } else {
                    null
                }
            }
    }

    /**
     * Loads the page, retrieving the image URL and downloading the image if necessary.
     * Downloaded images are stored in the chapter cache.
     *
     * @param page the page whose source image has to be downloaded.
     */
    private suspend fun internalLoadPage(page: ReaderPage, force: Boolean) {
        try {
            if (page.imageUrl.isNullOrEmpty()) {
                page.status = Page.State.LoadPage
                page.imageUrl = source.getImageUrl(page)
            }
            val imageUrl = page.imageUrl!!

            if (/* KMK --> */ force || /* KMK <-- */ !chapterCache.isImageInCache(imageUrl)) {
                page.status = Page.State.DownloadImage
                downloadImage(page, imageUrl)
            }

            // KMK -->
            // Two things can leave the cache without the file we just asked it for: a second
            // loader racing this same key gets a null editor from DiskLruCache and returns
            // without writing anything, and a trim can evict the entry between the write and
            // here. Marking the page Ready either way surfaces it as a bare
            // FileNotFoundException when the viewer renders it, which no retry can clear.
            if (!chapterCache.isImageInCache(imageUrl)) {
                throw IOException("Page ${page.number} left the cache before it could be read")
            }
            // KMK <--

            page.stream = { chapterCache.getImageFile(imageUrl).inputStream() }
            page.status = Page.State.Ready
        } catch (e: Throwable) {
            page.status = Page.State.Error(e)
            if (e is CancellationException) {
                throw e
            }
        }
    }

    // KMK -->
    /**
     * Downloads the image for [page] into the chapter cache, splitting the body across concurrent
     * byte ranges when the host allows it.
     */
    private suspend fun downloadImage(page: ReaderPage, imageUrl: String) {
        val response = source.getImage(page, dataSaver)
        when (val result = parallelDownloader?.fetch(response, page) ?: ParallelImageDownloader.Result.Declined) {
            is ParallelImageDownloader.Result.Success ->
                try {
                    chapterCache.putImageToCache(imageUrl, result.file)
                } finally {
                    result.file.delete()
                }
            ParallelImageDownloader.Result.Declined ->
                chapterCache.putImageToCache(imageUrl, response)
            ParallelImageDownloader.Result.Failed ->
                chapterCache.putImageToCache(imageUrl, source.getImage(page, dataSaver))
        }
    }
    // KMK <--

    // EXH -->
    fun boostPage(page: ReaderPage) {
        if (page.status == Page.State.Queue) {
            scope.launchIO {
                // KMK -->
                // Force a redownload since this is a retry, not a normal load
                loadPageInternal(page, PriorityPage.RETRY)
                // KMK <--
            }
        }
    }
    // EXH <--
}

/**
 * Data class used to keep ordering of pages in order to maintain priority.
 */
@OptIn(ExperimentalAtomicApi::class)
private class PriorityPage(
    val page: ReaderPage,
    val priority: Int,
) : Comparable<PriorityPage> {
    companion object {
        private val idGenerator = AtomicInt(0)

        // KMK -->
        const val RETRY = 2
        const val DEFAULT = 1
        const val ADJACENT = 0
        // KMK <--
    }

    private val identifier = idGenerator.incrementAndFetch()

    override fun compareTo(other: PriorityPage): Int {
        val p = other.priority.compareTo(priority)
        return if (p != 0) p else identifier.compareTo(other.identifier)
    }
}
