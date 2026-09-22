package eu.kanade.tachiyomi.debug.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import dev.mkonic.volition.Volition
import dev.mkonic.volition.VolitionSpec
import dev.mkonic.volition.voyager.screen
import dev.mkonic.volition.voyager.voyager
import eu.kanade.presentation.more.settings.screen.SettingsAdvancedScreen
import eu.kanade.presentation.more.settings.screen.SettingsAppearanceScreen
import eu.kanade.presentation.more.settings.screen.SettingsBrowseScreen
import eu.kanade.presentation.more.settings.screen.SettingsConnectionScreen
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.presentation.more.settings.screen.SettingsDownloadScreen
import eu.kanade.presentation.more.settings.screen.SettingsLibraryScreen
import eu.kanade.presentation.more.settings.screen.SettingsReaderScreen
import eu.kanade.presentation.more.settings.screen.SettingsSecurityScreen
import eu.kanade.presentation.more.settings.screen.SettingsTrackingScreen
import eu.kanade.presentation.more.settings.screen.SettingsWebGpuScreen
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import eu.kanade.presentation.more.settings.screen.debug.DebugInfoScreen
import eu.kanade.tachiyomi.debug.stress.StressHooks
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.stats.StatsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetLibraryManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * What this app can be told to go to, for [Volition] - `volition go settings_webgpu` from a terminal, wherever the
 * app happens to be, rather than a screenshot and a tap at a guessed coordinate.
 *
 * A destination belongs here as soon as reaching it by hand is a chore. Registering from a provider keeps the whole
 * thing inside the debug source set: no other build has this file, the library or the component to call.
 */
class KomikkuVolitionProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        Volition.register {
            voyager(MainActivity::class.java) { StressHooks.navigator }

            tab("library", HomeScreen.Tab.Library())
            tab("updates", HomeScreen.Tab.Updates)
            tab("history", HomeScreen.Tab.History)
            tab("browse", HomeScreen.Tab.Browse(toExtensions = false), "sources")
            tab("extensions", HomeScreen.Tab.Browse(toExtensions = true))
            tab("more", HomeScreen.Tab.More(toDownloads = false))
            tab("downloads", HomeScreen.Tab.More(toDownloads = true))
            tab("library_update_errors", HomeScreen.Tab.More(toDownloads = false, toLibraryUpdateErrors = true))

            screen("settings") { SettingsScreen() }
            screen("settings_appearance") { SettingsAppearanceScreen }
            screen("settings_library") { SettingsLibraryScreen }
            screen("settings_reader") { SettingsReaderScreen }
            screen("settings_webgpu", "webgpu") { SettingsWebGpuScreen }
            screen("settings_downloads") { SettingsDownloadScreen }
            screen("settings_tracking") { SettingsTrackingScreen }
            screen("settings_connection") { SettingsConnectionScreen }
            screen("settings_browse") { SettingsBrowseScreen }
            screen("settings_data") { SettingsDataScreen }
            screen("settings_security") { SettingsSecurityScreen }
            screen("settings_advanced") { SettingsAdvancedScreen }
            screen("about") { AboutScreen() }
            screen("debug_info") { DebugInfoScreen() }
            screen("download_queue") { DownloadQueueScreen }
            screen("categories") { CategoryScreen() }
            screen("stats") { StatsScreen() }
            screen("extension_stores") { ExtensionStoresScreen() }

            screen("manga", takesArgument = true, description = "an entry by id - see the entries command") { id ->
                id.toLongOrNull()?.let { MangaScreen(it) }
            }
            screen("search", takesArgument = true, description = "global search for a query") { query ->
                query.takeIf { it.isNotBlank() }?.let { GlobalSearchScreen(it) }
            }

            destination(
                "reader",
                takesArgument = true,
                description = "open <mangaId>/<chapterId> in the reader",
            ) { argument ->
                val parts = argument?.split('/', ':')?.mapNotNull { it.trim().toLongOrNull() }
                if (parts == null || parts.size < 2) {
                    return@destination "error: reader needs <mangaId>/<chapterId>"
                }
                withContext(Dispatchers.Main) {
                    val activity = StressHooks.activity ?: Volition.currentActivity
                        ?: return@withContext "error: the app is not running"
                    activity.startActivity(ReaderActivity.newIntent(activity, parts[0], parts[1]))
                    "ok: reader ${parts[0]}/${parts[1]}"
                }
            }

            // The ids the manga and reader destinations take, without reading the database out from under the
            // running app - which the phone cannot do anyway, having no sqlite3.
            command("entries") { query ->
                Injekt.get<GetLibraryManga>().await()
                    .map { it.manga }
                    .distinctBy { it.id }
                    .filter { query.isNullOrBlank() || it.title.contains(query, ignoreCase = true) }
                    .take(LIST_LIMIT)
                    .joinToString("\n") { "${it.id}\t${it.title}" }
                    .ifEmpty { "error: no entry matches '${query.orEmpty()}'" }
            }

            command("chapters") { argument ->
                val mangaId = argument?.trim()?.toLongOrNull()
                    ?: return@command "error: chapters needs an entry id"
                Injekt.get<GetChaptersByMangaId>().await(mangaId)
                    .take(LIST_LIMIT)
                    .joinToString("\n") { "${it.id}\t${it.name}${if (it.read) "\tread" else ""}" }
                    .ifEmpty { "error: no chapters for entry $mangaId" }
            }

            state("reader") {
                (Volition.currentActivity as? ReaderActivity)?.viewModel?.state?.value?.let { reader ->
                    mapOf(
                        "manga" to reader.manga?.title,
                        "chapter" to reader.currentChapter?.chapter?.name,
                        "page" to reader.currentPage,
                        "pages" to reader.totalPages,
                        "viewer" to reader.viewer?.javaClass?.simpleName,
                        "menuVisible" to reader.menuVisible,
                    )
                }
            }
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val LIST_LIMIT = 50
    }
}

/**
 * A bottom bar destination. The tabs live under whatever is pushed on top of home, so switching one while a screen is
 * open would leave that screen showing: asking for a tab means wanting to be on it.
 */
private fun VolitionSpec.tab(name: String, tab: HomeScreen.Tab, vararg aliases: String) {
    destination(name, *aliases, description = "the $name tab") {
        withContext(Dispatchers.Main) { StressHooks.navigator?.popUntilRoot() }
        HomeScreen.openTab(tab)
        "ok: tab $name"
    }
}
