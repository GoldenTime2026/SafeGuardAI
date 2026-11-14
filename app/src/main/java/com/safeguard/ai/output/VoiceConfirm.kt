package com.safeguard.ai.output

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class Answer { YES, NO, NONE }

/**
 * Listens after a question — the only input SafeGuard asks for while driving. Uses the Pleos on-device STT
 * engine (no app registration needed).
 * - [ask]: a short yes/no ("우회할까요?").
 * - [listen]: whatever the driver says, or null for silence ("괜찮으세요?" after a crash).
 * Scenarios queue replies with [inject] / [injectReply] so demos don't depend on the emulator microphone.
 */
class VoiceConfirm(context: Context) {

    private val stt = SpeechToText(context, Mode.ON_DEVICE)
    private val main = Handler(Looper.getMainLooper())
    private val ready = CompletableDeferred<Boolean>()
    @Volatile private var pending: CompletableDeferred<String?>? = null
    /** Decides when a (partial) transcript is enough to stop listening. */
    @Volatile private var accept: (String, Boolean) -> Boolean = { _, final -> final }
    /** Queued scenario replies; a null entry is silence. */
    private val injected = ArrayDeque<String?>()

    private val results = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) {
            if (accept(text, isFinal)) {
                Log.i(TAG, "heard \"$text\"")
                pending?.complete(text)
            }
        }
        override fun onUpdatedEpdData(start: Long, end: Long) = Unit
        override fun onStartedRecognition() = Unit
        override fun onEndedRecognition() {
            pending?.complete(null)
        }
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "stt error: $errorType")
            pending?.complete(null)
        }
        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }

    fun start() {
        stt.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                stt.addListener(results)
                ready.complete(true)
                Log.i(TAG, "stt ready")
            }
            override fun onFailure(errorType: ErrorType) {
                Log.w(TAG, "stt init failed: $errorType")
                ready.complete(false)
            }
        })
    }

    /** The next question is answered with [answer] without listening (scenario demos). */
    fun inject(answer: Answer) = injectReply(
        when (answer) {
            Answer.YES -> "응"
            Answer.NO -> "아니"
            Answer.NONE -> null
        }
    )

    /** The next question hears [text]; null means the driver stays silent. */
    fun injectReply(text: String?) {
        synchronized(injected) { injected.addLast(text) }
    }

    suspend fun ask(timeoutMs: Long = LISTEN_MS): Answer =
        classify(hear(timeoutMs) { text, final -> classify(text) != Answer.NONE || final }.orEmpty())

    /** The driver's words after an open question, or null if nothing was said within [timeoutMs]. */
    suspend fun listen(timeoutMs: Long): String? = hear(timeoutMs) { _, final -> final }?.takeIf { it.isNotBlank() }

    private suspend fun hear(timeoutMs: Long, accept: (String, Boolean) -> Boolean): String? {
        synchronized(injected) { if (injected.isNotEmpty()) return injected.removeFirst().also { Log.i(TAG, "injected reply \"$it\"") } }
        if (withTimeoutOrNull(INIT_WAIT_MS) { ready.await() } != true) return null
        this.accept = accept
        val result = CompletableDeferred<String?>().also { pending = it }
        // Like TTS, the speech SDK is driven from the main thread.
        withContext(Dispatchers.Main) { stt.request() }
        val text = withTimeoutOrNull(timeoutMs) { result.await() }
        pending = null
        main.post { runCatching { stt.stop() } }
        return text
    }

    fun stop() {
        main.post {
            runCatching { stt.removeListener(results) }
            stt.release()
        }
    }

    companion object {
        private const val TAG = "SafeGuard/Confirm"
        private const val LISTEN_MS = 5_000L
        private const val INIT_WAIT_MS = 3_000L

        /** Refusals found anywhere in the reply (STT often drops spaces: "그건아니야"). */
        private val NO_PHRASES = listOf(
            "아니", "싫어", "싫다", "됐어", "됐다", "괜찮아", "괜찮습니다", "하지 마", "하지마", "취소",
            "안 해", "안 할", "안 돼", "필요 없", "필요없", "그냥 가", "그냥가", "나중에",
        )
        /** Refusals that only count as a word of their own or its start ("노" is not "노래", "안해" is not "편안해"). */
        private val NO_WORDS = listOf("안해", "안할", "안돼", "노", "놉", "no", "nope", "말아", "말고")
        private val YES_WORDS = listOf(
            "응", "어", "엉", "웅", "네", "넵", "넹", "예", "해", "콜",
            "그래", "그러자", "그렇게", "좋아", "좋지", "좋습니다", "오케이", "오케", "ok", "okay", "yes",
            "해줘", "해 줘", "해주세요", "해 주세요", "부탁", "우회", "켜줘", "켜 줘", "켜 주세요",
            "알겠어", "알았어", "알겠습니다", "당연", "물론", "맞아", "고고", "가자", "쉴게", "쉬어 갈게",
        )
        private val SEPARATORS = Regex("[\\s,.!?~]+")

        /** "No" wins when both appear ("아니 괜찮아", "응 아니야", "그냥 가자"). */
        fun classify(text: String): Answer {
            val t = text.trim().lowercase()
            if (t.isEmpty()) return Answer.NONE
            val words = t.split(SEPARATORS).filter { it.isNotEmpty() }
            if (NO_PHRASES.any { t.contains(it) } || NO_WORDS.any { w -> words.any { it.matches(w) } }) return Answer.NO
            val yes = YES_WORDS.any { w -> if (' ' in w) t.contains(w) else words.any { it.matches(w) } }
            return if (yes) Answer.YES else Answer.NONE
        }

        /** One-syllable answers must be the whole word, so "예약", "네비", "어디" aren't taken as answers. */
        private fun String.matches(word: String) = this == word || (word.length > 1 && startsWith(word))
    }
}
