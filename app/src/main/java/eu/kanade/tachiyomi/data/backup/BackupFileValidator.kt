package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class BackupFileValidator(
    private val context: Context,

    private val sourceManager: SourceManager = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
) {

    /**
     * Checks for critical backup file data.
     *
     * @return List of missing sources or missing trackers.
     */
    suspend fun validate(uri: Uri): Results {
        // KMK --> the entries are read one at a time rather than the whole backup at once (mihonapp/mihon#3850)
        val decoder = BackupDecoder(context)
        val backup = try {
            decoder.decodeMetadata(uri).second
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }
        // KMK <--

        val sources = backup.backupSources.associate { it.sourceId to it.name }
        val missingSources = sources
            .filter { sourceManager.get(it.key) == null }
            .map { (id, name) ->
                // Some backups store the id as the name, and sources without a stub were backed up with no name
                if (name.isBlank() || name.toLongOrNull() != null) {
                    sourceManager.getOrStub(id).toString()
                } else {
                    name
                }
            }
            .distinct()
            .sorted()

        // KMK -->
        val trackers = mutableSetOf<Long>()
        try {
            decoder.decodeManga(uri).collect { manga ->
                manga.tracking.forEach { trackers += it.syncId.toLong() }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw IllegalStateException(e)
        }
        val missingTrackers = trackers
            .mapNotNull { trackerManager.get(it) }
            // KMK <--
            .filter { !it.isLoggedIn }
            .map { it.name }
            .sorted()

        return Results(missingSources, missingTrackers)
    }

    data class Results(
        val missingSources: List<String>,
        val missingTrackers: List<String>,
    )
}
