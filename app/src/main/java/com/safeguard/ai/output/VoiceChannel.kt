package com.safeguard.ai.output

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Speaks warnings through the Pleos on-device TTS engine (no app registration needed).
 *
 * Follows the Pleos pre-roll guide as far as the SDK allows: the TTS SDK plays audio itself,
 * so silence can't be padded into the PCM. Instead the engine is initialised at start-up,
 * audio focus is taken first, and speech starts [PREROLL_MS] later so the output path is awake.
 */
class VoiceChannel(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    // Each request gets its own listener so the two are separate focus clients: with a shared client an
    // urgent request made while the interrupted one is still held came back FAILED (seen on the emulator).
    /** Music and podcasts keep playing, quieter, while a warning is spoken. */
    private val duckRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { }
        .build()
    /** Crash and stop-now warnings pause other audio entirely. */
    private val pauseRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { }
        .build()

    private val tts = TextToSpeech(context, Mode.ON_DEVICE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ready = CompletableDeferred<Boolean>()
    @Volatile private var utterance: CompletableDeferred<Unit>? = null

    private val events = object : EventListener {
        override fun onStart() {
            Log.d(TAG, "tts start")
        }
        override fun onReady() = Unit
        override fun onDone() {
            Log.d(TAG, "tts done")
            finish()
        }
        // Only our own interrupt() stops the engine, and it already finished that sentence. The stop event
        // arrives late, after the next (more urgent) sentence has started, and must not end that one.
        override fun onStop() {
            Log.d(TAG, "tts stopped")
        }
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "tts error: $errorType")
            finish()
        }
        override fun onUpdatedRms(rms: Double) {
            if (rms > SILENT_RMS) lastVoiceAt = SystemClock.elapsedRealtime()
        }
    }

    /** When the engine last produced sound; 0 until the current sentence makes any. */
    @Volatile private var lastVoiceAt = 0L

    fun start() {
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                // addEventListener before initialize crashes inside the SDK, so register here.
                tts.addEventListener(events)
                tts.setAudioAttributes(attributes)
                ready.complete(true)
                Log.i(TAG, "tts ready")
            }
            override fun onFailure(errorType: ErrorType) {
                Log.w(TAG, "tts init failed: $errorType")
                ready.complete(false)
            }
        })
    }

    /** Suspends until the sentence finished, failed, was interrupted, or timed out. */
    suspend fun speak(text: String, urgent: Boolean = false) {
        val focus = if (urgent) pauseRequest else duckRequest
        if (withTimeoutOrNull(INIT_WAIT_MS) { ready.await() } != true) {
            Log.w(TAG, "tts unavailable, skipped: $text")
            return
        }
        val done = CompletableDeferred<Unit>().also { utterance = it }
        val granted = audioManager.requestAudioFocus(focus)
        Log.i(TAG, "audio focus ${if (urgent) "GAIN_TRANSIENT" else "MAY_DUCK"} -> ${if (granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "granted" else "result $granted"}")
        try {
            delay(PREROLL_MS)
            // The TTS SDK blocks when called off the main thread (it worked from the main thread in stage 0).
            lastVoiceAt = 0L
            withContext(Dispatchers.Main) { tts.speak(text, false) }
            Log.i(TAG, "speaking: $text")
            // Korean on-device speech runs at roughly 8 characters a second; a sentence can't be over sooner.
            val earliestEnd = SystemClock.elapsedRealtime() + text.length * MIN_CHAR_MS
            val finished = withTimeoutOrNull(BASE_TIMEOUT_MS + text.length * PER_CHAR_MS) {
                // The emulator's engine never sends onDone; its loudness updates stopping is the next best sign.
                while (!done.isCompleted) {
                    val voice = lastVoiceAt
                    val now = SystemClock.elapsedRealtime()
                    if (voice > 0 && now >= earliestEnd && now - voice > SILENCE_END_MS) {
                        Log.d(TAG, "silence after speech, treating as done")
                        break
                    }
                    delay(POLL_MS)
                }
            }
            if (finished == null) Log.w(TAG, "no completion event, moving on")
        } finally {
            utterance = null
            audioManager.abandonAudioFocusRequest(focus)
        }
    }

    fun interrupt() {
        mainHandler.post { runCatching { tts.stop() } }
        finish()
    }

    fun stop() {
        interrupt()
        runCatching { tts.removeEventListener(events) }
        tts.release()
    }

    private fun finish() {
        utterance?.complete(Unit)
    }

    companion object {
        private const val TAG = "SafeGuard/Voice"
        /** Pleos guide: 150–200 ms before every utterance. */
        private const val PREROLL_MS = 200L
        // Cold start on a just-booted head unit can take this long; skipping the warning would be worse.
        private const val INIT_WAIT_MS = 10_000L
        private const val BASE_TIMEOUT_MS = 3_000L
        private const val PER_CHAR_MS = 150L
        private const val POLL_MS = 100L
        private const val MIN_CHAR_MS = 120L
        /** Longer than the pause between two sentences of one warning. */
        private const val SILENCE_END_MS = 900L
        private const val SILENT_RMS = 0.0
    }
}
