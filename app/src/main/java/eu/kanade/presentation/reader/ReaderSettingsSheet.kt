package eu.kanade.presentation.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.text.ReaderFonts
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Close
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ReaderSettingsSheet(
    preferences: ReaderPreferences,
    novelReadingMode: ReaderPreferences.ReadingMode?,
    onNovelReadingModeChange: (ReaderPreferences.ReadingMode?) -> Unit,
    onDismissRequest: () -> Unit,
) {
    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = persistentListOf(
            stringResource(KMR.strings.reader_settings_text),
            stringResource(MR.strings.pref_category_general),
        ),
    ) { page ->
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> TextPage(preferences, novelReadingMode, onNovelReadingModeChange)
                1 -> GeneralPage(preferences)
            }
        }
    }
}

@Composable
private fun TextPage(
    preferences: ReaderPreferences,
    novelReadingMode: ReaderPreferences.ReadingMode?,
    onNovelReadingModeChange: (ReaderPreferences.ReadingMode?) -> Unit,
) {
    // Here the reading mode is this novel's own; the default is in the reader settings.
    SettingsChipRow(KMR.strings.reader_settings_mode) {
        FilterChip(
            selected = novelReadingMode == null,
            onClick = { onNovelReadingModeChange(null) },
            label = { Text(stringResource(MR.strings.label_default)) },
        )
        ReaderPreferences.ReadingMode.entries.forEach { mode ->
            FilterChip(
                selected = novelReadingMode == mode,
                onClick = { onNovelReadingModeChange(mode) },
                label = { Text(stringResource(mode.titleRes)) },
            )
        }
    }
    EnumChips(KMR.strings.reader_settings_theme, preferences.theme(), ReaderPreferences.ReaderTheme.entries) {
        stringResource(it.titleRes)
    }
    FontChips(preferences)
    EnumChips(KMR.strings.reader_settings_align, preferences.textAlign(), ReaderPreferences.ReaderTextAlign.entries) {
        stringResource(it.titleRes)
    }

    val fontSize by preferences.fontSize().collectAsState()
    SliderItem(
        value = fontSize,
        valueRange = ReaderPreferences.MIN_FONT_SIZE..ReaderPreferences.MAX_FONT_SIZE,
        label = stringResource(KMR.strings.reader_settings_font_size),
        onChange = { preferences.fontSize().set(it) },
    )
    FloatSlider(KMR.strings.reader_settings_line_height, preferences.lineHeight(), 1.0f, 2.5f, step = 0.1f)
    FloatSlider(KMR.strings.reader_settings_paragraph_spacing, preferences.paragraphSpacing(), 0f, 3f, step = 0.1f)
    FloatSlider(KMR.strings.reader_settings_indent, preferences.paragraphIndent(), 0f, 4f, step = 0.5f)

    val horizontalMargin by preferences.horizontalMargin().collectAsState()
    SliderItem(
        value = horizontalMargin,
        valueRange = 0..64 step 4,
        label = stringResource(KMR.strings.reader_settings_horizontal_margin),
        onChange = { preferences.horizontalMargin().set(it) },
        steps = 15,
    )
    val verticalMargin by preferences.verticalMargin().collectAsState()
    SliderItem(
        value = verticalMargin,
        valueRange = 0..96 step 4,
        label = stringResource(KMR.strings.reader_settings_vertical_margin),
        onChange = { preferences.verticalMargin().set(it) },
        steps = 23,
    )
}

