package eu.kanade.presentation.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.collections.immutable.persistentMapOf
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Info
import mihon.icons.materialsymbols.rounded.Warning
import mihon.icons.materialsymbols.roundedfilled.CheckCircle
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.calculateChapterGap
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The screen between two chapters, as the image readers showed it: the chapter being left and the one ahead, which
 * of them are downloaded, and a warning when chapters are missing in between. Tapping the chapter ahead opens it.
 *
 * Ported from the image readers' ChapterTransition, drawn in the reader's own text colour so it suits its themes.
 */
@Composable
fun ChapterTransition(
    isNext: Boolean,
    current: Chapter,
    target: Chapter?,
    currentDownloaded: Boolean,
    targetDownloaded: Boolean,
    contentColor: Color,
    onOpenTarget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        if (isNext) {
            TransitionText(
                topLabel = stringResource(MR.strings.transition_finished),
                topChapter = current,
                topChapterDownloaded = currentDownloaded,
                onClickTop = null,
                bottomLabel = stringResource(MR.strings.transition_next),
                bottomChapter = target,
                bottomChapterDownloaded = targetDownloaded,
                onClickBottom = onOpenTarget,
                fallbackLabel = stringResource(MR.strings.transition_no_next),
                chapterGap = calculateChapterGap(target, current),
                modifier = modifier,
            )
        } else {
            TransitionText(
                topLabel = stringResource(MR.strings.transition_previous),
                topChapter = target,
                topChapterDownloaded = targetDownloaded,
                onClickTop = onOpenTarget,
                bottomLabel = stringResource(MR.strings.transition_current),
                bottomChapter = current,
                bottomChapterDownloaded = currentDownloaded,
                onClickBottom = null,
                fallbackLabel = stringResource(MR.strings.transition_no_previous),
                chapterGap = calculateChapterGap(current, target),
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun TransitionText(
    topLabel: String,
    topChapter: Chapter?,
    topChapterDownloaded: Boolean,
    onClickTop: (() -> Unit)?,
    bottomLabel: String,
    bottomChapter: Chapter?,
    bottomChapterDownloaded: Boolean,
    onClickBottom: (() -> Unit)?,
    fallbackLabel: String,
    chapterGap: Int,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(max = 460.dp)
            .fillMaxWidth(),
    ) {
        if (topChapter != null) {
            ChapterText(topLabel, topChapter, topChapterDownloaded, onClickTop)
            Spacer(Modifier.height(VerticalSpacerSize))
        } else {
            NoticeCard(fallbackLabel, isWarning = false, Modifier.align(Alignment.CenterHorizontally))
        }

        if (bottomChapter != null) {
            if (chapterGap > 0) {
                NoticeCard(
                    pluralStringResource(MR.plurals.missing_chapters_warning, count = chapterGap, chapterGap),
                    isWarning = true,
                    Modifier.align(Alignment.CenterHorizontally),
                )
            }
            Spacer(Modifier.height(VerticalSpacerSize))
            ChapterText(bottomLabel, bottomChapter, bottomChapterDownloaded, onClickBottom)
        } else {
            NoticeCard(fallbackLabel, isWarning = false, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun NoticeCard(text: String, isWarning: Boolean, modifier: Modifier = Modifier) {
    OutlinedCard(
        modifier = modifier,
        colors = CardDefaults.outlinedCardColors(
            containerColor = Color.Transparent,
            contentColor = LocalContentColor.current,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isWarning) MaterialSymbols.Rounded.Warning else MaterialSymbols.Rounded.Info,
                tint = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                contentDescription = null,
            )
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ChapterText(header: String, chapter: Chapter, downloaded: Boolean, onClick: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = header,
            modifier = Modifier.padding(bottom = 4.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = buildAnnotatedString {
                if (downloaded) {
                    appendInlineContent(DOWNLOADED_ICON_ID)
                    append(' ')
                }
                append(chapter.name)
            },
            fontSize = 20.sp,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleLarge,
            inlineContent = persistentMapOf(
                DOWNLOADED_ICON_ID to InlineTextContent(
                    Placeholder(width = 22.sp, height = 22.sp, placeholderVerticalAlign = PlaceholderVerticalAlign.Center),
                ) {
                    Icon(
                        imageVector = MaterialSymbols.RoundedFilled.CheckCircle,
                        contentDescription = stringResource(MR.strings.label_downloaded),
                    )
                },
            ),
        )
        chapter.scanlator?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                modifier = Modifier.padding(top = 2.dp),
                color = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private val VerticalSpacerSize = 24.dp
private const val DOWNLOADED_ICON_ID = "downloaded"
private const val SECONDARY_ALPHA = 0.78f
