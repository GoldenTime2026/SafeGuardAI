package com.safeguard.ai.features.share

import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskRule

/**
 * Notices this car braking hard at speed and reports where, so the server can spot a sudden jam tail when
 * several cars brake at the same place. It says nothing to this driver (who already knows they braked).
 *
 * Hard braking: from at least [MIN_FROM_KMH], losing [MIN_DROP_KMH] or more within [WINDOW_MS]
 * (about 0.4 g on average; ordinary braking stays well below that).
 */
class HardBrakingDetector(
    private val report: (lat: Double, lon: Double, heading: Float?) -> Unit,
) : RiskRule {

    private val samples = ArrayDeque<Pair<Long, Double>>()
    private var lastReportAt: Long? = null

    override fun evaluate(snapshot: VehicleSnapshot, now: Long): List<RiskEvent> {
        val speed = snapshot.speedKmh ?: return emptyList()
        samples.addLast(now to speed)
        while (samples.isNotEmpty() && now - samples.first().first > WINDOW_MS) samples.removeFirst()
        val peak = samples.maxOf { it.second }
        val coolingDown = lastReportAt?.let { now - it < COOLDOWN_MS } ?: false
        if (peak >= MIN_FROM_KMH && peak - speed >= MIN_DROP_KMH && !coolingDown) {
            val lat = snapshot.latitude
            val lon = snapshot.longitude
            if (lat != null && lon != null) {
                lastReportAt = now
                samples.clear()
                report(lat, lon, snapshot.headingDeg)
            }
        }
        return emptyList()
    }

    companion object {
        const val MIN_FROM_KMH = 50.0
        const val MIN_DROP_KMH = 30.0
        const val WINDOW_MS = 3_000L
        private const val COOLDOWN_MS = 60_000L
    }
}
