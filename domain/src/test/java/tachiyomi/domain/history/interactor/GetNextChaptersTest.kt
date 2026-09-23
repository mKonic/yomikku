package tachiyomi.domain.history.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga

class GetNextChaptersTest {

    // Sorted by source order, which runs newest first: the oldest chapter has the highest number.
    private val manga = Manga.create().copy(id = MANGA_ID, source = SOURCE_ID)
    private val oldest = chapter(id = 1, sourceOrder = 2)
    private val middle = chapter(id = 2, sourceOrder = 1)
    private val newest = chapter(id = 3, sourceOrder = 0)

    @Test
    fun `resumes the chapter itself while it is unread`() = runTest {
        val chapters = listOf(newest, middle, oldest.copy(read = true))

        interactor(chapters).awaitResumeTarget(MANGA_ID, middle.id) shouldBe middle
    }

    @Test
    fun `resumes the following chapter once the current one is read`() = runTest {
        val readMiddle = middle.copy(read = true)
        val chapters = listOf(newest, readMiddle, oldest.copy(read = true))

        interactor(chapters).awaitResumeTarget(MANGA_ID, readMiddle.id) shouldBe newest
    }

    @Test
    fun `falls back to the first chapter when nothing is left to read`() = runTest {
        val chapters = listOf(newest, middle, oldest).map { it.copy(read = true) }

        interactor(chapters).awaitResumeTarget(MANGA_ID, newest.id) shouldBe oldest.copy(read = true)
    }

    @Test
    fun `opens nothing for an entry without chapters`() = runTest {
        interactor(emptyList()).awaitResumeTarget(MANGA_ID, newest.id) shouldBe null
    }

    @Test
    fun `resumes the entry read most recently`() = runTest {
        val history = mockk<HistoryWithRelations> {
            every { mangaId } returns MANGA_ID
            every { chapterId } returns newest.id
        }
        val chapters = listOf(newest, middle, oldest).map { it.copy(read = true) }

        interactor(chapters, history).awaitResumeTarget() shouldBe oldest.copy(read = true)
    }

    private fun interactor(chapters: List<Chapter>, history: HistoryWithRelations? = null): GetNextChapters {
        val getChaptersByMangaId = mockk<GetChaptersByMangaId>()
        coEvery { getChaptersByMangaId.await(MANGA_ID, any()) } returns chapters
        val getManga = mockk<GetManga>()
        coEvery { getManga.await(MANGA_ID) } returns manga
        val historyRepository = mockk<HistoryRepository>()
        coEvery { historyRepository.getLastHistory() } returns history
        return GetNextChapters(getChaptersByMangaId, getManga, historyRepository)
    }

    private fun chapter(id: Long, sourceOrder: Long) =
        Chapter.create().copy(id = id, mangaId = MANGA_ID, sourceOrder = sourceOrder)

    private companion object {
        const val MANGA_ID = 10L
        const val SOURCE_ID = 1L
    }
}
