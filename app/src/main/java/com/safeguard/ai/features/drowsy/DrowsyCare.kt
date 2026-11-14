package com.safeguard.ai.features.drowsy

import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.data.publicdata.Place
import com.safeguard.ai.data.publicdata.Places
import com.safeguard.ai.risk.Confirm
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import com.safeguard.ai.risk.RiskRule
import com.safeguard.ai.risk.spokenDistance
import java.time.Instant
import java.time.ZoneId

/**
 * Feature ④ — drowsiness care: detect → wake up → rest stop → power nap.
 *
 * Main signals: the car's own drowsiness telltale (DRIVER_ATTENTION) and continuous driving time
 * (2 h, or 1.5 h between midnight and 6 am). Auxiliary: [SteeringFatigue] (skipped while lane following
 * assist steers).
 * - Stage 1: long drive or steering caution → suggest a break, naming the next rest area.
 * - Stage 2: telltale or steering warning → wake-up cabin program ([awaken]).
 * - Stage 3: still drowsy [ESCALATE_MS] after stage 2 → offer the next rest area as a waypoint.
 * - Parked at a rest area after any stage → offer a power nap ([nap]).
 */
class DrowsyCare(
    private val restAreas: () -> List<Place>,
    private val awaken: () -> Unit,
    private val addWaypoint: (Place) -> Unit,
    private val nap: () -> Unit,
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) : RiskRule {

    val steering = SteeringFatigue()

    var stage = 0
        private set
    private var stage2At = 0L
    private var breakDueSince: Long? = null
    private var escalateRequested = false
    /** The current episode started with the driver saying they are sleepy, not with a warning. */
    private var selfReported = false
    @Volatile private var driverSleepy = false
    private var parkedSince: Long? = null
    private var arrivedAtRestStop = false
    private var napOffered = false

    /** Scenario hook: skip the wait between the wake-up program and the rest-stop offer. */
    fun escalateNow() {
        escalateRequested = true
    }

    /**
     * The driver said "나 졸려" (Gleo voice command). That is as strong as the car's own drowsiness warning,
     * and the driver is asking for help, so the rest-area offer follows right after the wake-up program.
     */
    fun driverSaysSleepy() {
        driverSleepy = true
    }

    /** Navigation reported arriving at the waypoint we added. */
    fun onArrivedAtRestStop() {
        arrivedAtRestStop = true
    }

    /** A nap or a long stop ends the episode. */
    fun onRested() {
        stage = 0
        selfReported = false
        breakDueSince = null
        escalateRequested = false
        arrivedAtRestStop = false
        steering.reset()
    }

    override fun evaluate(snapshot: VehicleSnapshot, now: Long): List<RiskEvent> {
        val s = snapshot
        if (s.steeringDeg != null && !s.has("LFA")) steering.add(now, s.steeringDeg, s.speedKmh ?: 0.0)

        if (driverSleepy) {
            driverSleepy = false
            if (s.isParked) {
                stage = maxOf(stage, 1)
                napOffered = true
                return listOf(napOffer())
            }
            stage = 2
            stage2At = now
            escalateRequested = true
            selfReported = true
            return listOf(wakeUpEvent("졸리시군요."))
        }
        if (s.isParked) return whileParked(s, now)
        parkedSince = null
        if (!s.isMoving) return emptyList()
        napOffered = false
        arrivedAtRestStop = false

        val telltale = s.has("DRIVER_ATTENTION")
        val fatigue = steering.level
        val stillDrowsy = telltale || fatigue != Fatigue.NORMAL
        return when {
            stage < 2 && (telltale || fatigue == Fatigue.WARNING) -> {
                stage = 2
                stage2At = now
                listOf(wakeUpEvent(if (telltale) "졸음 경고가 켜졌어요." else "핸들 조작이 평소와 달라요."))
            }
            stage == 2 && (escalateRequested || (stillDrowsy && now - stage2At >= ESCALATE_MS)) -> {
                stage = 3
                escalateRequested = false
                listOf(restStopOffer(s))
            }
            stage < 1 && (longDrive(s, now) || fatigue == Fatigue.CAUTION) -> {
                // Not urgent: give the position a moment to arrive so the suggestion can name the rest area.
                val since = breakDueSince ?: now.also { breakDueSince = it }
                if (s.latitude == null && now - since < LOCATION_WAIT_MS) return emptyList()
                stage = 1
                listOf(breakSuggestion(s, fatigue == Fatigue.CAUTION))
            }
            else -> emptyList()
        }
    }

    private fun whileParked(s: VehicleSnapshot, now: Long): List<RiskEvent> {
        val since = parkedSince ?: now.also { parkedSince = it }
        val atRestArea = arrivedAtRestStop || (s.latitude != null && s.longitude != null &&
            Places.nearest(restAreas(), s.latitude, s.longitude, REST_AREA_RADIUS_M) != null)
        if (stage >= 1 && atRestArea && !napOffered) {
            napOffered = true
            return listOf(napOffer())
        }
        if (now - since >= LONG_STOP_MS) onRested()
        return emptyList()
    }

    private fun longDrive(s: VehicleSnapshot, now: Long): Boolean {
        val minutes = s.drivingMinutes ?: return false
        val minuteOfDay = s.minuteOfDayOverride ?: Instant.ofEpochMilli(now).atZone(zone).let { it.hour * 60 + it.minute }
        val limit = if (minuteOfDay < NIGHT_END_MINUTE) NIGHT_LIMIT_MIN else DAY_LIMIT_MIN
        return minutes >= limit
    }

    private fun nextRestArea(s: VehicleSnapshot): Pair<Place, Double>? {
        val lat = s.latitude ?: return null
        val lon = s.longitude ?: return null
        val heading = s.headingDeg?.toDouble() ?: return null
        return Places.nearestAhead(restAreas(), lat, lon, heading)
    }

    private fun breakSuggestion(s: VehicleSnapshot, fromSteering: Boolean): RiskEvent {
        val reason = if (fromSteering) "핸들 조작이 평소와 달라요." else "${drivingTime(s.drivingMinutes ?: 0)}째 운전 중이에요."
        val where = nextRestArea(s)?.let { (p, d) -> " ${spokenDistance(d)} 앞 ${p.name} 졸음쉼터에서 쉬어 가세요." }
            ?: " 다음 휴게소에서 쉬어 가세요."
        return RiskEvent(
            key = "drowsy-break",
            level = RiskLevel.DROWSY,
            speech = reason + where,
            screenText = "휴식 권장",
            cooldownMs = BREAK_COOLDOWN_MS,
        )
    }

    private fun wakeUpEvent(reason: String) = RiskEvent(
        key = "drowsy-wake",
        level = RiskLevel.DROWSY,
        speech = "$reason 시원한 바람과 밝은 조명으로 바꿀게요.",
        screenText = "졸음 주의 · 각성 모드",
        cooldownMs = WAKE_COOLDOWN_MS,
    ).also { awaken() }

    private fun restStopOffer(s: VehicleSnapshot): RiskEvent {
        // "졸음이 계속되고 있어요" only makes sense when a warning came first; a driver who asked gets the offer directly.
        val lead = if (selfReported) "쉬어 가는 게 가장 좋아요." else "졸음이 계속되고 있어요."
        val next = nextRestArea(s) ?: return RiskEvent(
            key = "drowsy-rest",
            level = RiskLevel.DROWSY,
            speech = "$lead 가까운 휴게소에서 꼭 쉬어 가세요.",
            screenText = "휴식 필요",
            cooldownMs = WAKE_COOLDOWN_MS,
        )
        val (place, meters) = next
        return RiskEvent(
            key = "drowsy-rest",
            level = RiskLevel.DROWSY,
            speech = "$lead ${spokenDistance(meters)} 앞 ${place.name} 졸음쉼터를 경유지로 넣을까요?",
            screenText = "${place.name} 졸음쉼터 · ${spokenDistance(meters)}",
            cooldownMs = WAKE_COOLDOWN_MS,
            ttlMs = 20_000L,
            confirm = Confirm(
                yesSpeech = "${place.name} 졸음쉼터를 경유지로 넣었어요.",
                noSpeech = "알겠어요. 졸리면 꼭 쉬어 가세요.",
                onYes = { addWaypoint(place) },
            ),
        )
    }

    private fun napOffer() = RiskEvent(
        key = "drowsy-nap",
        level = RiskLevel.INFO,
        speech = "15분 쉬어 가실래요? 등받이를 젖히고 조명을 어둡게 할게요.",
        screenText = "15분 휴식?",
        cooldownMs = 0L,
        ttlMs = 20_000L,
        confirm = Confirm(yesSpeech = "편히 쉬세요. 15분 뒤에 깨워 드릴게요.", onYes = { nap() }),
    )

    companion object {
        const val ESCALATE_MS = 3 * 60_000L
        private const val DAY_LIMIT_MIN = 120
        private const val NIGHT_LIMIT_MIN = 90
        private const val NIGHT_END_MINUTE = 6 * 60
        private const val REST_AREA_RADIUS_M = 300.0
        private const val LOCATION_WAIT_MS = 30_000L
        private const val LONG_STOP_MS = 10 * 60_000L
        private const val BREAK_COOLDOWN_MS = 30 * 60_000L
        private const val WAKE_COOLDOWN_MS = 10 * 60_000L

        /** 125 → "2시간 5분", 120 → "2시간", 45 → "45분". */
        internal fun drivingTime(minutes: Int): String {
            val h = minutes / 60
            val m = minutes % 60
            return listOfNotNull(h.takeIf { it > 0 }?.let { "${it}시간" }, m.takeIf { it > 0 }?.let { "${it}분" })
                .joinToString(" ").ifEmpty { "0분" }
        }
    }
}
