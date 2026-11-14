package com.safeguard.ai.features.weather

import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.core.WiperLevel
import kotlin.math.roundToInt

/**
 * Road condition as the car experiences it.
 * [legalFactor] follows 도로교통법 시행규칙 제19조: wet road or rain → 20 % slower,
 * visibility under 100 m (heavy rain, fog) or icy road → 50 % slower.
 * [friction] is a typical tyre-road coefficient used for the stopping-distance estimate.
 */
enum class RoadCondition(val label: String, val legalFactor: Double, val friction: Double) {
    UNKNOWN("확인 중", 1.0, 0.7),
    DRY("맑음", 1.0, 0.7),
    WET("젖은 노면", 0.8, 0.5),
    RAIN("비", 0.8, 0.4),
    HEAVY_RAIN("폭우", 0.5, 0.3),
    FOG("안개", 0.5, 0.5),
    ICE("결빙", 0.5, 0.15);

    val isAdverse get() = legalFactor < 1.0
}

data class WeatherStatus(
    val condition: RoadCondition = RoadCondition.UNKNOWN,
    val recommendedKmh: Int? = null,
    /** How many times longer than on a dry road the car needs to stop at the current speed. */
    val stoppingFactor: Double = 1.0,
)

object WeatherModel {

    private const val GRAVITY = 9.81
    private const val REACTION_TIME_S = 1.0
    private const val FOG_CAUTION_RANGE_M = 1_000
    /** Speed used for the stopping-distance comparison when the car's speed is unknown. */
    private const val REFERENCE_KMH = 80.0

    /**
     * Picks the worst condition the signals support.
     * [wetUntil] keeps the road "wet" for a while after the wipers stop (the road doesn't dry instantly).
     */
    fun classify(s: VehicleSnapshot, now: Long, wetUntil: Long): RoadCondition = when {
        s.has("ICY_ROAD") -> RoadCondition.ICE
        s.frontFogOn == true || s.has("FRONT_FOG_LAMP") ||
            s.cautions.any { it.type == "FOG_AREA" && it.remainMeters <= FOG_CAUTION_RANGE_M } -> RoadCondition.FOG
        s.wiper == WiperLevel.HIGH -> RoadCondition.HEAVY_RAIN
        s.wiper == WiperLevel.LOW || s.wiper == WiperLevel.INTERMITTENT -> RoadCondition.RAIN
        now < wetUntil -> RoadCondition.WET
        s.wiper == WiperLevel.OFF -> RoadCondition.DRY
        else -> RoadCondition.UNKNOWN
    }

    /** Legal adverse-weather speed for the current road; null when the limit is unknown. */
    fun recommendedSpeed(limitKmh: Int?, condition: RoadCondition): Int? =
        limitKmh?.takeIf { it > 0 }?.let { (it * condition.legalFactor).roundToInt() }

    /** Reaction distance + braking distance, in metres. */
    fun stoppingDistanceMeters(speedKmh: Double, condition: RoadCondition): Double {
        val v = speedKmh / 3.6
        return v * REACTION_TIME_S + v * v / (2 * condition.friction * GRAVITY)
    }

    fun stoppingFactor(speedKmh: Double?, condition: RoadCondition): Double {
        val v = speedKmh?.takeIf { it >= 10.0 } ?: REFERENCE_KMH
        return stoppingDistanceMeters(v, condition) / stoppingDistanceMeters(v, RoadCondition.DRY)
    }

    /**
     * How long the road stays wet after the wipers stop: as long as it rained,
     * at least 5 and at most 20 minutes, half again after heavy rain.
     */
    fun wetHoldMillis(rainDurationMs: Long, wasHeavy: Boolean): Long {
        val base = rainDurationMs.coerceIn(5 * 60_000L, 20 * 60_000L)
        return if (wasHeavy) (base * 1.5).toLong() else base
    }

    fun status(s: VehicleSnapshot, condition: RoadCondition) = WeatherStatus(
        condition = condition,
        recommendedKmh = recommendedSpeed(s.roadSpeedLimitKmh, condition),
        stoppingFactor = stoppingFactor(s.speedKmh, condition),
    )
}
