package eu.kanade.tachiyomi.data.cache

import android.content.Context
import android.text.format.Formatter
import com.jakewharton.disklrucache.DiskLruCache
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import logcat.LogPriority
import okio.buffer
import okio.sink
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import java.io.File
import java.io.IOException

/**
 * Class used to create chapter cache
 * For each image in a chapter a file is created
 * For each chapter a Json list is created and converted to a file.
 * The files are in format *md5key*.0
 *
 * @param context the application context.
 */
class ChapterCache(
    private val context: Context,
    // SY -->
    readerPreferences: ReaderPreferences,
    // SY <--
) {

    // --> EH
    private val scope = CoroutineScope(Job() + Dispatchers.Main)

    /** Cache class used for cache management.  */
    private val diskCache = setupDiskCache(readerPreferences.cacheSize().get())

    init {
        readerPreferences.cacheSize().changes()
            .drop(1)
            .onEach {
                // Resize in place. Opening a second DiskLruCache over the same directory while
                // the old one still holds its journal writer interleaves both writers' lines and
                // corrupts the journal, which wipes the whole cache on the next launch.
                diskCache.maxSize = toMaxSizeBytes(it)
            }
            .launchIn(scope)
    }
    // <-- EH

    /**
     * Returns directory of cache.
     */
    private val cacheDir: File = diskCache.directory

    /**
     * Returns real size of directory.
     */
    private val realSize: Long
        get() = DiskUtil.getDirectorySize(cacheDir)

    /**
     * Returns real size of directory in human readable format.
     */
    val readableSize: String
        get() = Formatter.formatFileSize(context, realSize)

    // --> EH
    // Cache size is in MB
    private fun setupDiskCache(cacheSize: String): DiskLruCache {
        return DiskLruCache.open(
            File(context.cacheDir, "chapter_disk_cache"),
            PARAMETER_APP_VERSION,
            PARAMETER_VALUE_COUNT,
            toMaxSizeBytes(cacheSize),
        )
    }

    /**
     * Translates the stored cache size preference (in MB) into a byte budget for [DiskLruCache].
     *
     * A value of zero or less means unlimited: nothing is ever evicted, so a page that has been
     * downloaded once stays readable until the cache is cleared explicitly. Pair it with the
     * "Clear chapter cache on app launch" setting to keep it from growing without bound.
     */
    private fun toMaxSizeBytes(cacheSize: String): Long {
        val megabytes = cacheSize.toLongOrNull() ?: DEFAULT_CACHE_SIZE_MB
        return if (megabytes <= 0) Long.MAX_VALUE else megabytes * 1024 * 1024
    }
    // <-- EH

    /**
     * The cached text of [chapter], or null if it is not in the cache. A read counts as a use, so eviction is least
     * recently used.
     */
    fun getChapterText(chapter: Chapter): String? {
        val key = DiskUtil.hashKeyForDisk(getKey(chapter))
        return try {
            diskCache.get(key)?.use { it.getString(0) }
        } catch (e: IOException) {
            logcat(LogPriority.WARN, e) { "Failed to read chapter text from cache" }
            null
        }
    }

    fun putChapterText(chapter: Chapter, text: String) {
        var editor: DiskLruCache.Editor? = null
        try {
            val key = DiskUtil.hashKeyForDisk(getKey(chapter))
            editor = diskCache.edit(key) ?: return
            editor.newOutputStream(0).sink().buffer().use {
                it.writeUtf8(text)
            }
            // commit() flushes the journal itself. An extra flush() here would also run trimToSize() on this thread,
            // holding the cache lock while it deletes evicted files.
            editor.commit()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to put chapter text to cache" }
        } finally {
            editor?.abortUnlessCommitted()
        }
    }

    fun clear(): Int {
        var deletedFiles = 0
        cacheDir.listFiles()?.forEach {
            if (removeFileFromCache(it.name)) {
                deletedFiles++
            }
        }
        return deletedFiles
    }

    /**
     * Remove file from cache.
     *
     * @param file name of file "md5.0".
     * @return status of deletion for the file.
     */
    private fun removeFileFromCache(file: String): Boolean {
        // Make sure we don't delete the journal file (keeps track of cache)
        if (file == "journal" || file.startsWith("journal.")) {
            return false
        }

        return try {
            // Remove the extension from the file to get the key of the cache
            val key = file.substringBeforeLast(".")
            // Remove file from cache
            diskCache.remove(key)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to remove file from cache" }
            false
        }
    }

    private fun getKey(chapter: Chapter): String {
        return "${chapter.mangaId}${chapter.url}"
    }
}

/** Application cache version.  */
private const val PARAMETER_APP_VERSION = 1

/** The number of values per cache entry. Must be positive.  */
private const val PARAMETER_VALUE_COUNT = 1

/** The maximum number of bytes this cache should use to store.  */
private const val PARAMETER_CACHE_SIZE = 100L * 1024 * 1024

/** Fallback in MB used when the stored cache size preference isn't a number.  */
private const val DEFAULT_CACHE_SIZE_MB = 75L
