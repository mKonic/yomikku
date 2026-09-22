package eu.kanade.presentation.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.ReadingMode
import eu.kanade.tachiyomi.ui.reader.text.PagedTextReader
import eu.kanade.tachiyomi.ui.reader.text.ReaderNavigation
import eu.kanade.tachiyomi.ui.reader.text.ReaderTextStyle
import eu.kanade.tachiyomi.ui.reader.text.ScrollTextReader
import eu.kanade.tachiyomi.ui.reader.text.TapZone
import eu.kanade.tachiyomi.ui.reader.text.rememberReaderTextStyle
import kotlinx.coroutines.flow.Flow
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.automirroredrounded.FormatListBulleted
import mihon.icons.materialsymbols.rounded.Bookmark
import mihon.icons.materialsymbols.rounded.BookmarkAdd
import mihon.icons.materialsymbols.rounded.Public
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.rounded.SkipNext
import mihon.icons.materialsymbols.rounded.SkipPrevious
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(
    state: ReaderViewModel.State,
    preferences: ReaderPreferences,
    navigation: Flow<ReaderNavigation>,
    onNavigateUp: () -> Unit,
    onProgress: (Float, Boolean) -> Unit,
    onToggleMenus: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeek: (Float) -> Unit,
    onRetry: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onOpenChapterList: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val style = rememberReaderTextStyle(preferences)
    val readingMode by preferences.readingMode().collectAsState()
    val tapToTurn by preferences.tapToTurnPages().collectAsState()
    val showProgress by preferences.showProgress().collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(style.background),
    ) {
        val document = state.document
        val chapter = state.chapter
        when {
            state.initError != null -> ReaderMessage(state.initError.message.orEmpty(), style, onRetry = null)
            state.loadError != null -> ReaderMessage(state.loadError.message.orEmpty(), style, onRetry = onRetry)
            document == null || chapter == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            readingMode == ReadingMode.PAGED -> PagedTextReader(
                document = document,
                style = style,
                chapter = chapter,
                previousChapter = state.previousChapter,
                nextChapter = state.nextChapter,
                restoreToken = state.restoreToken,
                restoreFraction = state.restoreFraction,
                navigation = navigation,
                tapToTurn = tapToTurn,
                onProgress = onProgress,
                onTap = { if (it == TapZone.MENU) onToggleMenus() },
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
            )
            else -> ScrollTextReader(
                document = document,
                style = style,
                chapter = chapter,
                previousChapter = state.previousChapter,
                nextChapter = state.nextChapter,
                restoreToken = state.restoreToken,
                restoreFraction = state.restoreFraction,
                navigation = navigation,
                tapToTurn = tapToTurn,
                onProgress = onProgress,
                onTap = { if (it == TapZone.MENU) onToggleMenus() },
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
            )
        }

        if (showProgress && document != null && !state.menuVisible) {
            Text(
                text = "${(state.progress * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = style.foreground.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 4.dp),
            )
        }

        ReaderTopBar(
            visible = state.menuVisible,
            mangaTitle = state.manga?.title.orEmpty(),
            chapterTitle = chapter?.name.orEmpty(),
            bookmarked = state.bookmarked,
            onNavigateUp = onNavigateUp,
            onToggleBookmark = onToggleBookmark,
            onOpenInWebView = onOpenInWebView,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        val vertical = readingMode == ReadingMode.SCROLL
        if (vertical) {
            VerticalChapterNavigator(
                visible = state.menuVisible,
                progress = state.progress,
                hasPrevious = state.previousChapter != null,
                hasNext = state.nextChapter != null,
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
                onSeek = onSeek,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        ReaderBottomBar(
            visible = state.menuVisible,
            showSlider = !vertical,
            progress = state.progress,
            hasPrevious = state.previousChapter != null,
            hasNext = state.nextChapter != null,
            onPreviousChapter = onPreviousChapter,
            onNextChapter = onNextChapter,
            onSeek = onSeek,
            onOpenChapterList = onOpenChapterList,
            onOpenSettings = onOpenSettings,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ReaderMessage(message: String, style: ReaderTextStyle, onRetry: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = message, color = style.foreground, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text(stringResource(MR.strings.action_retry)) }
        }
    }
}

@Composable
private fun ReaderTopBar(
    visible: Boolean,
    mangaTitle: String,
    chapterTitle: String,
    bookmarked: Boolean,
    onNavigateUp: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateUp) {
                Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack, stringResource(MR.strings.action_bar_up_description))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = mangaTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = chapterTitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    imageVector = if (bookmarked) MaterialSymbols.Rounded.Bookmark else MaterialSymbols.Rounded.BookmarkAdd,
                    contentDescription = stringResource(
                        if (bookmarked) MR.strings.action_remove_bookmark else MR.strings.action_bookmark,
                    ),
                )
            }
            if (onOpenInWebView != null) {
                IconButton(onClick = onOpenInWebView) {
                    Icon(MaterialSymbols.Rounded.Public, stringResource(MR.strings.action_open_in_web_view))
                }
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    visible: Boolean,
    showSlider: Boolean,
    progress: Float,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeek: (Float) -> Unit,
    onOpenChapterList: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            // The slider follows the finger while dragging and only seeks on release, so dragging does not rebuild
            // the page under it at every step.
            var dragging by remember { mutableStateOf(false) }
            var dragValue by remember { mutableFloatStateOf(progress) }
            if (showSlider) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPreviousChapter, enabled = hasPrevious) {
                        Icon(MaterialSymbols.Rounded.SkipPrevious, stringResource(MR.strings.action_previous_chapter))
                    }
                    Slider(
                        value = if (dragging) dragValue else progress,
                        onValueChange = {
                            dragging = true
                            dragValue = it
                        },
                        onValueChangeFinished = {
                            dragging = false
                            onSeek(dragValue)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onNextChapter, enabled = hasNext) {
                        Icon(MaterialSymbols.Rounded.SkipNext, stringResource(MR.strings.action_next_chapter))
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(onClick = onOpenChapterList) {
                    Icon(
                        MaterialSymbols.AutoMirroredRounded.FormatListBulleted,
                        stringResource(MR.strings.chapters),
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(MaterialSymbols.Rounded.Settings, stringResource(MR.strings.action_settings))
                }
            }
        }
    }
}

