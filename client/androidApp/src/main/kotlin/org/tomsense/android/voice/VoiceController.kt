package org.tomsense.android.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tomsense.voice.SentenceChunker
import org.tomsense.voice.speakableText

/**
 * Listening, speaking, and the handover between them.
 *
 * ## Why the device engines by default
 *
 * The spec's voice path is a duplex WebSocket to Deepgram flux with
 * server-side turn detection. That is the right destination and it is also
 * unmeasurable without hardware, so it is not what ships first. Android's own
 * recogniser and TTS need no network, cost nothing, start instantly and work
 * on a plane. aura-2 is available as a quality tier for the OUTPUT half, where
 * the difference is audible and a round trip is affordable — the input half
 * stays local, because that is the half latency is measured on.
 *
 * ## The three things that make it feel fast
 *
 * **Speak sentence one while sentence two is still arriving.** Time to first
 * sound stops depending on how long the answer is. This is the whole trick,
 * and it is free.
 *
 * **Barge-in.** The recogniser stays warm while speech plays; the first thing
 * the user says stops playback immediately. An assistant you have to wait out
 * feels broken in a way that no latency number captures.
 *
 * **Partial transcripts.** Shown as they arrive, so something moves on screen
 * within a few hundred milliseconds of speaking.
 */
class VoiceController(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Speech out through the edge. Null falls back to the device engine. */
    private val remoteTts: (suspend (String) -> ByteArray?)? = null,
    private val onFinalTranscript: (String) -> Unit,
) {

    enum class Phase { Idle, Listening, Speaking }

    var phase by mutableStateOf(Phase.Idle)
        private set

    /** What the recogniser has heard so far, for live display. */
    var partial by mutableStateOf("")
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** Keep listening after each reply — hands-free until switched off. */
    var handsFree by mutableStateOf(false)

    /** Empty means the device engine; otherwise an aura-2 speaker name. */
    var remoteVoice: String = ""

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null

    private val chunker = SentenceChunker()
    private var spokenAnything = false

    // ─── listening ──────────────────────────────────────────────────────────

    fun startListening() {
        if (phase == Phase.Listening) return
        // Barge-in from the button as well as the voice: tapping the mic while
        // it is talking should interrupt, not queue.
        stopSpeaking()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            error = "No speech recogniser on this device."
            return
        }

        error = null
        partial = ""
        phase = Phase.Listening

        val rec = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(results: Bundle?) {
                partial = firstResult(results) ?: partial
            }

            override fun onResults(results: Bundle?) {
                val text = firstResult(results)?.trim().orEmpty()
                partial = ""
                phase = Phase.Idle
                release()
                if (text.isNotEmpty()) onFinalTranscript(text)
            }

            override fun onError(code: Int) {
                phase = Phase.Idle
                release()
                // NO_MATCH and SPEECH_TIMEOUT are the user saying nothing.
                // Reporting those as errors makes the assistant feel broken
                // every time someone opens the mic and thinks for a moment.
                error = when (code) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    -> null
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed."
                    else -> "Didn't catch that."
                }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        rec.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Prefer on-device where the platform has it: no network hop,
                // and the audio never leaves the phone.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            },
        )
    }

    /**
     * The mic button on surfaces that cannot ask for permission themselves
     * (the assistant overlay, the feed panel): no Activity, no dialog.
     * Tapping mid-reply barges in, because [startListening] stops speech.
     */
    fun toggleListening() {
        if (phase == Phase.Listening) stopListening() else startListening()
    }

    fun stopListening() {
        recognizer?.stopListening()
        phase = Phase.Idle
        partial = ""
    }

    private fun firstResult(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    // ─── speaking ───────────────────────────────────────────────────────────

    /** Start a spoken reply. Call [speakStreaming] as the text grows. */
    fun beginReply() {
        chunker.reset()
        spokenAnything = false
    }

    /**
     * Speak whatever has become a complete sentence since the last call.
     *
     * Called on every token. Doing nothing until a sentence completes is the
     * point — it is what lets speech start before the answer does.
     */
    fun speakStreaming(fullText: String) {
        val speakable = speakableText(fullText)
        for (sentence in chunker.feed(speakable)) enqueue(sentence)
    }

    /** The reply is finished; say the trailing fragment. */
    fun endReply(fullText: String) {
        chunker.drain(speakableText(fullText))?.let { enqueue(it) }
    }

    private fun enqueue(sentence: String) {
        if (sentence.isBlank()) return
        phase = Phase.Speaking
        spokenAnything = true

        if (remoteVoice.isNotEmpty() && remoteTts != null) {
            scope.launch { speakRemote(sentence) }
        } else {
            speakOnDevice(sentence)
        }
    }

    private fun speakOnDevice(sentence: String) {
        val engine = tts
        if (engine != null && ttsReady) {
            engine.speak(sentence, TextToSpeech.QUEUE_ADD, null, sentence.hashCode().toString())
            return
        }
        if (engine != null) return // still initialising; the queue below catches up

        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (!ttsReady) {
                error = "No speech engine available."
                phase = Phase.Idle
                return@TextToSpeech
            }
            tts?.language = Locale.getDefault()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = finishedSpeaking()
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finishedSpeaking()
            })
            tts?.speak(sentence, TextToSpeech.QUEUE_ADD, null, sentence.hashCode().toString())
        }
    }

    /**
     * aura-2, one sentence at a time.
     *
     * Sequential by construction: each call awaits the previous player. Firing
     * these concurrently would produce overlapping voices, which is how a
     * naive implementation of this sounds.
     */
    private suspend fun speakRemote(sentence: String) {
        val bytes = withContext(Dispatchers.IO) {
            runCatching { remoteTts?.invoke(sentence) }.getOrNull()
        }
        if (bytes == null) {
            // Falling back rather than going silent: a failed round trip
            // should cost quality, not the answer.
            withContext(Dispatchers.Main) { speakOnDevice(sentence) }
            return
        }

        withContext(Dispatchers.IO) {
            val file = File.createTempFile("tts", ".mp3", context.cacheDir)
            file.writeBytes(bytes)
            withContext(Dispatchers.Main) { play(file) }
        }
    }

    private fun play(file: File) {
        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                file.delete()
                finishedSpeaking()
            }
            setOnErrorListener { _, _, _ ->
                file.delete()
                finishedSpeaking()
                true
            }
            prepare()
            start()
        }
    }

    /**
     * Stop mid-sentence.
     *
     * Both engines, unconditionally: which one is speaking depends on a
     * setting that may have changed since playback started.
     */
    fun stopSpeaking() {
        tts?.stop()
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        if (phase == Phase.Speaking) phase = Phase.Idle
    }

    private fun finishedSpeaking() {
        if (phase != Phase.Speaking) return
        phase = Phase.Idle
        // Hands-free: go straight back to listening so a conversation does not
        // need a tap between every turn.
        if (handsFree && spokenAnything) startListening()
    }

    fun shutdown() {
        stopSpeaking()
        release()
        tts?.shutdown()
        tts = null
    }

    companion object {
        val MIC_PERMISSION = Manifest.permission.RECORD_AUDIO

        fun hasMicPermission(context: Context): Boolean =
            context.checkSelfPermission(MIC_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
