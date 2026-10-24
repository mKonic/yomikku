package eu.kanade.tachiyomi.util

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream

fun Manga.removeCovers(coverCache: CoverCache = Injekt.get()) {
    if (isLocal()) return
    coverCache.deleteFromCache(this, true)
}

suspend fun Manga.editCover(
    coverManager: LocalCoverManager,
    stream: InputStream,
    updateManga: UpdateManga = Injekt.get(),
    coverCache: CoverCache = Injekt.get(),
) {
    if (isLocal()) {
        coverManager.update(toSManga(), stream)
        updateManga.awaitUpdateCoverLastModified(id)
    } else if (favorite) {
        coverCache.setCustomCoverToCache(this, stream)
        updateManga.awaitUpdateCoverLastModified(id)
    }
}
