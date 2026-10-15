package eu.kanade.tachiyomi.extension.model

import android.graphics.drawable.Drawable
import eu.kanade.tachiyomi.source.Source
import mihon.domain.extension.model.ExtensionStore
import tachiyomi.domain.source.model.StubSource

sealed class Extension {

    abstract val name: String
    abstract val pkgName: String
    abstract val versionName: String
    abstract val versionCode: Long
    abstract val libVersion: Double
    abstract val lang: String?
    abstract val isNsfw: Boolean

    // KMK -->
    abstract val signatureHash: String
    abstract val storeName: String?
    // KMK <--

    /** An installed APK can have an update even when its sources cannot be loaded. */
    sealed class OnDevice : Extension() {
        abstract val signatures: List<String>
        abstract val isShared: Boolean
        abstract val hasUpdate: Boolean
        abstract val store: ExtensionStore?
    }

    data class Installed(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val lang: String,
        override val isNsfw: Boolean,
        // KMK -->
        override val signatureHash: String,
        /** Guessing store name from built-in signatures preset */
        override val storeName: String? = null,
        // KMK <--
        val pkgFactory: String?,
        val sources: List<Source>,
        val icon: Drawable?,
        override val hasUpdate: Boolean = false,
        val isObsolete: Boolean = false,
        override val isShared: Boolean,
        override val store: ExtensionStore? = null,
        // SY -->
        val isRedundant: Boolean = false,
        // SY <--
        override val signatures: List<String> = listOf(signatureHash),
    ) : OnDevice()

    data class Available(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val lang: String,
        override val isNsfw: Boolean,
        // KMK -->
        override val signatureHash: String,
        override val storeName: String,
        // KMK <--
        val sources: List<Source>,
        val apkUrl: String,
        val iconUrl: String,
        val store: ExtensionStore,
    ) : Extension() {

        data class Source(
            val id: Long,
            val lang: String,
            val name: String,
            val baseUrl: String,
        ) {
            fun toStubSource(): StubSource {
                return StubSource(
                    id = this.id,
                    lang = this.lang,
                    name = this.name,
                )
            }
        }
    }

    sealed class NotLoaded : OnDevice()

    data class Untrusted(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        /* KMK --> */
        override /* KMK <-- */ val signatureHash: String,
        // KMK -->
        override val storeName: String? = null,
        // KMK <--
        override val lang: String? = null,
        override val isNsfw: Boolean = false,
        override val isShared: Boolean = true,
        override val signatures: List<String> = listOf(signatureHash),
        override val store: ExtensionStore? = null,
        override val hasUpdate: Boolean = false,
    ) : NotLoaded()

    data class Failed(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val signatureHash: String,
        override val isShared: Boolean,
        val reason: String,
        override val storeName: String? = null,
        override val lang: String? = null,
        override val isNsfw: Boolean = false,
        override val signatures: List<String> = listOf(signatureHash),
        override val store: ExtensionStore? = null,
        override val hasUpdate: Boolean = false,
    ) : NotLoaded()
}

/** A store listing is relevant only when its signing key belongs to the installed APK. */
fun Extension.OnDevice.findListing(available: Iterable<Extension.Available>): Extension.Available? =
    available.asSequence()
        .filter { listing ->
            listing.pkgName == pkgName &&
                listing.signatureHash == listing.store.signingKey &&
                listing.store.signingKey in signatures
        }
        .maxWithOrNull(compareBy<Extension.Available> { it.versionCode }.thenBy { it.libVersion })

fun Extension.OnDevice.findUpdate(available: Iterable<Extension.Available>): Extension.Available? =
    findListing(available)?.takeIf {
        it.versionCode >= versionCode &&
            (it.versionCode > versionCode || (libVersion > 0 && it.libVersion > libVersion))
    }
