package eu.kanade.tachiyomi.ui.reader

import android.app.assist.AssistContent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.presentation.reader.ChapterListDialog
import eu.kanade.presentation.reader.ReaderScreen
import eu.kanade.presentation.reader.ReaderSettingsSheet
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.ReaderData
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.text.ReaderNavigation
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.injectLazy

class ReaderActivity : BaseActivity() {

    companion object {
        fun newIntent(context: Context, mangaId: Long?, chapterId: Long?, page: Int? = null): Intent {
            return Intent(context, ReaderActivity::class.java).apply {
                putExtra("manga", mangaId)
                putExtra("chapter", chapterId)
                putExtra("page", page)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    private val readerPreferences: ReaderPreferences by injectLazy()
    private val preferences: BasePreferences by injectLazy()
    private val connectionsPreferences: ConnectionsPreferences by injectLazy()

    val viewModel by viewModels<ReaderViewModel>()

    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }

    override fun onCreate(savedInstanceState: Bundle?) {
        registerSecureActivity(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                R.anim.shared_axis_x_push_enter,
                R.anim.shared_axis_x_push_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_push_enter, R.anim.shared_axis_x_push_exit)
        }
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        super.onCreate(savedInstanceState)

        if (!viewModel.hasValidArgs) {
            finish()
            return
        }
        NotificationReceiver.dismissNotification(this, viewModel.mangaId.hashCode(), Notifications.ID_NEW_CHAPTERS)

        setContent {
            TachiyomiTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val volumeKeys by readerPreferences.readWithVolumeKeys().collectAsState()
                ReaderScreen(
                    state = state,
                    preferences = readerPreferences,
                    navigation = viewModel.navigation,
                    onNavigateUp = ::finish,
                    onProgress = viewModel::onProgress,
                    onToggleMenus = viewModel::toggleMenus,
                    onPreviousChapter = { viewModel.loadPreviousChapter() },
                    onNextChapter = viewModel::loadNextChapter,
                    onContinueToNextChapter = viewModel::continueToNextChapter,
                    onSeek = viewModel::seek,
                    onRetry = viewModel::retry,
                    onToggleBookmark = viewModel::toggleChapterBookmark,
                    onOpenInWebView = ::openChapterInWebView.takeIf { viewModel.getSource() != null },
                    onOpenChapterList = viewModel::openChapterList,
                    onOpenSettings = viewModel::openSettings,
                    onToggleSpeech = viewModel::toggleSpeech,
                )
                when (state.dialog) {
                    ReaderViewModel.Dialog.Settings -> ReaderSettingsSheet(
                        preferences = readerPreferences,
                        novelReadingMode = state.manga?.let { ReaderPreferences.ReadingMode.fromFlags(it.viewerFlags) },
                        onNovelReadingModeChange = viewModel::setReadingMode,
                        onDismissRequest = viewModel::closeDialog,
                    )
                    ReaderViewModel.Dialog.ChapterList -> ChapterListDialog(
                        onDismissRequest = viewModel::closeDialog,
                        chapters = viewModel.getChapters().toImmutableList(),
                        onClickChapter = {
                            viewModel.loadChapterFromList(it)
                            viewModel.closeDialog()
                        },
                        onBookmark = { viewModel.toggleBookmark(it.id, !it.bookmark) },
                        dateRelativeTime = true,
                        onDownloadAction = viewModel::handleDownloadAction,
                    )
                    null -> Unit
                }
                // Volume keys only turn pages while the setting is on; reading the state here keeps the handler
                // in sync without a second subscription.
                volumeKeysEnabled = volumeKeys
            }
        }

        viewModel.state
            .map { it.menuVisible }
            .distinctUntilChanged()
            .combine(readerPreferences.fullscreen().changes()) { visible, fullscreen -> visible to fullscreen }
            .onEach { (visible, fullscreen) -> setSystemBars(visible || !fullscreen) }
            .launchIn(lifecycleScope)

        readerPreferences.keepScreenOn().changes()
            .onEach { enabled ->
                if (enabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            .launchIn(lifecycleScope)

        readerPreferences.drawUnderCutout().changes()
            .onEach { underCutout ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = if (underCutout) {
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        } else {
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                        }
                    }
                }
            }
            .launchIn(lifecycleScope)

        // Finish when incognito mode is disabled.
        preferences.incognitoMode().changes()
            .drop(1)
            .onEach { if (!it) finish() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.initError }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { error ->
                logcat(LogPriority.ERROR, error)
                toast(error.message)
            }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.chapter?.id }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { updateDiscordRPC(exitingReader = false) }
            .launchIn(lifecycleScope)
    }

    private var volumeKeysEnabled = false

    private fun setSystemBars(visible: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (visible) {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onPause() {
        lifecycleScope.launchNonCancellable { viewModel.updateHistory() }
        updateDiscordRPC(exitingReader = true)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        viewModel.restartReadTimer()
        updateDiscordRPC(exitingReader = false)
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        viewModel.getChapterUrl()?.let { outContent.webUri = it.toUri() }
    }

    override fun finish() {
        viewModel.onActivityFinish()
        super.finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                R.anim.shared_axis_x_pop_enter,
                R.anim.shared_axis_x_pop_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_pop_enter, R.anim.shared_axis_x_pop_exit)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val forward = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeysEnabled) !volumeInverted() else null
            KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeysEnabled) volumeInverted() else null
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_RIGHT -> true
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT -> false
            else -> null
        } ?: return super.dispatchKeyEvent(event)
        // Consume both halves of the press so the system volume does not change, and act once on release.
        if (event.action == KeyEvent.ACTION_UP) {
            viewModel.navigate(if (forward) ReaderNavigation.FORWARD else ReaderNavigation.BACKWARD)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_N -> viewModel.loadNextChapter()
            KeyEvent.KEYCODE_P -> viewModel.loadPreviousChapter()
            KeyEvent.KEYCODE_MENU -> viewModel.toggleMenus()
            else -> return super.onKeyUp(keyCode, event)
        }
        return true
    }

    private fun volumeInverted() = readerPreferences.readWithVolumeKeysInverted().get()

    private fun openChapterInWebView() {
        val manga = viewModel.manga ?: return
        val source = viewModel.getSource() ?: return
        val url = viewModel.getChapterUrl() ?: return
        startActivity(WebViewActivity.newIntent(this, url, source.id, manga.title))
    }

    private fun updateDiscordRPC(exitingReader: Boolean) {
        if (!connectionsPreferences.enableDiscordRPC().get()) return
        DiscordRPCService.discordScope.launchIO {
            try {
                if (exitingReader) {
                    with(DiscordRPCService) { setScreen(this@ReaderActivity) }
                    return@launchIO
                }
                val manga = viewModel.manga ?: return@launchIO
                val chapter = viewModel.state.value.chapter ?: return@launchIO
                DiscordRPCService.setReaderActivity(
                    context = this@ReaderActivity,
                    ReaderData(
                        incognitoMode = viewModel.incognitoMode,
                        mangaId = manga.id,
                        mangaTitle = manga.ogTitle,
                        thumbnailUrl = manga.thumbnailUrl,
                        chapterNumber = if (connectionsPreferences.useChapterTitles().get()) {
                            chapter.name
                        } else {
                            chapter.chapterNumber.toString()
                        },
                        startTimestamp = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Error updating Discord RPC: ${e.message}" }
            }
        }
    }
}
