package exh.debug

import android.app.Application
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.util.system.workManager
import exh.util.ThrottleManager
import exh.util.jobScheduler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.schema.ProtoBufSchemaGenerator
import mihon.core.migration.MigrationContext
import mihon.core.migration.MigrationJobFactory
import mihon.core.migration.MigrationStrategyFactory
import mihon.core.migration.Migrator
import mihon.core.migration.migrations.migrations
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.manga.interactor.GetAllManga
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.UUID

@Suppress("unused")
object DebugFunctions {
    private val app: Application by injectLazy()
    private val handler: DatabaseHandler by injectLazy()
    private val sourceManager: SourceManager by injectLazy()
    private val updateMangaFromRemote: UpdateMangaFromRemote by injectLazy()
    private val getFavorites: GetFavorites by injectLazy()
    private val getAllManga: GetAllManga by injectLazy()

    fun forceUpgradeMigration(): Boolean {
        val migrationContext = MigrationContext(dryrun = false)
        val migrationJobFactory = MigrationJobFactory(migrationContext, Migrator.scope)
        val migrationStrategyFactory = MigrationStrategyFactory(migrationJobFactory) {}
        val strategy = migrationStrategyFactory.create(1, BuildConfig.VERSION_CODE)
        return runBlocking { strategy(migrations).await() }
    }

    fun forceSetupJobs(): Boolean {
        val migrationContext = MigrationContext(dryrun = false)
        val migrationJobFactory = MigrationJobFactory(migrationContext, Migrator.scope)
        val migrationStrategyFactory = MigrationStrategyFactory(migrationJobFactory) {}
        val strategy = migrationStrategyFactory.create(0, BuildConfig.VERSION_CODE)
        return runBlocking { strategy(migrations).await() }
    }

    private val throttleManager = ThrottleManager()

    fun addAllMangaInDatabaseToLibrary() {
        runBlocking { handler.await { ehQueries.addAllMangaInDatabaseToLibrary() } }
    }

    fun countMangaInDatabaseInLibrary() = runBlocking { getFavorites.await().size }

    fun countMangaInDatabaseNotInLibrary() = runBlocking { getAllManga.await() }.count { !it.favorite }

    fun countMangaInDatabase() = runBlocking { getAllManga.await() }.size

    fun clearSavedSearches() = runBlocking { handler.await { saved_searchQueries.deleteAll() } }

    suspend fun listAllSources() = sourceManager.getAll().joinToString("\n") {
        "${it.id}: ${it.name} (${it.lang.uppercase()})"
    }

    suspend fun listAllSourcesClassName() = sourceManager.getAll().joinToString("\n") {
        "${it::class.qualifiedName}: ${it.name} (${it.lang.uppercase()})"
    }

    suspend fun listVisibleSources() = sourceManager.getVisibleSources().joinToString("\n") {
        "${it.id}: ${it.name} (${it.lang.uppercase()})"
    }

    suspend fun listAllHttpSources() = sourceManager.getOnlineSources().joinToString("\n") {
        "${it.id}: ${it.name} (${it.lang.uppercase()})"
    }
    suspend fun listVisibleHttpSources() = sourceManager.getVisibleOnlineSources().joinToString("\n") {
        "${it.id}: ${it.name} (${it.lang.uppercase()})"
    }

    fun listScheduledJobs() = app.jobScheduler.allPendingJobs.joinToString(",\n") { j ->
        val info = j.extras.getString("EXTRA_WORK_SPEC_ID")?.let {
            app.workManager.getWorkInfoById(UUID.fromString(it)).get()
        }

        if (info != null) {
            """
            {
                id: ${info.id},
                isPeriodic: ${j.extras.getBoolean("EXTRA_IS_PERIODIC")},
                state: ${info.state.name},
                tags: [
                    ${info.tags.joinToString(separator = ",\n                    ")}
                ],
            }
            """.trimIndent()
        } else {
            """
            {
                info: ${j.id},
                isPeriodic: ${j.isPeriodic},
                isPersisted: ${j.isPersisted},
                intervalMillis: ${j.intervalMillis},
            }
            """.trimIndent()
        }
    }

    fun cancelAllScheduledJobs() = app.jobScheduler.cancelAll()

    fun exportProtobufScheme() = ProtoBufSchemaGenerator.generateSchemaText(Backup.serializer().descriptor)

    fun killSyncJobs() {
        val context = Injekt.get<Application>()
        SyncDataJob.stop(context)
    }

    fun killLibraryJobs() {
        val context = Injekt.get<Application>()
        LibraryUpdateJob.stop(context)
    }
}