@Composable
private fun GeneralPage(preferences: ReaderPreferences) {
    CheckboxItem(stringResource(KMR.strings.reader_settings_show_images), preferences.showImages())
    CheckboxItem(stringResource(KMR.strings.reader_settings_tap_to_turn), preferences.tapToTurnPages())
    CheckboxItem(stringResource(KMR.strings.reader_settings_append_next_chapter), preferences.appendNextChapter())
    CheckboxItem(stringResource(MR.strings.pref_read_with_volume_keys), preferences.readWithVolumeKeys())
    val volumeKeys by preferences.readWithVolumeKeys().collectAsState()
    if (volumeKeys) {
        CheckboxItem(
            stringResource(MR.strings.pref_read_with_volume_keys_inverted),
            preferences.readWithVolumeKeysInverted(),
        )
    }
    CheckboxItem(stringResource(KMR.strings.reader_settings_show_progress), preferences.showProgress())
    CheckboxItem(stringResource(MR.strings.pref_fullscreen), preferences.fullscreen())
    CheckboxItem(stringResource(MR.strings.pref_keep_screen_on), preferences.keepScreenOn())

    HeadingItem(KMR.strings.reader_settings_tts)
    FloatSlider(KMR.strings.reader_settings_tts_rate, preferences.speechRate(), 0.5f, 3f, step = 0.1f)
    FloatSlider(KMR.strings.reader_settings_tts_pitch, preferences.speechPitch(), 0.5f, 2f, step = 0.1f)
}

/**
 * The system fonts and every added font file, with a chip to add another. The selected added font can be removed
 * from its chip.
 */
@Composable
private fun FontChips(preferences: ReaderPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val font by preferences.fontFamily().collectAsState()
    val customFont by preferences.customFont().collectAsState()
    var files by remember { mutableStateOf(ReaderFonts.list(context)) }
    val invalidMessage = stringResource(KMR.strings.reader_settings_font_invalid)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val added = withIOContext { ReaderFonts.add(context, uri) }
            if (added == null) {
                context.toast(invalidMessage)
            } else {
                files = ReaderFonts.list(context)
                preferences.customFont().set(added.name)
            }
        }
    }

    SettingsChipRow(KMR.strings.reader_settings_font) {
        ReaderPreferences.ReaderFont.entries.forEach { entry ->
            FilterChip(
                selected = customFont.isEmpty() && font == entry,
                onClick = {
                    preferences.fontFamily().set(entry)
                    preferences.customFont().set("")
                },
                label = { Text(stringResource(entry.titleRes)) },
            )
        }
        files.forEach { file ->
            val selected = customFont == file.name
            FilterChip(
                selected = selected,
                onClick = { preferences.customFont().set(file.name) },
                label = { Text(ReaderFonts.title(file)) },
                trailingIcon = if (selected) {
                    {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Close,
                            contentDescription = stringResource(KMR.strings.reader_settings_remove_font),
                            modifier = Modifier
                                .size(FilterChipDefaults.IconSize)
                                .clickable {
                                    ReaderFonts.remove(file)
                                    preferences.customFont().set("")
                                    files = ReaderFonts.list(context)
                                },
                        )
                    }
                } else {
                    null
                },
            )
        }
        AssistChip(
            onClick = { picker.launch(ReaderFonts.MIME_TYPES) },
            label = { Text(stringResource(KMR.strings.reader_settings_add_font)) },
            leadingIcon = {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
        )
    }
}

@Composable
private fun <T : Enum<T>> EnumChips(
    label: dev.icerock.moko.resources.StringResource,
    preference: Preference<T>,
    entries: List<T>,
    title: @Composable (T) -> String,
) {
    val selected by preference.collectAsState()
    SettingsChipRow(label) {
        entries.forEach { entry ->
            FilterChip(
                selected = selected == entry,
                onClick = { preference.set(entry) },
                label = { Text(title(entry)) },
            )
        }
    }
}

/**
 * A slider over a float preference, in [step] increments. The generic slider item works on integers, so the value
 * is scaled to whole steps and back.
 */
@Composable
private fun FloatSlider(
    label: dev.icerock.moko.resources.StringResource,
    preference: Preference<Float>,
    min: Float,
    max: Float,
    step: Float,
) {
    val value by preference.collectAsState()
    val steps = ((max - min) / step).roundToInt()
    SliderItem(
        value = ((value - min) / step).roundToInt().coerceIn(0, steps),
        valueRange = 0..steps,
        label = stringResource(label),
        valueString = String.format(Locale.ROOT, "%.1f", value),
        onChange = { preference.set(min + it * step) },
        steps = steps - 1,
    )
}
