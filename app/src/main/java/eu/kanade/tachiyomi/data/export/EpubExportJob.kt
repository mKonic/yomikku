package eu.kanade.tachiyomi.data.export

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import okhttp3.Headers
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Writes a novel's downloaded chapters, in reading order, to a chosen file as an EPUB. It runs as its own job with a
 * progress notification, so a long novel keeps exporting after its screen is closed.
 */
class EpubExportJob(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val progress = context.notificationBuilder(Notifications.CHANNEL_COMMON) {
        setSmallIcon(R.drawable.ic_yomikku)
        setColor(ContextCompat.getColor(context, R.color.ic_launcher))
        setOngoing(true)
        setOnlyAlertOnce(true)
        setProgress(0, 0, true)
    }

    override suspend fun doWork(): Result {
        val mangaId = inputData.getLong(MANGA_ID_KEY, -1L)
        val uri = inputData.getString(URI_KEY)?.toUri() ?: return Result.failure()
        val manga = Injekt.get<GetManga>().await(mangaId) ?: return Result.failure()
        progress.setContentTitle(manga.title)
        setForegroundSafely()

        return try {
            val count = export(manga, uri)
            finished(manga, context.pluralStringResource(KMR.plurals.epub_exported, count, count), uri)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            finished(manga, context.stringResource(KMR.strings.epub_export_failed, e.message.orEmpty()), null)
            Result.failure()
        } finally {
            context.cancelNotification(Notifications.ID_EPUB_EXPORT_PROGRESS)
        }
    }

    private suspend fun export(manga: Manga, uri: Uri): Int {
        val sourceManager = Injekt.get<SourceManager>()
        val downloadProvider = Injekt.get<DownloadProvider>()
        val source = sourceManager.getOrStub(manga.source)
        val files = Injekt.get<GetChaptersByMangaId>().await(manga.id)
            .sortedByDescending { it.sourceOrder }
            .mapNotNull { chapter ->
                downloadProvider.findChapterDir(chapter.name, chapter.scanlator, chapter.url, manga.ogTitle, source)
                    ?.takeIf { it.isFile }
                    ?.let { chapter to it }
            }
        if (files.isEmpty()) error(context.stringResource(KMR.strings.epub_export_no_downloads))

        val writer = EpubWriter(
            title = manga.title,
            authors = listOfNotNull(manga.author).flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() },
            description = manga.description,
            language = (source as? CatalogueSource)?.lang?.takeIf { it.length in 2..3 } ?: "und",
            identifier = "urn:yomikku:${manga.source}:${manga.url}",
            cover = coverImage(manga, source),
        )
        // Chapters are read as the book is written, so a long novel is never all in memory at once.
        val chapters = files.asSequence().mapNotNull { (chapter, file) ->
            file.openInputStream()?.bufferedReader()?.use { it.readText() }?.let { EpubWriter.Chapter(chapter.name, it) }
        }
        var written = 0
        context.contentResolver.openOutputStream(uri)?.use { out ->
            writer.write(chapters, out) { done ->
                written = done
                showProgress(done, files.size)
            }
        } ?: error("Cannot write to the chosen file")
        return written
    }

    private fun showProgress(done: Int, total: Int) {
        context.notify(
            Notifications.ID_EPUB_EXPORT_PROGRESS,
            progress
                .setContentText(context.stringResource(KMR.strings.epub_exporting_progress, done, total))
                .setProgress(total, done, false)
                .build(),
        )
    }

    private fun finished(manga: Manga, message: String, uri: Uri?) {
        context.notify(Notifications.ID_EPUB_EXPORT_COMPLETE, Notifications.CHANNEL_COMMON) {
            setSmallIcon(R.drawable.ic_yomikku)
            setColor(ContextCompat.getColor(context, R.color.ic_launcher))
            setContentTitle(manga.title)
            setContentText(message)
            setAutoCancel(true)
            if (uri != null) {
                val open = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, EPUB_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent.createChooser(open, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
            setPriority(NotificationCompat.PRIORITY_DEFAULT)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        Notifications.ID_EPUB_EXPORT_PROGRESS,
        progress.build(),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    /** The novel's cover: the cached one for library novels, else downloaded through the source. */
    private suspend fun coverImage(manga: Manga, source: Source): EpubWriter.Image? {
        val coverCache = Injekt.get<CoverCache>()
        val cached = coverCache.getCustomCoverFile(manga.id).takeIf { it.exists() }
            ?: coverCache.getCoverFile(manga.thumbnailUrl)?.takeIf { it.exists() }
        val bytes = cached?.readBytes() ?: runCatching {
            val url = manga.thumbnailUrl?.takeIf { it.startsWith("http") } ?: return null
            val client = (source as? HttpSource)?.client ?: Injekt.get<NetworkHelper>().client
            val headers = (source as? HttpSource)?.headers ?: Headers.headersOf()
            client.newCall(GET(url, headers)).await().use { if (it.isSuccessful) it.body.bytes() else null }
        }.getOrNull() ?: return null
        val type = when {
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
            bytes.size > 12 && String(bytes, 8, 4) == "WEBP" -> "image/webp"
            bytes.size > 3 && String(bytes, 0, 3) == "GIF" -> "image/gif"
            else -> return null
        }
        return EpubWriter.Image(bytes, type)
    }

    companion object {
        private const val TAG = "EpubExport"
        private const val MANGA_ID_KEY = "manga_id"
        private const val URI_KEY = "uri"
        private const val EPUB_TYPE = "application/epub+zip"

        fun start(context: Context, mangaId: Long, uri: Uri) {
            val request = OneTimeWorkRequestBuilder<EpubExportJob>()
                .addTag(TAG)
                .setInputData(workDataOf(MANGA_ID_KEY to mangaId, URI_KEY to uri.toString()))
                .build()
            // One export at a time; another novel's waits for the one running.
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
