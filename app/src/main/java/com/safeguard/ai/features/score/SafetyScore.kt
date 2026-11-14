package com.safeguard.ai.features.score

import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import com.safeguard.ai.risk.RiskRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt

/** One trip's result, for the parked screen and the spoken summary. */
data class TripReport(
    val score: Int,
    val minutes: Int,
    val hardBrakes: Int,
    val overspeedSeconds: Int,
    val sharpTurns: Int,
    val seatBeltUnbuckled: Boolean,
    val night: Boolean,
    val warningsHeeded: Int,
    val warningsIgnored: Int,
    /** Average over this week's trips including this one; null for the first trip of the week. */
    val weekAverage: Int?,
    /** Hazards this car passed on to the cars behind this week. */
    val weekContributions: Int = 0,
    /** Stretches more than [SafetyScore.OVERSPEED_MARGIN_KMH] over the legal limit, and their camera fines. */
    val speedingEpisodes: Int = 0,
    val fineWon: Int = 0,
    val insurance: InsuranceOutlook? = null,
)

/** Keeps past scores and shared reports; the app stores them on the car only. */
interface ScoreStore {
    fun saveTrip(at: Long, score: Int)
    fun tripsSince(since: Long): List<Int>
    fun addContribution(at: Long)
    fun contributionsSince(since: Long): Int
    /** Adds a trip to its month ("2026-10"); months are kept for a year, longer than the trip list. */
    fun addToMonth(month: String, score: Int)
    fun monthAverage(month: String): Int?
}

/**
 * SafeGuard Score — a 0–100 trip score said once when the driver parks, plus a weekly average.
 *
 * Deductions (capped per item so one bad habit can't hide the rest):
 * hard braking, time above the speed limit (or the weather coach's recommended speed), sharp steering at
 * speed, driving unbelted, late-night driving, and not slowing down after a hazard or weather warning.
 * Slowing down after a warning is counted as a good response. Nothing is spoken while driving.
 *
 * A number alone doesn't change habits, so the summary also says what they cost ([ScoreCost]): the camera fines
 * the trip's speeding would have drawn, and where this month stands against the insurers' safe-driving threshold.
 */
