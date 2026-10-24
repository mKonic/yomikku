package exh.log

import com.elvishew.xlog.XLog
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

// KMK -->
/** Keeps credentials out of logs that users share. Cookies too: E-Hentai and most site logins are cookies. */
fun HttpLoggingInterceptor.redactSensitiveHeaders() {
    redactHeader("Authorization")
    redactHeader("Auth")
    redactHeader("Cookie")
    redactHeader("Set-Cookie")
}
// KMK <--

fun OkHttpClient.Builder.maybeInjectEHLogger(redactHeaders: Boolean): OkHttpClient.Builder {
    // KMK -->
    if (EHLogLevel.isExtremeLogging()) {
        // KMK <--
        val xlogBorder = XLog.tag("||EH-NETWORK-JSON").build()
        val xlogNoBorder = XLog.tag("||EH-NETWORK-JSON").disableBorder().build()
        val logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger { message ->
            try {
                Json.decodeFromString<Any>(message)
                xlogBorder.json(message)
            } catch (ex: Exception) {
                xlogNoBorder.d(message)
            }
        }
        return addInterceptor(
            HttpLoggingInterceptor(logger).apply {
                level = HttpLoggingInterceptor.Level.BODY
                // KMK -->
                if (redactHeaders) redactSensitiveHeaders()
                // KMK <--
            },
        )
    }
    return this
}
