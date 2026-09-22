package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.saveTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import okhttp3.Response
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.renameToOrCopy
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNow
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.HttpURLConnection.HTTP_PARTIAL
import java.util.Locale

/**
 * This class is the one in charge of downloading chapters.
 *
 * Its queue contains the list of chapters to download.
 */
@OptIn(DelicateCoroutinesApi::class)
class Downloader internal constructor(
    private val context: Context,
    private val provider: DownloadProvider,
    private val cache: DownloadCache,
    private val sourceManager: SourceManager = Injekt.get(),
    private val chapterCache: ChapterCache = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    // KMK --> injectable, so the downloader can be tested off a device
    /** Store for persisting downloads across restarts. */
    private val store: DownloadStore = DownloadStore(context),
    notifierProvider: () -> DownloadNotifier = { DownloadNotifier(context) },
    // KMK <--
) {

    /**
     * Queue where active downloads are kept.
     */
    private val _queueState = MutableStateFlow<List<Download>>(emptyList())
    val queueState = _queueState.asStateFlow()

    /**
     * Notifier for the downloader state and progress.
     */
    private val notifier by lazy(notifierProvider)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // A cancelled download may still be closing a stream or writing a file when the next attempt starts.
    private val downloaderMutex = Mutex()

    @Volatile
    private var downloaderJob: Job? = null

    /**
     * Whether the downloader is running.
     */
    val isRunning: Boolean
        get() = downloaderJob?.isActive ?: false

    /**
     * Whether the downloader is paused
     */
    @Volatile
    var isPaused: Boolean = false

    // KMK --> the store drops every download whose source it cannot find, so wait for the sources to load
    private val restoreJob = scope.async {
        sourceManager.isInitialized.first { it }
        addAllToQueue(store.restore())
    }

    internal suspend fun awaitQueueRestored() = restoreJob.await()
    // KMK <--

    /**
     * Starts the downloader. It doesn't do anything if it's already running or there isn't anything
     * to download.
     *
     * @return true if the downloader is started, false otherwise.
     */
    fun start(): Boolean = synchronized(DownloadJob.session.lock) {
        if (isRunning || queueState.value.isEmpty()) {
            return false
        }

        // KMK -->
        notifier.dismissPaused()
        // KMK <--

        isPaused = false

        launchDownloaderJob()

        return true
    }

    /**
     * Stops the downloader.
     */
    fun stop(reason: String? = null): Unit = synchronized(DownloadJob.session.lock) {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING }
            .forEach { it.status = Download.State.ERROR }

        if (reason != null) {
            notifier.onWarning(reason)
            return
        }

        if (isPaused && queueState.value.isNotEmpty()) {
            notifier.onPaused()
        } else {
            notifier.onComplete()
        }

        isPaused = false

        DownloadJob.stop(context)
    }

    /**
     * Pauses the downloader
     */
    fun pause(): Unit = synchronized(DownloadJob.session.lock) {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING }
            .forEach { it.status = Download.State.QUEUE }
        isPaused = true
    }

    /**
     * Pauses active downloads while the worker waits for the network to come back. Unlike [stop], nothing is marked
     * as failed.
     */
    fun pauseForNetwork(reason: String): Unit = synchronized(DownloadJob.session.lock) {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING }
            .forEach { it.status = Download.State.QUEUE }
        notifier.onWarning(reason)
    }

    /**
     * Removes everything from the queue.
     */
    fun clearQueue(): Unit = synchronized(DownloadJob.session.lock) {
        cancelDownloaderJob()

        internalClearQueue()
        notifier.dismissProgress()
    }

    /**
     * Prepares the subscriptions to start downloading.
     */
    private fun launchDownloaderJob() {
        if (isRunning) return

        downloaderJob = scope.launch(start = CoroutineStart.LAZY) {
            val owner = currentCoroutineContext().job
            // A cancelled download may still be closing a stream or writing a file.
            downloaderMutex.withLock {
                synchronized(DownloadJob.session.lock) {
                    if (downloaderJob !== owner || !owner.isActive) return@withLock
                    queueState.value
                        .filter { it.status == Download.State.DOWNLOADED }
                        .forEach(::removeFromQueue)
                    if (queueState.value.isEmpty()) {
                        stop()
                        return@withLock
                    }
                    queueState.value.forEach { it.status = Download.State.QUEUE }
                }

                val activeDownloadsFlow = combine(
                    queueState,
                    downloadPreferences.parallelSourceLimit().changes(),
                ) { a, b -> a to b }.transformLatest { (queue, parallelCount) ->
                    while (true) {
                        val activeDownloads = queue.asSequence()
                            // Ignore completed downloads, leave them in the queue
                            .filter { it.status.value <= Download.State.DOWNLOADING.value }
                            .groupBy { it.source }
                            .toList()
                            .take(parallelCount)
                            .map { (_, downloads) -> downloads.first() }
                        emit(activeDownloads)

                        if (activeDownloads.isEmpty()) break
                        // Suspend until a download enters the ERROR state
                        val activeDownloadsErroredFlow =
                            combine(activeDownloads.map(Download::statusFlow)) { states ->
                                states.contains(Download.State.ERROR)
                            }.filter { it }
                        activeDownloadsErroredFlow.first()
                    }
                }
                    .distinctUntilChanged()

                // Use supervisorScope to cancel child jobs when the downloader job is cancelled
                supervisorScope {
                    val downloadJobs = mutableMapOf<Download, Job>()

                    activeDownloadsFlow.collectLatest { activeDownloads ->
                        val downloadJobsToStop = downloadJobs.filter { it.key !in activeDownloads }
                        downloadJobsToStop.forEach { (download, job) ->
                            job.cancel()
                            downloadJobs.remove(download)
                        }

                        val downloadsToStart = activeDownloads.filter { it !in downloadJobs }
                        downloadsToStart.forEach { download ->
                            downloadJobs[download] = launchDownloadJob(download, owner)
                        }
                    }
                }
            }
        }
        downloaderJob?.start()
    }

    private fun CoroutineScope.launchDownloadJob(download: Download, owner: Job) = launchIO {
        try {
            downloadChapter(download)

            synchronized(DownloadJob.session.lock) {
                // A run that was cancelled meanwhile must not stop the one that replaced it.
                if (downloaderJob !== owner || !owner.isActive) return@launchIO
                // Remove successful download from queue
                if (download.status == Download.State.DOWNLOADED) {
                    removeFromQueue(download)
                }
                if (areAllDownloadsFinished()) {
                    stop()
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            synchronized(DownloadJob.session.lock) {
                if (downloaderJob !== owner || !owner.isActive) return@launchIO
                logcat(LogPriority.ERROR, e)
                notifier.onError(e.message)
                stop()
            }
        }
    }

    /**
     * Destroys the downloader subscriptions.
     */
    private fun cancelDownloaderJob() {
        downloaderJob?.cancel()
        downloaderJob = null
    }

    /**
     * Creates a download object for every chapter and adds them to the downloads queue.
     *
     * @param manga the manga of the chapters to download.
     * @param chapters the list of chapters to download.
     * @param autoStart whether to start the downloader after enqueing the chapters.
     */
    fun queueChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean) {
        if (chapters.isEmpty()) return

        val source = sourceManager.peek(manga.source) as? HttpSource ?: return

        val wasEmpty = queueState.value.isEmpty()
        val chaptersToQueue = chapters.asSequence()
            // Filter out those already downloaded.
            .filter {
                provider.findChapterDir(it.name, it.scanlator, it.url, /* SY --> */ manga.ogTitle /* SY <-- */, source) == null
            }
            // Add chapters to queue from the start.
            .sortedByDescending { it.sourceOrder }
            // Filter out those already enqueued.
            .filter { chapter -> queueState.value.none { it.chapter.id == chapter.id } }
            // Create a download for each one.
            .map { Download(source, manga, it) }
            .toList()

        if (chaptersToQueue.isNotEmpty()) {
            addAllToQueue(chaptersToQueue)

            // Start downloader if needed
            if (autoStart && wasEmpty) {
                val queuedDownloads = queueState.value.count { it.source !is UnmeteredSource }
                val maxDownloadsFromSource = queueState.value
                    .groupBy { it.source }
                    .filterKeys { it !is UnmeteredSource }
                    .maxOfOrNull { it.value.size }
                    ?: 0
                if (
                    queuedDownloads > DOWNLOADS_QUEUED_WARNING_THRESHOLD ||
                    maxDownloadsFromSource > CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD
                ) {
                    notifier.onWarning(
                        context.stringResource(
                            MR.strings.download_queue_size_warning,
                            context.stringResource(MR.strings.app_name),
                        ),
                        WARNING_NOTIF_TIMEOUT_MS,
                        NotificationHandler.openUrl(context, LibraryUpdateNotifier.HELP_WARNING_URL),
                    )
                }
                DownloadJob.start(context)
            }
        }
    }

    /**
     * Downloads a chapter.
     *
     * @param download the chapter to be downloaded.
     */
    private suspend fun downloadChapter(download: Download) {
        val mangaDir = provider.getMangaDir(/* SY --> */ download.manga.ogTitle /* SY <-- */, download.source).getOrElse { e ->
            download.status = Download.State.ERROR
            notifier.onError(e.message, download.chapter.name, download.manga.title, download.manga.id)
            return
        }

        val availSpace = DiskUtil.getAvailableStorageSpace(mangaDir)
        if (availSpace != -1L && availSpace < MIN_DISK_SPACE) {
            download.status = Download.State.ERROR
            notifier.onError(
                context.stringResource(MR.strings.download_insufficient_space),
                download.chapter.name,
                download.manga.title,
                download.manga.id,
            )
            return
        }

        val chapterFileName = provider.getChapterDirName(
            download.chapter.name,
            download.chapter.scanlator,
            download.chapter.url,
        ) + "." + DownloadProvider.CHAPTER_EXTENSION

        try {
            download.status = Download.State.DOWNLOADING
            download.progress = 0

            val text = chapterCache.getChapterText(download.chapter) ?: fetchChapterText(download)
            if (text.isBlank()) {
                throw Exception(context.stringResource(MR.strings.page_list_empty_error))
            }

            // Written beside the final name and renamed at the end, so an interrupted download never leaves a
            // file the cache would take for a finished chapter.
            mangaDir.findFile(chapterFileName + TMP_DIR_SUFFIX)?.delete()
            val tmpFile = mangaDir.createFile(chapterFileName + TMP_DIR_SUFFIX)
                ?: throw Exception("Failed to create a file for the downloaded chapter")
            tmpFile.openOutputStream().bufferedWriter().use { it.write(text) }
            tmpFile.renameToOrCopy(chapterFileName)

            cache.addChapter(chapterFileName, mangaDir, download.manga)

            download.progress = 100
            download.status = Download.State.DOWNLOADED
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logcat(LogPriority.ERROR, error)
            download.status = Download.State.ERROR
            notifier.onError(error.message, download.chapter.name, download.manga.title, download.manga.id)
        }
    }

    /**
     * Fetches the chapter text from the source, retrying 3 times with 2, 4 and 8 seconds between attempts.
     */
    private suspend fun fetchChapterText(download: Download): String {
        return flow { emit(download.source.getChapterText(download.chapter.toSChapter())) }
            .retryWhen { e, attempt ->
                if (e is CancellationException || attempt >= 3) return@retryWhen false
                delay((2L shl attempt.toInt()) * 1000)
                true
            }
            .first()
    }

    /**
     * Returns true if all the queued downloads are in DOWNLOADED or ERROR state.
     */
    private fun areAllDownloadsFinished(): Boolean {
        return queueState.value.none { it.status.value <= Download.State.DOWNLOADING.value }
    }

    private fun addAllToQueue(downloads: List<Download>) {
        _queueState.update {
            downloads.forEach { download ->
                download.status = Download.State.QUEUE
            }
            store.addAll(downloads)
            it + downloads
        }
    }

    private fun removeFromQueue(download: Download) {
        _queueState.update {
            store.remove(download)
            if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                download.status = Download.State.NOT_DOWNLOADED
            }
            it - download
        }
    }

    private inline fun removeFromQueueIf(predicate: (Download) -> Boolean) {
        _queueState.update { queue ->
            val downloads = queue.filter { predicate(it) }
            store.removeAll(downloads)
            downloads.forEach { download ->
                if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            queue - downloads.toSet()
        }
    }

    fun removeFromQueue(chapters: List<Chapter>) {
        val chapterIds = chapters.map { it.id }
        removeFromQueueIf { it.chapter.id in chapterIds }
    }

    fun removeFromQueue(manga: Manga) {
        removeFromQueueIf { it.manga.id == manga.id }
    }

    private fun internalClearQueue() {
        _queueState.update {
            it.forEach { download ->
                if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            store.clear()
            emptyList()
        }
    }

    fun updateQueue(downloads: List<Download>): Unit = synchronized(DownloadJob.session.lock) {
        val wasRunning = isRunning

        if (downloads.isEmpty()) {
            clearQueue()
            stop()
            return
        }

        pause()
        internalClearQueue()
        addAllToQueue(downloads)

        if (wasRunning) {
            start()
        }
    }

    companion object {
        const val TMP_DIR_SUFFIX = "_tmp"
        const val WARNING_NOTIF_TIMEOUT_MS = 30_000L
        const val CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 15
        private const val DOWNLOADS_QUEUED_WARNING_THRESHOLD = 30
    }
}

// Arbitrary minimum required space to start a download: 200 MB
private const val MIN_DISK_SPACE = 200L * 1024 * 1024
