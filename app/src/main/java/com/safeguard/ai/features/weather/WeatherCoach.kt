package com.safeguard.ai.features.weather

import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.core.WiperLevel
import com.safeguard.ai.features.hazard.HazardRule
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import com.safeguard.ai.risk.RiskRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Feature ① — adverse-weather coach (benchmark: Porsche Wet Mode: detect, suggest, let the driver act).
 *
 * Speaks when the road condition worsens (with the legal speed and stopping distance), warns when the
 * driver stays above the recommended speed, and covers the overlaps that matter most: a school zone in
 * the rain, sport mode on a wet road, low washer fluid or low tyre pressure while it rains.
 */
class WeatherCoach(private val clock: () -> Long = System::currentTimeMillis) : RiskRule {

    private val _status = MutableStateFlow(WeatherStatus())
    val status: StateFlow<WeatherStatus> = _status.asStateFlow()

    private var condition = RoadCondition.UNKNOWN
    private var rainStartedAt: Long? = null
    private var heavySeen = false
    private var wetUntil = 0L
    private var overSince: Long? = null
    /** Worst condition already announced; reset when the road clears so the next rain is announced again. */
    private var announcedSeverity = 0

    /** Back to a dry, unannounced road (scenario start, so each demo begins from the same state). */
    fun reset() {
        rainStartedAt = null
        heavySeen = false
        wetUntil = 0L
        overSince = null
        announcedSeverity = 0
    }

    override fun evaluate(snapshot: VehicleSnapshot, now: Long): List<RiskEvent> {
        trackRain(snapshot, now)
        condition = WeatherModel.classify(snapshot, now, wetUntil)
        val status = WeatherModel.status(snapshot, condition)
        _status.value = status
        if (!condition.isAdverse) announcedSeverity = 0

        // While parked the departure briefing covers the weather and car checks; the coach speaks once
        // the car is under way (also when it was already raining at start).
        if (snapshot.isParked) return emptyList()
        return buildList {
            if (condition.isAdverse && condition.severity > announcedSeverity) {
                announcedSeverity = condition.severity
                add(onset(snapshot, status))
            }
            overspeed(snapshot, status, now)?.let(::add)
            if (condition.isAdverse) addAll(overlaps(snapshot))
        }
    }

    /** Remembers how long and how hard it rained so the road stays "wet" after the wipers stop. */
    private fun trackRain(s: VehicleSnapshot, now: Long) {
        val raining = s.wiper == WiperLevel.INTERMITTENT || s.wiper == WiperLevel.LOW || s.wiper == WiperLevel.HIGH
        val started = rainStartedAt
        when {
            raining && started == null -> rainStartedAt = now
            raining -> Unit
            started != null && s.wiper == WiperLevel.OFF -> {
                wetUntil = now + WeatherModel.wetHoldMillis(now - started, heavySeen)
                rainStartedAt = null
                heavySeen = false
            }
        }
        if (s.wiper == WiperLevel.HIGH) heavySeen = true
    }

    private fun onset(s: VehicleSnapshot, status: WeatherStatus): RiskEvent {
        val opening = when (status.condition) {
            RoadCondition.WET -> "노면이 젖어 있어요."
            RoadCondition.RAIN -> "비가 내립니다."
            RoadCondition.HEAVY_RAIN -> "비가 강해졌어요."
            RoadCondition.FOG -> "안개 구간입니다."
            RoadCondition.ICE -> "노면이 얼었을 수 있어요."
            else -> ""
        }
        // Short on purpose: the driver should get it in one breath (Pleos glanceability rule).
        val speed = status.recommendedKmh?.let { " 권장 ${it}킬로미터." } ?: " 속도를 줄이세요."
        val stopping = if (status.stoppingFactor >= 1.2) {
            " 정지거리 ${"%.1f".format(Locale.KOREA, status.stoppingFactor)}배."
        } else ""
        val assist = if (VisibilityAssist.shouldEngage(status.condition, s)) " 김서림 방지를 켤게요." else ""
        return RiskEvent(
            key = "weather-onset-${status.condition}",
            level = RiskLevel.WEATHER,
            speech = opening + speed + stopping + assist,
            screenText = status.screenText(),
            cooldownMs = ONSET_COOLDOWN_MS,
        )
    }

