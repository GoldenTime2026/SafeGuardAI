package com.safeguard.ai.features.hazard

import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.risk.Confirm
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import com.safeguard.ai.risk.RiskRule
import java.time.ZoneId

/**
 * During school commute hours, when the guided route runs through a school zone, offers once per trip to
 * switch navigation to its school-zone-avoiding option (NaviHelper RouteOption.SCHOOL_ZONE).
 * The driver decides by voice; nothing changes without a "yes".
 */
class SchoolZoneRoute(
    private val avoidSchoolZones: () -> Unit,
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) : RiskRule {

    private var offeredThisTrip = false
    private var avoiding = false

    override fun evaluate(snapshot: VehicleSnapshot, now: Long): List<RiskEvent> {
        val s = snapshot
        if (s.isParked || s.traffic?.routing != true) {
            if (s.traffic?.routing != true) {
                offeredThisTrip = false
                avoiding = false
            }
            return emptyList()
        }
        if (offeredThisTrip || avoiding || !HazardRule.isSchoolHours(s, now, zone)) return emptyList()
        // Far enough ahead that a different route is still possible.
        val ahead = s.cautions.filter { it.type == "SCHOOL_ZONE" && it.remainMeters in MIN_AHEAD_M..MAX_AHEAD_M }
        if (ahead.isEmpty()) return emptyList()
        offeredThisTrip = true
        return listOf(
            RiskEvent(
                key = "school-zone-route",
                level = RiskLevel.TRAFFIC,
                speech = "등하교 시간이에요. 경로에 어린이보호구역이 있어요. 피해 가는 길로 바꿀까요?",
                screenText = "등하교 시간 · 스쿨존 회피 경로?",
                cooldownMs = TRIP_COOLDOWN_MS,
                ttlMs = OFFER_TTL_MS,
                confirm = Confirm(
                    yesSpeech = "어린이보호구역을 피하는 길로 바꿀게요.",
                    noSpeech = "알겠어요. 스쿨존에서는 시속 30킬로미터로 서행하세요.",
                    onYes = {
                        avoiding = true
                        avoidSchoolZones()
                    },
                ),
            )
        )
    }

    companion object {
        const val MIN_AHEAD_M = 500
        const val MAX_AHEAD_M = 3_000
        private const val TRIP_COOLDOWN_MS = 60 * 60_000L
        private const val OFFER_TTL_MS = 20_000L
    }
}
