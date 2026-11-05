package mihon.domain.extension.model

data class ExtensionStore(
    val indexUrl: String,
    val name: String,
    val badgeLabel: String,
    val signingKey: String,
    val contact: Contact,
    val isLegacy: Boolean,
    val extensionListUrl: String?,
) {
    data class Contact(
        val website: String,
        val discord: String?,
    )
}

const val REPO_HELP = "https://github.com/mKonic/yomikku-extensions"

// The store mKonic/yomikku-extensions publishes. Added once on first use so the app works out of the box.
const val YOMIKKU_STORE_URL = "https://raw.githubusercontent.com/mKonic/yomikku-extensions/repo/index.min.json"

// The key mKonic/yomikku-extensions signs its APKs with. Extensions it signed are trusted without asking.
const val YOMIKKU_SIGNATURE = "34378863c3c0f4ce1afdbff10539450877733758f64832c07b8623e0f058dda8"
const val REPO_SIGNATURE = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"

// KMK -->
/** Signing key of a store migrated from the old repo preference, until its first refresh fills in the real one. */
const val NO_SIGNING_KEY = "NO_SIGNING_KEY"

/** Short name shown next to extensions, e.g. "@KEI". */
val ExtensionStore.label: String
    get() = badgeLabel.ifBlank { name }

/**
 * Whether [fetched], the store currently served at this store's index URL, may overwrite this saved store.
 *
 * A store is identified by its signing key. If the URL now serves a store signed by another key (the repo
 * was handed over, or now mirrors another store), it is a different store and the user has to add it again.
 * The same applies if [fetched] moved to the URL of another saved store that has a different key.
 *
 * @param savedStores all saved stores, used to find a store already saved at the new URL.
 */
fun ExtensionStore.canBeRefreshedTo(fetched: ExtensionStore, savedStores: Collection<ExtensionStore>): Boolean {
    if (signingKey != NO_SIGNING_KEY && fetched.signingKey != signingKey) return false

    val storeAtNewUrl = savedStores.find { it.indexUrl == fetched.indexUrl && it.indexUrl != indexUrl }
        ?: return true
    return storeAtNewUrl.signingKey == NO_SIGNING_KEY || storeAtNewUrl.signingKey == fetched.signingKey
}

/**
 * Applies the index URL changes of a store refresh ([renamed]: old URL to new URL) to these URLs, so a
 * store keeps its settings (e.g. being disabled) when a refresh moves it to a new URL.
 */
fun Set<String>.remapIndexUrls(renamed: Map<String, String>): Set<String> {
    if (renamed.isEmpty()) return this
    return mapTo(mutableSetOf()) { url ->
        var current = url
        val seen = mutableSetOf(current)
        while (true) {
            val next = renamed[current] ?: break
            if (!seen.add(next)) break
            current = next
        }
        current
    }
}
// KMK <--
