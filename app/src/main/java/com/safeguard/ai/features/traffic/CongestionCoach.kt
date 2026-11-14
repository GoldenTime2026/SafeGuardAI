package com.safeguard.ai.features.traffic

import com.safeguard.ai.core.Congestion
import com.safeguard.ai.core.TrafficIncident
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.data.publicdata.Geo
import com.safeguard.ai.risk.Confirm
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import com.safeguard.ai.risk.RiskRule
import com.safeguard.ai.risk.spokenDistance
import kotlin.math.roundToInt

/**
 * Feature ③ — explains a jam ahead in one sentence (cause, distance, how long) and offers a detour.
 * The driver doesn't have to read the map: "2.3킬로미터 앞 사고로 정체 중입니다 … 우회할까요?" → "응".
 */
class CongestionCoach(private val reroute: () -> Unit) : RiskRule {

    override fun evaluate(snapshot: VehicleSnapshot, now: Long): List<RiskEvent> {
        val traffic = snapshot.traffic ?: return emptyList()
        if (!traffic.routing || snapshot.isParked) return emptyList()
        val jam = traffic.congestions
            .filter { it.offsetMeters in 0.0..LOOKAHEAD_M && it.timeSeconds >= MIN_DELAY_S }
            .minByOrNull { it.offsetMeters } ?: return emptyList()
        val cause = causeOf(jam, traffic.incidents, snapshot)
        return listOf(
            RiskEvent(
                // Keyed by the jam only: the matched cause can change as the car moves, and that's no reason to repeat.
                key = "traffic-${jam.road}-${jam.fromCross}",
                level = RiskLevel.TRAFFIC,
                speech = speech(jam, cause),
                screenText = "${distance(jam.offsetMeters)} 앞 ${causeLabel(cause)}정체 · ${minutes(jam.timeSeconds)}분",
                cooldownMs = COOLDOWN_MS,
                ttlMs = TTL_MS,
                confirm = Confirm(
                    yesSpeech = "더 빠른 길을 찾을게요.",
                    noSpeech = "현재 경로를 유지할게요.",
                    onYes = { reroute() },
                ),
            )
        )
    }

    companion object {
        private const val LOOKAHEAD_M = 3_000.0
        private const val MIN_DELAY_S = 180L
        private const val COOLDOWN_MS = 15 * 60_000L
        private const val TTL_MS = 20_000L
        /** An incident counts as the cause if it lies within this margin of the jammed stretch. */
        private const val CAUSE_MARGIN_M = 500.0

        /**
         * The incident nearest to the jam: its straight-line distance from the car should fall between the
         * start and end of the congested stretch (with a margin), since the route is roughly ahead.
         */
        internal fun causeOf(jam: Congestion, incidents: List<TrafficIncident>, s: VehicleSnapshot): TrafficIncident? {
            val lat = s.latitude ?: return incidents.firstOrNull()
            val lon = s.longitude ?: return incidents.firstOrNull()
            val from = jam.offsetMeters - CAUSE_MARGIN_M
            val to = jam.offsetMeters + jam.lengthMeters + CAUSE_MARGIN_M
            return incidents
                .map { it to Geo.distanceMeters(lat, lon, it.latitude, it.longitude) }
                .filter { (_, d) -> d in from..to }
                .minByOrNull { (_, d) -> d }?.first
        }

        internal fun speech(jam: Congestion, cause: TrafficIncident?): String {
            val avg = jam.avgKmh.roundToInt().coerceAtLeast(1)
            return "${distance(jam.offsetMeters)} 앞 ${causeLabel(cause)}정체 중입니다. " +
                "평균 ${avg}킬로미터, 통과에 약 ${minutes(jam.timeSeconds)}분 걸려요. 우회할까요?"
        }

        private fun causeLabel(cause: TrafficIncident?) = when (cause?.type) {
            "ACCIDENT" -> "사고로 "
            "REPAIRING" -> "공사로 "
            "DISASTER" -> "재난 상황으로 "
            "ROAD_DAMAGE" -> "도로 파손으로 "
            "EVENT" -> "행사로 "
            else -> ""
        }

        private fun minutes(seconds: Long) = (seconds / 60.0).roundToInt().coerceAtLeast(1)

        internal fun distance(meters: Double): String = spokenDistance(meters)
    }
}
