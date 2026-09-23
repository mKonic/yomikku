package eu.kanade.tachiyomi.data.download

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Makes a downloaded chapter readable offline by embedding its remote images as data URIs, so the chapter stays a
 * single file and the reader needs nothing but that file.
 */
internal object ChapterImages {

    /** Images larger than this keep their remote address; a chapter should not grow without bound. */
    private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024

    /**
     * [html] with every remote image fetched through [source]'s client and headers and inlined. An image that fails
     * to download keeps its address, so the chapter still shows it when online.
     */
    suspend fun embed(html: String, source: Source): String {
        val document = Jsoup.parseBodyFragment(html, (source as? HttpSource)?.baseUrl.orEmpty())
        val images = document.select("img[src]").filter { it.absUrl("src").startsWith("http") }
        if (images.isEmpty()) return html

        val client: OkHttpClient = (source as? HttpSource)?.client ?: Injekt.get<NetworkHelper>().client
        val headers: Headers = (source as? HttpSource)?.headers ?: Headers.headersOf()
        val fetched = mutableMapOf<String, String?>()
        images.forEach { image ->
            val url = image.absUrl("src")
            val dataUri = fetched.getOrPut(url) { download(client, url, headers) } ?: return@forEach
            image.attr("src", dataUri)
            image.removeAttr("srcset")
        }
        return document.body().html()
    }

    private suspend fun download(client: OkHttpClient, url: String, headers: Headers): String? = try {
        client.newCall(GET(url, headers)).await().use { response ->
            val body = response.body
            val type = body.contentType()
            when {
                !response.isSuccessful -> null
                type == null || type.type != "image" -> null
                body.contentLength() > MAX_IMAGE_BYTES -> null
                else -> {
                    val bytes = body.bytes()
                    if (bytes.size > MAX_IMAGE_BYTES) {
                        null
                    } else {
                        "data:${type.type}/${type.subtype};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "Could not download chapter image $url" }
        null
    }
}
