package eu.kanade.tachiyomi.ui.reader.text

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/** A request to move through the chapter, from volume keys or the keyboard. */
enum class ReaderNavigation { FORWARD, BACKWARD }

/** Where a tap landed, for tap-to-turn and showing the menus. */
enum class TapZone { PREVIOUS, MENU, NEXT }

/**
 * The chapter in scroll mode: one long column of paragraphs, with the neighbouring chapters at either end.
 */
@Composable
fun ScrollTextReader(
    document: ChapterDocument,
    style: ReaderTextStyle,
    chapter: Chapter,
    previousChapter: Chapter?,
    nextChapter: Chapter?,
    restoreToken: Int,
    restoreFraction: Float,
    navigation: Flow<ReaderNavigation>,
    tapToTurn: Boolean,
    onProgress: (fraction: Float, reachedEnd: Boolean) -> Unit,
    onTap: (TapZone) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentOnProgress by rememberUpdatedState(onProgress)

    // Item 0 is the header, blocks follow, and the last item is the footer.
    LaunchedEffect(restoreToken, document) {
        val offset = (restoreFraction * document.length).toInt()
        val blockIndex = document.blockIndexAt(offset)
        listState.scrollToItem(blockIndex + 1)
        val block = document.blocks.getOrNull(blockIndex) ?: return@LaunchedEffect
        val within = ((offset - block.start).toFloat() / block.length).coerceIn(0f, 1f)
        val size = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: return@LaunchedEffect
        if (within > 0f) listState.scrollBy(within * size)
    }

    LaunchedEffect(listState, document) {
        snapshotFlow { listState.readingPosition(document) }
            .distinctUntilChanged()
            .collectLatest { (fraction, reachedEnd) -> currentOnProgress(fraction, reachedEnd) }
    }

    LaunchedEffect(navigation) {
        navigation.collect { direction ->
            val viewport = listState.layoutInfo.let { it.viewportEndOffset - it.viewportStartOffset }
            val distance = viewport * PAGE_SCROLL_FRACTION
            listState.animateScrollBy(if (direction == ReaderNavigation.FORWARD) distance else -distance)
        }
    }

    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(style.background)
            .pointerInput(tapToTurn) {
                detectTapGestures { position ->
                    val zone = when {
                        !tapToTurn -> TapZone.MENU
                        position.y < size.height / 3f -> TapZone.PREVIOUS
                        position.y > size.height * 2f / 3f -> TapZone.NEXT
                        else -> TapZone.MENU
                    }
                    when (zone) {
                        TapZone.MENU -> onTap(zone)
                        else -> scope.launch {
                            val viewport = with(density) { size.height.toFloat() }
                            val distance = viewport * PAGE_SCROLL_FRACTION
                            listState.animateScrollBy(if (zone == TapZone.NEXT) distance else -distance)
                        }
                    }
                }
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = style.horizontalPadding, vertical = style.verticalPadding),
        ) {
            item(key = "header") {
                ChapterBoundary(
                    style = style,
                    title = chapter.name,
                    neighbour = previousChapter,
                    neighbourLabel = stringResource(MR.strings.transition_previous),
                    onClick = onPreviousChapter,
                    atStart = true,
                )
            }
            itemsIndexed(document.blocks, key = { index, _ -> index }) { _, block ->
                BlockContent(block, style, Modifier.padding(bottom = style.paragraphSpacing))
            }
            item(key = "footer") {
                ChapterBoundary(
                    style = style,
                    title = null,
                    neighbour = nextChapter,
                    neighbourLabel = stringResource(MR.strings.transition_next),
                    onClick = onNextChapter,
                    atStart = false,
                )
            }
        }
    }
}

/**
 * The chapter in paged mode: pages laid out to the screen, turned sideways, with a page for each neighbouring
 * chapter at either end.
 */
