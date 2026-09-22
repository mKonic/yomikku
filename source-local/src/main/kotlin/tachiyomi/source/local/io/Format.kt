package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import tachiyomi.core.common.storage.extension

/**
 * What a file in a local novel's folder holds.
 */
sealed interface Format {
    /** A whole book; each document in its reading order is a chapter. */
    data class Epub(val file: UniFile) : Format

    /** One chapter of plain text. */
    data class Text(val file: UniFile) : Format

    /** One chapter of HTML. */
    data class Html(val file: UniFile) : Format

    class UnknownFormatException : Exception()

    companion object {
        private val TEXT = setOf("txt", "text", "md")
        private val HTML = setOf("html", "htm", "xhtml")

        fun isSupported(file: UniFile): Boolean {
            if (!file.isFile) return false
            val extension = file.extension?.lowercase() ?: return false
            return extension == "epub" || extension in TEXT || extension in HTML
        }

        fun valueOf(file: UniFile): Format {
            val extension = file.extension?.lowercase()
            return when {
                !file.isFile -> throw UnknownFormatException()
                extension == "epub" -> Epub(file)
                extension in TEXT -> Text(file)
                extension in HTML -> Html(file)
                else -> throw UnknownFormatException()
            }
        }
    }
}
