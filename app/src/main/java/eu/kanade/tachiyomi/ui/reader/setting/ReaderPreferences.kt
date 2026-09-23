package eu.kanade.tachiyomi.ui.reader.setting

import dev.icerock.moko.resources.StringResource
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR

class ReaderPreferences(
    private val preferenceStore: PreferenceStore,
) {

    // region Display

    fun readingMode() = preferenceStore.getEnum("pref_text_reading_mode", ReadingMode.SCROLL)

    fun fontSize() = preferenceStore.getInt("pref_text_font_size", DEFAULT_FONT_SIZE)

    fun fontFamily() = preferenceStore.getEnum("pref_text_font_family", ReaderFont.SERIF)

    /** File name of an added font the reader uses instead of [fontFamily]; empty for none. */
    fun customFont() = preferenceStore.getString("pref_text_custom_font", "")

    fun lineHeight() = preferenceStore.getFloat("pref_text_line_height", 1.6f)

    fun paragraphSpacing() = preferenceStore.getFloat("pref_text_paragraph_spacing", 0.9f)

    fun paragraphIndent() = preferenceStore.getFloat("pref_text_paragraph_indent", 0f)

    fun textAlign() = preferenceStore.getEnum("pref_text_align", ReaderTextAlign.JUSTIFY)

    fun horizontalMargin() = preferenceStore.getInt("pref_text_horizontal_margin", 20)

    fun verticalMargin() = preferenceStore.getInt("pref_text_vertical_margin", 24)

    fun theme() = preferenceStore.getEnum("pref_text_theme", ReaderTheme.FOLLOW_APP)

    fun showImages() = preferenceStore.getBoolean("pref_text_show_images", true)

    // endregion

    // region Behaviour

    fun fullscreen() = preferenceStore.getBoolean("fullscreen", true)

    fun drawUnderCutout() = preferenceStore.getBoolean("cutout_short", true)

    fun keepScreenOn() = preferenceStore.getBoolean("pref_keep_screen_on_key", false)

    fun showProgress() = preferenceStore.getBoolean("pref_show_page_number_key", true)

    fun readWithVolumeKeys() = preferenceStore.getBoolean("reader_volume_keys", false)

    fun readWithVolumeKeysInverted() = preferenceStore.getBoolean("reader_volume_keys_inverted", false)

    fun tapToTurnPages() = preferenceStore.getBoolean("pref_text_tap_to_turn", true)

    /** In scroll mode, show the next chapter below the end of this one, so reading carries on. */
    fun speechRate() = preferenceStore.getFloat("pref_text_speech_rate", 1f)

    fun speechPitch() = preferenceStore.getFloat("pref_text_speech_pitch", 1f)

    fun appendNextChapter() = preferenceStore.getBoolean("pref_text_append_next_chapter", true)

    fun skipRead() = preferenceStore.getBoolean("skip_read", false)

    fun skipFiltered() = preferenceStore.getBoolean("skip_filtered", true)

    fun skipDupe() = preferenceStore.getBoolean("skip_dupe", false)

    // KMK --> in MB; the chapter text cache
    fun cacheSize() = preferenceStore.getString("pref_reader_cache_size", "75")
    // KMK <--

    // endregion

    enum class ReadingMode(val titleRes: StringResource, private val flag: Long) {
        SCROLL(KMR.strings.reader_mode_scroll, 1L),
        PAGED(KMR.strings.reader_mode_paged, 2L),
        ;

        companion object {
            /** The low bits of a novel's viewer flags hold its own reading mode; 0 means the default one. */
            const val FLAG_MASK = 0x7L

            fun fromFlags(flags: Long): ReadingMode? = entries.firstOrNull { it.flag == flags and FLAG_MASK }

            fun toFlags(flags: Long, mode: ReadingMode?): Long = (flags and FLAG_MASK.inv()) or (mode?.flag ?: 0L)
        }
    }

    enum class ReaderFont(val titleRes: StringResource) {
        DEFAULT(MR.strings.label_default),
        SERIF(KMR.strings.reader_font_serif),
        SANS_SERIF(KMR.strings.reader_font_sans_serif),
        MONOSPACE(KMR.strings.reader_font_monospace),
    }

    enum class ReaderTextAlign(val titleRes: StringResource) {
        START(KMR.strings.reader_align_start),
        JUSTIFY(KMR.strings.reader_align_justify),
    }

    enum class ReaderTheme(val titleRes: StringResource) {
        FOLLOW_APP(KMR.strings.reader_theme_app),
        LIGHT(MR.strings.theme_light),
        SEPIA(KMR.strings.reader_theme_sepia),
        DARK(MR.strings.theme_dark),
        BLACK(KMR.strings.reader_theme_black),
    }

    companion object {
        const val DEFAULT_FONT_SIZE = 18
        const val MIN_FONT_SIZE = 10
        const val MAX_FONT_SIZE = 40
    }
}