@Composable
fun PagedTextReader(
    document: ChapterDocument,
    style: ReaderTextStyle,
    chapter: Chapter,
    previousChapter: Chapter?,
    nextChapter: Chapter?,
    restoreToken: Int,
    restoreFraction: Float,
    navigation: Flow<ReaderNavigation>,
    tapToTurn: Boolean,
    onProgress: (fraction: Float, reachedEnd: Boolean) -> Unit,
    onTap: (TapZone) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val currentOnProgress by rememberUpdatedState(onProgress)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(style.background),
    ) {
        val width = with(density) { (maxWidth - style.horizontalPadding * 2).roundToPx() }
        val height = with(density) { (maxHeight - style.verticalPadding * 2).roundToPx() }
        val paragraphSpacing = with(density) { style.paragraphSpacing.toPx() }
        val ruleHeight = with(density) { RULE_HEIGHT.toPx() }

        val pages by produceState<List<TextPage>?>(null, document, style, width, height) {
            value = null
            value = withContext(Dispatchers.Default) {
                TextPaginator.paginate(document, style, measurer, width, height, paragraphSpacing, ruleHeight)
            }
        }
        val laidOut = pages ?: return@BoxWithConstraints

        // Page 0 leads to the previous chapter and the last page to the next one.
        val pagerState = rememberPagerState(
            initialPage = 1 + laidOut.pageAt(restoreFraction, document),
            pageCount = { laidOut.size + 2 },
        )
        val scope = rememberCoroutineScope()

        LaunchedEffect(restoreToken) {
            val target = 1 + laidOut.pageAt(restoreFraction, document)
            if (pagerState.currentPage != target) pagerState.scrollToPage(target)
        }

        LaunchedEffect(pagerState, laidOut) {
            snapshotFlow { pagerState.settledPage }
                .distinctUntilChanged()
                .collectLatest { index ->
                    val contentIndex = (index - 1).coerceIn(0, (laidOut.size - 1).coerceAtLeast(0))
                    val page = laidOut.getOrNull(contentIndex) ?: return@collectLatest
                    val fraction = if (document.length == 0) 0f else page.startOffset.toFloat() / document.length
                    currentOnProgress(fraction, index >= laidOut.size)
                }
        }

        LaunchedEffect(navigation, pagerState) {
            navigation.collect { direction ->
                pagerState.turn(direction == ReaderNavigation.FORWARD, onPreviousChapter, onNextChapter)
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(tapToTurn) {
                    detectTapGestures { position ->
                        val zone = when {
                            !tapToTurn -> TapZone.MENU
                            position.x < size.width / 3f -> TapZone.PREVIOUS
                            position.x > size.width * 2f / 3f -> TapZone.NEXT
                            else -> TapZone.MENU
                        }
                        when (zone) {
                            TapZone.MENU -> onTap(zone)
                            else -> scope.launch {
                                pagerState.turn(zone == TapZone.NEXT, onPreviousChapter, onNextChapter)
                            }
                        }
                    }
                },
            key = { it },
        ) { index ->
            when (index) {
                0 -> ChapterBoundary(
                    style = style,
                    title = chapter.name,
                    neighbour = previousChapter,
                    neighbourLabel = stringResource(MR.strings.transition_previous),
                    onClick = onPreviousChapter,
                    atStart = true,
                    modifier = Modifier.fillMaxSize(),
                )
                laidOut.size + 1 -> ChapterBoundary(
                    style = style,
                    title = null,
                    neighbour = nextChapter,
                    neighbourLabel = stringResource(MR.strings.transition_next),
                    onClick = onNextChapter,
                    atStart = false,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> TextPageContent(
                    page = laidOut[index - 1],
                    style = style,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = style.horizontalPadding, vertical = style.verticalPadding),
                )
            }
        }
    }
}

private suspend fun PagerState.turn(forward: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    when {
        forward && currentPage == pageCount - 1 -> onNext()
        !forward && currentPage == 0 -> onPrevious()
        else -> animateScrollToPage(currentPage + if (forward) 1 else -1)
    }
}

private fun List<TextPage>.pageAt(fraction: Float, document: ChapterDocument): Int {
    if (isEmpty()) return 0
    val offset = (fraction * document.length).toInt()
    return (indexOfLast { it.startOffset <= offset }).coerceIn(0, lastIndex)
}

