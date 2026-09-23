package eu.kanade.tachiyomi.ui.reader.text

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Reads a chapter aloud with the system's text to speech, one block after another, and says which block it is on.
 *
 * Each paragraph is queued as its own utterance, so the reader can show and follow the one being spoken; a paragraph
 * longer than the engine takes at once is split at sentence ends. Callbacks arrive on the engine's thread.
 */
class ReaderSpeech(
    context: Context,
    private val onBlock: (index: Int) -> Unit,
    /** Where in the chapter's text the engine is, as it gets to each word; not every engine says. */
    private val onOffset: (offset: Int) -> Unit,
    private val onFinished: () -> Unit,
    private val onUnavailable: () -> Unit,
) {
    private var ready = false
    private var pending: (() -> Unit)? = null

    /** Bumped on every [speak] and [stop], so callbacks from utterances queued before are ignored. */
    @Volatile
    private var generation = 0

    private val engine: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            pending?.invoke()
        } else {
            onUnavailable()
        }
        pending = null
    }

    init {
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    val id = UtteranceId.parse(utteranceId) ?: return
                    if (id.generation == generation && id.part == 0) onBlock(id.block)
                }

                override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                    val id = UtteranceId.parse(utteranceId) ?: return
                    if (id.generation == generation) onOffset(id.offset + start)
                }

                override fun onDone(utteranceId: String) {
                    val id = UtteranceId.parse(utteranceId) ?: return
                    if (id.generation == generation && id.last) onFinished()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) = onDone(utteranceId)

                override fun onError(utteranceId: String, errorCode: Int) = onDone(utteranceId)
            },
        )
    }

    /**
     * Reads [document] from block [from] to its end, replacing whatever was being read. [language] is the source's
     * language tag, used when the engine has a voice for it.
     */
    fun speak(document: ChapterDocument, from: Int, rate: Float, pitch: Float, language: String?) {
        val run = run@{
            val current = ++generation
            engine.stop()
            engine.setSpeechRate(rate)
            engine.setPitch(pitch)
            language?.let { Locale.forLanguageTag(it) }
                ?.takeIf { engine.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
                ?.let { engine.setLanguage(it) }

            val utterances = document.blocks.withIndex()
                .drop(from.coerceAtLeast(0))
                .flatMap { (index, block) ->
                    val text = (block as? TextBlock.Paragraph)?.text?.text
                    if (text.isNullOrBlank()) return@flatMap emptyList()
                    split(text).mapIndexed { part, (start, chunk) -> Utterance(index, part, block.start + start, chunk) }
                }
            if (utterances.isEmpty()) {
                onFinished()
                return@run
            }
            utterances.forEachIndexed { i, utterance ->
                val id = UtteranceId(
                    generation = current,
                    block = utterance.block,
                    part = utterance.part,
                    offset = utterance.offset,
                    last = i == utterances.lastIndex,
                )
                engine.speak(utterance.text, TextToSpeech.QUEUE_ADD, null, id.toString())
            }
        }
        if (ready) run() else pending = run
    }

    fun stop() {
        generation++
        pending = null
        if (ready) engine.stop()
    }

    fun shutdown() {
        stop()
        engine.shutdown()
    }

    /**
     * [text] in pieces the engine accepts, broken after a sentence where it can be, each with where it starts in
     * [text].
     */
    private fun split(text: String): List<Pair<Int, String>> {
        val max = TextToSpeech.getMaxSpeechInputLength() - 1
        val pieces = mutableListOf<Pair<Int, String>>()
        var start = 0
        while (start < text.length) {
            while (start < text.length && text[start].isWhitespace()) start++
            if (start >= text.length) break
            var end = (start + max).coerceAtMost(text.length)
            if (end < text.length) {
                val window = text.substring(start, end)
                end = start + (
                    SENTENCE_END.findAll(window).lastOrNull()?.range?.last?.plus(1)
                        ?: window.lastIndexOf(' ').takeIf { it > 0 }
                        ?: window.length
                    )
            }
            pieces += start to text.substring(start, end).trimEnd()
            start = end
        }
        return pieces
    }

    private class Utterance(val block: Int, val part: Int, val offset: Int, val text: String)

    private data class UtteranceId(val generation: Int, val block: Int, val part: Int, val offset: Int, val last: Boolean) {
        override fun toString() = "$generation:$block:$part:$offset:${if (last) 1 else 0}"

        companion object {
            fun parse(id: String): UtteranceId? {
                val parts = id.split(':').mapNotNull { it.toIntOrNull() }
                if (parts.size != 5) return null
                return UtteranceId(parts[0], parts[1], parts[2], parts[3], parts[4] == 1)
            }
        }
    }

    private companion object {
        val SENTENCE_END = Regex("""[.!?。！？…]["'”’)]*\s""")
    }
}
