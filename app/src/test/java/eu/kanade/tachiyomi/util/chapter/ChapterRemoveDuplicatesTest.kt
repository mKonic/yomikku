package eu.kanade.tachiyomi.util.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ChapterRemoveDuplicatesTest {

    @Test
    fun `chapters sharing a number are kept when they are not adjacent`() {
        // A source that splits a series into seasons restarts the numbering in each one.
        val seasons = listOf(
            chapter(id = 1, number = 1.0),
            chapter(id = 2, number = 2.0),
            chapter(id = 3, number = 1.0),
            chapter(id = 4, number = 2.0),
        )

        seasons.removeDuplicates(currentChapter = seasons[2]).map { it.id } shouldBe listOf(1L, 2L, 3L, 4L)
    }

    @Test
    fun `chapters with no recognised number are never duplicates`() {
        val oneShots = listOf(
            chapter(id = 1, number = -1.0),
            chapter(id = 2, number = -1.0),
            chapter(id = 3, number = -1.0),
        )

        oneShots.removeDuplicates(currentChapter = oneShots[1]).map { it.id } shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `adjacent duplicates keep the chapter being read`() {
        val chapters = listOf(
            chapter(id = 1, number = 1.0, scanlator = "A"),
            chapter(id = 2, number = 1.0, scanlator = "B"),
            chapter(id = 3, number = 2.0, scanlator = "A"),
        )

        chapters.removeDuplicates(currentChapter = chapters[1]).map { it.id } shouldBe listOf(2L, 3L)
    }

    @Test
    fun `adjacent duplicates prefer the scanlator of the chapter being read`() {
        val current = chapter(id = 10, number = 5.0, scanlator = "B")
        val chapters = listOf(
            chapter(id = 1, number = 6.0, scanlator = "A"),
            chapter(id = 2, number = 6.0, scanlator = "B"),
            chapter(id = 3, number = 6.0, scanlator = "C"),
        )

        chapters.removeDuplicates(currentChapter = current).map { it.id } shouldBe listOf(2L)
    }

    @Test
    fun `adjacent duplicates with no preferred match keep the first`() {
        val current = chapter(id = 10, number = 5.0, scanlator = "Z")
        val chapters = listOf(
            chapter(id = 1, number = 6.0, scanlator = "A"),
            chapter(id = 2, number = 6.0, scanlator = "B"),
        )

        chapters.removeDuplicates(currentChapter = current).map { it.id } shouldBe listOf(1L)
    }

    private fun chapter(id: Long, number: Double, scanlator: String? = null) =
        Chapter.create().copy(id = id, chapterNumber = number, scanlator = scanlator)
}
