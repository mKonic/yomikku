package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.setUserAgent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.DelicateCoroutinesApi
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.i18n.MR
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

abstract class WebViewInterceptor(
    private val context: Context,
    private val defaultUserAgentProvider: () -> String,
) : Interceptor {

    /**
     * When this is called, it initializes the WebView if it wasn't already. We use this to avoid
     * blocking the main thread too much. If used too often we could consider moving it to the
     * Application class.
     */
    private val initWebView by lazy {
        // Crashes on some devices. We skip this in some cases since the only impact is slower
        // WebView init in those rare cases.
        // See https://bugs.chromium.org/p/chromium/issues/detail?id=1279562
        if (DeviceUtil.isMiui || (Build.VERSION.SDK_INT == Build.VERSION_CODES.S && DeviceUtil.isSamsung)) {
            return@lazy
        }

        try {
            WebSettings.getDefaultUserAgent(context)
        } catch (_: Exception) {
            // Avoid some crashes like when Chrome/WebView is being updated.
        }
    }

    // KMK --> one challenge per host at a time (mihonapp/mihon#3858)
    private val hostLocks = HostLocks()
    // KMK <--

    abstract fun shouldIntercept(response: Response): Boolean

    /** The value that changes once the challenge for [url] has been passed, like a clearance cookie. */
    abstract fun getNonce(url: HttpUrl): String?

    open fun isBypassed(url: HttpUrl, oldNonce: String?): Boolean = getNonce(url).let {
        !it.isNullOrBlank() && it != oldNonce
    }

    /**
     * Resolves the challenge in [response]. Returns the response to hand back, or null to send [request] again
     * once the challenge is passed.
     */
    abstract fun intercept(chain: Interceptor.Chain, request: Request, response: Response, nonce: String?): Response?

    @OptIn(DelicateCoroutinesApi::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        return hostLocks.withLock(url.host) { lock ->
            // Requests to the host run side by side; only a challenge takes the host for itself.
            val response = lock.read { chain.proceed(request) }
            if (!shouldIntercept(response)) return@withLock response
            val nonce = getNonce(url)

            // A nested request from inside another one to this host still holds its read lock, and waiting for
            // the write lock there would never return.
            if (lock.readHoldCount > 0) return@withLock resolve(chain, request, response, nonce)

            lock.write {
                if (isBypassed(url, nonce)) {
                    // Another request passed the challenge while this one waited for the lock.
                    response.close()
                    return@write null
                }
                resolve(chain, request, response, nonce)
            }
        } ?: chain.proceed(request)
    }

    private fun resolve(chain: Interceptor.Chain, request: Request, response: Response, nonce: String?): Response? {
        if (!WebViewUtil.supportsWebView(context)) {
            launchUI {
                context.toast(MR.strings.information_webview_required, Toast.LENGTH_LONG)
            }
            return response
        }
        initWebView

        return intercept(chain, request, response, nonce)
    }

    fun CountDownLatch.awaitFor30Seconds() {
        await(30, TimeUnit.SECONDS)
    }

    fun createWebView(request: Request): WebView {
        return WebView(context).apply {
            setDefaultSettings()
            // Avoid sending empty User-Agent, Chromium WebView will reset to default if empty
            setUserAgent(request.header("User-Agent") ?: defaultUserAgentProvider())
        }
    }
}

/**
 * A read-write lock per host. Entries past [MAX_HOSTS] are dropped oldest first, but never while a request holds
 * them: each entry carries a second lock that pins it for as long as it is in use.
 */
private class HostLocks {
    private val entries = object : LinkedHashMap<String, Pair<ReentrantReadWriteLock, ReentrantReadWriteLock>>() {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<ReentrantReadWriteLock, ReentrantReadWriteLock>>,
        ): Boolean {
            if (size > MAX_HOSTS) {
                val pin = eldest.value.second.writeLock()
                if (pin.tryLock()) {
                    try {
                        remove(eldest.key)
                    } finally {
                        pin.unlock()
                    }
                }
            }
            return false
        }
    }

    inline fun <T> withLock(host: String, block: (ReentrantReadWriteLock) -> T): T {
        val (lock, pin) = synchronized(entries) {
            val entry = entries.getOrPut(host) { ReentrantReadWriteLock() to ReentrantReadWriteLock() }
            entry.first to entry.second.readLock().apply { lock() }
        }
        try {
            return block(lock)
        } finally {
            pin.unlock()
        }
    }

    private companion object {
        const val MAX_HOSTS = 256
    }
}
