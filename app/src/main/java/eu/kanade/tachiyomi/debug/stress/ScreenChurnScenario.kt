package eu.kanade.tachiyomi.debug.stress

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
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
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.interactor.GetLibraryManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.system.measureTimeMillis

/**
 * Opens and closes the app's screens over and over, the way leaks like a screen model outliving its screen get made:
 * every tab, every settings page, and more library entries each pass, until the whole library has been opened.
 * Screens that reach online sources only open when the run allows the network.
 */
object ScreenChurnScenario : StressScenario {
    override val name = "screens"

    private const val ENTRIES_PER_PASS = 10

    override suspend fun run(context: StressContext, iteration: Int) {
        val navigator = context.navigator()
        onMain { navigator.popUntilRoot() }

        val tabs = buildList {
            add("library" to HomeScreen.Tab.Library())
            add("updates" to HomeScreen.Tab.Updates)
            add("history" to HomeScreen.Tab.History)
            add("sources" to HomeScreen.Tab.Browse(toExtensions = false))
            if (context.network) add("extensions" to HomeScreen.Tab.Browse(toExtensions = true))
            add("more" to HomeScreen.Tab.More(toDownloads = false))
        }
        for ((label, tab) in tabs) {
            val ms = measureTimeMillis { HomeScreen.openTab(tab) }
            settle(context, "tab", label, ms)
        }

        val screens = buildList<Pair<String, Screen>> {
            add("settings" to SettingsScreen())
            add("settings_appearance" to SettingsAppearanceScreen)
            add("settings_library" to SettingsLibraryScreen)
            add("settings_reader" to SettingsReaderScreen)
            add("settings_downloads" to SettingsDownloadScreen)
            add("settings_tracking" to SettingsTrackingScreen)
            add("settings_connection" to SettingsConnectionScreen)
            add("settings_browse" to SettingsBrowseScreen)
            add("settings_data" to SettingsDataScreen)
            add("settings_security" to SettingsSecurityScreen)
            add("settings_advanced" to SettingsAdvancedScreen)
            add("about" to AboutScreen())
            add("download_queue" to DownloadQueueScreen)
            if (context.network) add("global_search" to GlobalSearchScreen("one"))
        }
        screens.forEach { (label, screen) -> openAndClose(context, navigator, label, screen) }

        val entries = Injekt.get<GetLibraryManga>().await()
            .map { it.manga }
            .distinctBy { it.id }
            .take(ENTRIES_PER_PASS * (iteration + 1))
        entries.forEach { manga -> openAndClose(context, navigator, "entry", MangaScreen(manga.id)) }
        context.step("entries", mapOf("opened" to entries.size))

        onMain { navigator.popUntilRoot() }
    }

    private suspend fun openAndClose(context: StressContext, navigator: Navigator, label: String, screen: Screen) {
        val openMs = measureTimeMillis { onMain { navigator.push(screen) } }
        settle(context, "open", label, openMs)
        val closeMs = measureTimeMillis { onMain { navigator.pop() } }
        settle(context, "close", label, closeMs)
    }

    private suspend fun settle(context: StressContext, event: String, label: String, actionMs: Long) {
        var settled = true
        val ms = measureTimeMillis { settled = awaitUiSettled() }
        context.step(event, mapOf("screen" to label, "actionMs" to actionMs, "settleMs" to ms, "settled" to settled))
    }

    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
}
