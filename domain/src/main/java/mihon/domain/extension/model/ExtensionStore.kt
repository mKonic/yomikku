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

// The key mKonic/yomikku-extensions signs its APKs with. Extensions it signed are trusted without asking.
const val YOMIKKU_SIGNATURE = "34378863c3c0f4ce1afdbff10539450877733758f64832c07b8623e0f058dda8"
const val REPO_SIGNATURE = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
