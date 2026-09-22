package mihon.telemetry

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

object TelemetryConfig {
    private var analytics: FirebaseAnalytics? = null
    private var crashlytics: FirebaseCrashlytics? = null

    fun init(context: Context, isPreviewBuildType: Boolean, commitCount: String) {
        // To stop forks/test builds from polluting our data
        if (!context.isMihonProductionApp()) return

        FirebaseApp.initializeApp(context)
        analytics = FirebaseAnalytics.getInstance(context)
        crashlytics = FirebaseCrashlytics.getInstance()
        // KMK -->
        // The commit count rides on the crash report itself rather than on an analytics user property, so a
        // preview crash still says which build it came from when analytics is switched off.
        if (isPreviewBuildType) {
            crashlytics?.setCustomKey("preview_version", commitCount)
        }
        // KMK <--
    }

    fun setAnalyticsEnabled(enabled: Boolean) {
        analytics?.setAnalyticsCollectionEnabled(enabled)
    }

    fun setCrashlyticsEnabled(enabled: Boolean) {
        crashlytics?.isCrashlyticsCollectionEnabled = enabled
    }

    private fun Context.isMihonProductionApp(): Boolean {
        if (packageName !in MIHON_PACKAGES) return false

        return packageManager.getPackageInfo(packageName, SignatureFlags)
            .getCertificateFingerprints()
            .any { it == MIHON_CERTIFICATE_FINGERPRINT }
    }
}

private val MIHON_PACKAGES = hashSetOf("app.yomikku", "app.yomikku.beta")

// KMK: yomikku's release key (alias yomikku), which signs both release and CI preview builds
private const val MIHON_CERTIFICATE_FINGERPRINT =
    "BF:FF:55:08:C4:07:C4:A5:FC:F8:7C:21:CE:C7:6E:A5:44:1D:C0:D6:F3:86:CF:BE:11:57:92:F8:A8:B4:07:C3"
