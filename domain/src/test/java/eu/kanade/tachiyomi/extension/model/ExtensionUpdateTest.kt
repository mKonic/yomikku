package eu.kanade.tachiyomi.extension.model

import io.kotest.matchers.shouldBe
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test

class ExtensionUpdateTest {

    @Test
    fun `only listings signed by the installed APK are eligible`() {
        val extension = failed(signatures = listOf("installed"))
        val otherSigner = available(signature = "other", versionCode = 15)
        val matchingSigner = available(signature = "installed", versionCode = 11)

        extension.findListing(listOf(otherSigner, matchingSigner)) shouldBe matchingSigner
        extension.findUpdate(listOf(otherSigner, matchingSigner)) shouldBe matchingSigner
        extension.findUpdate(listOf(otherSigner)) shouldBe null
    }

    @Test
    fun `listing signature must match its store signing key`() {
        val extension = failed()
        val mismatchedStore = available(signature = "installed", storeSignature = "other")

        extension.findListing(listOf(mismatchedStore)) shouldBe null
        extension.findUpdate(listOf(mismatchedStore)) shouldBe null
    }

    @Test
    fun `newer version is selected regardless of listing order`() {
        val extension = failed()
        val older = available(versionCode = 11)
        val newer = available(versionCode = 12)

        extension.findUpdate(listOf(newer, older)) shouldBe newer
        extension.findUpdate(listOf(older, newer)) shouldBe newer
    }

    @Test
    fun `an older version code cannot be an update even with a newer library`() {
        val extension = failed(versionCode = 10, libVersion = 1.6)

        extension.findUpdate(listOf(available(versionCode = 9, libVersion = 2.0))) shouldBe null
        extension.findUpdate(listOf(available(versionCode = 10, libVersion = 1.6))) shouldBe null
        extension.findUpdate(listOf(available(versionCode = 10, libVersion = 1.7))) shouldBe
            available(versionCode = 10, libVersion = 1.7)
    }

    @Test
    fun `untrusted APKs use the same update check`() {
        val extension = Extension.Untrusted(
            name = "Test",
            pkgName = "test.extension",
            versionName = "1.6.10",
            versionCode = 10,
            libVersion = 1.6,
            signatureHash = "installed",
        )

        extension.findUpdate(listOf(available())) shouldBe available()
    }

    @Test
    fun `unknown library version requires a newer version code`() {
        val extension = failed(libVersion = 0.0)

        extension.findUpdate(listOf(available(versionCode = 10, libVersion = 1.6))) shouldBe null
        extension.findUpdate(listOf(available(versionCode = 11, libVersion = 1.6))) shouldBe
            available(versionCode = 11, libVersion = 1.6)
    }

    private fun failed(
        versionCode: Long = 10,
        libVersion: Double = 1.6,
        signatures: List<String> = listOf("installed"),
    ) = Extension.Failed(
        name = "Test",
        pkgName = "test.extension",
        versionName = "1.6.$versionCode",
        versionCode = versionCode,
        libVersion = libVersion,
        signatureHash = signatures.last(),
        signatures = signatures,
        isShared = true,
        reason = "Unsupported library version",
    )

    private fun available(
        signature: String = "installed",
        storeSignature: String = signature,
        versionCode: Long = 11,
        libVersion: Double = 1.6,
    ) = Extension.Available(
        name = "Test",
        pkgName = "test.extension",
        versionName = "$libVersion.$versionCode",
        versionCode = versionCode,
        libVersion = libVersion,
        lang = "en",
        isNsfw = false,
        signatureHash = signature,
        storeName = "Test store",
        sources = emptyList(),
        apkUrl = "https://example.com/test.apk",
        iconUrl = "https://example.com/test.png",
        store = ExtensionStore(
            indexUrl = "https://example.com/index.json",
            name = "Test store",
            badgeLabel = "Test",
            signingKey = storeSignature,
            contact = ExtensionStore.Contact("https://example.com", null),
            isLegacy = false,
            extensionListUrl = null,
        ),
    )
}