class SafetyScore(
    private val store: ScoreStore,
    /** The weather coach's recommended speed when the road is bad, else null. */
    private val recommendedKmh: () -> Int?,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** True while another flow owns this stop (a drowsy driver resting): the score stays on screen, unspoken. */
    private val quiet: () -> Boolean = { false },
    /** The yearly car-insurance premium the driver entered in settings, for the discount in won. */
    private val annualPremiumWon: () -> Int? = { null },
) : RiskRule {

    private val _report = MutableStateFlow<TripReport?>(null)
    /** The last finished trip, shown while parked. */
    val report: StateFlow<TripReport?> = _report.asStateFlow()

    /** Trips shorter than this are not scored (a scenario shortens it for the demo). */
    @Volatile var minTripMs = MIN_TRIP_MS

    private var trip: Trip? = null
    private var lastNow: Long? = null
    private val speeds = ArrayDeque<Pair<Long, Double>>()
    private val angles = ArrayDeque<Pair<Long, Float>>()
    private var lastBrakeAt = Long.MIN_VALUE / 2
    private var lastTurnAt = Long.MIN_VALUE / 2
    private var unbeltedSince: Long? = null
    private val watched = mutableListOf<Watch>()

    override fun evaluate(snapshot: VehicleSnapshot, now: Long): List<RiskEvent> {
        val s = snapshot
        val dt = lastNow?.let { (now - it).coerceIn(0, MAX_TICK_MS) } ?: 0L
        lastNow = now
        val current = trip
        if (current == null) {
            if (s.isMoving) {
                trip = Trip(startedAt = now)
                _report.value = null
                speeds.clear()
                angles.clear()
                watched.clear()
            }
            return emptyList()
        }
        if (s.isMoving) {
            current.movingMs += dt
            track(current, s, now, dt)
        }
        checkWarnings(current, s, now)
        if (!s.isParked) return emptyList()

        trip = null
        if (current.movingMs < minTripMs) return emptyList()
        val report = finish(current, now)
        _report.value = report
        return if (quiet()) emptyList() else listOf(summary(report))
    }

    /** A warning was delivered: see whether the driver slows down within [RESPONSE_WINDOW_MS]. */
    fun onWarning(event: RiskEvent, snapshot: VehicleSnapshot, now: Long) {
        if (trip == null || event.level !in WATCHED_LEVELS) return
        val speed = snapshot.speedKmh ?: return
        if (speed < MIN_RESPONSE_KMH) return
        watched += Watch(event.key, now, speed)
    }

    /** Drops the trip in progress without scoring it (a demo scenario ending, whose car never reacts). */
    fun discardTrip() {
        trip = null
        watched.clear()
    }

    /** This car shared a hazard with the cars behind. */
    fun onShared(now: Long) = store.addContribution(now)

    private fun track(t: Trip, s: VehicleSnapshot, now: Long, dt: Long) {
        val speed = s.speedKmh ?: return
        speeds.addLast(now to speed)
        while (speeds.isNotEmpty() && now - speeds.first().first > BRAKE_WINDOW_MS) speeds.removeFirst()
        val peak = speeds.maxOf { it.second }
        if (peak >= BRAKE_FROM_KMH && peak - speed >= BRAKE_DROP_KMH && now - lastBrakeAt > EVENT_GAP_MS) {
            t.hardBrakes++
            lastBrakeAt = now
            speeds.clear()
        }

        val limit = recommendedKmh() ?: s.roadSpeedLimitKmh
        if (limit != null && speed > limit + OVERSPEED_MARGIN_KMH) t.overspeedMs += dt
        trackSpeeding(t, s, speed, now)

        s.steeringDeg?.let { angle ->
            angles.addLast(now to angle)
            while (angles.isNotEmpty() && now - angles.first().first > TURN_WINDOW_MS) angles.removeFirst()
            val swing = angles.maxOf { it.second } - angles.minOf { it.second }
            if (speed >= TURN_FROM_KMH && swing >= TURN_SWING_DEG && now - lastTurnAt > EVENT_GAP_MS) {
                t.sharpTurns++
                lastTurnAt = now
                angles.clear()
            }
        }

        if (s.seatBeltUnbuckled == true) {
            val since = unbeltedSince ?: now.also { unbeltedSince = it }
            if (now - since >= UNBELTED_MS) t.unbelted = true
        } else {
            unbeltedSince = null
        }

        if (isLateNight(s, now)) t.nightMs += dt
    }

    /** Fines go by the legal limit, not the weather advice; a stretch must last [SPEEDING_HOLD_MS] to count. */
    private fun trackSpeeding(t: Trip, s: VehicleSnapshot, speed: Double, now: Long) {
        val legal = s.roadSpeedLimitKmh
        if (legal == null || speed <= legal + OVERSPEED_MARGIN_KMH) {
            t.closeSpeeding(now)
            return
        }
        if (t.speedingSince == null) t.speedingSince = now
        t.speedingPeak = maxOf(t.speedingPeak, speed - legal)
        if (inSchoolZone(s, now)) t.speedingInSchoolZone = true
    }

    private fun inSchoolZone(s: VehicleSnapshot, now: Long): Boolean {
        val minute = s.minuteOfDayOverride ?: Instant.ofEpochMilli(now).atZone(zone).let { it.hour * 60 + it.minute }
        return minute in SCHOOL_ZONE_FROM_MINUTE until SCHOOL_ZONE_TO_MINUTE &&
            s.cautions.any { it.type == "SCHOOL_ZONE" && it.remainMeters <= SCHOOL_ZONE_RANGE_M }
    }

    private fun checkWarnings(t: Trip, s: VehicleSnapshot, now: Long) {
        val speed = s.speedKmh
        watched.removeAll { w ->
            val slowed = speed != null && (speed <= w.speedKmh * SLOWED_RATIO || s.isParked)
            when {
                slowed -> { t.heeded++; true }
                now - w.at >= RESPONSE_WINDOW_MS -> { t.ignored++; true }
                else -> false
            }
        }
    }

    private fun finish(t: Trip, now: Long): TripReport {
        // Warnings still open when the driver parks count as heeded: stopping is the safest response.
        t.heeded += watched.size
        watched.clear()
        t.closeSpeeding(now)
        val overspeedSeconds = (t.overspeedMs / 1000).toInt()
        val deductions = minOf(t.hardBrakes * BRAKE_POINTS, BRAKE_CAP) +
            minOf(overspeedSeconds / OVERSPEED_SECONDS_PER_POINT, OVERSPEED_CAP) +
            minOf(t.sharpTurns * TURN_POINTS, TURN_CAP) +
            (if (t.unbelted) BELT_POINTS else 0) +
            (if (t.nightMs >= NIGHT_MIN_MS) NIGHT_POINTS else 0) +
            minOf(t.ignored * IGNORED_POINTS, IGNORED_CAP)
        val score = (100 - deductions).coerceIn(0, 100)
        val weekStart = now - WEEK_MS
        val earlier = store.tripsSince(weekStart)
        store.saveTrip(now, score)
        val month = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone))
        store.addToMonth(month.toString(), score)
        val year = (0L until 12L).mapNotNull { store.monthAverage(month.minusMonths(it).toString()) }
        return TripReport(
            score = score,
            minutes = (t.movingMs / 60_000).toInt(),
            hardBrakes = t.hardBrakes,
            overspeedSeconds = overspeedSeconds,
            sharpTurns = t.sharpTurns,
            seatBeltUnbuckled = t.unbelted,
            night = t.nightMs >= NIGHT_MIN_MS,
            warningsHeeded = t.heeded,
            warningsIgnored = t.ignored,
            weekAverage = if (earlier.isEmpty()) null else (earlier + score).average().roundToInt(),
            weekContributions = store.contributionsSince(weekStart),
            speedingEpisodes = t.fines.size,
            fineWon = t.fines.sum(),
            insurance = ScoreCost.insurance(year, store.monthAverage(month.toString()) ?: score, annualPremiumWon()),
        )
    }

    private fun summary(r: TripReport): RiskEvent {
        val sentences = buildList {
            add("이번 운전은 ${r.score}점이에요.")
            add(fineSentence(r) ?: worstHabit(r))
            r.insurance?.let { add(insuranceSentence(it)) }
            if (r.warningsHeeded > 0 && r.warningsIgnored == 0) add("위험 안내에 잘 대응하셨어요.")
            r.weekAverage?.let { add("이번 주 평균은 ${it}점이에요.") }
            if (r.weekContributions > 0) add("이번 주 뒤차에 위험 ${r.weekContributions}건을 알려 줬어요.")
        }.filterNotNull()
        val fine = if (r.fineWon > 0) " · 과태료 환산 ${ScoreCost.spoken(r.fineWon)}" else ""
        return RiskEvent(
            key = "score-${r.hashCode()}",
            level = RiskLevel.INFO,
            speech = sentences.take(MAX_SENTENCES).joinToString(" "),
            screenText = "세이프가드 스코어 ${r.score}점$fine",
            cooldownMs = 0L,
            ttlMs = SUMMARY_TTL_MS,
        )
    }

    private fun fineSentence(r: TripReport): String? {
        if (r.fineWon <= 0) return null
        val times = if (r.speedingEpisodes == 1) "한 번" else "${r.speedingEpisodes}번"
        return "제한 속도를 ${OVERSPEED_MARGIN_KMH}킬로미터 넘게 달린 구간이 $times 있었어요. " +
            "단속 카메라였다면 과태료 ${ScoreCost.spoken(r.fineWon)}이에요."
    }

    /** Where this month stands against the insurers' 70-point, 9-month rule, in won when the premium is known. */
    private fun insuranceSentence(o: InsuranceOutlook): String {
        val standing = if (o.monthAverage >= ScoreCost.INSURANCE_MIN_SCORE) {
            "이번 달 평균 ${o.monthAverage}점으로 보험 안전운전 할인 기준 ${ScoreCost.INSURANCE_MIN_SCORE}점을 넘고 있어요."
        } else {
            "이번 달 평균 ${o.monthAverage}점이라 보험 안전운전 할인 기준 ${ScoreCost.INSURANCE_MIN_SCORE}점보다 낮아요."
        }
        val saving = o.savingWon ?: return standing
        return if (o.goodMonths >= ScoreCost.INSURANCE_MIN_MONTHS) {
            "$standing 최근 1년 중 ${o.goodMonths}달이 기준을 넘어 보험료 약 ${ScoreCost.spoken(saving)}을 아낄 수 있는 조건이에요."
        } else {
            "$standing 1년에 ${ScoreCost.INSURANCE_MIN_MONTHS}달을 넘기면 보험료 약 ${ScoreCost.spoken(saving)}을 아낄 수 있어요. 지금 ${o.goodMonths}달이에요."
        }
    }

    /** The one thing most worth improving, said plainly. */
    private fun worstHabit(r: TripReport): String? = when {
        r.seatBeltUnbuckled -> "안전벨트를 꼭 매 주세요."
        r.warningsIgnored > 0 -> "위험 안내 뒤에 속도를 줄이지 않은 적이 ${r.warningsIgnored}번 있었어요."
        r.hardBrakes >= 2 -> "급정거가 ${r.hardBrakes}번 있었어요. 앞차와 거리를 넉넉히 두세요."
        r.overspeedSeconds >= 60 -> "제한 속도를 넘은 시간이 ${r.overspeedSeconds / 60}분쯤 됐어요."
        r.sharpTurns >= 2 -> "급하게 핸들을 꺾은 적이 ${r.sharpTurns}번 있었어요."
        r.hardBrakes == 1 -> "급정거가 한 번 있었어요."
        r.overspeedSeconds >= OVERSPEED_SECONDS_PER_POINT -> "제한 속도를 넘은 시간이 ${r.overspeedSeconds}초 있었어요."
        r.night -> "늦은 밤 운전이었어요. 충분히 쉬세요."
        else -> null
    }

    private fun isLateNight(s: VehicleSnapshot, now: Long): Boolean {
        val minute = s.minuteOfDayOverride ?: Instant.ofEpochMilli(now).atZone(zone).let { it.hour * 60 + it.minute }
        return minute < NIGHT_END_MINUTE
    }

    private class Trip(val startedAt: Long) {
        var movingMs = 0L
        var hardBrakes = 0
        var overspeedMs = 0L
        var sharpTurns = 0
        var unbelted = false
        var nightMs = 0L
        var heeded = 0
        var ignored = 0
        val fines = mutableListOf<Int>()
        var speedingSince: Long? = null
        var speedingPeak = 0.0
        var speedingInSchoolZone = false

        fun closeSpeeding(now: Long) {
            val since = speedingSince ?: return
            if (now - since >= SPEEDING_HOLD_MS) fines += ScoreCost.fineWon(speedingPeak, speedingInSchoolZone)
            speedingSince = null
            speedingPeak = 0.0
            speedingInSchoolZone = false
        }
    }

    private class Watch(val key: String, val at: Long, val speedKmh: Double)

    companion object {
        /** Gentler than the sharing detector: the score should notice braking the driver could have avoided. */
        const val BRAKE_FROM_KMH = 30.0
        const val BRAKE_DROP_KMH = 20.0
        const val BRAKE_WINDOW_MS = 2_000L
        const val TURN_FROM_KMH = 40.0
        const val TURN_SWING_DEG = 90f
        const val TURN_WINDOW_MS = 1_000L
        const val OVERSPEED_MARGIN_KMH = 10
        const val OVERSPEED_SECONDS_PER_POINT = 10
        const val RESPONSE_WINDOW_MS = 15_000L
        const val SLOWED_RATIO = 0.9
        const val MIN_TRIP_MS = 2 * 60_000L
        /** A GPS jump or a moment past a sign doesn't make a fine; a stretch this long does. */
        const val SPEEDING_HOLD_MS = 3_000L
        /** School-zone fines are higher between 08:00 and 20:00. */
        private const val SCHOOL_ZONE_FROM_MINUTE = 8 * 60
        private const val SCHOOL_ZONE_TO_MINUTE = 20 * 60
        private const val SCHOOL_ZONE_RANGE_M = 300
        const val WEEK_MS = 7 * 24 * 60 * 60_000L
        private const val MAX_TICK_MS = 2_000L
        private const val EVENT_GAP_MS = 5_000L
        private const val UNBELTED_MS = 5_000L
        private const val MIN_RESPONSE_KMH = 20.0
        private const val NIGHT_END_MINUTE = 5 * 60
        private const val NIGHT_MIN_MS = 10 * 60_000L
        private const val BRAKE_POINTS = 5
        private const val BRAKE_CAP = 25
        private const val OVERSPEED_CAP = 20
        private const val TURN_POINTS = 3
        private const val TURN_CAP = 15
        private const val BELT_POINTS = 10
        private const val NIGHT_POINTS = 3
        private const val IGNORED_POINTS = 3
        private const val IGNORED_CAP = 15
        private const val MAX_SENTENCES = 3
        private const val SUMMARY_TTL_MS = 30_000L
        private val WATCHED_LEVELS = setOf(RiskLevel.FORWARD_HAZARD, RiskLevel.HOTSPOT, RiskLevel.WEATHER)
    }
}
