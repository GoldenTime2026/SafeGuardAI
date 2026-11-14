package com.safeguard.ai.features.disaster

import com.safeguard.ai.core.RoutePlace
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.core.WiperLevel
import com.safeguard.ai.features.weather.RoadCondition
import com.safeguard.ai.features.weather.WeatherStatus
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import com.safeguard.ai.risk.RiskRule
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** One 행정안전부 disaster SMS as the server relays it; [text] is the original, never edited. */
data class DisasterMessage(
    val sn: Long,
    val createdAt: Long,
    /** "경상남도 김해시", "경상남도" (the whole province), "경기도 수원시 장안구". */
    val regions: List<String>,
    /** 위급재난, 긴급재난 or 안전안내. */
    val step: String,
    val kind: String,
    val text: String,
    /** A past message replayed by a demo scenario: read whatever its age, and marked as a replay on screen. */
    val replay: Boolean = false,
)

/**
 * Plan 3-3 P2 — the disaster SMS, where the phone's alert can't reach a driver.
 *
 * A phone gets a disaster SMS only if it is in the area at the moment it is sent; a car that drives in half an
 * hour later never hears of it, and a driver shouldn't read a phone anyway. This rule covers the three cases:
 * - **entering**: a message for the area the car is in (or has just entered) is read out once;
 * - **ahead**: a message for the 시군구 of a waypoint or the destination is read before the car gets there,
 *   with the time to arrival (navigation gives apps those addresses, not the route line);
 * - **with the car's own signals**: in an area under a rain, snow, wind or fog message, the wipers, outside
 *   temperature, speed or other cars' heavy-rain reports turn the message into what to do now.
 *
 * The car never tells the server where it is: the server hands every new message in the country and the matching
 * happens here against [region], the 시군구 of the navigation's current address.
 * The data is 공공누리 제4유형 (no modification), so the text is spoken word for word, introduced with its source;
 * the app's own advice is said in separate sentences.
 */
