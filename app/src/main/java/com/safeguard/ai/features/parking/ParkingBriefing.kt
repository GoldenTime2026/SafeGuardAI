package com.safeguard.ai.features.parking

import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.core.WiperLevel
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import com.safeguard.ai.risk.RiskRule
import kotlin.math.roundToInt

/**
 * Parking safety briefing — the moment the car is parked, what tonight's weather means for where it stands.
 *
 * Six in ten flooded cars were parked when the water came (삼성화재 교통안전문화연구소). The insurers' evacuation
 * alert only goes out after a patrol types in the plate number. The car knows, at the moment it is parked, that a
 * rain or snow warning is in force here and whether a window is open, so it says so before the driver walks away:
 * - a rain, flood or typhoon disaster SMS here (or pouring rain now): park away from basements and riverbanks;
 * - a heavy-snow SMS: lift the wipers, park indoors;
 * - a cold-wave SMS or a very cold night with an EV battery running low: charge before tomorrow;
 * - a window or the sunroof open while it rains or a rain or snow warning is out: close it.
 * One sentence group, once per parking, and only when one of these applies.
 */
class ParkingBriefing(
    /** Kinds of the disaster SMS in force here (DisasterBriefing.kindsHere). */
    private val alerts: () -> Set<String>,
) : RiskRule {

    private var drivingSince: Long? = null
    private var drove = false
    private var briefed = false

    fun reset() {
        drivingSince = null
        drove = false
        briefed = false
    }

    override fun evaluate(snapshot: VehicleSnapshot, now: Long): List<RiskEvent> {
        if (snapshot.isMoving) {
            val since = drivingSince ?: now.also { drivingSince = it }
            if (now - since >= MIN_DRIVE_MS) drove = true
            briefed = false
            return emptyList()
        }
        drivingSince = null
        if (!snapshot.isParked || !drove || briefed) return emptyList()
        briefed = true
        drove = false
        val sentences = sentences(snapshot, alerts())
        if (sentences.isEmpty()) return emptyList()
        return listOf(
            RiskEvent(
                key = "parking-briefing-$now",
                level = RiskLevel.WEATHER,
                speech = sentences.joinToString(" "),
                screenText = "주차 안내 · " + screen(snapshot, alerts()),
                cooldownMs = 0L,
                // Said while parked; it may wait behind the trip score.
                ttlMs = WAIT_MS,
            )
        )
    }

    companion object {
        private const val MIN_DRIVE_MS = 60_000L
        private const val WAIT_MS = 60_000L
        private const val VERY_COLD_C = -10.0
        private const val LOW_SOC_PERCENT = 30.0
        private val RAIN_KINDS = setOf("호우", "홍수", "태풍")
        private val SNOW_KINDS = setOf("대설")
        private val COLD_KINDS = setOf("한파")

        internal fun sentences(s: VehicleSnapshot, kinds: Set<String>): List<String> = buildList {
            val rainAlert = kinds.firstOrNull { it in RAIN_KINDS }
            val pouring = s.wiper == WiperLevel.HIGH
            when {
                rainAlert != null -> add(
                    "이 지역에 $rainAlert 재난문자가 나와 있어요. 지하주차장이나 하천 옆이라면 물에 잠기지 않게 높은 곳으로 옮겨 세우세요."
                )
                pouring -> add("비가 아주 많이 와요. 지하주차장이나 하천 옆은 피해서 세우세요.")
            }
            if (kinds.any { it in SNOW_KINDS }) add("대설 재난문자가 나와 있어요. 와이퍼를 세워 두고, 되도록 실내 주차장에 세우세요.")
            val cold = kinds.any { it in COLD_KINDS } || (s.outsideTempC ?: 0.0) <= VERY_COLD_C
            val soc = s.evSocPercent
            if (cold && soc != null && soc <= LOW_SOC_PERCENT) {
                add("밤사이 아주 추워요. 배터리가 ${soc.roundToInt()}%라 추위에 더 빨리 줄어요. 내일 출발 전에 충전해 두세요.")
            }
            val wetNight = rainAlert != null || pouring || s.wiper == WiperLevel.LOW || s.wiper == WiperLevel.INTERMITTENT ||
                kinds.any { it in SNOW_KINDS }
            val open = s.openWindows.orEmpty()
            if (wetNight && open.isNotEmpty()) openLabel(open).let { add("$it${subjectParticle(it)} 열려 있어요. 닫고 내리세요.") }
        }

        private fun screen(s: VehicleSnapshot, kinds: Set<String>) = buildList {
            kinds.filter { it in RAIN_KINDS + SNOW_KINDS + COLD_KINDS }.forEach { add("$it 재난문자") }
            if (s.wiper == WiperLevel.HIGH) add("폭우")
            s.evSocPercent?.let { add("배터리 ${it.roundToInt()}%") }
            if (!s.openWindows.isNullOrEmpty()) add("${openLabel(s.openWindows)} 열림")
        }.joinToString(" · ")

        /** "선루프", "조수석 창문", "창문" (several). */
        internal fun openLabel(open: Set<String>): String {
            val roof = open.any { it.startsWith("ROOF") }
            val windows = open.filterNot { it.startsWith("ROOF") }
            val window = when {
                windows.isEmpty() -> null
                windows.size == 1 -> (SEAT_NAMES[windows.single()] ?: "") + "창문"
                else -> "창문"
            }
            return listOfNotNull(if (roof) "선루프" else null, window?.trim()).joinToString("과 ").let {
                // "선루프과" reads wrong; with both it is "선루프와 창문".
                it.replace("선루프과", "선루프와")
            }
        }

        /** 이 after a final consonant ("창문이"), 가 after a vowel ("선루프가"). */
        internal fun subjectParticle(word: String): String {
            val last = word.lastOrNull() ?: return "이"
            return if (last in '가'..'힣' && (last - '가') % 28 == 0) "가" else "이"
        }

        private val SEAT_NAMES = mapOf(
            "ROW_1_LEFT" to "운전석 ", "ROW_1_RIGHT" to "조수석 ", "ROW_2_LEFT" to "뒷좌석 ", "ROW_2_RIGHT" to "뒷좌석 ",
        )
    }
}
