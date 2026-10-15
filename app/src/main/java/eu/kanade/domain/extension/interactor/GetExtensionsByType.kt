package eu.kanade.domain.extension.interactor

import eu.kanade.domain.extension.model.Extensions
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class GetExtensionsByType(
    private val preferences: SourcePreferences,
    private val extensionManager: ExtensionManager,
) {

    fun subscribe(): Flow<Extensions> {
        val showNsfwSources = preferences.showNsfwSource().get()

        return combine(
            preferences.enabledLanguages().changes(),
            extensionManager.installedExtensionsFlow,
            extensionManager.untrustedExtensionsFlow,
            extensionManager.failedExtensionsFlow,
            extensionManager.availableExtensionsFlow,
        ) { enabledLanguages, _installed, _untrusted, _failed, _available ->
            val (installedUpdates, installed) = _installed
                .filter { showNsfwSources || !it.isNsfw }
                .sortedWith(
                    compareBy<Extension.Installed> {
                        !it.isObsolete /* SY --> */ && !it.isRedundant /* SY <-- */
                    }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                )
                .partition { it.hasUpdate }

            val (notLoadedUpdates, notLoaded) = (_untrusted + _failed)
                .filter { showNsfwSources || !it.isNsfw }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .partition { it.hasUpdate }
            val updates = (installedUpdates + notLoadedUpdates)
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

            val onDevice = buildList<Extension.OnDevice> {
                addAll(_installed)
                addAll(_untrusted)
                addAll(_failed)
            }
            val available = _available
                .filter { extension ->
                    onDevice.none {
                        it.pkgName == extension.pkgName &&
                            extension.signatureHash == extension.store.signingKey &&
                            extension.store.signingKey in it.signatures
                    } && (showNsfwSources || !extension.isNsfw)
                }
                .flatMap { ext ->
                    ext.sources.filter { it.lang in enabledLanguages }
                        .map {
                            ext.copy(
                                name = it.name,
                                lang = it.lang,
                                pkgName = "${ext.pkgName}-${it.id}",
                                sources = listOf(it),
                            )
                        }
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

            Extensions(updates, installed, available, notLoaded)
        }
    }
}
