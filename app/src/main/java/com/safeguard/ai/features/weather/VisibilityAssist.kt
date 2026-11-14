package com.safeguard.ai.features.weather

import com.safeguard.ai.core.VehicleGateway
import com.safeguard.ai.core.VehicleSnapshot
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Feature ① — one-shot visibility automation.
 * Heavy rain, fog, or rain at night: windscreen defrost on, outside air (prevents fogging), mirror heat on.
 * The driver's previous settings are saved and restored once the condition has been clear for [RESTORE_AFTER_MS].
 * Stage 0 (V2) confirmed HVAC control is allowed while driving, so this runs automatically.
 */
class VisibilityAssist(
    private val scope: CoroutineScope,
    private val gateway: VehicleGateway,
    private val weather: StateFlow<WeatherStatus>,
    private val snapshots: StateFlow<VehicleSnapshot>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Saved(val defroster: Boolean?, val recirculation: Boolean?, val mirrorHeat: Int?)

    private var saved: Saved? = null

    /** True while defrost / outside air are switched on for visibility. */
    val engaged: Boolean get() = saved != null
    private var clearSince: Long? = null

    /** Whether the cabin should switch to visibility mode. */
    var enabled = true

    fun start() {
        scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    private suspend fun tick() {
        val engage = enabled && shouldEngage(weather.value.condition, snapshots.value)
        when {
            engage && saved == null -> engageNow()
            engage -> clearSince = null
            saved != null -> {
                val now = clock()
                val since = clearSince ?: now.also { clearSince = it }
                if (now - since >= RESTORE_AFTER_MS) restore()
            }
        }
    }

    private suspend fun engageNow() {
        saved = Saved(
            defroster = gateway.getDefroster().getOrNull(),
            recirculation = gateway.getRecirculation().getOrNull(),
            mirrorHeat = gateway.getMirrorHeat().getOrNull(),
        )
        clearSince = null
        val results = listOf(
            "defroster" to gateway.setDefroster(true),
            "outside air" to gateway.setRecirculation(false),
            "mirror heat" to gateway.setMirrorHeat(MIRROR_HEAT_LEVEL),
        )
        // A timeout isn't a failure: on a slow vehicle service the command is often applied but the reply is lost.
        Log.i(TAG, "engaged: " + results.joinToString { (name, r) -> "$name=${r.outcomeLabel()}" })
    }

    private suspend fun restore() {
        val previous = saved ?: return
        previous.defroster?.let { gateway.setDefroster(it) }
        previous.recirculation?.let { gateway.setRecirculation(it) }
        previous.mirrorHeat?.let { gateway.setMirrorHeat(it) }
        saved = null
        clearSince = null
        Log.i(TAG, "restored driver settings: $previous")
    }

    companion object {
        private const val TAG = "SafeGuard/Visibility"
        private const val TICK_MS = 2_000L
        private const val RESTORE_AFTER_MS = 5 * 60_000L
        private const val MIRROR_HEAT_LEVEL = 1

        private fun Result<Unit>.outcomeLabel() = when {
            isSuccess -> "ok"
            exceptionOrNull() is VehicleGateway.VehicleTimeoutException -> "no-reply"
            else -> "fail"
        }

        fun shouldEngage(condition: RoadCondition, s: VehicleSnapshot) =
            condition == RoadCondition.HEAVY_RAIN || condition == RoadCondition.FOG ||
                (condition == RoadCondition.RAIN && s.lowBeamOn == true)
    }
}
