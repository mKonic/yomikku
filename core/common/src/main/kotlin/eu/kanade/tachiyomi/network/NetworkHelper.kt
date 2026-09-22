package eu.kanade.tachiyomi.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import exh.log.EHLogLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import okio.IOException
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection.HTTP_PARTIAL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.random.Random

/* SY --> */ open /* SY <-- */ class NetworkHelper(
    private val context: Context,
    private val preferences: NetworkPreferences,
) {

    /* SY --> */ open /* SY <-- */val cookieJar = AndroidCookieJar()

    // KMK -->
    /**
     * One [Cache] instance shared by every client built here. Two caches over the same directory
     * race each other's journal writers and can wipe it, so this must never be built per client.
     */
    private val cache by lazy {
        Cache(
            directory = File(context.cacheDir, "network_cache"),
            maxSize = 5L * 1024 * 1024, // 5 MiB
        )
    }

    /**
     * Shared so every client draws on one pool of threads and connections.
     *
     * OkHttp allows 5 concurrent requests per host by default, which sits below what the app itself
     * asks for: the reader loads several pages at once and each of those can split into concurrent
     * ranged requests, while the downloader has its own page and source parallelism settings. Left
     * at 5, those knobs quietly cap out here instead of where the user set them.
     */
    private val dispatcher = Dispatcher().apply {
        maxRequestsPerHost = MAX_REQUESTS_PER_HOST
    }

    /**
     * Sources serve their pages from a handful of CDN hosts. The default of 5 idle connections
     * evicts those between pages and pays a fresh TLS handshake on the next one.
     */
    private val connectionPool = SharedConnections.pool

    init {
        SharedConnections.evictStaleOnce(context)
    }

    /**
     * One pool and one set of listeners for the whole process. Every source builds its own
     * NetworkHelper, and registering a network callback per instance ran into Android's per-app
     * limit (TooManyRequestsException) at launch.
     */
    private object SharedConnections {
        val pool = ConnectionPool(
            maxIdleConnections = MAX_IDLE_CONNECTIONS,
            keepAliveDuration = 5,
            timeUnit = TimeUnit.MINUTES,
        )

        private var registered = false

        /**
         * Closing a secure connection sends its close alert, which Android refuses on the main thread
         * (NetworkOnMainThreadException), and the process lifecycle calls back on it.
         */
        private val evictor = Executors.newSingleThreadExecutor { Thread(it, "ConnectionPoolEvictor").apply { isDaemon = true } }

        private fun evictAll() = evictor.execute { pool.evictAll() }

        /**
         * Idle keep-alive connections die while the app is backgrounded or the network changes, and
         * a request handed one hangs until the 5-minute idle reap. Drop them whenever either happens.
         * Every client shares [pool], the DNS-over-HTTPS resolvers included.
         */
        @Synchronized
        fun evictStaleOnce(context: Context) {
            if (registered) return
            registered = true
            val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return
            connectivityManager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    private var wasOnline = false

                    override fun onAvailable(network: Network) = evictAll()

                    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                        val isOnline = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        if (isOnline != wasOnline) {
                            wasOnline = isOnline
                            evictAll()
                        }
                    }

                    override fun onLost(network: Network) = evictAll()

                    override fun onUnavailable() = evictAll()
                },
            )
            // Lifecycle observers may only be added on the main thread, and this can be built off it.
            Handler(Looper.getMainLooper()).post {
                ProcessLifecycleOwner.get().lifecycle.addObserver(
                    LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_START) evictAll()
                    },
                )
            }
        }
    }
    // KMK <--

    /**
     * Timeout in unit of seconds.
     */
    private /* KMK --> */ fun /* KMK <-- */ clientBuilder(
        // KMK -->
        connectTimeout: Long = 30,
        readTimeout: Long = 30,
        callTimeout: Long = 120,
        // KMK <--
    ): OkHttpClient.Builder = run {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            // KMK -->
            .connectTimeout(connectTimeout, TimeUnit.SECONDS)
            .readTimeout(readTimeout, TimeUnit.SECONDS)
            .callTimeout(callTimeout, TimeUnit.SECONDS)
            .dispatcher(dispatcher)
            .connectionPool(connectionPool)
            .cache(cache)
            // KMK <--
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))

        // KMK -->
        if (EHLogLevel.isExtraLogging()) {
            // KMK <--
            val httpLoggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            }
            builder.addNetworkInterceptor(httpLoggingInterceptor)
        }

        when (preferences.dohProvider().get()) {
            PREF_DOH_CLOUDFLARE -> builder.dohCloudflare()
            PREF_DOH_GOOGLE -> builder.dohGoogle()
            PREF_DOH_ADGUARD -> builder.dohAdGuard()
            PREF_DOH_QUAD9 -> builder.dohQuad9()
            PREF_DOH_ALIDNS -> builder.dohAliDNS()
            PREF_DOH_DNSPOD -> builder.dohDNSPod()
            PREF_DOH_360 -> builder.doh360()
            PREF_DOH_QUAD101 -> builder.dohQuad101()
            PREF_DOH_MULLVAD -> builder.dohMullvad()
            PREF_DOH_CONTROLD -> builder.dohControlD()
            PREF_DOH_NJALLA -> builder.dohNajalla()
            PREF_DOH_SHECAN -> builder.dohShecan()
            else -> builder
        }
    }

    /* SY --> */ open /* SY <-- */ val client /* KMK --> */ by lazy /* KMK <-- */ {
        clientBuilder()
            .addInterceptor(
                CloudflareInterceptor(context, cookieJar, ::defaultUserAgentProvider),
            )
            .build()
    }

    // KMK -->
    /**
     * Derived from [client] so the connection pool, dispatcher, cache and DNS settings are shared
     * rather than standing up a second stack alongside it.
     *
     * Timeout in unit of seconds. A [callTimeout] of 0 disables the whole-call deadline.
     */
    private fun clientWithTimeOut(
        connectTimeout: Long = 30,
        readTimeout: Long = 30,
        callTimeout: Long = 120,
    ) = client.newBuilder()
        .connectTimeout(connectTimeout, TimeUnit.SECONDS)
        .readTimeout(readTimeout, TimeUnit.SECONDS)
        .callTimeout(callTimeout, TimeUnit.SECONDS)
        .build()

    /**
     * Allow to download a big file with retry & resume capability because
     * normally it would get a Timeout exception.
     */
    suspend fun downloadFileWithResume(
        url: String,
        outputFile: File,
        progressListener: ProgressListener,
    ) = withIOContext {
        // No whole-call deadline: this is an entire APK, and a total-call timeout kills a healthy
        // transfer for being slow. A stall is still caught by the read timeout, and cancelling the
        // caller now aborts the request outright instead of leaving it to run to completion.
        val client = clientWithTimeOut(callTimeout = 0)

        var attempt = 0
        var totalAttempts = 0

        while (attempt < MAX_RETRY && totalAttempts < MAX_TOTAL_ATTEMPTS) {
            ensureActive()
            totalAttempts++

            // Resume from whatever earlier attempts already wrote.
            val downloadedBytes = outputFile.length()
            val request = GET(
                url = url,
                headers = Headers.Builder()
                    .add("Range", "bytes=$downloadedBytes-")
                    .build(),
            )

            try {
                client.newCachelessCallWithProgress(request, progressListener).await().use { response ->
                    when {
                        // The range was honoured, so what is already on disk still stands.
                        response.code == HTTP_PARTIAL -> {
                            saveResponseToFile(response, outputFile, downloadedBytes)
                            return@withIOContext
                        }
                        // The server ignored the range and sent the whole file. Writing that at the
                        // resume offset would duplicate the bytes already on disk, so restart at 0.
                        response.isSuccessful -> {
                            saveResponseToFile(response, outputFile, 0)
                            return@withIOContext
                        }
                        // What is on disk is no shorter than the file itself. Drop it and retry.
                        response.code == HTTP_RANGE_NOT_SATISFIABLE -> {
                            outputFile.delete()
                        }
                        else -> {
                            logcat(LogPriority.ERROR) { "Unexpected response code: ${response.code}. Retrying..." }
                        }
                    }
                }
            } catch (e: IOException) {
                // A cancelled call surfaces here as a plain IOException, so check before retrying.
                ensureActive()
                logcat(LogPriority.ERROR) { "Download interrupted: ${e.message}. Retrying..." }
            }

            if (outputFile.length() > downloadedBytes) {
                // The attempt still gained ground, so don't spend the retry budget on it: a long
                // download over a flaky link should keep going for as long as it keeps moving.
                attempt = 0
            } else {
                attempt++
                exponentialBackoff(attempt - 1)
            }
        }
        throw IOException("Max retry attempts reached.")
    }

    // Helper function to save data incrementally
    private fun saveResponseToFile(response: Response, outputFile: File, startPosition: Long) {
        val body = response.body

        // Use RandomAccessFile to write from specific position
        RandomAccessFile(outputFile, "rw").use { file ->
            // Drop anything past the resume point before writing. On a restart, or on an attempt
            // that turns out shorter than an earlier one, a leftover tail would otherwise survive
            // underneath what we write and be counted as downloaded by the next resume.
            file.setLength(startPosition)
            file.seek(startPosition)
            body.byteStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    file.write(buffer, 0, bytesRead)
                }
            }
        }
    }

    // Increment attempt and apply exponential backoff
    private suspend fun exponentialBackoff(attempt: Int) {
        delay(calculateExponentialBackoff(attempt))
    }

    // Helper function to calculate exponential backoff with jitter
    private fun calculateExponentialBackoff(attempt: Int, baseDelay: Long = 1000L, maxDelay: Long = 32000L): Long {
        // Calculate the exponential delay
        val delay = baseDelay * 2.0.pow(attempt).toLong()
        logcat(LogPriority.ERROR) { "Exponential backoff delay: $delay ms" }
        // Apply jitter by adding a random value to avoid synchronized retries in distributed systems
        return (delay + Random.nextLong(0, 1000)).coerceAtMost(maxDelay)
    }
    // KMK <--

    /**
     * @deprecated Since extension-lib 1.5
     */
    @Deprecated("The regular client handles Cloudflare by default", ReplaceWith("client"))
    @Suppress("UNUSED")
    /* SY --> */
    open /* SY <-- */val cloudflareClient: OkHttpClient
        get() = client

    fun defaultUserAgentProvider() = preferences.defaultUserAgent().get().trim()

    companion object {
        // KMK -->
        /** Consecutive attempts that gained no ground. */
        private const val MAX_RETRY = 5

        /** Bounds a download that keeps inching forward without ever finishing. */
        private const val MAX_TOTAL_ATTEMPTS = 50

        /**
         * Room for the reader's concurrent pages at several ranged requests each, plus headroom for
         * a download running against the same host. Sources that want less still say so through
         * their own rate limit interceptor.
         */
        private const val MAX_REQUESTS_PER_HOST = 12

        private const val MAX_IDLE_CONNECTIONS = 16

        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        // KMK <--
    }
}