/**
 * The chapter navigator for scroll mode, standing on the right edge so dragging it moves the same way the text
 * does.
 */
@Composable
private fun VerticalChapterNavigator(
    visible: Boolean,
    progress: Float,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
        modifier = modifier,
    ) {
        var dragging by remember { mutableStateOf(false) }
        var dragValue by remember { mutableFloatStateOf(progress) }
        val shown = if (dragging) dragValue else progress
        Column(
            modifier = Modifier
                .padding(end = 8.dp)
                .fillMaxHeight(0.6f)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(onClick = onPreviousChapter, enabled = hasPrevious) {
                Icon(
                    MaterialSymbols.Rounded.SkipPrevious,
                    stringResource(MR.strings.action_previous_chapter),
                    modifier = Modifier.rotate(90f),
                )
            }
            Slider(
                value = shown,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    dragging = false
                    onSeek(dragValue)
                },
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        rotationZ = 90f
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(
                            Constraints(
                                minWidth = constraints.minHeight,
                                maxWidth = constraints.maxHeight,
                                minHeight = constraints.minWidth,
                                maxHeight = constraints.maxWidth,
                            ),
                        )
                        layout(placeable.height, placeable.width) {
                            placeable.place(0, -placeable.height)
                        }
                    },
            )
            Text(
                text = "${(shown * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
            )
            IconButton(onClick = onNextChapter, enabled = hasNext) {
                Icon(
                    MaterialSymbols.Rounded.SkipNext,
                    stringResource(MR.strings.action_next_chapter),
                    modifier = Modifier.rotate(90f),
                )
            }
        }
    }
}
