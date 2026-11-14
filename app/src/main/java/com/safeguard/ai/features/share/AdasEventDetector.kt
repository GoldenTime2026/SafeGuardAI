package com.safeguard.ai.features.share

import com.safeguard.ai.core.TelltaleColor
import com.safeguard.ai.core.TelltaleLevel
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.features.weather.RoadCondition
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskRule

/**
 * Turns the driver-assist lights into two anonymous Safety Mesh reports. It says nothing to this driver.
 *
 * - **NEAR_MISS**: the forward-collision or emergency-lane-keeping light flashing or turning red while driving —
 *   the car warned of a collision. Accident statistics only count crashes that happened; spots where several cars
 *   were warned are where the next one is likely.
 * - **LANE_FADE**: lane following on (green) at speed on a dry road, then dropping to standby without a turn
 *   signal — the camera lost the lane lines. Several cars at the same place point to worn paint.
 *
 * The server publishes a cell only after several different cars report it, so one driver switching the assist
 * off does not mark a road. How the lights behave during a warning on a real car is [확인 필요].
 */
class AdasEventDetector(
    private val weather: () -> RoadCondition,
    private val report: (type: String, lat: Double, lon: Double, heading: Float?) -> Unit,
) : RiskRule {

    private var warning = false
    private var lastNearMissAt: Long? = null
    private var laneOnSince: Long? = null
    private var lastLaneFadeAt: Long? = null

    override fun evaluate(snapshot: VehicleSnapshot, now: Long): List<RiskEvent> {
        val speed = snapshot.speedKmh ?: 0.0
        nearMiss(snapshot, speed, now)
        laneFade(snapshot, speed, now)
        return emptyList()
    }

    private fun nearMiss(s: VehicleSnapshot, speed: Double, now: Long) {
        val warningNow = COLLISION_LIGHTS.any { s.telltales[it] == TelltaleLevel.BLINK || s.telltaleColors[it] == TelltaleColor.RED }
        val started = warningNow && !warning
        warning = warningNow
        if (!started || speed < NEAR_MISS_MIN_KMH || recent(lastNearMissAt, now)) return
        if (send("NEAR_MISS", s)) lastNearMissAt = now
    }

    private fun laneFade(s: VehicleSnapshot, speed: Double, now: Long) {
        val lane = LANE_LIGHTS.firstNotNullOfOrNull { s.telltaleColors[it] }
        if (lane == TelltaleColor.GREEN && speed >= LANE_MIN_KMH) {
            if (laneOnSince == null) laneOnSince = now
            return
        }
        val heldOn = laneOnSince?.let { now - it >= LANE_HOLD_MS } ?: false
        laneOnSince = null
        val standby = lane == TelltaleColor.WHITE || lane == TelltaleColor.GRAY
        val dry = weather() == RoadCondition.DRY
        if (!heldOn || !standby || !dry || speed < LANE_MIN_KMH || s.turnSignal != "OFF" || recent(lastLaneFadeAt, now)) return
        if (send("LANE_FADE", s)) lastLaneFadeAt = now
    }

    private fun send(type: String, s: VehicleSnapshot): Boolean {
        val lat = s.latitude ?: return false
        val lon = s.longitude ?: return false
        report(type, lat, lon, s.headingDeg)
        return true
    }

    private fun recent(at: Long?, now: Long) = at != null && now - at < COOLDOWN_MS

    companion object {
        private val COLLISION_LIGHTS = listOf("FCA", "ELK")
        private val LANE_LIGHTS = listOf("LFA", "LKA")
        private const val NEAR_MISS_MIN_KMH = 20.0
        private const val LANE_MIN_KMH = 60.0
        private const val LANE_HOLD_MS = 20_000L
        private const val COOLDOWN_MS = 60_000L
    }
}