    /** Warns once the driver has stayed above the recommended speed for a few seconds. */
    private fun overspeed(s: VehicleSnapshot, status: WeatherStatus, now: Long): RiskEvent? {
        val recommended = status.recommendedKmh
        val speed = s.speedKmh
        if (!condition.isAdverse || recommended == null || speed == null || speed <= recommended + OVERSPEED_MARGIN_KMH) {
            overSince = null
            return null
        }
        val since = overSince ?: now.also { overSince = it }
        if (now - since < OVERSPEED_HOLD_MS) return null
        return RiskEvent(
            key = "weather-overspeed-${condition}-$recommended",
            level = RiskLevel.WEATHER,
            speech = "권장 속도 ${recommended}킬로미터를 넘었어요.",
            screenText = "권장 $recommended km/h 초과 · 감속",
            cooldownMs = OVERSPEED_COOLDOWN_MS,
        )
    }

    private fun overlaps(s: VehicleSnapshot): List<RiskEvent> = buildList {
        if (s.cautions.any { it.type == "SCHOOL_ZONE" && it.remainMeters <= SCHOOL_ZONE_RANGE_M }) add(
            RiskEvent(
                key = HazardRule.SCHOOL_ZONE_KEY,
                level = RiskLevel.FORWARD_HAZARD,
                speech = "${condition.label} 속 스쿨존입니다. 시속 30킬로미터로 서행하세요.",
                screenText = "${condition.label} · 어린이보호구역 30 km/h",
                cooldownMs = SCHOOL_ZONE_COOLDOWN_MS,
            )
        )
        if (s.drivingMode == "SPORT") add(
            RiskEvent(
                key = "weather-sport-mode",
                level = RiskLevel.WEATHER,
                speech = "노면이 미끄러워요. 노멀 모드를 권장해요.",
                screenText = "노멀 모드 권장",
                cooldownMs = LONG_COOLDOWN_MS,
            )
        )
        if (s.washerFluidLow == true && (condition == RoadCondition.RAIN || condition == RoadCondition.HEAVY_RAIN)) add(
            RiskEvent(
                key = "weather-washer-low",
                level = RiskLevel.WEATHER,
                speech = "워셔액이 부족해요. 가까운 곳에서 보충하세요.",
                screenText = "워셔액 부족",
                cooldownMs = TRIP_COOLDOWN_MS,
            )
        )
        if (s.has("TPMS")) add(
            RiskEvent(
                key = "weather-tpms",
                level = RiskLevel.WEATHER,
                speech = "공기압이 낮은 채로 젖은 길을 달리고 있어요. 감속하세요.",
                screenText = "공기압 낮음 · ${condition.label} 감속",
                cooldownMs = LONG_COOLDOWN_MS,
            )
        )
    }

    companion object {
        private const val OVERSPEED_MARGIN_KMH = 5
        private const val OVERSPEED_HOLD_MS = 3_000L
        private const val OVERSPEED_COOLDOWN_MS = 60_000L
        private const val ONSET_COOLDOWN_MS = 10 * 60_000L
        private const val SCHOOL_ZONE_RANGE_M = 300
        private const val SCHOOL_ZONE_COOLDOWN_MS = 5 * 60_000L
        private const val LONG_COOLDOWN_MS = 30 * 60_000L
        private const val TRIP_COOLDOWN_MS = 60 * 60_000L
    }
}

/** Ordering used to decide whether conditions got worse. */
private val RoadCondition.severity
    get() = when (this) {
        RoadCondition.UNKNOWN, RoadCondition.DRY -> 0
        RoadCondition.WET -> 1
        RoadCondition.RAIN -> 2
        RoadCondition.FOG -> 3
        RoadCondition.HEAVY_RAIN -> 4
        RoadCondition.ICE -> 5
    }

fun WeatherStatus.screenText() = recommendedKmh?.let { "${condition.label} · 권장 $it km/h" } ?: "${condition.label} · 감속하세요"
