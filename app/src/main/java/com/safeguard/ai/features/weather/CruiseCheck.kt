package com.safeguard.ai.features.weather

import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import com.safeguard.ai.risk.RiskRule
import kotlin.math.roundToInt

/**
 * Cruise control keeps its set speed into a school zone or onto a wet road. 3rd-party apps can only read
 * the set speed (Vehicle SDK DrivingMode), so this says the gap and leaves the change to the driver.
 */
class CruiseCheck(private val weather: () -> WeatherStatus) : RiskRule {

    override fun evaluate(snapshot: VehicleSnapshot, now: Long): List<RiskEvent> {
        val s = snapshot
        val set = s.cruiseTargetKmh?.roundToInt() ?: return emptyList()
        if (s.cruiseActive != true || !s.isMoving) return emptyList()

        if (s.cautions.any { it.type == "SCHOOL_ZONE" && it.remainMeters <= SCHOOL_ZONE_RANGE_M } && set > SCHOOL_ZONE_KMH) {
            return listOf(
                RiskEvent(
                    key = "cruise-school-zone",
                    level = RiskLevel.FORWARD_HAZARD,
                    speech = "크루즈가 ${set}킬로미터예요. 앞 스쿨존은 30킬로미터예요. 크루즈를 끄고 서행하세요.",
                    screenText = "크루즈 $set km/h · 스쿨존 30",
                    cooldownMs = COOLDOWN_MS,
                )
            )
        }
        val w = weather()
        val recommended = w.recommendedKmh
        if (w.condition.isAdverse && recommended != null && set > recommended + MARGIN_KMH) {
            return listOf(
                RiskEvent(
                    key = "cruise-weather-${w.condition}",
                    level = RiskLevel.WEATHER,
                    speech = "${w.condition.label} 중인데 크루즈가 ${set}킬로미터예요. 권장 ${recommended}킬로미터로 낮추세요.",
                    screenText = "크루즈 $set km/h · 권장 $recommended",
                    cooldownMs = COOLDOWN_MS,
                )
            )
        }
        return emptyList()
    }

    private companion object {
        const val SCHOOL_ZONE_KMH = 30
        const val SCHOOL_ZONE_RANGE_M = 500
        const val MARGIN_KMH = 5
        const val COOLDOWN_MS = 5 * 60_000L
    }
}
