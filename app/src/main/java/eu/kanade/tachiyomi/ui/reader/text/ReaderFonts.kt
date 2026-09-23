package eu.kanade.tachiyomi.ui.reader.text

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Font files the reader can use besides the system ones. A picked file is copied into the app's own storage, so it
 * keeps working when the original moves or its permission lapses; the preference names it by file name.
 */
object ReaderFonts {

    /** Types a document picker may report a font file as; many report plain binary. */
    val MIME_TYPES = arrayOf(
        "font/*",
        "application/x-font-ttf",
        "application/x-font-otf",
        "application/font-sfnt",
        "application/vnd.ms-opentype",
        "application/octet-stream",
    )

    private val EXTENSIONS = setOf("ttf", "otf", "ttc")

    fun directory(context: Context) = File(context.filesDir, "fonts")

    fun list(context: Context): List<File> = directory(context)
        .listFiles { file -> file.isFile && file.extension.lowercase() in EXTENSIONS }
        ?.sortedBy { it.name.lowercase() }
        .orEmpty()

    fun find(context: Context, name: String): File? =
        name.takeIf { it.isNotEmpty() }?.let { File(directory(context), it) }?.takeIf { it.isFile }

    /** The file's name without its extension, as the reader lists it. */
    fun title(file: File) = file.nameWithoutExtension

    /**
     * Copies the font at [uri] into the reader's fonts and returns the copy, or null when the file is not a font
     * Android can load.
     */
    fun add(context: Context, uri: Uri): File? {
        val name = displayName(context, uri)
            ?.replace(Regex("""[/\\:*?"<>|]"""), "_")
            ?.takeIf { it.substringAfterLast('.', "").lowercase() in EXTENSIONS }
            ?: "font-${System.currentTimeMillis()}.ttf"
        val directory = directory(context).apply { mkdirs() }
        val temporary = File(directory, ".$name.tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { input.copyTo(it) }
            } ?: return null
            if (!isFont(temporary)) return null
            val target = File(directory, name)
            target.delete()
            return target.takeIf { temporary.renameTo(it) }
        } finally {
            temporary.delete()
        }
    }

    fun remove(file: File) = file.delete()

    /** Whether [file] starts like a TrueType, OpenType or font collection file. */
    private fun isFont(file: File): Boolean {
        val magic = ByteArray(4)
        val read = file.inputStream().use { it.read(magic) }
        return read == 4 && FONT_MAGIC.any { it.contentEquals(magic) }
    }

    private val FONT_MAGIC = listOf(
        byteArrayOf(0, 1, 0, 0),
        "OTTO".toByteArray(),
        "true".toByteArray(),
        "ttcf".toByteArray(),
    )

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
}
