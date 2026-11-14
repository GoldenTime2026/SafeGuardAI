package com.safeguard.ai.features.briefing

import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.data.publicdata.Geo
import com.safeguard.ai.features.hazard.HazardMap
import com.safeguard.ai.features.weather.WeatherStatus
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import com.safeguard.ai.risk.RiskRule
import com.safeguard.ai.risk.withSubjectParticle

/**
 * Departure briefing — one or two sentences once per start, only while parked.
 * Re-uses what the app already knows (weather, hazard map, car checks); it creates no new data.
 * Route-aware items (weather and hazards along the planned route) come with the navigation stage.
 */
class DepartureBriefing(
    private val map: HazardMap,
    private val weather: () -> WeatherStatus,
    private var startedAt: Long = System.currentTimeMillis(),
) : RiskRule {

    private var done = false

    /** Treat [now] as a new start (scenario "briefing" step, later a real ignition event). */
    fun restart(now: Long) {
        startedAt = now
        done = false
    }

    override fun evaluate(snapshot: VehicleSnapshot, now: Long): List<RiskEvent> {
        if (done || now - startedAt < SETTLE_MS) return emptyList()
        if (!snapshot.isParked) {
            // Already driving when signals settled: a briefing now would distract, so skip it for this trip.
            if (snapshot.isMoving) done = true
            return emptyList()
        }
        done = true
        val items = items(snapshot, now)
        val speech = items.take(MAX_SENTENCES).joinToString(" ").ifEmpty { "오늘 경로는 특이사항이 없습니다." }
        return listOf(
            RiskEvent(
                key = "briefing-$startedAt",
                level = RiskLevel.INFO,
                speech = speech,
                screenText = if (items.isEmpty()) "출발 브리핑 · 특이사항 없음" else "출발 브리핑",
                cooldownMs = 0,
                ttlMs = BRIEFING_TTL_MS,
            )
        )
    }

    /**
     * Most important first: conditions that change how to drive, then car checks (something the driver
     * can act on before leaving), then the count of nearby hazards.
     */
    internal fun items(s: VehicleSnapshot, now: Long): List<String> = buildList {
        val w = weather()
        if (w.condition.isAdverse) {
            val speed = w.recommendedKmh?.let { " 권장 속도는 ${it}킬로미터예요." } ?: " 평소보다 천천히 출발하세요."
            add("지금 ${w.condition.label} 상태예요.$speed")
        }
        val checks = buildList {
            if (s.washerFluidLow == true) add("워셔액")
            if (s.has("TPMS")) add("타이어 공기압")
        }
        if (checks.isNotEmpty()) add("출발 전에 ${checks.joinToString("과 ")}을 확인하세요.")
        val lat = s.latitude
        val lon = s.longitude
        if (lat != null && lon != null) {
            val nearby = map.active(now).count { Geo.distanceMeters(lat, lon, it.latitude, it.longitude) <= NEARBY_M }
            if (nearby > 0) add("주변 5킬로미터 안에 주의 지점이 ${nearby}곳 있어요.")
        }
    }

    /**
     * Asked for by voice ("세이프가드, 브리핑해 줘"), so it may be given while driving: the same items as at
     * departure plus how long the driver has been driving, still capped at a few short sentences.
     */
    fun onDemand(s: VehicleSnapshot, now: Long, activeLights: List<String>): RiskEvent {
        val items = buildList {
            addAll(items(s, now).filterNot { it.startsWith("출발 전에") })
            if (activeLights.isNotEmpty()) add("${activeLights.joinToString(", ").withSubjectParticle()} 켜져 있어요.")
            s.drivingMinutes?.takeIf { it >= 60 }?.let { add("${it / 60}시간 넘게 운전 중이에요. 졸리면 꼭 쉬어 가세요.") }
        }
        return RiskEvent(
            key = "briefing-on-demand-$now",
            level = RiskLevel.INFO,
            speech = items.take(ON_DEMAND_SENTENCES).joinToString(" ").ifEmpty { "지금은 특별히 조심할 것이 없어요. 안전 운전하세요." },
            screenText = "안전 브리핑",
            cooldownMs = 0,
            ttlMs = BRIEFING_TTL_MS,
        )
    }

    companion object {
        private const val ON_DEMAND_SENTENCES = 3
        /** Wait for the first vehicle signals before deciding what to say. */
        private const val SETTLE_MS = 8_000L
        private const val BRIEFING_TTL_MS = 30_000L
        private const val MAX_SENTENCES = 2
        private const val NEARBY_M = 5_000.0
    }
}
