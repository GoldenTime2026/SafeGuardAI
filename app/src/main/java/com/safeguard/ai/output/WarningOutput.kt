package com.safeguard.ai.output

import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskOutput
import android.util.Log

/**
 * Delivers an accepted warning. Light and voice run in parallel: a slow vehicle call
 * (seen as 5 s timeouts on a loaded emulator) must never delay the spoken warning.
 * If the warning asks a question, the driver's spoken yes/no decides the follow-up; an open question
 * (after a crash) passes on whatever was said, or silence.
 * The light lingers briefly after the voice finishes.
 */
class WarningOutput(
    private val voice: VoiceChannel,
    private val ambient: AmbientChannel,
    private val confirm: VoiceConfirm,
) : RiskOutput {

    override suspend fun present(event: RiskEvent) {
        try {
            ambient.showAsync(event.level)
            voice.speak(event.speech, urgent = event.level.urgent)
            event.confirm?.let { follow ->
                when (confirm.ask()) {
                    Answer.YES -> {
                        voice.speak(follow.yesSpeech)
                        follow.onYes()
                    }
                    Answer.NO -> {
                        follow.noSpeech?.let { voice.speak(it) }
                        follow.onNo()
                    }
                    Answer.NONE -> Log.i(TAG, "no answer to ${event.key}, nothing changed")
                }
            }
            event.reply?.let { it.onReply(confirm.listen(it.timeoutMs)) }
        } finally {
            ambient.releaseLater()
        }
    }

    override fun interrupt() = voice.interrupt()

    companion object {
        private const val TAG = "SafeGuard/Warning"
    }
}
