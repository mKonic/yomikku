package eu.kanade.tachiyomi.extension.api

import android.content.Context
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import mihon.domain.extension.interactor.AddExtensionStore
import mihon.domain.extension.interactor.UpdateExtensionStores
import mihon.domain.extension.model.YOMIKKU_STORE_URL
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import kotlin.time.Duration.Companion.days

internal class ExtensionApi {

    private val repository: ExtensionStoreRepository by injectLazy()

    private val preferenceStore: PreferenceStore by injectLazy()
    private val updateExtensionStores: UpdateExtensionStores by injectLazy()
    private val addExtensionStore: AddExtensionStore by injectLazy()
    private val extensionManager: ExtensionManager by injectLazy()

    // SY -->
    private val sourcePreferences: SourcePreferences by injectLazy()
    // SY <--

    private val lastExtCheck: Preference<Long> by lazy {
        preferenceStore.getLong(Preference.appStateKey("last_ext_check"), 0)
    }

    private val yomikkuStoreAdded: Preference<Boolean> by lazy {
        preferenceStore.getBoolean(Preference.appStateKey("yomikku_store_added"), false)
    }

    /**
     * Adds the yomikku store the first time extensions are looked up. Only once: a user who removes it keeps it
     * removed. A failed fetch, like being offline, leaves it to the next lookup.
     */
    private suspend fun addYomikkuStoreOnce() {
        if (yomikkuStoreAdded.get()) return
        addExtensionStore(YOMIKKU_STORE_URL).onSuccess { yomikkuStoreAdded.set(true) }
    }

    suspend fun findExtensions(): List<Extension.Available> {
        // KMK -->
        val disabledRepos = sourcePreferences.disabledRepos().get()
        // KMK <--
        return withIOContext {
            addYomikkuStoreOnce()
            repository.fetchExtensions(
                // KMK -->
                disabledRepos,
                // KMK <--
            )
        }
    }

    suspend fun checkForUpdates(
        context: Context,
        fromAvailableExtensionList: Boolean = false,
    ): List<Extension.Installed>? {
        // Limit checks to once a day at most
        if (!fromAvailableExtensionList &&
            Instant.now().toEpochMilli() < lastExtCheck.get() + 1.days.inWholeMilliseconds
        ) {
            return null
        }

        updateExtensionStores()

        val extensions = if (fromAvailableExtensionList) {
            extensionManager.availableExtensionsFlow.value
        } else {
            findExtensions().also { lastExtCheck.set(Instant.now().toEpochMilli()) }
        }

        val installedExtensions = ExtensionLoader.loadExtensions(context)
            .filterIsInstance<LoadResult.Success>()
            .map { it.extension }

        val extensionsWithUpdate = mutableListOf<Extension.Installed>()
        for (installedExt in installedExtensions) {
            val pkgName = installedExt.pkgName
            val availableExt = extensions.find { it.pkgName == pkgName } ?: continue
            val hasUpdatedVer = availableExt.versionCode > installedExt.versionCode
            val hasUpdatedLib = availableExt.libVersion > installedExt.libVersion
            val hasUpdate = hasUpdatedVer || hasUpdatedLib
            if (hasUpdate) {
                extensionsWithUpdate.add(installedExt)
            }
        }

        if (extensionsWithUpdate.isNotEmpty()) {
            ExtensionUpdateNotifier(context).promptUpdates(extensionsWithUpdate.map { it.name })
        }

        return extensionsWithUpdate
    }
}
