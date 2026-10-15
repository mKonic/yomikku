package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga

class SyncChaptersWithSourceTest {

    private lateinit var downloadManager: DownloadManager
    private lateinit var chapterRepository: ChapterRepository
    private lateinit var getChaptersByMangaId: GetChaptersByMangaId
    private lateinit var syncChaptersWithSource: SyncChaptersWithSource

    // Not a favorite, so no custom manga info is looked up through Injekt.
    private val manga = Manga.create().copy(
        id = MANGA_ID,
        source = SOURCE_ID,
        ogTitle = "Test Manga",
        fetchInterval = 1,
        nextUpdate = Long.MAX_VALUE,
    )
    private val source = mockk<Source> {
        every { id } returns SOURCE_ID
    }
    private val presentChapter = chapter(id = 1, url = "/chapter-1", name = "Chapter 1", number = 1.0, sourceOrder = 0)
    private val removedChapter = chapter(id = 2, url = "/chapter-2", name = "Chapter 2", number = 2.0, sourceOrder = 1)
    private val sourceChapters = listOf(
        sourceChapter(
            url = presentChapter.url,
            name = presentChapter.name,
            number = presentChapter.chapterNumber.toFloat(),
        ),
    )

    @BeforeEach
    fun setUp() {
        downloadManager = mockk(relaxed = true)
        chapterRepository = mockk(relaxed = true)
        coEvery { chapterRepository.updateFromRemote(any(), any(), any()) } returns emptyList()
        getChaptersByMangaId = mockk()

        val libraryPreferences = mockk<LibraryPreferences>()
        every { libraryPreferences.markDuplicateReadChapterAsRead().get() } returns emptySet()

        val getExcludedScanlators = mockk<GetExcludedScanlators>()
        coEvery { getExcludedScanlators.await(any()) } returns emptySet()

        every { downloadManager.getQueuedDownloadOrNull(any()) } returns null
        every {
            downloadManager.isChapterDownloaded(any(), any(), any(), any(), any(), any())
        } returns false
        coEvery { getChaptersByMangaId.await(MANGA_ID) } returns listOf(presentChapter, removedChapter)

        syncChaptersWithSource = SyncChaptersWithSource(
            downloadManager = downloadManager,
            downloadProvider = mockk<DownloadProvider>(relaxed = true),
            chapterRepository = chapterRepository,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = mockk<UpdateManga>(relaxed = true),
            getChaptersByMangaId = getChaptersByMangaId,
            getExcludedScanlators = getExcludedScanlators,
            libraryPreferences = libraryPreferences,
        )
    }

    @Test
    fun `keeps a downloaded chapter that is missing from the source`() = runTest {
        every {
            downloadManager.isChapterDownloaded(
                removedChapter.name,
                removedChapter.scanlator,
                removedChapter.url,
                manga.ogTitle,
                manga.source,
                false,
            )
        } returns true

        syncChaptersWithSource.await(sourceChapters, manga, source)

        coVerify(exactly = 0) { chapterRepository.updateFromRemote(any(), any(), any()) }
        verify(exactly = 0) { downloadManager.cancelQueuedDownloads(any()) }
    }

    @Test
    fun `cancels a queued chapter before removing it when it is missing from the source`() = runTest {
        val download = mockk<Download>()
        every { downloadManager.getQueuedDownloadOrNull(removedChapter.id) } returns download

        syncChaptersWithSource.await(sourceChapters, manga, source)

        coVerifyOrder {
            downloadManager.cancelQueuedDownloads(listOf(download))
            chapterRepository.updateFromRemote(listOf(removedChapter.id), emptyList(), emptyList())
        }
    }

    @Test
    fun `removes a missing chapter that is neither downloaded nor queued`() = runTest {
        syncChaptersWithSource.await(sourceChapters, manga, source)

        coVerify { chapterRepository.updateFromRemote(listOf(removedChapter.id), emptyList(), emptyList()) }
        verify(exactly = 0) { downloadManager.cancelQueuedDownloads(any()) }
    }

    @Test
    fun `keeps downloaded chapters while cancelling and removing queued ones in the same sync`() = runTest {
        val queuedChapter = chapter(id = 3, url = "/chapter-3", name = "Chapter 3", number = 3.0, sourceOrder = 2)
        val queuedDownload = mockk<Download>()
        coEvery { getChaptersByMangaId.await(MANGA_ID) } returns listOf(presentChapter, removedChapter, queuedChapter)
        every { downloadManager.getQueuedDownloadOrNull(queuedChapter.id) } returns queuedDownload
        every {
            downloadManager.isChapterDownloaded(
                removedChapter.name,
                removedChapter.scanlator,
                removedChapter.url,
                manga.ogTitle,
                manga.source,
                false,
            )
        } returns true

        syncChaptersWithSource.await(sourceChapters, manga, source)

        coVerifyOrder {
            downloadManager.cancelQueuedDownloads(listOf(queuedDownload))
            chapterRepository.updateFromRemote(listOf(queuedChapter.id), emptyList(), emptyList())
        }
    }

    @Test
    fun `applies additions removals and updates in one repository call`() = runTest {
        val chapters = listOf(
            sourceChapter(presentChapter.url, "Chapter 1 (updated)", 1f),
            sourceChapter("/chapter-3", "Chapter 3", 3f),
        )

        syncChaptersWithSource.await(chapters, manga, source)

        coVerify(exactly = 1) {
            chapterRepository.updateFromRemote(
                listOf(removedChapter.id),
                match { it.single().url == "/chapter-3" },
                match { it.single().id == presentChapter.id && it.single().name == "Chapter 1 (updated)" },
            )
        }
    }

    private companion object {
        const val MANGA_ID = 1L
        const val SOURCE_ID = 2L

        fun chapter(id: Long, url: String, name: String, number: Double, sourceOrder: Long) =
            Chapter.create().copy(
                id = id,
                mangaId = MANGA_ID,
                url = url,
                name = name,
                chapterNumber = number,
                dateUpload = 1_000,
                sourceOrder = sourceOrder,
            )

        fun sourceChapter(url: String, name: String, number: Float) = SChapter.create().apply {
            this.url = url
            this.name = name
            chapter_number = number
            date_upload = 1_000
        }
    }
}
