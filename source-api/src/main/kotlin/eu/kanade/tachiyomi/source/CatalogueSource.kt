package eu.kanade.tachiyomi.source

/**
 * A source that can be browsed: popular, latest and search listings in a single language.
 */
interface CatalogueSource : Source {

    /**
     * An ISO 639-1 compliant language code (two letters in lower case).
     */
    override val lang: String
}