@Composable
private fun TextPageContent(page: TextPage, style: ReaderTextStyle, modifier: Modifier = Modifier) {
    val image = page.items.singleOrNull() as? PageItem.Image
    if (image != null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            AsyncImage(
                model = image.block.url,
                contentDescription = image.block.description,
                // Inside: a small image, like an icon, stays its own size instead of filling the page.
                contentScale = ContentScale.Inside,
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }
    Canvas(modifier) {
        page.items.forEach { item ->
            when (item) {
                is PageItem.Lines -> clipRect(top = item.y, bottom = item.y + item.height) {
                    drawText(item.layout, topLeft = Offset(0f, item.y - item.top))
                }
                is PageItem.Rule -> {
                    val y = item.y + RULE_HEIGHT.toPx() / 2
                    drawLine(
                        color = style.foreground.copy(alpha = 0.4f),
                        start = Offset(size.width * 0.35f, y),
                        end = Offset(size.width * 0.65f, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                is PageItem.Image -> Unit
            }
        }
    }
}

@Composable
private fun BlockContent(block: TextBlock, style: ReaderTextStyle, modifier: Modifier = Modifier) {
    when (block) {
        is TextBlock.Paragraph -> Text(
            text = block.text,
            style = style.forKind(block.kind),
            modifier = modifier.fillMaxWidth(),
        )
        is TextBlock.Image -> if (style.showImages) {
            // Scaled down to the column width but never up, so a small image stays its own size.
            Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = block.url,
                    contentDescription = block.description,
                    contentScale = ContentScale.Fit,
                )
            }
        }
        is TextBlock.Rule -> HorizontalDivider(
            modifier = modifier
                .padding(horizontal = 64.dp)
                .padding(vertical = RULE_HEIGHT / 2),
            color = style.foreground.copy(alpha = 0.4f),
        )
    }
}

@Composable
private fun ChapterBoundary(
    style: ReaderTextStyle,
    title: String?,
    neighbour: Chapter?,
    neighbourLabel: String,
    onClick: () -> Unit,
    atStart: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = style.horizontalPadding, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!atStart) {
            Text(
                text = stringResource(KMR.strings.reader_end_of_chapter),
                style = style.subheading.copy(textAlign = TextAlign.Center),
            )
            Spacer(Modifier.height(16.dp))
        }
        if (neighbour != null) {
            OutlinedButton(onClick = onClick) {
                Text(text = "$neighbourLabel ${neighbour.name}", maxLines = 2)
            }
        } else {
            Text(
                text = stringResource(
                    if (atStart) MR.strings.transition_no_previous else MR.strings.transition_no_next,
                ),
                style = style.body.copy(textAlign = TextAlign.Center, color = style.foreground.copy(alpha = 0.7f)),
            )
        }
        if (atStart && title != null) {
            Spacer(Modifier.height(32.dp))
            Text(text = title, style = style.heading)
            Spacer(Modifier.width(0.dp))
        }
    }
}

/** Fraction of the chapter above the top of the screen, and whether the end of the chapter is visible. */
private fun LazyListState.readingPosition(document: ChapterDocument): Pair<Float, Boolean> {
    val info = layoutInfo
    val lastIndex = info.totalItemsCount - 1
    val reachedEnd = info.visibleItemsInfo.any { it.index == lastIndex }
    if (document.length == 0) return 0f to reachedEnd
    val first = info.visibleItemsInfo.firstOrNull() ?: return 0f to reachedEnd
    val block = document.blocks.getOrNull(first.index - 1) ?: return (if (first.index == 0) 0f else 1f) to reachedEnd
    val within = if (first.size > 0) (firstVisibleItemScrollOffset.toFloat() / first.size).coerceIn(0f, 1f) else 0f
    val offset = block.start + within * block.length
    return (offset / document.length).coerceIn(0f, 1f) to reachedEnd
}

private const val PAGE_SCROLL_FRACTION = 0.9f
private val RULE_HEIGHT = 32.dp
