package com.safeguard.ai.features.traffic

import com.safeguard.ai.core.Gear
import com.safeguard.ai.core.VehicleGateway
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.risk.Confirm
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Feature ③ — the cabin while stuck in a jam.
 * - Crawling for a while switches to recirculation (less exhaust from the car ahead) and restores the
 *   driver's setting once traffic moves. Skipped while defogging or the drowsiness wake-up needs outside air.
 * - After a long jam it offers a short relax program (benchmark: BMW Caring Car "Relax").
 */
class CongestionCabin(
    private val scope: CoroutineScope,
    private val gateway: VehicleGateway,
    private val snapshots: StateFlow<VehicleSnapshot>,
    /** Defogging or the drowsiness wake-up program needs outside air; recirculation would undo it. */
    private val keepOutsideAir: () -> Boolean,
    private val offer: (RiskEvent) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private enum class Phase { FREE, CRAWLING, IN_JAM, LEAVING }

    private var phase = Phase.FREE
    private var phaseSince = 0L
    private var jamStartedAt = 0L
    private var savedRecirculation: Boolean? = null
    private var recirculating = false
    private var relaxOffered = false

    fun start() {
        scope.launch {
            while (isActive) {
                tick(snapshots.value, clock())
                delay(TICK_MS)
            }
        }
    }

    /** Scenario hook: offer the relax program now instead of after a long jam. */
    fun offerRelaxNow() = offer(relaxEvent())

    private suspend fun tick(s: VehicleSnapshot, now: Long) {
        val speed = s.speedKmh ?: return
        val driving = s.gear == Gear.DRIVE || s.gear == Gear.UNKNOWN
        when (phase) {
            Phase.FREE -> if (driving && speed < CRAWL_KMH) move(Phase.CRAWLING, now)
            Phase.CRAWLING -> when {
                !driving || speed >= CRAWL_KMH -> move(Phase.FREE, now)
                now - phaseSince >= ENTER_JAM_MS -> {
                    move(Phase.IN_JAM, now)
                    jamStartedAt = now
                    enterJam()
                }
            }
            Phase.IN_JAM -> when {
                !driving -> leaveJam(now)
                speed >= FREE_KMH -> move(Phase.LEAVING, now)
                !relaxOffered && now - jamStartedAt >= RELAX_AFTER_MS -> {
                    relaxOffered = true
                    offer(relaxEvent())
                }
            }
            Phase.LEAVING -> when {
                speed < FREE_KMH -> move(Phase.IN_JAM, now)
                now - phaseSince >= LEAVE_JAM_MS -> leaveJam(now)
            }
        }
    }

    private fun move(next: Phase, now: Long) {
        phase = next
        phaseSince = now
    }

    private suspend fun enterJam() {
        if (keepOutsideAir()) {
            Log.i(TAG, "in jam, keeping outside air (defogging or wake-up program)")
            return
        }
        savedRecirculation = gateway.getRecirculation().getOrNull()
        val result = gateway.setRecirculation(true)
        // A timed-out command is often applied anyway, so it still gets restored later.
        recirculating = result.isSuccess || result.exceptionOrNull() is VehicleGateway.VehicleTimeoutException
        Log.i(TAG, "in jam: recirculation on -> ${if (result.isSuccess) "ok" else result.exceptionOrNull()?.message} (was $savedRecirculation)")
    }

    private suspend fun leaveJam(now: Long) {
        if (recirculating) {
            // Unknown previous state: back to outside air, so CO2 doesn't build up and make the driver drowsy.
            val restoreTo = savedRecirculation ?: false
            val result = gateway.setRecirculation(restoreTo)
            Log.i(TAG, "traffic moving: recirculation=$restoreTo -> ${if (result.isSuccess) "ok" else result.exceptionOrNull()?.message}")
        }
        recirculating = false
        savedRecirculation = null
        relaxOffered = false
        move(Phase.FREE, now)
    }

    private fun relaxEvent() = RiskEvent(
        key = "traffic-relax",
        level = RiskLevel.INFO,
        speech = "정체가 길어지고 있어요. 편안한 모드로 바꿀까요?",
        screenText = "정체 중 · 편안한 모드?",
        cooldownMs = RELAX_COOLDOWN_MS,
        ttlMs = 20_000L,
        confirm = Confirm(yesSpeech = "3분 동안 편안한 모드로 바꿀게요.", onYes = { relax() }),
    )

    /** Warm light, gentle airflow for a few minutes, then the driver's settings come back. */
    private fun relax() {
        scope.launch {
            val color = gateway.getAmbientColor().getOrNull()
            gateway.setAmbientColor(WARM_COLOR)
            gateway.setFanSpeed(RELAX_FAN)
            delay(RELAX_MS)
            color?.let { gateway.setAmbientColor(it) }
            Log.i(TAG, "relax program finished")
        }
    }

    companion object {
        private const val TAG = "SafeGuard/Jam"
        private const val TICK_MS = 2_000L
        private const val CRAWL_KMH = 15.0
        private const val FREE_KMH = 35.0
        private const val ENTER_JAM_MS = 30_000L
        private const val LEAVE_JAM_MS = 30_000L
        private const val RELAX_AFTER_MS = 15 * 60_000L
        private const val RELAX_MS = 3 * 60_000L
        private const val RELAX_COOLDOWN_MS = 60 * 60_000L
        private const val RELAX_FAN = 2
        private const val WARM_COLOR = 0xFFFFB74D.toInt()
    }
}
