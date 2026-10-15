package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.util.system.isOutdated
import eu.kanade.tachiyomi.util.system.toast
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.IOException
import java.util.concurrent.CountDownLatch

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)

    private val listenerScript = """
        addEventListener("message", ({data}) => {
            if (data?.source === "cloudflare-challenge") {
                $BRIDGE?.postMessage(data.event);
            }
        })
    """.trimIndent()

    override fun shouldIntercept(response: Response): Boolean {
        // Check if Cloudflare anti-bot is on
        // Checking the cf-mitigated header is the official way to detect a Cloudflare challenge:
        // https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/detect-response/
        return response.header("cf-mitigated") == "challenge" && response.header("Server") in SERVER_CHECK
    }

    override fun getNonce(url: HttpUrl): String? = cookieManager.get(url).firstOrNull {
        it.name == "cf_clearance"
    }?.value

    override fun intercept(
        chain: Interceptor.Chain,
        request: Request,
        response: Response,
        nonce: String?,
    ): Response? {
        try {
            response.close()
            cookieManager.remove(request.url, COOKIE_NAMES, 0)
            resolveWithWebView(request, nonce)
            return null
        }
        // Because OkHttp's enqueue only handles IOExceptions, wrap the exception so that
        // we don't crash the entire app
        catch (e: CloudflareBypassException) {
            throw IOException(context.stringResource(MR.strings.information_cloudflare_bypass_failure), e)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(originalRequest: Request, originalNonce: String?) {
        // We need to lock this thread until the WebView finds the challenge solution url, because
        // OkHttp doesn't support asynchronous interceptors.
        val latch = CountDownLatch(1)

        var webview: WebView? = null

        var challengeFound = false
        var cloudflareBypassed = false
        var failed = false
        var isWebViewOutdated = false
        var isRenderProcessGone = false

        var listenerScriptHandler: ScriptHandler? = null
        // Read once on the main thread, where the WebView APIs below run.
        var isolatedWorld = false

        val challengeUrl = if (originalRequest.method == "GET") {
            originalRequest.url.toString()
        } else {
            // WebView loads with GET; a POST endpoint may not serve a challenge on GET.
            originalRequest.url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
        }
        val headers = parseHeaders(originalRequest.headers)

        executor.execute {
            webview = createWebView(originalRequest)

            with(webview) {
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }

            fun handleEvent(event: String) {
                when (event) {
                    // The challenge cannot be solved non-interactively, abort.
                    "interactiveBegin" -> latch.countDown()
                    "complete" -> failed = false
                    "fail" -> {
                        failed = true
                        latch.countDown()
                    }
                }
            }

            isolatedWorld = WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)
            if (isolatedWorld) {
                // An isolated world keeps the page from seeing the bridge.
                val world = WebViewCompat.getExecutionWorld(webview, BRIDGE)
                val allowedOrigins = setOf("${originalRequest.url.scheme}://${originalRequest.url.host}")

                WebViewCompat.addWebMessageListener(webview, BRIDGE, allowedOrigins, world) {
                        _,
                        message,
                        _,
                        isMainFrame,
                        _,
                    ->
                    if (isMainFrame) message.data?.let(::handleEvent)
                }
                listenerScriptHandler = WebViewCompat.addJavaScriptOnEvent(
                    webview,
                    listenerScript,
                    WebViewCompat.INJECTION_EVENT_DOCUMENT_START,
                    allowedOrigins,
                    world,
                )
            } else {
                webview.addJavascriptInterface(
                    object {
                        @Suppress("unused")
                        @JavascriptInterface
                        fun postMessage(event: String) = handleEvent(event)
                    },
                    BRIDGE,
                )
            }

            webview.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (!failed && isBypassed(originalRequest.url, originalNonce)) {
                        cloudflareBypassed = true
                        latch.countDown()
                    }

                    if (url == challengeUrl) {
                        if (!challengeFound) {
                            // The first request didn't return the challenge, abort.
                            latch.countDown()
                        } else if (!isolatedWorld) {
                            // Listen for challenge events
                            view.evaluateJavascript(listenerScript, null)
                        }
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame == true) {
                        if (errorResponse?.responseHeaders["cf-mitigated"] == "challenge") {
                            // Found the Cloudflare challenge page.
                            challengeFound = true
                        } else {
                            // Unlock thread, the challenge wasn't found.
                            latch.countDown()
                        }
                    }
                }

                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    // The renderer died; this WebView is unusable. Returning true keeps the app alive.
                    isRenderProcessGone = true
                    latch.countDown()
                    return true
                }
            }

            webview.loadUrl(challengeUrl, headers)
        }

        latch.awaitFor30Seconds()

        executor.execute {
            if (!cloudflareBypassed && !isRenderProcessGone) {
                isWebViewOutdated = webview?.isOutdated() == true
            }

            webview?.let { view ->
                if (!isRenderProcessGone) {
                    if (isolatedWorld) {
                        WebViewCompat.removeWebMessageListener(view, WebViewCompat.getExecutionWorld(view, BRIDGE), BRIDGE)
                    } else {
                        view.removeJavascriptInterface(BRIDGE)
                    }
                    listenerScriptHandler?.remove()
                    view.stopLoading()
                }
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
        }

        // Throw exception if we failed to bypass Cloudflare
        if (!cloudflareBypassed) {
            // A failed attempt can leave a clearance cookie the site no longer accepts.
            cookieManager.remove(originalRequest.url, COOKIE_NAMES, 0)
            // Prompt user to update WebView if it seems too outdated
            if (isWebViewOutdated) {
                context.toast(MR.strings.information_webview_outdated, Toast.LENGTH_LONG)
            }

            throw CloudflareBypassException()
        }
    }
}

private const val BRIDGE = "komikku"
private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
private val COOKIE_NAMES = listOf("cf_clearance")

private class CloudflareBypassException : Exception()
