package eu.kanade.presentation.reader

import eu.kanade.tachiyomi.ui.reader.ReaderViewModel

/**
 * How far into a chapter [lastPageRead] is, in whole percent. A chapter that has been opened never shows 0%, and one
 * that is not finished never shows 100%.
 */
fun readPercent(lastPageRead: Long): Int {
    return (lastPageRead * 100 / ReaderViewModel.PROGRESS_SCALE).toInt().coerceIn(1, 99)
}
