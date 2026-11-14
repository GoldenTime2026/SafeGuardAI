package com.safeguard.ai.features.hazard

import com.safeguard.ai.core.TelltaleLevel
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.core.WiperLevel
import com.safeguard.ai.data.publicdata.Geo
import com.safeguard.ai.data.publicdata.HazardKind
import com.safeguard.ai.data.publicdata.HazardPoint
import com.safeguard.ai.features.weather.RoadCondition
import com.safeguard.ai.features.weather.WeatherStatus
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import com.safeguard.ai.risk.RiskRule
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Feature ② — warns about hazard points ahead and feeds this car's own events into the map.
 *
 * Public hotspots only speak under their condition (school hours, holidays, freezing), so drivers
 * aren't told about every hotspot all the time. Rain promotes a hotspot to a forward hazard.
 * Crowd points (slips, hazard-light stops) always speak while they are fresh.
 */
class HazardRule(
    private val map: HazardMap,
    private val weather: () -> WeatherStatus,
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
    /** This car's own events also go to the sharing server (type, position, heading). */
    private val share: (type: String, lat: Double, lon: Double, heading: Float?) -> Unit = { _, _, _, _ -> },
) : RiskRule {

    private var lastEsc: TelltaleLevel? = null
    private var lastIcy = false
    private var lastHazard = false
    private var lastRain: String? = null
    private var lastRainSharedAt = Long.MIN_VALUE / 2

    override fun evaluate(snapshot: VehicleSnapshot, now: Long): List<RiskEvent> {
        reportOwnEvents(snapshot, now)
        val lat = snapshot.latitude ?: return emptyList()
        val lon = snapshot.longitude ?: return emptyList()
        val condition = weather().condition
        return map.active(now)
            .filter { isActive(it, snapshot, condition, now) }
            .mapNotNull { point -> warningFor(point, snapshot, lat, lon, condition) }
    }

    /** This car's slips, icy telltale and hazard-light stops become map events (uploaded in stage 6). */
    private fun reportOwnEvents(s: VehicleSnapshot, now: Long) {
        val lat = s.latitude
        val lon = s.longitude
        val esc = s.telltales["ESC"]
        val icy = s.has("ICY_ROAD")
        val hazard = s.hazardOn && (s.speedKmh ?: 0.0) < HAZARD_STOP_KMH
        if (lat != null && lon != null) {
            if (esc == TelltaleLevel.BLINK && lastEsc != TelltaleLevel.BLINK) {
                map.report(CrowdEventKind.SLIP, lat, lon, now)
                share("SLIP", lat, lon, s.headingDeg)
            }
            if (icy && !lastIcy) {
                map.report(CrowdEventKind.ICE, lat, lon, now)
                share("ICE", lat, lon, s.headingDeg)
            }
            if (hazard && !lastHazard) {
                map.report(CrowdEventKind.HAZARD_LIGHTS, lat, lon, now)
                share("STOPPED_VEHICLE", lat, lon, s.headingDeg)
            }
            // Wipers on while moving: rain here, which the cars behind will reach in a few minutes.
            val rain = when (s.wiper) {
                WiperLevel.HIGH -> "HEAVY_RAIN"
                WiperLevel.LOW -> "RAIN"
                else -> null
            }
            if (rain != null && s.isMoving && (rain != lastRain || now - lastRainSharedAt >= RAIN_RESHARE_MS)) {
                share(rain, lat, lon, s.headingDeg)
                lastRainSharedAt = now
            }
            lastRain = rain
        }
        lastEsc = esc
        lastIcy = icy
        lastHazard = hazard
    }

    internal fun isActive(p: HazardPoint, s: VehicleSnapshot, condition: RoadCondition, now: Long): Boolean = when (p.kind) {
        HazardKind.CHILD_HOTSPOT -> isSchoolHours(s, now)
        HazardKind.HOLIDAY_HOTSPOT -> s.holidayOverride ?: KoreanHolidays.isSeollalOrChuseok(localDate(now))
        HazardKind.ICE_RISK -> s.has("ICY_ROAD") || condition == RoadCondition.ICE || (s.outsideTempC ?: Double.MAX_VALUE) <= FREEZING_C
        HazardKind.CROWD_SLIP, HazardKind.CROWD_HAZARD_LIGHTS, HazardKind.SHARED -> true
    }

    private fun warningFor(p: HazardPoint, s: VehicleSnapshot, lat: Double, lon: Double, condition: RoadCondition): RiskEvent? {
        val toEdge = Geo.distanceMeters(lat, lon, p.latitude, p.longitude) - p.radiusMeters
        if (p.isSharedRain) return rainAhead(p, s, lat, lon, toEdge, condition)
        if (toEdge > triggerMeters(p.kind) || toEdge < -p.radiusMeters) return null
        // Hazard-light points come from this car's own stops: don't warn it while it is still there.
        if (p.kind == HazardKind.CROWD_HAZARD_LIGHTS && toEdge <= OWN_STOP_M) return null
        if (!isAhead(p, s, lat, lon, toEdge)) return null
        if (!sameDirection(p, s)) return null
        // Faded lane paint is for the road operator's map; there is nothing for a driver to do about it.
        if (p.kind == HazardKind.SHARED && p.name == "LANE_FADE") return null
        if (p.kind == HazardKind.SHARED) return sharedWarning(p, spokenDistance(toEdge), toEdge)

        val raining = condition == RoadCondition.RAIN || condition == RoadCondition.HEAVY_RAIN
        val distance = spokenDistance(toEdge)
        val level = when (p.kind) {
            HazardKind.CROWD_SLIP, HazardKind.CROWD_HAZARD_LIGHTS -> RiskLevel.FORWARD_HAZARD
            HazardKind.ICE_RISK -> if (s.has("ICY_ROAD") || p.confirmations > 1) RiskLevel.FORWARD_HAZARD else RiskLevel.HOTSPOT
            else -> if (raining) RiskLevel.FORWARD_HAZARD else RiskLevel.HOTSPOT
        }
        val recommended = weather().recommendedKmh?.takeIf { raining }
        val (speech, screen) = when (p.kind) {
            HazardKind.CROWD_SLIP -> if (p.name.contains("결빙")) {
                "$distance 앞, 차량 ${p.confirmations}대가 결빙을 감지한 구간입니다. 감속하세요." to "$distance 앞 결빙 감지"
            } else {
                "$distance 앞, 최근 차량 ${p.confirmations}대가 미끄러진 구간입니다." to "$distance 앞 미끄러짐 주의"
            }
            HazardKind.CROWD_HAZARD_LIGHTS ->
                "$distance 앞에 비상등을 켠 차량이 있어요. 감속하세요." to "$distance 앞 비상등 차량"
            HazardKind.ICE_RISK -> {
                val confirmed = if (p.confirmations > 1) " 차량 신호로도 확인됐어요." else ""
                "$distance 앞 ${p.name.ifBlank { "구간" }}, 노면이 얼어 있을 수 있어요.$confirmed" to "$distance 앞 결빙 주의"
            }
            HazardKind.CHILD_HOTSPOT -> {
                val rain = if (raining) "비 오는 " else ""
                val speed = recommended?.let { " 권장 ${it}킬로미터." } ?: " 서행하세요."
                "$distance 앞 ${rain}어린이 사고 다발 지점입니다.$speed" to "$distance 앞 어린이 사고 다발"
            }
            HazardKind.HOLIDAY_HOTSPOT ->
                "$distance 앞, 연휴 사고 다발 구간입니다. 앞차 간격을 넓히세요." to "$distance 앞 연휴 사고 다발"
            HazardKind.SHARED -> return null
        }
        return RiskEvent(
            // Shares the weather coach's school-zone key so a navigation school zone and a TAAS child
            // hotspot at the same place are spoken once, not twice.
            key = if (p.kind == HazardKind.CHILD_HOTSPOT) SCHOOL_ZONE_KEY else "hazard-${p.id}",
            level = level,
            speech = speech,
            screenText = screen,
            cooldownMs = if (p.source.name == "CROWD") CROWD_COOLDOWN_MS else PUBLIC_COOLDOWN_MS,
        )
    }

    /**
     * What other cars shared. Said at 1 km and again at 300 m for the ones that need braking (a crash, a jam
     * tail), since a car at highway speed covers 1 km in about 36 s.
     */
    private fun sharedWarning(p: HazardPoint, distance: String, toEdge: Double): RiskEvent {
        val cars = if (p.confirmations > 1) "차량 ${p.confirmations}대가 " else ""
        val (speech, screen, level) = when (p.name) {
            "CRASH", "ACCIDENT" -> Triple("$distance 앞에 사고 차량이 있어요. 속도를 줄이세요.", "$distance 앞 사고 차량", RiskLevel.FORWARD_HAZARD)
            "HARD_BRAKING" -> Triple("$distance 앞에서 ${cars}급정거했어요. 정체 꼬리에 주의하세요.", "$distance 앞 급정거 · 정체 꼬리", RiskLevel.FORWARD_HAZARD)
            "STOPPED_VEHICLE" -> Triple("$distance 앞에 멈춘 차가 있어요. 감속하세요.", "$distance 앞 멈춘 차량", RiskLevel.FORWARD_HAZARD)
            "DEBRIS" -> Triple("$distance 앞에 낙하물이 있다는 제보가 있어요. 차로를 살피세요.", "$distance 앞 낙하물 제보", RiskLevel.FORWARD_HAZARD)
            "POTHOLE" -> Triple("$distance 앞에 포트홀 제보가 있어요. 감속하세요.", "$distance 앞 포트홀", RiskLevel.HOTSPOT)
            "ICE", "SLIP" -> Triple("$distance 앞, ${cars}미끄러진 구간이에요. 감속하세요.", "$distance 앞 미끄러짐 공유", RiskLevel.FORWARD_HAZARD)
            "NEAR_MISS" -> Triple("$distance 앞은 앞차들의 충돌 경고가 잦았던 구간이에요. 앞차와 거리를 두세요.", "$distance 앞 아차사고 잦은 구간", RiskLevel.HOTSPOT)
            else -> Triple("$distance 앞에 위험 제보가 있어요. 주의하세요.", "$distance 앞 위험 제보", RiskLevel.HOTSPOT)
        }
        val near = toEdge <= POINT_TRIGGER_M
        return RiskEvent(
            key = "hazard-${p.id}${if (near) "-near" else ""}",
            level = level,
            speech = speech,
            screenText = screen,
            cooldownMs = CROWD_COOLDOWN_MS,
        )
    }

    /**
     * Rain reported by cars ahead (their wipers), said once a few kilometres before it so the driver can
     * slow down on a dry road; nothing is said once it is already raining here.
     */
    private fun rainAhead(p: HazardPoint, s: VehicleSnapshot, lat: Double, lon: Double, toEdge: Double, condition: RoadCondition): RiskEvent? {
        if (toEdge > RAIN_TRIGGER_M || toEdge < MIN_RAIN_AHEAD_M) return null
        if (condition == RoadCondition.RAIN || condition == RoadCondition.HEAVY_RAIN) return null
        if (!isAhead(p, s, lat, lon, toEdge) || !sameDirection(p, s)) return null
        val distance = spokenDistance(toEdge)
        val heavy = p.name == "HEAVY_RAIN"
        return RiskEvent(
            key = "rain-ahead-${p.id}",
            level = RiskLevel.WEATHER,
            speech = if (heavy) {
                "$distance 앞에 폭우가 내리고 있어요. 앞차들 와이퍼가 최고 속도예요. 미리 속도를 줄이세요."
            } else {
                "$distance 앞부터 비가 와요. 노면이 젖기 전에 앞차 간격을 넓히세요."
            },
            screenText = "$distance 앞 ${if (heavy) "폭우" else "비"} · 앞차 와이퍼 감지",
            cooldownMs = RAIN_COOLDOWN_MS,
        )
    }

    private val HazardPoint.isSharedRain get() = kind == HazardKind.SHARED && (name == "RAIN" || name == "HEAVY_RAIN")

    /** Shared hazards carry the reporters' direction: a crash on the other carriageway is not ours. */
    private fun sameDirection(p: HazardPoint, s: VehicleSnapshot): Boolean {
        val theirs = p.headingDeg?.toDouble() ?: return true
        val ours = s.headingDeg?.toDouble() ?: return true
        return Geo.headingDelta(theirs, ours) <= SAME_DIRECTION_DEG
    }

    /** Only warn about points in front; when the heading is unknown or the car is crawling, distance alone decides. */
    private fun isAhead(p: HazardPoint, s: VehicleSnapshot, lat: Double, lon: Double, toEdge: Double): Boolean {
        if (toEdge <= 0) return true
        val heading = s.headingDeg?.toDouble() ?: return true
        if ((s.speedKmh ?: 0.0) < MOVING_KMH) return true
        return Geo.headingDelta(heading, Geo.bearingDeg(lat, lon, p.latitude, p.longitude)) <= AHEAD_CONE_DEG
    }

    private fun isSchoolHours(s: VehicleSnapshot, now: Long) = isSchoolHours(s, now, zone)

    private fun localDate(now: Long) = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    private fun triggerMeters(kind: HazardKind) =
        if (kind == HazardKind.CROWD_HAZARD_LIGHTS || kind == HazardKind.SHARED) HAZARD_TRIGGER_M else POINT_TRIGGER_M

    companion object {
        const val SCHOOL_ZONE_KEY = "school-zone"
        private const val POINT_TRIGGER_M = 300.0
        private const val HAZARD_TRIGGER_M = 1_000.0
        private const val OWN_STOP_M = 100.0
        private const val AHEAD_CONE_DEG = 50.0
        private const val SAME_DIRECTION_DEG = 45.0
        private const val MOVING_KMH = 5.0
        private const val HAZARD_STOP_KMH = 10.0
        private const val FREEZING_C = 1.0
        private const val CROWD_COOLDOWN_MS = 5 * 60_000L
        private const val PUBLIC_COOLDOWN_MS = 15 * 60_000L
        private const val RAIN_TRIGGER_M = 3_000.0
        private const val MIN_RAIN_AHEAD_M = 300.0
        private const val RAIN_COOLDOWN_MS = 20 * 60_000L
        /** A car driving through a long shower reports it again so the shared point follows the rain. */
        private const val RAIN_RESHARE_MS = 5 * 60_000L
        private val MORNING = 7 * 60 until 9 * 60
        private val AFTERNOON = 13 * 60 until 17 * 60

        /** Weekday commute hours for schools; a scenario's time override counts as a weekday. */
        fun isSchoolHours(s: VehicleSnapshot, now: Long, zone: ZoneId): Boolean {
            val local = Instant.ofEpochMilli(now).atZone(zone)
            val minute = s.minuteOfDayOverride ?: (local.hour * 60 + local.minute)
            val weekday = s.minuteOfDayOverride != null || local.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            return weekday && (minute in MORNING || minute in AFTERNOON)
        }

        /** "300미터", "1킬로미터": rounded so it reads naturally. */
        internal fun spokenDistance(meters: Double): String {
            val m = meters.coerceAtLeast(0.0)
            if (m >= 950) return "${(m / 1000).roundToInt().coerceAtLeast(1)}킬로미터"
            return "${((m / 50).roundToInt() * 50).coerceAtLeast(50)}미터"
        }
    }
}
