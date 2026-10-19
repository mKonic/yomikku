package eu.kanade.tachiyomi.ui.reader.text

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import java.util.Locale

/**
 * Reads a chapter aloud with the system's text to speech, one block after another, and says which block it is on.
 *
 * Each paragraph is queued as its own utterance, so the reader can show and follow the one being spoken; a paragraph
 * longer than the engine takes at once is split at sentence ends. Callbacks arrive on the engine's thread.
 *
 * Speaking holds audio focus like any player: a call or another app's audio, or headphones coming out, stops it.
 */
class ReaderSpeech(
    context: Context,
    private val onBlock: (index: Int) -> Unit,
    /** Where in the chapter's text the engine is, as it gets to each word; not every engine says. */
    private val onOffset: (offset: Int) -> Unit,
    private val onFinished: () -> Unit,
    private val onUnavailable: () -> Unit,
    /** Something else took the audio, or the headphones were unplugged; reading has stopped. */
    private val onInterrupted: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService<AudioManager>()

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            // Ducking for a notification is fine; anything longer ends reading.
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) interrupt()
        }
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) interrupt()
        }
    }
    private var holdingAudio = false

    private var ready = false
    private var pending: (() -> Unit)? = null

    /** Bumped on every [speak] and [stop], so callbacks from utterances queued before are ignored. */
    @Volatile
    private var generation = 0

    private val engine: TextToSpeech = TextToSpeech(appContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            pending?.invoke()
        } else {
            onUnavailable()
        }
        pending = null
    }

    init {
        engine.setAudioAttributes(attributes)
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
            if (!holdAudio()) {
                onInterrupted()
                return@run
            }
            engine.setSpeechRate(rate)
            engine.setPitch(pitch)
            language?.let { Locale.forLanguageTag(it) }
                ?.takeIf { engine.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
                ?.let { engine.setLanguage(it) }

            val utterances = document.blocks.withIndex()
                .drop(from.coerceAtLeast(0))
                .flatMap { (index, block) ->
                    val text = (block as? TextBlock.Paragraph)?.text?.text
                    // Nothing to say in a divider such as "* * *" or "-----", which engines read out symbol by symbol.
                    if (text == null || text.none { it.isLetterOrDigit() }) return@flatMap emptyList()
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
        releaseAudio()
    }

    private fun interrupt() {
        stop()
        onInterrupted()
    }

    private fun holdAudio(): Boolean {
        if (holdingAudio) return true
        val granted = audioManager?.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_FAILED
        if (!granted) return false
        ContextCompat.registerReceiver(
            appContext,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        holdingAudio = true
        return true
    }

    private fun releaseAudio() {
        if (!holdingAudio) return
        holdingAudio = false
        audioManager?.abandonAudioFocusRequest(focusRequest)
        appContext.unregisterReceiver(noisyReceiver)
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
        // Quotes opening or closing the paragraph: some engines say "quote" for them.
        var start = 0
        while (start < text.length && (text[start].isWhitespace() || text[start] in QUOTES)) start++
        var limit = text.length
        while (limit > start && (text[limit - 1].isWhitespace() || text[limit - 1] in QUOTES)) limit--
        while (start < limit) {
            while (start < limit && text[start].isWhitespace()) start++
            if (start >= limit) break
            var end = (start + max).coerceAtMost(limit)
            if (end < limit) {
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
        const val QUOTES = "\"'“”‘’「」『』"
    }
}
