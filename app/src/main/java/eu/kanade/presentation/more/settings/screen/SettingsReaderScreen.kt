package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsReaderScreen : SearchableSettings {
    private fun readResolve(): Any = SettingsReaderScreen

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_reader

    @Composable
    override fun getPreferences(): List<Preference> {
        val readerPref = remember { Injekt.get<ReaderPreferences>() }
        return listOf(
            getTextGroup(readerPref),
            getDisplayGroup(readerPref),
            getReadingGroup(readerPref),
        )
    }

    @Composable
    private fun getTextGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val fontSize by readerPreferences.fontSize().collectAsState()
        return Preference.PreferenceGroup(
            title = stringResource(KMR.strings.reader_settings_text),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.readingMode(),
                    entries = ReaderPreferences.ReadingMode.entries
                        .associateWith { stringResource(it.titleRes) }
                        .toImmutableMap(),
                    title = stringResource(KMR.strings.reader_settings_mode),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.theme(),
                    entries = ReaderPreferences.ReaderTheme.entries
                        .associateWith { stringResource(it.titleRes) }
                        .toImmutableMap(),
                    title = stringResource(KMR.strings.reader_settings_theme),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.fontFamily(),
                    entries = ReaderPreferences.ReaderFont.entries
                        .associateWith { stringResource(it.titleRes) }
                        .toImmutableMap(),
                    title = stringResource(KMR.strings.reader_settings_font),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = fontSize,
                    valueRange = ReaderPreferences.MIN_FONT_SIZE..ReaderPreferences.MAX_FONT_SIZE,
                    title = stringResource(KMR.strings.reader_settings_font_size),
                    valueString = fontSize.toString(),
                    onValueChanged = { readerPreferences.fontSize().set(it) },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.textAlign(),
                    entries = ReaderPreferences.ReaderTextAlign.entries
                        .associateWith { stringResource(it.titleRes) }
                        .toImmutableMap(),
                    title = stringResource(KMR.strings.reader_settings_align),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.showImages(),
                    title = stringResource(KMR.strings.reader_settings_show_images),
                ),
            ),
        )
    }

    @Composable
    private fun getDisplayGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_display),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.fullscreen(),
                    title = stringResource(MR.strings.pref_fullscreen),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.drawUnderCutout(),
                    title = stringResource(MR.strings.pref_cutout_short),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.keepScreenOn(),
                    title = stringResource(MR.strings.pref_keep_screen_on),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.showProgress(),
                    title = stringResource(KMR.strings.reader_settings_show_progress),
                ),
            ),
        )
    }

    @Composable
    private fun getReadingGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val volumeKeys by readerPreferences.readWithVolumeKeys().collectAsState()
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_reading),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.skipRead(),
                    title = stringResource(MR.strings.pref_skip_read_chapters),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.skipFiltered(),
                    title = stringResource(MR.strings.pref_skip_filtered_chapters),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.skipDupe(),
                    title = stringResource(MR.strings.pref_skip_dupe_chapters),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.tapToTurnPages(),
                    title = stringResource(KMR.strings.reader_settings_tap_to_turn),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.appendNextChapter(),
                    title = stringResource(KMR.strings.reader_settings_append_next_chapter),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.readWithVolumeKeys(),
                    title = stringResource(MR.strings.pref_read_with_volume_keys),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.readWithVolumeKeysInverted(),
                    title = stringResource(MR.strings.pref_read_with_volume_keys_inverted),
                    enabled = volumeKeys,
                ),
            ),
        )
    }
}
