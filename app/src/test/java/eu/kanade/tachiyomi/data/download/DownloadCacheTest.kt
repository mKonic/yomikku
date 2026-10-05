package eu.kanade.tachiyomi.data.download

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The disk cache exists so a launch does not have to walk the whole downloads tree. A query
 * arriving before the cache file has been read used to start that walk anyway, because
 * [DownloadCache.renewCache] only looks at `lastRenew`, which the read had not set yet.
 *
 * The walk is what calls [SourceManager.getVisibleOnlineSources], so counting those calls says
 * whether a renewal really scanned or gave up once the read answered for it.
 */
class DownloadCacheTest {

    private lateinit var cacheDir: File
    private val scans = AtomicInteger()

    private val context = mockk<Context>(relaxed = true)
    private val sourceManager = mockk<SourceManager>(relaxed = true)
    private val storageManager = mockk<StorageManager>(relaxed = true)

    @BeforeEach
    fun setUp() {
        cacheDir = File.createTempFile("dl-cache", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        every { context.cacheDir } returns cacheDir

        // A null downloads directory keeps the whole test off Android: the tree's UniFile is then
        // serialised as a null instead of a Uri, and a scan finds no source directories.
        every { storageManager.getDownloadsDirectory() } returns null
        // The real one is a Channel's receiveAsFlow, which replays nothing. A StateFlow here
        // would hand the collector a value on subscribe and invalidate the cache on construction.
        every { storageManager.changes } returns MutableSharedFlow()

        every { sourceManager.isInitialized } returns MutableStateFlow(true)
        coEvery { sourceManager.getVisibleOnlineSources() } answers {
            scans.incrementAndGet()
            emptyList()
        }
        coEvery { sourceManager.getStubSources() } returns emptyList()

        val interval = mockk<Preference<Int>>()
        every { interval.get() } returns 1
        val preferences = mockk<DownloadPreferences>(relaxed = true)
        every { preferences.downloadCacheRenewInterval() } returns interval
        Injekt.addSingleton<DownloadPreferences>(preferences)
    }

    @AfterEach
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    private fun cache() = DownloadCache(
        context = context,
        provider = mockk(relaxed = true),
        sourceManager = sourceManager,
        storageManager = storageManager,
    )

    private val diskCacheFile get() = File(cacheDir, "dl_index_cache_v3")

    private fun eventually(timeout: Duration = 10.seconds, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }

    /**
     * Runs one cache to completion so the next one starts with a disk cache to read.
     *
     * The file appears about a second after the renewal is asked for, whether or not the scan it
     * launched has run yet, so waiting on the file alone would leave that scan free to land after
     * [scans] is cleared and be counted against the instance under test. Wait for the scan too,
     * then for the count to stop moving.
     */
    private fun seedDiskCache() {
        cache().getTotalDownloadCount()
        eventually { scans.get() > 0 } shouldBe true
        // An empty tree protobuf-encodes to no bytes at all, so the file being there is the
        // whole signal that it was written.
        eventually { diskCacheFile.exists() } shouldBe true
        var settled = scans.get()
        eventually {
            Thread.sleep(250)
            (scans.get() == settled).also { settled = scans.get() }
        }
        scans.set(0)
    }

    @Test
    fun `a query answered by the disk cache does not also scan the filesystem`() = runBlocking<Unit> {
        seedDiskCache()

        // The query lands while the read is still in flight, which is the case that used to scan.
        val cache = cache()
        cache.getTotalDownloadCount()

        eventually(2.seconds) { scans.get() > 0 } shouldBe false
    }

    @Test
    fun `invalidating still scans, even against a disk cache that just loaded`() = runBlocking<Unit> {
        // A race, so many tries: the disk read could land between the reset and the renewal's
        // check and drop the forced scan, about 1 in 75 runs before the fix.
        var misses = 0
        repeat(150) {
            diskCacheFile.writeBytes(ByteArray(0))
            scans.set(0)
            val cache = cache()
            cache.invalidateCache()
            if (!eventually(3.seconds) { scans.get() > 0 }) misses++
        }
        misses shouldBe 0
    }

    @Test
    fun `a first run with no disk cache scans`() = runBlocking<Unit> {
        diskCacheFile.exists() shouldBe false

        cache().getTotalDownloadCount()

        eventually { scans.get() > 0 } shouldBe true
    }
}
