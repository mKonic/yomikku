package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterItem
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.text.ChapterDocument
import eu.kanade.tachiyomi.ui.reader.text.ChapterTextLoader
import eu.kanade.tachiyomi.ui.reader.text.ReaderNavigation
import eu.kanade.tachiyomi.util.chapter.filterDownloaded
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date
import kotlin.math.roundToLong

/**
 * Reading state for one novel: which chapter is open, its text, and how far into it the reader is.
 *
 * Progress is a fraction of the chapter's text, stored in [Chapter.lastPageRead] as parts per
 * [PROGRESS_SCALE]. It does not depend on font size or screen, so a chapter reopens at the same sentence however
 * the reader is set up.
 */
class ReaderViewModel @JvmOverloads constructor(
    private val savedState: SavedStateHandle,
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val textLoader: ChapterTextLoader = ChapterTextLoader(),
    val readerPreferences: ReaderPreferences = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val syncPreferences: SyncPreferences = Injekt.get(),
    private val uiPreferences: UiPreferences = Injekt.get(),
    private val trackChapter: TrackChapter = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val upsertHistory: UpsertHistory = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val getIncognitoState: GetIncognitoState = Injekt.get(),
) : ViewModel() {

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    val mangaId = savedState.get<Long>("manga") ?: -1L
    private val initialChapterId = savedState.get<Long>("chapter") ?: -1L
    val hasValidArgs = mangaId != -1L && initialChapterId != -1L

    /** The chapter open now, kept in the saved state so a process death reopens it. */
    private var chapterId: Long
        get() = savedState.get<Long>("chapter_id") ?: initialChapterId
        set(value) {
            savedState["chapter_id"] = value
        }

    /** Chapters in reading order, after the skip settings are applied. */
    private var chapterList: List<Chapter> = emptyList()

    /** Every chapter, whatever the skip settings, for marking duplicates read. */
    private var unfilteredChapterList: List<Chapter> = emptyList()

    private var source: Source? = null

    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var preloadedChapterId: Long? = null

    private var readStartTime: Long? = null

    val manga: Manga? get() = state.value.manga

    val incognitoMode: Boolean by lazy { getIncognitoState.await(manga?.source) }

    init {
        if (hasValidArgs) viewModelScope.launch { init() }
    }

    private suspend fun init() {
        withIOContext {
            try {
                val manga = getManga.await(mangaId) ?: error("No novel with id $mangaId")
                sourceManager.isInitialized.first { it }
                source = sourceManager.getOrStub(manga.source)
                mutableState.update { it.copy(manga = manga) }
                loadChapterLists(manga)
                val chapter = chapterList.find { it.id == chapterId }
                    ?: unfilteredChapterList.find { it.id == chapterId }
                    ?: error("Requested chapter of id $chapterId not found in chapter list")
                openChapter(chapter, startFraction = chapter.startFraction())
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e)
                mutableState.update { it.copy(initError = e) }
            }
        }
    }

    private suspend fun loadChapterLists(manga: Manga) {
        unfilteredChapterList = getChaptersByMangaId.await(manga.id, applyFilter = false)
        val chapters = getChaptersByMangaId.await(manga.id, applyFilter = true)
        val selected = chapters.find { it.id == chapterId }

        val base = if (readerPreferences.skipFiltered().get()) chapters else unfilteredChapterList
        val forReader = if (readerPreferences.skipRead().get()) {
            base.filter { !it.read || it.id == chapterId }
        } else {
            base
        }

        chapterList = forReader
            .sortedWith(getChapterSort(manga, sortDescending = false))
            .run { if (readerPreferences.skipDupe().get() && selected != null) removeDuplicates(selected) else this }
            .run { if (basePreferences.downloadedOnly().get()) filterDownloaded(manga, null) else this }
    }

    // region Chapter loading

    /**
     * Opens [chapter] at [startFraction] of its text, replacing whatever is open.
     */
    private fun openChapter(chapter: Chapter, startFraction: Float) {
        loadJob?.cancel()
        chapterId = chapter.id
        val index = chapterList.indexOfFirst { it.id == chapter.id }
        mutableState.update {
            it.copy(
                chapter = chapter,
                document = null,
                loadError = null,
                isLoading = true,
                bookmarked = chapter.bookmark,
                previousChapter = chapterList.getOrNull(index - 1),
                nextChapter = chapterList.getOrNull(index + 1).takeIf { index >= 0 },
                progress = startFraction,
                // A new token makes the content jump to startFraction even when it equals the old value.
                restoreToken = it.restoreToken + 1,
                restoreFraction = startFraction,
            )
        }
        loadJob = viewModelScope.launchIO {
            val manga = manga ?: return@launchIO
            val source = source ?: return@launchIO
            try {
                val document = textLoader.load(manga, chapter, source)
                mutableState.update { it.copy(document = document, isLoading = false) }
                recordChapterOpened(chapter)
                readStartTime = System.currentTimeMillis()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Failed to load chapter ${chapter.url}" }
                mutableState.update { it.copy(isLoading = false, loadError = e) }
            }
        }
    }

    fun retry() {
        val chapter = state.value.chapter ?: return
        openChapter(chapter, state.value.progress)
    }

    fun loadNextChapter() {
        val next = state.value.nextChapter ?: return
        viewModelScope.launch {
            updateHistory()
            openChapter(next, startFraction = 0f)
        }
    }

    fun loadPreviousChapter(atEnd: Boolean = false) {
        val previous = state.value.previousChapter ?: return
        viewModelScope.launch {
            updateHistory()
            openChapter(previous, startFraction = if (atEnd) 1f else previous.startFraction())
        }
    }

    fun loadChapterFromList(chapter: Chapter) {
        if (chapter.id == state.value.chapter?.id) return
        viewModelScope.launch {
            updateHistory()
            openChapter(chapter, startFraction = chapter.startFraction())
        }
    }

    private fun Chapter.startFraction(): Float {
        return if (read) 0f else (lastPageRead.toFloat() / PROGRESS_SCALE).coerceIn(0f, 1f)
    }

    // endregion

    // region Progress

    /**
     * Called by the content as the reader moves through the chapter.
     *
     * @param fraction how far into the chapter's text the top of the screen is, 0 to 1.
     * @param reachedEnd whether the end of the chapter is on screen.
     */
    fun onProgress(fraction: Float, reachedEnd: Boolean) {
        val chapter = state.value.chapter ?: return
        if (state.value.document == null) return
        mutableState.update { it.copy(progress = fraction) }

        if (fraction >= PRELOAD_THRESHOLD) preloadNextChapter()
        if (fraction >= DOWNLOAD_AHEAD_THRESHOLD) downloadNextChapters()

        if (incognitoMode) return
        saveJob?.cancel()
        saveJob = viewModelScope.launchNonCancellable {
            saveProgress(chapter, fraction, reachedEnd)
        }
    }

    private suspend fun saveProgress(chapter: Chapter, fraction: Float, reachedEnd: Boolean) {
        val completed = reachedEnd && !chapter.read
        val lastPageRead = (fraction * PROGRESS_SCALE).roundToLong().coerceIn(0, PROGRESS_SCALE)
        val updated = chapter.copy(
            read = chapter.read || reachedEnd,
            lastPageRead = if (reachedEnd) PROGRESS_SCALE else lastPageRead,
        )
        mutableState.update { if (it.chapter?.id == chapter.id) it.copy(chapter = updated) else it }
        chapterList = chapterList.map { if (it.id == chapter.id) updated else it }

        updateChapter.await(
            ChapterUpdate(id = chapter.id, read = updated.read, lastPageRead = updated.lastPageRead),
        )

        val sync = syncPreferences.getSyncTriggerOptions()
        val context = Injekt.get<Application>()
        if (completed) {
            onChapterCompleted(updated)
            if (syncPreferences.isSyncEnabled() && sync.syncOnChapterRead) SyncDataJob.startNow(context)
        }
    }

    private suspend fun onChapterCompleted(chapter: Chapter) {
        updateTrackChapterRead(chapter)
        deleteChapterIfNeeded(chapter)

        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead().get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
        if (!markDuplicateAsRead) return
        val duplicates = unfilteredChapterList
            .filter { !it.read && it.isRecognizedNumber && it.chapterNumber == chapter.chapterNumber }
            .map { ChapterUpdate(id = it.id, read = true) }
        updateChapter.awaitAll(duplicates)
    }

    private fun updateTrackChapterRead(chapter: Chapter) {
        if (incognitoMode || !trackPreferences.autoUpdateTrack().get()) return
        val manga = manga ?: return
        viewModelScope.launchNonCancellable {
            trackChapter.await(Injekt.get<Application>(), manga.id, chapter.chapterNumber)
        }
    }

    private fun deleteChapterIfNeeded(chapter: Chapter) {
        val removeAfterReadSlots = downloadPreferences.removeAfterReadSlots().get()
        if (removeAfterReadSlots == -1) return
        val position = chapterList.indexOfFirst { it.id == chapter.id }
        val toDelete = chapterList.getOrNull(position - removeAfterReadSlots)?.takeIf { it.read || it.id == chapter.id }
            ?: return
        val manga = manga ?: return
        viewModelScope.launchNonCancellable {
            downloadManager.enqueueChaptersToDelete(listOf(toDelete), manga)
        }
    }

    private fun preloadNextChapter() {
        val next = state.value.nextChapter ?: return
        if (preloadedChapterId == next.id) return
        preloadedChapterId = next.id
        val manga = manga ?: return
        val source = source ?: return
        viewModelScope.launchIO {
            try {
                textLoader.preload(manga, next, source)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                // The chapter still loads normally when it is opened.
                logcat(LogPriority.WARN, e) { "Failed to preload ${next.url}" }
            }
        }
    }

    private var downloadedAheadFrom: Long? = null

    private fun downloadNextChapters() {
        val amount = downloadPreferences.autoDownloadWhileReading().get()
        if (amount == 0) return
        val manga = manga ?: return
        val chapter = state.value.chapter ?: return
        val next = state.value.nextChapter ?: return
        if (downloadedAheadFrom == chapter.id) return
        downloadedAheadFrom = chapter.id
        viewModelScope.launchIO {
            // Only keep a run of downloads going: reading a chapter that is not downloaded does not start one.
            val source = source ?: return@launchIO
            val isCurrentDownloaded = downloadManager.isChapterDownloaded(
                chapter.name,
                chapter.scanlator,
                chapter.url,
                manga.ogTitle,
                source.id,
            )
            if (!isCurrentDownloaded) return@launchIO
            val toDownload = getNextChapters.await(manga.id, next.id).take(amount)
            downloadManager.downloadChapters(manga, toDownload)
        }
    }

    // endregion

    // region History

    private suspend fun recordChapterOpened(chapter: Chapter) {
        if (incognitoMode) return
        withNonCancellableContext {
            upsertHistory.await(HistoryUpdate(chapter.id, Date(), sessionReadDuration = 0L))
        }
    }

    suspend fun updateHistory() {
        if (incognitoMode) return
        val chapter = state.value.chapter ?: return
        val end = Date()
        val duration = readStartTime?.let { end.time - it } ?: 0L
        readStartTime = null
        withNonCancellableContext {
            upsertHistory.await(HistoryUpdate(chapter.id, end, duration))
        }
    }

    fun restartReadTimer() {
        readStartTime = System.currentTimeMillis()
    }

    fun onActivityFinish() {
        viewModelScope.launchNonCancellable { downloadManager.deletePendingChapters() }
    }

    // endregion

    // region Navigation

    private val navigationEvents = MutableSharedFlow<ReaderNavigation>(extraBufferCapacity = 4)
    val navigation = navigationEvents.asSharedFlow()

    /** Moves a screen forward or back, for volume keys and the keyboard. */
    fun navigate(direction: ReaderNavigation) {
        navigationEvents.tryEmit(direction)
    }

    /** Jumps to [fraction] of the chapter, from the progress slider. */
    fun seek(fraction: Float) {
        mutableState.update {
            it.copy(progress = fraction, restoreFraction = fraction, restoreToken = it.restoreToken + 1)
        }
    }

    // endregion

    // region Menus and dialogs

    fun showMenus(visible: Boolean) {
        mutableState.update { it.copy(menuVisible = visible) }
    }

    fun toggleMenus() = showMenus(!state.value.menuVisible)

    fun openSettings() = mutableState.update { it.copy(dialog = Dialog.Settings, menuVisible = false) }

    fun openChapterList() = mutableState.update { it.copy(dialog = Dialog.ChapterList) }

    fun closeDialog() = mutableState.update { it.copy(dialog = null) }

    fun getChapters(): List<ReaderChapterItem> {
        val manga = manga ?: return emptyList()
        val current = state.value.chapter?.id
        val dateFormat = UiPreferences.dateFormat(uiPreferences.dateFormat().get())
        return chapterList.map { ReaderChapterItem(it, manga, it.id == current, dateFormat) }
    }

    fun toggleChapterBookmark() {
        val chapter = state.value.chapter ?: return
        toggleBookmark(chapter.id, !chapter.bookmark)
    }

    fun toggleBookmark(chapterId: Long, bookmarked: Boolean) {
        chapterList = chapterList.map { if (it.id == chapterId) it.copy(bookmark = bookmarked) else it }
        mutableState.update {
            if (it.chapter?.id == chapterId) {
                it.copy(chapter = it.chapter.copy(bookmark = bookmarked), bookmarked = bookmarked)
            } else {
                it
            }
        }
        viewModelScope.launchNonCancellable {
            updateChapter.await(ChapterUpdate(id = chapterId, bookmark = bookmarked))
        }
    }

    fun handleDownloadAction(chapter: Chapter, action: ChapterDownloadAction) {
        val manga = manga ?: return
        when (action) {
            ChapterDownloadAction.START -> {
                downloadManager.downloadChapters(manga, listOf(chapter))
                downloadManager.startDownloads()
            }
            ChapterDownloadAction.START_NOW -> downloadManager.startDownloadNow(chapter.id)
            ChapterDownloadAction.CANCEL -> {
                val download = downloadManager.getQueuedDownloadOrNull(chapter.id) ?: return
                downloadManager.cancelQueuedDownloads(listOf(download))
            }
            ChapterDownloadAction.DELETE -> viewModelScope.launchNonCancellable {
                val source = source ?: return@launchNonCancellable
                downloadManager.deleteChapters(listOf(chapter), manga, source, ignoreCategoryExclusion = true)
            }
        }
    }

    fun getSource() = source as? HttpSource

    fun getChapterUrl(): String? {
        val chapter = state.value.chapter ?: return null
        return try {
            getSource()?.getChapterUrl(chapter.toSChapter())
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    // endregion

    @Immutable
    data class State(
        val manga: Manga? = null,
        val chapter: Chapter? = null,
        val document: ChapterDocument? = null,
        val isLoading: Boolean = true,
        val loadError: Throwable? = null,
        val initError: Throwable? = null,
        val previousChapter: Chapter? = null,
        val nextChapter: Chapter? = null,
        val bookmarked: Boolean = false,
        /** Fraction of the chapter's text above the top of the screen. */
        val progress: Float = 0f,
        /** Where the content should scroll to when [restoreToken] changes. */
        val restoreFraction: Float = 0f,
        val restoreToken: Int = 0,
        val menuVisible: Boolean = false,
        val dialog: Dialog? = null,
    )

    sealed interface Dialog {
        data object Settings : Dialog
        data object ChapterList : Dialog
    }

    companion object {
        /** [Chapter.lastPageRead] holds progress in parts per this many. */
        const val PROGRESS_SCALE = 100_000L

        private const val PRELOAD_THRESHOLD = 0.5f
        private const val DOWNLOAD_AHEAD_THRESHOLD = 0.25f
    }
}
