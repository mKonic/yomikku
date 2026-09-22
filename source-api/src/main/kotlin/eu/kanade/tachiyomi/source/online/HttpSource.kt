package eu.kanade.tachiyomi.source.online

import android.app.Application
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import exh.log.maybeInjectEHLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

/**
 * A novel source reached over HTTP.
 *
 * Every public operation is a suspend function. The request/parse pairs below are the usual way to implement one:
 * override the request builder and the parser, and the default implementation runs the call. A source whose site
 * does not fit that shape (paginated chapter lists, JSON APIs that need several calls) overrides the suspend
 * function itself instead.
 */
@Suppress("unused")
abstract class HttpSource : CatalogueSource {

    /**
     * Network service. Each source gets its own [NetworkHelper] view over the shared client, so cookies and the
     * connection pool are shared with the rest of the app.
     */
    protected val network: NetworkHelper by lazy {
        val network = Injekt.get<NetworkHelper>()
        object : NetworkHelper(Injekt.get<Application>(), Injekt.get()) {
            override val client: OkHttpClient
                get() = network.client
                    .newBuilder()
                    .maybeInjectEHLogger()
                    .build()

            @Deprecated("The regular client handles Cloudflare by default")
            override val cloudflareClient: OkHttpClient
                get() = client

            override val cookieJar: AndroidCookieJar
                get() = network.cookieJar
        }
    }

    /**
     * Base url of the website without the trailing slash, like: https://example.com
     */
    abstract val baseUrl: String

    /**
     * The URL opened when the user taps "Open in WebView" on the browse screen. Defaults to [baseUrl].
     */
    open fun getHomeUrl(): String = baseUrl

    /**
     * Version id used to generate the source id. Increase it if the site changes so much that stored URLs no
     * longer work, and the source will be treated as a new one.
     */
    open val versionId: Int = 1

    /**
     * ID of the source: the first 64 bits of the MD5 of `"${name.lowercase()}/$lang/$versionId"`, sign bit cleared.
     */
    override val id: Long by lazy { generateId(name, lang, versionId) }

    /**
     * Headers used for requests.
     */
    open val headers: Headers by lazy { headersBuilder().build() }

    /**
     * Default network client for requests.
     */
    open val client: OkHttpClient
        get() = network.client

    protected fun generateId(name: String, lang: String, versionId: Int): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    /**
     * Headers builder for requests. Implementations can override this method for custom headers.
     */
    protected open fun headersBuilder(): Headers.Builder = Headers.Builder().apply {
        add("User-Agent", network.defaultUserAgentProvider())
    }

    override fun toString(): String = "$name (${lang.uppercase()})"

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage {
        return client.newCall(popularMangaRequest(page)).awaitSuccess().use(::popularMangaParse)
    }

    protected open fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()

    protected open fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        return client.newCall(latestUpdatesRequest(page)).awaitSuccess().use(::latestUpdatesParse)
    }

    protected open fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    protected open fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        return client.newCall(searchMangaRequest(page, query, filters)).awaitSuccess().use(::searchMangaParse)
    }

    protected open fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request = throw UnsupportedOperationException()

    protected open fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // Details and chapters

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = supervisorScope {
        val details = if (fetchDetails) async { getMangaDetails(manga) } else null
        val chapterList = if (fetchChapters) async { getChapterList(manga) } else null
        SMangaUpdate(details?.await() ?: manga, chapterList?.await() ?: chapters)
    }

    /**
     * The novel's details. Called by [getMangaUpdate] when details are requested.
     */
    open suspend fun getMangaDetails(manga: SManga): SManga {
        return client.newCall(mangaDetailsRequest(manga)).awaitSuccess()
            .use(::mangaDetailsParse)
            .apply { initialized = true }
    }

    open fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    protected open fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException()

    /**
     * Every chapter of the novel, newest first. Called by [getMangaUpdate] when chapters are requested. Sites that
     * spread the list over several pages override this and fetch them all.
     */
    open suspend fun getChapterList(manga: SManga): List<SChapter> {
        return client.newCall(chapterListRequest(manga)).awaitSuccess().use(::chapterListParse)
    }

    protected open fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    protected open fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    // Chapter text

    override suspend fun getChapterText(chapter: SChapter): String {
        return client.newCall(chapterTextRequest(chapter)).awaitSuccess().use(::chapterTextParse)
    }

    protected open fun chapterTextRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    /**
     * The chapter body as HTML. See [eu.kanade.tachiyomi.source.Source.getChapterText] for what is expected.
     */
    protected open fun chapterTextParse(response: Response): String = throw UnsupportedOperationException()

    // KMK -->

    override val supportsRelatedMangas: Boolean get() = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        if (!isRelatedMangaListParseAvailable || relatedMangaListParseRefused) return emptyList()

        return try {
            client.newCall(relatedMangaListRequest(manga))
                .awaitSuccess()
                .use { response ->
                    relatedMangaListParse(response)
                }
        } catch (e: UnsupportedOperationException) {
            // Declaring popularMangaParse is not the same as implementing it: plenty of sources declare one
            // that throws. Every novel opened in such a source spent a request on the same refusal, and on a
            // rate limited site those requests are taken from the ones the reader needs.
            relatedMangaListParseRefused = true
            throw e
        }
    }

    @Volatile
    private var relatedMangaListParseRefused = false

    /**
     * Whether this source (or a superclass other than [HttpSource]) declares a parser the related list can use.
     */
    private val isRelatedMangaListParseAvailable by lazy(LazyThreadSafetyMode.NONE) {
        try {
            var clazz: Class<*>? = javaClass
            while (clazz != null && clazz != HttpSource::class.java) {
                if (clazz.declaredMethods.any { it.name == "relatedMangaListParse" || it.name == "popularMangaParse" }) {
                    return@lazy true
                }
                clazz = clazz.superclass
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    protected open fun relatedMangaListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    protected open fun relatedMangaListParse(response: Response): List<SManga> = popularMangaParse(response).mangas
    // KMK <--

    // URLs

    /**
     * Assigns the url of the chapter without the scheme and domain, so a domain change does not break it.
     */
    fun SChapter.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    /**
     * Assigns the url of the novel without the scheme and domain, so a domain change does not break it.
     */
    fun SManga.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    private fun getUrlWithoutDomain(orig: String): String {
        return try {
            val uri = URI(orig.replace(" ", "%20"))
            var out = uri.path
            if (uri.query != null) {
                out += "?" + uri.query
            }
            if (uri.fragment != null) {
                out += "#" + uri.fragment
            }
            out
        } catch (_: URISyntaxException) {
            orig
        }
    }

    /**
     * The URL of the novel's page on the site, for WebView and sharing.
     */
    open fun getMangaUrl(manga: SManga): String = mangaDetailsRequest(manga).url.toString()

    /**
     * The URL of the chapter's page on the site, for WebView and sharing.
     */
    open fun getChapterUrl(chapter: SChapter): String = chapterTextRequest(chapter).url.toString()
}
