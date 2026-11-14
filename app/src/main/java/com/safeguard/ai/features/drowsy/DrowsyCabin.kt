package com.safeguard.ai.features.drowsy

import com.safeguard.ai.core.VehicleGateway
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import ai.pleos.playground.vehicle.constant.control.MoveControl
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The cabin side of feature ④.
 * - Wake-up program: climate on if it was off, outside air, stronger fan, 2 °C cooler, seat ventilation for
 *   [AWAKEN_MS], then the driver's settings come back. (The white cabin light comes with the DROWSY warning itself.)
 * - Power nap (parked only — the seat refuses to move while driving): recline, dim warm light, then after
 *   [napMs] wake with outside air, bright light and voice, and bring the backrest back.
 * Every vehicle call may fail on a given car; each step is attempted independently and logged.
 */
class DrowsyCabin(
    private val scope: CoroutineScope,
    private val gateway: VehicleGateway,
    private val offer: (RiskEvent) -> Unit,
    private val onRested: () -> Unit,
) {
    private data class Saved(
        val power: Boolean?,
        val recirculation: Boolean?,
        val fan: Int?,
        val temperature: Float?,
        val ventilation: Int?,
    )

    private var awakenJob: Job? = null
    private var napJob: Job? = null

    /** True while the wake-up program wants outside air (the jam care must not switch to recirculation). */
    @Volatile var awakening = false
        private set

    /** Shortened by scenarios so a demo doesn't wait 15 minutes. */
    var napMs = NAP_MS

    fun awaken() {
        if (awakenJob?.isActive == true) return
        awakenJob = scope.launch {
            awakening = true
            val power = gateway.getHvacPower().getOrNull()
            // Fan and temperature can't be set while the climate system is off.
            val powerOn = if (power == false) gateway.setHvacPower(true) else null
            val saved = Saved(
                power = power,
                recirculation = gateway.getRecirculation().getOrNull(),
                fan = gateway.getFanSpeed().getOrNull(),
                temperature = gateway.getTemperature().getOrNull(),
                ventilation = gateway.getSeatVentilation().getOrNull(),
            )
            val results = listOfNotNull(
                powerOn?.let { "climate on" to it },
                "outside air" to gateway.setRecirculation(false),
                "fan" to gateway.setFanSpeed(((saved.fan ?: 0) + FAN_BOOST).coerceAtLeast(MIN_AWAKE_FAN)),
                saved.temperature?.let { "temperature" to gateway.setTemperature(it - COOLER_BY_C) },
                "seat ventilation" to gateway.setSeatVentilation(SEAT_VENT_LEVEL),
            )
            Log.i(TAG, "wake-up program: " + results.joinToString { (name, r) -> "$name=${r.label()}" } + " (saved $saved)")
            try {
                delay(AWAKEN_MS)
            } finally {
                restore(saved)
                awakening = false
            }
        }
    }

    fun nap() {
        if (napJob?.isActive == true) return
        awakenJob?.cancel()
        napJob = scope.launch {
            val color = gateway.getAmbientColor().getOrNull()
            val temperature = gateway.getTemperature().getOrNull()
            val reclined = moveBackrest(MoveControl.BACKWARD)
            gateway.setAmbientColor(NAP_COLOR)
            gateway.setTemperature(NAP_TEMPERATURE_C)
            Log.i(TAG, "nap started for ${napMs / 1000}s (backrest ${if (reclined) "reclined" else "not moved"})")
            delay(napMs)

            gateway.setRecirculation(false)
            gateway.setAmbientColor(WAKE_COLOR)
            offer(
                RiskEvent(
                    key = "drowsy-wake-up-${System.currentTimeMillis()}",
                    level = RiskLevel.DROWSY,
                    speech = (if (napMs >= 60_000L) "${napMs / 60_000L}분 지났어요." else "휴식 시간이 끝났어요.") +
                        " 기지개 한 번 켜고 상쾌하게 출발하세요.",
                    screenText = "휴식 끝 · 상쾌하게 출발",
                    cooldownMs = 0L,
                )
            )
            if (reclined) moveBackrest(MoveControl.FORWARD)
            delay(WAKE_LIGHT_MS)
            color?.let { gateway.setAmbientColor(it) }
            temperature?.let { gateway.setTemperature(it) }
            onRested()
            Log.i(TAG, "nap finished")
        }
    }

    private suspend fun restore(saved: Saved) {
        saved.recirculation?.let { gateway.setRecirculation(it) }
        saved.fan?.let { gateway.setFanSpeed(it) }
        saved.temperature?.let { gateway.setTemperature(it) }
        saved.ventilation?.let { gateway.setSeatVentilation(it) }
        if (saved.power == false) gateway.setHvacPower(false)
        Log.i(TAG, "wake-up program ended, settings restored")
    }

    /** The SDK moves the backrest while a direction is held, so move for a fixed time and stop. */
    private suspend fun moveBackrest(control: MoveControl): Boolean {
        val started = gateway.moveBackrest(control)
        if (started.isFailure) {
            Log.w(TAG, "backrest $control: ${started.exceptionOrNull()?.message}")
            return false
        }
        delay(BACKREST_MOVE_MS)
        gateway.moveBackrest(MoveControl.STOP)
        return true
    }

    companion object {
        private const val TAG = "SafeGuard/Drowsy"
        private const val AWAKEN_MS = 10 * 60_000L
        private const val NAP_MS = 15 * 60_000L
        private const val WAKE_LIGHT_MS = 60_000L
        private const val BACKREST_MOVE_MS = 3_000L
        private const val FAN_BOOST = 2
        private const val MIN_AWAKE_FAN = 4
        private const val COOLER_BY_C = 2f
        private const val SEAT_VENT_LEVEL = 2
        private const val NAP_TEMPERATURE_C = 23f
        private const val NAP_COLOR = 0xFF3D2A12.toInt()
        private const val WAKE_COLOR = 0xFFFFFFFF.toInt()

        private fun Result<Unit>.label() = when {
            isSuccess -> "ok"
            exceptionOrNull() is VehicleGateway.VehicleTimeoutException -> "no-reply"
            else -> "fail"
        }
    }
}
