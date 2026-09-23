package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

/**
 * A source declaring [popularMangaParse] counts as one that can list related manga, because the
 * default [HttpSource.relatedMangaListParse] reads that. Plenty of sources declare one that throws
 * instead, and every manga opened in such a source used to spend a request finding that out again.
 */
class HttpSourceRelatedMangaTest {

    @Test
    fun `a source whose parse refuses is asked once, not once per manga`() = runBlocking<Unit> {
        val source = RefusingSource()

        assertThrows<UnsupportedOperationException> { source.fetchRelatedMangaList(manga()) }
        source.requests.get() shouldBe 1

        source.fetchRelatedMangaList(manga()) shouldBe emptyList()
        source.fetchRelatedMangaList(manga()) shouldBe emptyList()
        source.requests.get() shouldBe 1
    }

    @Test
    fun `a source that parses a related list keeps being asked`() = runBlocking<Unit> {
        val source = ListingSource()

        source.fetchRelatedMangaList(manga()).map { it.title } shouldBe listOf("Related")
        source.fetchRelatedMangaList(manga()).map { it.title } shouldBe listOf("Related")
        source.requests.get() shouldBe 2
    }

    private fun manga() = SManga.create().apply {
        url = "/manga/1"
        title = "Title"
    }

    private abstract class TestSource : HttpSource() {
        val requests = AtomicInteger()

        override val baseUrl = "https://example.test"
        override val name = "Test"
        override val lang = "en"
        override val supportsLatest = false
        override val headers: Headers = Headers.Builder().build()

        override val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("<html></html>".toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()
    }

    /** What a source that has no popular listing looks like: the method is there and it throws. */
    private class RefusingSource : TestSource() {
        @Suppress("DEPRECATION")
        override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    }

    private class ListingSource : TestSource() {
        @Suppress("DEPRECATION")
        override fun popularMangaParse(response: Response): MangasPage = MangasPage(
            mangas = listOf(
                SManga.create().apply {
                    url = "/manga/2"
                    title = "Related"
                },
            ),
            hasNextPage = false,
        )
    }
}
