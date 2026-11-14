package com.safeguard.ai.output

import com.safeguard.ai.core.VehicleGateway
import com.safeguard.ai.risk.RiskLevel
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Uses the cabin ambient light as a glance-free warning signal (benchmark: Mercedes Active Ambient Lighting).
 * The user's own colour is saved before the first warning and restored afterwards.
 * Only crash/critical levels blink, briefly, to avoid distraction.
 */
class AmbientChannel(
    private val scope: CoroutineScope,
    private val gateway: VehicleGateway,
) {
    private var userColor: Int? = null
    private var restoreJob: Job? = null
    private var showJob: Job? = null
    private var showing = false

    /** Fire-and-forget so the caller (voice) never waits on a slow vehicle call. */
    fun showAsync(level: RiskLevel) {
        showJob?.cancel()
        showJob = scope.launch { show(level) }
    }

    suspend fun show(level: RiskLevel) {
        val color = level.ambientColor ?: return
        restoreJob?.cancel()
        if (!showing) {
            userColor = gateway.getAmbientColor().getOrNull()
            showing = true
        }
        if (level.blink) {
            repeat(BLINK_COUNT) {
                gateway.setAmbientColor(color)
                delay(BLINK_ON_MS)
                gateway.setAmbientColor(OFF_COLOR)
                delay(BLINK_OFF_MS)
            }
        }
        gateway.setAmbientColor(color).onFailure { Log.d(TAG, "ambient unavailable: ${it.message}") }
    }

    /** Keeps the warning colour for [HOLD_MS] after the voice finished, then returns to the user's colour. */
    fun releaseLater() {
        if (!showing) return
        restoreJob?.cancel()
        restoreJob = scope.launch {
            delay(HOLD_MS)
            userColor?.let { gateway.setAmbientColor(it) }
            showing = false
        }
    }

    companion object {
        private const val TAG = "SafeGuard/Ambient"
        private const val BLINK_COUNT = 3
        private const val BLINK_ON_MS = 300L
        private const val BLINK_OFF_MS = 200L
        private const val HOLD_MS = 8_000L
        private const val OFF_COLOR = 0xFF000000.toInt()
    }
}
