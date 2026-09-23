package tachiyomi.source.local

import android.content.Context
import android.net.Uri
import android.text.TextUtils
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import mihon.core.archive.ArchiveReader
import mihon.core.archive.ZipWriter
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import nl.adaptivity.xmlutil.core.AndroidXmlReader
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.ComicInfoPublishingStatus
import tachiyomi.core.metadata.comicinfo.copyFromComicInfo
import tachiyomi.core.metadata.comicinfo.dateMillis
import tachiyomi.core.metadata.comicinfo.getComicInfo
import tachiyomi.core.metadata.tachiyomi.MangaDetails
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.source.local.epub.EpubBook
import tachiyomi.source.local.filter.ArtistFilter
import tachiyomi.source.local.filter.AuthorFilter
import tachiyomi.source.local.filter.GenreFilter
import tachiyomi.source.local.filter.OrderBy
import tachiyomi.source.local.filter.StatusFilter
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.time.Duration.Companion.days
import tachiyomi.domain.source.model.Source as DomainSource

class LocalSource(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
    private val coverManager: LocalCoverManager,
    // SY -->
    private val allowHiddenFiles: () -> Boolean,
    // SY <--
) : Source, UnmeteredSource {

    private val json: Json by injectLazy()
    private val xml: XML by injectLazy()

    @Suppress("PrivatePropertyName")
    private val PopularFilters = FilterList(OrderBy.Popular(context))

    @Suppress("PrivatePropertyName")
    private val LatestFilters = FilterList(OrderBy.Latest(context))

    override val name: String = context.stringResource(MR.strings.local_source)

    override val id: Long = ID

    override val lang: String = "other"

    override fun toString() = name

    override val supportsLatest: Boolean = true

    // Browse related
    override suspend fun getPopularManga(page: Int) = getSearchManga(page, "", PopularFilters)

    override suspend fun getLatestUpdates(page: Int) = getSearchManga(page, "", LatestFilters)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withIOContext {
        val lastModifiedLimit = if (filters === LatestFilters) {
            System.currentTimeMillis() - LATEST_THRESHOLD
        } else {
            0L
        }
        // SY -->
        val allowLocalSourceHiddenFolders = allowHiddenFiles()
        // SY <--

        // KMK --> filter on the metadata files, not only the folder name (mihonapp/mihon#3782)
        var authorFilter = ""
        var artistFilter = ""
        var genreFilter = ""
        var statusFilter = StatusFilter.ANY
        filters.forEach { filter ->
            when (filter) {
                is AuthorFilter -> authorFilter = filter.state.trim()
                is ArtistFilter -> artistFilter = filter.state.trim()
                is GenreFilter -> genreFilter = filter.state.trim()
                is StatusFilter -> statusFilter = StatusFilter.statusFor(filter.state)
                else -> Unit
            }
        }
        val genreTerms = genreFilter.split(',').map { it.trim() }.filterNot { it.isBlank() }
        // Reading every entry's metadata is only worth it when the query needs it; plain browsing stays cheap.
        val needsMetadata = query.isNotBlank() ||
            authorFilter.isNotBlank() ||
            artistFilter.isNotBlank() ||
            genreTerms.isNotEmpty() ||
            statusFilter != StatusFilter.ANY
        // KMK <--

        var mangaDirs = fileSystem.getFilesInBaseDirectory()
            // Filter out files that are hidden and is not a folder
            .filter {
                it.isDirectory &&
                    // SY -->
                    (
                        !it.name.orEmpty().startsWith('.') ||
                            allowLocalSourceHiddenFolders
                        )
                // SY <--
            }
            .distinctBy { it.name }
            .filter { lastModifiedLimit == 0L || it.lastModified() >= lastModifiedLimit }

        // KMK -->
        if (needsMetadata) {
            // Opening an entry searches every keyword of its title at once, so a search that read every folder in
            // parallel ran several full scans together. Folders are read a few at a time, sized to the device, a name
            // match needs no read when the query is all there is, and only matches are kept.
            val queryOnly = authorFilter.isBlank() && artistFilter.isBlank() && genreTerms.isEmpty() &&
                statusFilter == StatusFilter.ANY
            val readsAtOnce = Runtime.getRuntime().availableProcessors() * 2
            mangaDirs = mangaDirs.chunked(readsAtOnce).flatMap { chunk ->
                chunk.map { mangaDir ->
                    async {
                        val nameMatches = mangaDir.name.orEmpty().contains(query, ignoreCase = true)
                        if (queryOnly && nameMatches) return@async mangaDir
                        val metadata = getMetadataForFiltering(mangaDir)
                        val matchesQuery = query.isBlank() || nameMatches || metadata.matchesText(query)
                        val matchesAuthor = authorFilter.isBlank() ||
                            metadata?.author.orEmpty().contains(authorFilter, ignoreCase = true)
                        val matchesArtist = artistFilter.isBlank() ||
                            metadata?.artist.orEmpty().contains(artistFilter, ignoreCase = true)
                        val matchesGenre = genreTerms.all { term ->
                            metadata?.genres.orEmpty().any { it.contains(term, ignoreCase = true) }
                        }
                        val matchesStatus = statusFilter == StatusFilter.ANY || metadata?.status == statusFilter
                        mangaDir.takeIf { matchesQuery && matchesAuthor && matchesArtist && matchesGenre && matchesStatus }
                    }
                }.awaitAll().filterNotNull()
            }
        }
        // KMK <--

        filters.forEach { filter ->
            when (filter) {
                is OrderBy.Popular -> {
                    mangaDirs = if (filter.state!!.ascending) {
                        mangaDirs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })
                    } else {
                        mangaDirs.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })
                    }
                }
                is OrderBy.Latest -> {
                    mangaDirs = if (filter.state!!.ascending) {
                        mangaDirs.sortedBy(UniFile::lastModified)
                    } else {
                        mangaDirs.sortedByDescending(UniFile::lastModified)
                    }
                }
                else -> {
                    /* Do nothing */
                }
            }
        }

        val mangas = mangaDirs
            .map { mangaDir ->
                async {
                    SManga.create().apply {
                        title = mangaDir.name.orEmpty()
                        url = mangaDir.name.orEmpty()

                        // Try to find the cover
                        coverManager.find(mangaDir.name.orEmpty())?.let {
                            thumbnail_url = it.uri.toString()
                        }
                    }
                }
            }
            .awaitAll()

        MangasPage(mangas, false)
    }

    // SY -->
    fun updateMangaInfo(manga: SManga) {
        val mangaDirFiles = fileSystem.getFilesInMangaDirectory(manga.url)
        val existingFile = mangaDirFiles
            .firstOrNull { it.name == COMIC_INFO_FILE }
        val comicInfoArchiveFile = mangaDirFiles.firstOrNull { it.name == COMIC_INFO_ARCHIVE }
        val comicInfoArchiveReader = comicInfoArchiveFile?.archiveReader(context)
        val existingComicInfo =
            (existingFile?.openInputStream() ?: comicInfoArchiveReader?.getInputStream(COMIC_INFO_FILE))?.use {
                AndroidXmlReader(it, StandardCharsets.UTF_8.name()).use { xmlReader ->
                    xml.decodeFromReader<ComicInfo>(xmlReader)
                }
            }
        val newComicInfo = if (existingComicInfo != null) {
            manga.run {
                existingComicInfo.copy(
                    series = ComicInfo.Series(title),
                    summary = description?.let { ComicInfo.Summary(it) },
                    writer = author?.let { ComicInfo.Writer(it) },
                    penciller = artist?.let { ComicInfo.Penciller(it) },
                    genre = genre?.let { ComicInfo.Genre(it) },
                    publishingStatus = ComicInfo.PublishingStatusTachiyomi(
                        ComicInfoPublishingStatus.toComicInfoValue(status.toLong()),
                    ),
                )
            }
        } else {
            manga.getComicInfo()
        }

        fileSystem.getMangaDirectory(manga.url)?.let {
            copyComicInfoFile(
                xml.encodeToString(ComicInfo.serializer(), newComicInfo).byteInputStream(),
                it,
                comicInfoArchiveReader?.encrypted ?: false,
            )
        }
    }
    // SY <--

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = supervisorScope {
        val asyncManga = if (fetchDetails) async { getMangaDetails(manga) } else null
        val asyncChapters = if (fetchChapters) async { getChapterList(manga) } else null
        SMangaUpdate(asyncManga?.await() ?: manga, asyncChapters?.await() ?: chapters)
    }

    // Manga details related
    private suspend fun getMangaDetails(manga: SManga): SManga = withIOContext {
        coverManager.find(manga.url)?.let {
            manga.thumbnail_url = it.uri.toString()
        }

        // Augment manga details based on metadata files
        try {
            val mangaDir = fileSystem.getMangaDirectory(manga.url) ?: error("${manga.url} is not a valid directory")
            val mangaDirFiles = mangaDir.listFiles().orEmpty()

            val comicInfoFile = mangaDirFiles
                .firstOrNull { it.name == COMIC_INFO_FILE }
            val noXmlFile = mangaDirFiles
                .firstOrNull { it.name == ".noxml" }
            val legacyJsonDetailsFile = mangaDirFiles
                .firstOrNull { it.extension == "json" }
            // SY -->
            val comicInfoArchiveFile = mangaDirFiles
                .firstOrNull { it.name == COMIC_INFO_ARCHIVE }
            // SY <--

            when {
                // Top level ComicInfo.xml
                comicInfoFile != null -> {
                    noXmlFile?.delete()
                    setMangaDetailsFromComicInfoFile(comicInfoFile.openInputStream(), manga)
                }
                // SY -->
                comicInfoArchiveFile != null -> {
                    noXmlFile?.delete()

                    comicInfoArchiveFile.archiveReader(context).getInputStream(COMIC_INFO_FILE)
                        ?.let { setMangaDetailsFromComicInfoFile(it, manga) }
                }

                // SY <--

                // Old custom JSON format
                // TODO: remove support for this entirely after a while
                legacyJsonDetailsFile != null -> {
                    json.decodeFromStream<MangaDetails>(legacyJsonDetailsFile.openInputStream()).run {
                        title?.let { manga.title = it }
                        author?.let { manga.author = it }
                        artist?.let { manga.artist = it }
                        description?.let { manga.description = it }
                        genre?.let { manga.genre = it.joinToString() }
                        status?.let { manga.status = it }
                    }
                    // Replace with ComicInfo.xml file
                    val comicInfo = manga.getComicInfo()
                    mangaDir
                        .createFile(COMIC_INFO_FILE)
                        ?.openOutputStream()
                        ?.use {
                            val comicInfoString = xml.encodeToString(ComicInfo.serializer(), comicInfo)
                            it.write(comicInfoString.toByteArray())
                            legacyJsonDetailsFile.delete()
                        }
                }

                // Fill the details from the first EPUB's metadata, and keep them as a ComicInfo.xml so edits stick
                // and searches do not reopen the book.
                noXmlFile == null -> {
                    val epub = mangaDirFiles.firstOrNull { it.isFile && it.extension.equals("epub", true) }
                    val filled = epub?.let { fillDetailsFromEpub(it, manga) } == true
                    if (filled) {
                        mangaDir.createFile(COMIC_INFO_FILE)?.openOutputStream()?.use {
                            it.write(xml.encodeToString(ComicInfo.serializer(), manga.getComicInfo()).toByteArray())
                        }
                    } else {
                        // Avoid re-scanning
                        mangaDir.createFile(".noxml")
                    }
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Error setting manga details from local metadata for ${manga.title}" }
        }

        return@withIOContext manga
    }

    private fun copyComicInfoFile(
        comicInfoFileStream: InputStream,
        folder: UniFile,
        // SY -->
        encrypt: Boolean,
        // SY <--
    ): UniFile? {
        // SY -->
        if (encrypt) {
            val comicInfoArchiveFile = folder.createFile(COMIC_INFO_ARCHIVE)
            comicInfoArchiveFile?.let { archive ->
                ZipWriter(context, archive, encrypt = true).use { writer ->
                    writer.write(comicInfoFileStream.use { it.readBytes() }, COMIC_INFO_FILE)
                }
            }
            return comicInfoArchiveFile
        } else {
            // SY <--
            return folder.createFile(COMIC_INFO_FILE)?.apply {
                openOutputStream().use { outputStream ->
                    comicInfoFileStream.use { it.copyTo(outputStream) }
                }
            }
        }
    }

    private fun parseComicInfo(stream: InputStream): ComicInfo {
        return AndroidXmlReader(stream, StandardCharsets.UTF_8.name()).use {
            xml.decodeFromReader<ComicInfo>(it)
        }
    }

    private fun setMangaDetailsFromComicInfoFile(stream: InputStream, manga: SManga) {
        manga.copyFromComicInfo(parseComicInfo(stream))
    }

    // KMK -->
    /**
     * What a search or filter matches against, from the entry's ComicInfo.xml (or its encrypted archive copy) or the
     * legacy details.json. Null when there is neither or it does not parse.
     */
    private fun getMetadataForFiltering(mangaDir: UniFile): FilterMetadata? {
        return try {
            val files = mangaDir.listFiles().orEmpty()
            val comicInfoFile = files.firstOrNull { it.name == COMIC_INFO_FILE }
            val comicInfoArchive = files.firstOrNull { it.name == COMIC_INFO_ARCHIVE }
            val legacyJsonDetailsFile = files.firstOrNull { it.extension == "json" }
            when {
                comicInfoFile != null -> FilterMetadata.from(parseComicInfo(comicInfoFile.openInputStream()))
                comicInfoArchive != null -> comicInfoArchive.archiveReader(context).use { reader ->
                    reader.getInputStream(COMIC_INFO_FILE)?.let { FilterMetadata.from(parseComicInfo(it)) }
                }
                legacyJsonDetailsFile != null -> legacyJsonDetailsFile.openInputStream()
                    .use { json.decodeFromStream<MangaDetails>(it) }
                    .let {
                        FilterMetadata(
                            author = it.author,
                            artist = it.artist,
                            description = it.description,
                            genres = it.genre.orEmpty(),
                            status = it.status ?: SManga.UNKNOWN,
                        )
                    }
                else -> null
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Error reading local metadata for filtering: ${mangaDir.name}" }
            null
        }
    }

    private fun FilterMetadata?.matchesText(text: String): Boolean {
        if (this == null) return false
        return author.orEmpty().contains(text, ignoreCase = true) ||
            artist.orEmpty().contains(text, ignoreCase = true) ||
            description.orEmpty().contains(text, ignoreCase = true) ||
            genres.any { it.contains(text, ignoreCase = true) }
    }

    private data class FilterMetadata(
        val author: String?,
        val artist: String?,
        val description: String?,
        val genres: List<String>,
        val status: Int,
    ) {
        companion object {
            fun from(comicInfo: ComicInfo): FilterMetadata {
                val manga = SManga.create().apply { copyFromComicInfo(comicInfo) }
                return FilterMetadata(
                    author = manga.author,
                    artist = manga.artist,
                    description = manga.description,
                    genres = manga.getGenres().orEmpty(),
                    status = manga.status,
                )
            }
        }
    }
    // KMK <--

    // Chapters
    private suspend fun getChapterList(manga: SManga): List<SChapter> = withIOContext {
        val files = fileSystem.getFilesInMangaDirectory(manga.url)
            .filterNot { it.name.orEmpty().startsWith('.') }
            .filter(Format::isSupported)
            .sortedWith { f1, f2 -> f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(f2.name.orEmpty()) }

        // Oldest first while building, so an EPUB's chapters stay in reading order; the list is returned newest
        // first like every other source.
        val chapters = files.flatMap { file ->
            when (val format = Format.valueOf(file)) {
                is Format.Epub -> epubChapters(manga, format.file)
                is Format.Text, is Format.Html -> listOf(
                    SChapter.create().apply {
                        url = "${manga.url}/${file.name}"
                        name = file.nameWithoutExtension.orEmpty()
                        date_upload = file.lastModified()
                    },
                )
            }
        }
        chapters.forEachIndexed { index, chapter ->
            chapter.chapter_number = ChapterRecognition
                .parseChapterNumber(manga.title, chapter.name, (index + 1).toDouble())
                .toFloat()
        }

        if (manga.thumbnail_url.isNullOrBlank()) {
            files.firstOrNull { it.extension.equals("epub", true) }?.let { updateCover(it, manga) }
        }

        chapters.reversed()
    }

    private fun epubChapters(manga: SManga, file: UniFile): List<SChapter> {
        return file.epubReader(context).use { reader ->
            val book = EpubBook(reader)
            val lastModified = file.lastModified()
            book.chapters.map { chapter ->
                SChapter.create().apply {
                    url = "${manga.url}/${file.name}$EPUB_PATH_SEPARATOR${chapter.ref}"
                    name = chapter.title
                    date_upload = lastModified
                    scanlator = book.metadata.publisher
                }
            }
        }
    }

    override suspend fun getChapterText(chapter: SChapter): String = withIOContext {
        val (novelDirName, rest) = chapter.url.split('/', limit = 2).takeIf { it.size == 2 }
            ?: throw Exception(context.stringResource(MR.strings.chapter_not_found))
        val fileName = rest.substringBefore(EPUB_PATH_SEPARATOR)
        val file = fileSystem.getBaseDirectory()?.findFile(novelDirName)?.findFile(fileName)
            ?: throw Exception(context.stringResource(MR.strings.chapter_not_found))
        val format = try {
            Format.valueOf(file)
        } catch (_: Format.UnknownFormatException) {
            throw Exception(context.stringResource(MR.strings.local_invalid_format))
        }
        when (format) {
            is Format.Epub -> {
                val entry = rest.substringAfter(EPUB_PATH_SEPARATOR, "")
                format.file.epubReader(context).use { reader ->
                    val book = EpubBook(reader)
                    book.readChapter(entry) { imagePath -> extractEpubImage(book, file, imagePath) }
                }
            }
            is Format.Html -> file.openInputStream().use { it.bufferedReader().readText() }
            is Format.Text -> file.openInputStream().use { plainTextToHtml(it.bufferedReader().readText()) }
        }
    }

    /**
     * Copies an image out of the book into the app's cache and returns its file URI, so the reader loads it like
     * any other picture. Files are keyed by the book and the image's path, so a chapter reopened reuses them.
     */
    private fun extractEpubImage(book: EpubBook, file: UniFile, imagePath: String): String? {
        val bookKey = "${file.uri}:${file.length()}".hashCode().toUInt().toString(16)
        val target = File(File(context.cacheDir, "local_epub_images/$bookKey"), imagePath.replace('/', '_'))
        if (!target.exists()) {
            val input = book.open(imagePath) ?: return null
            target.parentFile?.mkdirs()
            input.use { stream -> target.outputStream().use { stream.copyTo(it) } }
        }
        return Uri.fromFile(target).toString()
    }

    private fun fillDetailsFromEpub(file: UniFile, manga: SManga): Boolean {
        return file.epubReader(context).use { reader ->
            val metadata = EpubBook(reader).metadata
            metadata.title?.let { manga.title = it }
            if (metadata.authors.isNotEmpty()) manga.author = metadata.authors.joinToString()
            metadata.description?.let { manga.description = it }
            if (metadata.subjects.isNotEmpty()) manga.genre = metadata.subjects.joinToString()
            metadata.title != null || metadata.authors.isNotEmpty() || metadata.description != null
        }
    }

    // Filters
    override fun getFilterList() = FilterList(
        OrderBy.Popular(context),
        // KMK -->
        Filter.Separator(),
        AuthorFilter(context),
        ArtistFilter(context),
        GenreFilter(context),
        StatusFilter(context),
        // KMK <--
    )

    private fun updateCover(epub: UniFile, manga: SManga): UniFile? {
        return try {
            epub.epubReader(context).use { reader ->
                val book = EpubBook(reader)
                val cover = book.coverPath ?: return null
                book.open(cover)?.let { coverManager.update(manga, it) }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Error updating cover for ${manga.title}" }
            null
        }
    }

    companion object {
        const val ID = 0L
        const val HELP_URL = "https://github.com/mKonic/yomikku#local-novels"

        // SY -->
        const val COMIC_INFO_ARCHIVE = "ComicInfo.cbm"
        // SY <--

        /** Separates an EPUB's file name from the chapter's path inside it, in a chapter URL. */
        const val EPUB_PATH_SEPARATOR = "#"

        /**
         * Plain text as HTML: blank lines separate paragraphs, and a file with no blank lines at all takes each line
         * as a paragraph, which is how most novel text files are written.
         */
        fun plainTextToHtml(text: String): String {
            val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
            val blankLine = Regex("\n\\s*\n")
            val paragraphs = if (blankLine.containsMatchIn(normalized)) normalized.split(blankLine) else normalized.split('\n')
            return paragraphs
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n") { "<p>" + TextUtils.htmlEncode(it).replace("\n", "<br>") + "</p>" }
        }

        private val LATEST_THRESHOLD = 7.days.inWholeMilliseconds
    }
}

fun Manga.isLocal(): Boolean = source == LocalSource.ID

fun Source.isLocal(): Boolean = id == LocalSource.ID

fun DomainSource.isLocal(): Boolean = id == LocalSource.ID
