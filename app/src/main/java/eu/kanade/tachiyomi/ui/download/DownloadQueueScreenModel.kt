package eu.kanade.tachiyomi.ui.download

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DownloadQueueScreenModel(
    private val downloadManager: DownloadManager = Injekt.get(),
    // KMK -->
    private val navigator: Navigator? = null,
    // KMK <--
) : ScreenModel {

    // KMK -->
    // Built straight from the queue and shared only while something is collecting, so a
    // backgrounded queue screen stops rebuilding this list on every download tick.
    // KMK <--
    val state: StateFlow<List<DownloadQueueSection>> = downloadManager.queueState
        .map { downloads ->
            downloads
                .groupBy { it.source }
                .map { (source, downloads) -> DownloadQueueSection(source.id, source.name, downloads) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(screenModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isDownloaderRunning = downloadManager.isDownloaderRunning
        .stateIn(screenModelScope, SharingStarted.WhileSubscribed(5000), false)

    // KMK --> stays true while downloads wait for a network, so the button still offers to pause (mihonapp/mihon#3500)
    val isDownloadRequested = downloadManager.isDownloadRequested
        .stateIn(screenModelScope, SharingStarted.WhileSubscribed(5000), false)
    // KMK <--

    fun startDownloads() {
        downloadManager.startDownloads()
    }

    fun pauseDownloads() {
        downloadManager.pauseDownloads()
    }

    fun clearQueue() {
        downloadManager.clearQueue()
    }

    fun reorder(downloads: List<Download>) {
        downloadManager.reorderQueue(downloads)
    }

    fun cancel(downloads: List<Download>) {
        downloadManager.cancelQueuedDownloads(downloads)
    }

    // KMK -->
    fun showManga(mangaId: Long) {
        navigator?.push(MangaScreen(mangaId))
    }
    // KMK <--

    /**
     * Moves [download] to the top or bottom of its section.
     */
    fun move(download: Download, toTop: Boolean) {
        reorder(
            state.value.flatMap { section ->
                if (download in section.downloads) {
                    val others = section.downloads - download
                    if (toTop) listOf(download) + others else others + download
                } else {
                    section.downloads
                }
            },
        )
    }

    /**
     * Moves all the downloads of the manga of [download] to the top or bottom of the queue.
     */
    fun moveSeries(download: Download, toTop: Boolean) {
        val (selectedSeries, otherSeries) = state.value
            .flatMap { it.downloads }
            .partition { it.manga.id == download.manga.id }
        reorder(if (toTop) selectedSeries + otherSeries else otherSeries + selectedSeries)
    }

    fun cancelSeries(download: Download) {
        val allDownloadsForSeries = state.value
            .flatMap { it.downloads }
            .filter { it.manga.id == download.manga.id }
        if (allDownloadsForSeries.isNotEmpty()) {
            cancel(allDownloadsForSeries)
        }
    }

    /**
     * Sorts the downloads of every section by [selector].
     */
    fun <R : Comparable<R>> reorderQueue(selector: (Download) -> R?, reverse: Boolean = false) {
        reorder(
            state.value.flatMap { section ->
                section.downloads.sortedBy(selector).let { if (reverse) it.reversed() else it }
            },
        )
    }
}

/**
 * Downloads of the queue from one source.
 */
@Immutable
data class DownloadQueueSection(
    val id: Long,
    val name: String,
    val downloads: List<Download>,
)