class DisasterBriefing(
    private val region: () -> String?,
    private val ahead: () -> List<RoutePlace> = { emptyList() },
    private val weather: () -> WeatherStatus = { WeatherStatus() },
    /** Other cars ahead reported heavy rain (shared wiper reports on the hazard map). */
    private val crowdRain: () -> Boolean = { false },
    private val zone: ZoneId = ZoneId.systemDefault(),
) : RiskRule {

    private val lock = Any()
    private val pending = ArrayList<DisasterMessage>()
    /** Pending messages already read as a preview, so entering the area only reminds. */
    private val previewed = HashSet<Long>()
    private val heard = LinkedHashSet<Long>()
    /** Messages read for an area, kept while they are in force to combine with the car's signals. */
    private val inForce = ArrayList<DisasterMessage>()
    private val combined = HashSet<String>()

    fun offer(messages: List<DisasterMessage>) = synchronized(lock) {
        messages.filter { it.sn !in heard && pending.none { p -> p.sn == it.sn } }.forEach { pending += it }
    }

    /** Kinds ("호우", "대설", "한파") of the messages in force where the car is now, heard or not; for the parking briefing. */
    fun kindsHere(now: Long = System.currentTimeMillis()): Set<String> = synchronized(lock) {
        val here = region() ?: return emptySet()
        (pending + inForce).filter { !expired(it, now) && covers(it.regions, here) }.map { it.kind }.toSet()
    }

    override fun evaluate(snapshot: VehicleSnapshot, now: Long): List<RiskEvent> = synchronized(lock) {
        pending.removeAll { expired(it, now) }
        inForce.removeAll { expired(it, now) }
        val here = region()
        if (here != null) {
            pending.firstOrNull { covers(it.regions, here) }?.let { return listOf(enter(it, here)) }
            combine(snapshot, here)?.let { return listOf(it) }
        }
        for (place in ahead()) {
            val there = regionOf(place.address) ?: continue
            if (there == here) continue
            val m = pending.firstOrNull { it.sn !in previewed && covers(it.regions, there) } ?: continue
            previewed += m.sn
            Log.i(TAG, "previewing disaster SMS ${m.sn} for $there, ${place.remainSeconds} s ahead")
            return listOf(preview(m, place, there))
        }
        emptyList()
    }

    private fun enter(m: DisasterMessage, here: String): RiskEvent {
        pending.remove(m)
        heard += m.sn
        if (heard.size > MAX_HEARD) heard.remove(heard.first())
        inForce += m
        Log.i(TAG, "reading disaster SMS ${m.sn} (${m.step} ${m.kind}) for $here")
        if (m.sn in previewed) {
            return event(
                m, "disaster-enter-${m.sn}",
                "앞서 들려드린 ${m.kind.ifEmpty { "재난" }} 재난문자 지역, ${town(here)}에 들어왔어요.",
                "재난문자 지역 진입 · ${town(here)} · ${m.kind}",
            )
        }
        return event(m, "disaster-${m.sn}", "${source(m)} ${m.text}", "재난문자 · ${m.kind.ifEmpty { m.step }} · ${clock(m)} 행정안전부")
    }

    private fun preview(m: DisasterMessage, place: RoutePlace, there: String): RiskEvent {
        val minutes = (place.remainSeconds / 60.0).roundToInt()
        val whenText = if (minutes > 0) "약 ${minutes}분 뒤 지나갈" else "곧 지나갈"
        return event(
            m, "disaster-ahead-${m.sn}",
            "경로 앞 ${town(there)}, $whenText 곳에 재난문자가 있어요. ${source(m)} ${m.text}",
            "경로 앞 재난문자 · ${town(there)}${if (minutes > 0) " · 약 ${minutes}분" else ""} · ${m.kind.ifEmpty { m.step }}",
        )
    }

    /** The message in force here plus what the car itself senses: once per message and kind of advice. */
    private fun combine(s: VehicleSnapshot, here: String): RiskEvent? {
        if (!s.isMoving) return null
        val w = weather()
        for (m in inForce.filter { covers(it.regions, here) }) {
            val (group, speech) = when (m.kind) {
                in RAIN_KINDS -> {
                    val pouring = s.wiper == WiperLevel.HIGH || w.condition == RoadCondition.HEAVY_RAIN
                    if (!pouring && w.condition != RoadCondition.RAIN) continue
                    "rain" to buildString {
                        append("${town(here)}에 ${m.kind} 재난문자가 나온 상태에서 ")
                        append(if (pouring) "지금 비가 거세요." else "비가 오고 있어요.")
                        if (crowdRain()) append(" 앞쪽 차들도 폭우를 만나고 있어요.")
                        append(" 지하차도와 하천변 도로는 피하고, 물이 고인 길로는 들어가지 마세요.")
                        w.recommendedKmh?.let { append(" 속도는 ${it}킬로미터 아래로 줄이세요.") }
                    }
                }
                in SNOW_KINDS -> {
                    val temp = s.outsideTempC
                    if (w.condition != RoadCondition.ICE && (temp == null || temp > FREEZING_C)) continue
                    "snow" to "${town(here)}에 ${m.kind} 재난문자가 나온 상태에서" +
                        (temp?.let { " 바깥 기온이 ${it.roundToInt()}도예요." } ?: " 길이 얼어 있을 수 있어요.") +
                        " 다리 위와 그늘진 길은 미끄러우니 속도를 줄이고 급제동을 피하세요."
                }
                in WIND_KINDS -> {
                    if ((s.speedKmh ?: 0.0) < WINDY_SPEED_KMH) continue
                    "wind" to "${town(here)}에 강풍 재난문자가 나온 상태예요. 다리 위와 터널 출구에서 차가 옆으로 밀릴 수 있어요. " +
                        "핸들을 두 손으로 잡고 속도를 줄이세요."
                }
                in FOG_KINDS -> {
                    if (w.condition != RoadCondition.FOG) continue
                    "fog" to "${town(here)}에 안개 재난문자가 나온 상태에서 앞이 흐려요. 전조등을 켜고 앞차와 거리를 두 배로 두세요."
                }
                else -> continue
            }
            if (!combined.add("${m.sn}-$group")) continue
            Log.i(TAG, "disaster SMS ${m.sn} (${m.kind}) + car signals: $group")
            return RiskEvent(
                key = "disaster-car-${m.sn}-$group",
                level = RiskLevel.FORWARD_HAZARD,
                speech = speech,
                screenText = "재난문자 + 차량 신호 · ${town(here)} · ${m.kind}",
                cooldownMs = 0L,
                ttlMs = WAIT_MS,
            )
        }
        return null
    }

    private fun event(m: DisasterMessage, key: String, speech: String, screen: String) = RiskEvent(
        key = key,
        level = if (m.step == SAFETY_NOTICE) RiskLevel.WEATHER else RiskLevel.FORWARD_HAZARD,
        speech = speech,
        screenText = screen + if (m.replay) " · 시연 재생" else "",
        cooldownMs = 0L,
        // A long message may have to wait for a road warning to finish; it is still worth hearing then.
        ttlMs = WAIT_MS,
    )

    private fun source(m: DisasterMessage) = "행정안전부 ${m.step.ifEmpty { "재난" }} 문자예요."

    private fun clock(m: DisasterMessage) =
        DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(m.createdAt).atZone(zone))

    private fun expired(m: DisasterMessage, now: Long) = !m.replay && now - m.createdAt > validMs(m.kind)

    companion object {
        private const val TAG = "SafeGuard/Disaster"
        private const val SAFETY_NOTICE = "안전안내"
        private const val WAIT_MS = 60_000L
        private const val MAX_HEARD = 500
        private const val FREEZING_C = 1.0
        private const val WINDY_SPEED_KMH = 80.0
        private val RAIN_KINDS = setOf("호우", "홍수", "태풍")
        private val SNOW_KINDS = setOf("대설", "한파")
        private val WIND_KINDS = setOf("강풍", "태풍")
        private val FOG_KINDS = setOf("안개")

        /**
         * How long a message stays worth telling a car that arrives later. Weather warnings last hours (and the
         * server keeps three hours for a car that has just started); a fire or a missing person is news for an hour.
         */
        private val LONG_KINDS = RAIN_KINDS + SNOW_KINDS + WIND_KINDS + FOG_KINDS + setOf("폭염", "산사태", "황사", "미세먼지")
        const val SHORT_VALID_MS = 60 * 60_000L
        const val LONG_VALID_MS = 3 * 60 * 60_000L

        fun validMs(kind: String) = if (kind in LONG_KINDS) LONG_VALID_MS else SHORT_VALID_MS

        /** A message addressed to the province, the city or one of its districts reaches a car anywhere in it. */
        fun covers(regions: List<String>, here: String): Boolean {
            val car = tokens(here)
            return regions.any { r ->
                val target = tokens(r)
                val n = minOf(target.size, car.size)
                n > 0 && target.take(n) == car.take(n)
            }
        }

        /** "경상남도 김해시 진례면 …" or "경남 김해시 …" → "경남 김해시"; "경기 수원시 장안구 …" keeps the 구. */
        fun regionOf(address: String): String? {
            val words = address.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val province = words.firstOrNull() ?: return null
            val towns = words.drop(1).takeWhile { it.endsWith("시") || it.endsWith("군") || it.endsWith("구") }.take(2)
            return (listOf(province) + towns).joinToString(" ").let { tokens(it).joinToString(" ") }
        }

        /** "경남 김해시" → "김해시", for speech; a province alone stays as it is. */
        private fun town(region: String) = region.split(" ").drop(1).joinToString(" ").ifEmpty { region }

        private fun tokens(region: String): List<String> {
            val words = region.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return if (words.isEmpty()) words else listOf(provinceKey(words[0])) + words.drop(1)
        }

        /**
         * The same province written three ways — "경상남도" in the messages, "경남" in addresses, "강원도" before
         * 2023 and "강원특별자치도" after — compared by its two-letter short name.
         */
        internal fun provinceKey(name: String): String =
            if (name.length == 4 && name.endsWith("도") && name[1] in "청라상") "${name[0]}${name[2]}" else name.take(2)
    }
}
