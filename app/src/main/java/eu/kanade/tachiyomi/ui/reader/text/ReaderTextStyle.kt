package eu.kanade.tachiyomi.ui.reader.text

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.ReaderFont
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.ReaderTextAlign
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.ReaderTheme
import tachiyomi.presentation.core.util.collectAsState

/**
 * Everything the reader needs to lay text out, resolved from the reader preferences. Both reading modes take this,
 * so a setting looks the same whichever mode shows it.
 */
@Immutable
data class ReaderTextStyle(
    val body: TextStyle,
    val heading: TextStyle,
    val subheading: TextStyle,
    val quote: TextStyle,
    val preformatted: TextStyle,
    val paragraphSpacing: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val background: Color,
    val foreground: Color,
    val showImages: Boolean,
) {
    fun forKind(kind: TextBlock.Kind): TextStyle = when (kind) {
        TextBlock.Kind.BODY -> body
        TextBlock.Kind.HEADING -> heading
        TextBlock.Kind.SUBHEADING -> subheading
        TextBlock.Kind.QUOTE -> quote
        TextBlock.Kind.PREFORMATTED -> preformatted
    }
}

@Composable
fun rememberReaderTextStyle(preferences: ReaderPreferences): ReaderTextStyle {
    val fontSize by preferences.fontSize().collectAsState()
    val font by preferences.fontFamily().collectAsState()
    val customFont by preferences.customFont().collectAsState()
    val context = LocalContext.current
    val lineHeight by preferences.lineHeight().collectAsState()
    val paragraphSpacing by preferences.paragraphSpacing().collectAsState()
    val indent by preferences.paragraphIndent().collectAsState()
    val align by preferences.textAlign().collectAsState()
    val horizontalMargin by preferences.horizontalMargin().collectAsState()
    val verticalMargin by preferences.verticalMargin().collectAsState()
    val theme by preferences.theme().collectAsState()
    val showImages by preferences.showImages().collectAsState()

    val colorScheme = MaterialTheme.colorScheme
    val (background, foreground) = when (theme) {
        ReaderTheme.FOLLOW_APP -> colorScheme.surface to colorScheme.onSurface
        ReaderTheme.LIGHT -> Color(0xFFFFFFFF) to Color(0xFF1B1B1B)
        ReaderTheme.SEPIA -> Color(0xFFF4ECD8) to Color(0xFF5B4636)
        ReaderTheme.DARK -> Color(0xFF1E1E1E) to Color(0xFFDADADA)
        ReaderTheme.BLACK -> Color(0xFF000000) to Color(0xFFC8C8C8)
    }

    return remember(
        fontSize, font, customFont, lineHeight, paragraphSpacing, indent, align, horizontalMargin, verticalMargin,
        background, foreground, showImages,
    ) {
        val customFile = ReaderFonts.find(context, customFont)
        val family = if (customFile != null) {
            FontFamily(Font(customFile))
        } else {
            when (font) {
                ReaderFont.DEFAULT -> FontFamily.Default
                ReaderFont.SERIF -> FontFamily.Serif
                ReaderFont.SANS_SERIF -> FontFamily.SansSerif
                ReaderFont.MONOSPACE -> FontFamily.Monospace
            }
        }
        val body = TextStyle(
            color = foreground,
            fontSize = fontSize.sp,
            fontFamily = family,
            lineHeight = lineHeight.em,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
            ),
            textAlign = if (align == ReaderTextAlign.JUSTIFY) TextAlign.Justify else TextAlign.Start,
            textIndent = if (indent > 0f) TextIndent(firstLine = indent.em) else null,
            hyphens = Hyphens.Auto,
            lineBreak = LineBreak.Paragraph,
        )
        ReaderTextStyle(
            body = body,
            heading = body.copy(
                fontSize = (fontSize * 1.4f).sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                textIndent = null,
                lineHeight = 1.3.em,
            ),
            subheading = body.copy(
                fontSize = (fontSize * 1.15f).sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Start,
                textIndent = null,
                lineHeight = 1.3.em,
            ),
            quote = body.copy(fontStyle = FontStyle.Italic, textIndent = TextIndent(1.em, 1.em)),
            preformatted = body.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = (fontSize * 0.85f).sp,
                textAlign = TextAlign.Start,
                textIndent = null,
                hyphens = Hyphens.None,
            ),
            paragraphSpacing = (fontSize * paragraphSpacing).dp,
            horizontalPadding = horizontalMargin.dp,
            verticalPadding = verticalMargin.dp,
            background = background,
            foreground = foreground,
            showImages = showImages,
        )
    }
}
