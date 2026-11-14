package com.safeguard.ai.features.ontime

import com.safeguard.ai.core.RoutePlace
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.risk.Reply
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import com.safeguard.ai.risk.RiskRule
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * On-time coach — the drive to a flight or a train.
 *
 * The airport app knows the flight, the navigation knows the arrival time; nobody adds them up, so a driver who
 * isn't sure keeps pushing the speed. When navigation is heading for an airport or a KTX/SRT station, the coach
 * asks once for the departure and then says how much slack there is before check-in closes:
 * - plenty of time: say so, and say it again if the driver speeds anyway ("you will not be late");
 * - tight: suggest the closest parking, nothing about speed;
 * - late: say how little speeding would gain and suggest calling the airline once parked.
 * It speaks again only when the picture changes (plenty → tight → late).
 *
 * At an airport the answer ("세 시 반 제주", "대한항공 1215") goes to the server, which looks the flight up in the
 * two operators' live data (인천국제공항공사, 한국공항공사): the real departure with its delay, domestic or
 * international, a cancellation, Incheon's terminal, full car parks and the security queue. When several flights fit
 * a spoken time the coach asks once for the flight number, since a guessed flight's delay would mislead. While
 * driving it checks the flight again every few minutes and says when the time changes.
 *
 * Without that data (no server, a station, a flight not found) it works from the spoken time: check-in closes 60
 * minutes before an international flight and 30 before a domestic one (a destination named 인천 counts as
 * international), which the speech states; parking and getting to the counter take 20 minutes at an airport and
 * 10 at a station.
 */
class OnTimeCoach(
    private val destination: () -> RoutePlace?,
    /** Delivers what the coach says after the driver answers (the arbiter's inject). */
    private val say: (RiskEvent) -> Unit,
    /** The server's flight lookup; null when it can't answer. */
    private val lookup: suspend (FlightQuery) -> FlightPlan? = { null },
    /** Runs lookups off the arbiter, so a slow airport API never holds a road warning back. */
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    private val zone: ZoneId = ZoneId.systemDefault(),
) : RiskRule {

    enum class Kind(val noun: String, val vehicle: String, val leadMin: Int, val accessMin: Int) {
        INTERNATIONAL("공항", "비행기", 60, 20),
        DOMESTIC("공항", "비행기", 30, 20),
        TRAIN("역", "기차", 0, 10),
    }

    private enum class Picture { PLENTY, TIGHT, LATE }

    private val lock = Any()
    private var askedFor: String? = null
    private var kind: Kind? = null
    /** Departure as minute of day (0..1439); null until known. */
    private var departureMinute: Int? = null
    private var picture: Picture? = null
    private var overSince: Long? = null
    /** Parking to counter; the measured queue replaces the default when the airport gives one. */
    private var accessMin: Int? = null
    private var airport: String? = null
    private var flight: FlightStatus? = null
    private var askedNumber = false
    private var lastRefreshAt = 0L
    /** A changed departure found by [refresh], said at the next evaluation. */
    private var news: RiskEvent? = null
    /** "수속·보안검색 약 12분", "출국장 혼잡 +10분": on screen with the slack. */
    private var queue: String? = null
    /** A flight for tomorrow: the operators list today only, so the live checks wait for this day. */
    private var pendingDay: LocalDate? = null

    fun reset() = synchronized(lock) {
        askedFor = null
        kind = null
        departureMinute = null
        picture = null
        overSince = null
        accessMin = null
        airport = null
        flight = null
        askedNumber = false
        lastRefreshAt = 0L
        news = null
        queue = null
        pendingDay = null
    }

    override fun evaluate(snapshot: VehicleSnapshot, now: Long): List<RiskEvent> = synchronized(lock) {
        val place = destination() ?: return emptyList()
        val placeKind = kindOf(place.name) ?: return emptyList()
        if (askedFor != place.name) {
            if (!snapshot.isMoving) return emptyList()
            reset()
            askedFor = place.name
            kind = placeKind
            airport = if (placeKind == Kind.TRAIN) null else airportCodeOf(place.name)
            return listOf(ask(place, placeKind, snapshot))
        }
        val changed = news?.also { news = null }
        val departure = departureMinute ?: return listOfNotNull(changed)
        val k = kind ?: return listOfNotNull(changed)
        val slack = slackMinutes(departure, minuteOfDay(snapshot, now), place.remainSeconds, k, accessMin ?: k.accessMin)
        val current = pictureOf(slack)
        if (current != picture) {
            picture = current
            return listOfNotNull(changed, status(k, slack, place, snapshot))
        }
        changed?.let { return listOf(it) }
        calm(snapshot, slack, place, now)?.let { return listOf(it) }
        emptyList()
    }

    private fun ask(place: RoutePlace, k: Kind, s: VehicleSnapshot) = RiskEvent(
        key = "ontime-ask-${place.name}",
        level = RiskLevel.INFO,
        speech = "${k.noun}으로 가시네요. 몇 시 ${k.vehicle}예요?",
        screenText = "${place.name} · 출발 시각이나 편명을 말씀해 주세요",
        cooldownMs = 0L,
        ttlMs = WAIT_MS,
        reply = Reply(REPLY_TIMEOUT_MS) { words -> scope.launch { onAnswer(words, place, s, null) } },
    )

    /** Several flights fit the time: one more question, for the number, before trusting any of them. */
    private fun askNumber(place: RoutePlace, s: VehicleSnapshot, minute: Int, candidates: Int) = RiskEvent(
        key = "ontime-number-${place.name}",
        level = RiskLevel.INFO,
        speech = "그 시간쯤 ${candidates}편이 있어요. 편명을 말씀해 주시면 지연 여부까지 알려 드릴게요.",
        screenText = "편명을 말씀해 주세요 · 예: 대한항공 1215",
        cooldownMs = 0L,
        ttlMs = WAIT_MS,
        reply = Reply(REPLY_TIMEOUT_MS) { words -> scope.launch { onAnswer(words, place, s, minute) } },
    )

    private suspend fun onAnswer(words: String?, place: RoutePlace, s: VehicleSnapshot, earlierMinute: Int?) {
        val nowMinute = minuteOfDay(s, System.currentTimeMillis())
        val minute = words?.let { parseDeparture(it, nowMinute) } ?: earlierMinute
        val number = words?.let(::parseFlightNumber)
        val code = synchronized(lock) {
            if (askedFor != place.name) return
            airport
        }
        val plan = if (code != null && (number != null || minute != null)) {
            runCatching { lookup(FlightQuery(code, number, minute?.let(::hhmm), words.orEmpty(), nowMinute / 60)) }
                .onFailure { Log.w(TAG, "flight lookup failed: ${it.message}") }
                .getOrNull()
        } else null
        Log.i(TAG, "answer '$words' → minute $minute, flight $number → ${plan?.flight?.flightId} (${plan?.candidates} close)")
        synchronized(lock) {
            if (askedFor != place.name) return
            val found = plan?.flight
            if (found == null && minute == null) return
            if (found == null && plan != null && plan.candidates > 1 && number == null && !askedNumber && minute != null) {
                askedNumber = true
                say(askNumber(place, s, minute, plan.candidates))
                return
            }
            val latest = destination() ?: place
            var tomorrow = false
            if (found != null) {
                // The operators list today's flights only. One that has left (or should have, by over half an hour) is
                // tomorrow's when it comes round again within half a day (23:00, driving to the 06:05) or the driver
                // said 내일; otherwise it is the wrong flight. Today's delay, gate or cancellation say nothing about
                // tomorrow's, so only the timetable time is kept.
                // The list is today's, so its times are compared straight, not round the clock: at 23:00 a 09:00
                // flight is fourteen hours gone, not ten hours away.
                val scheduled = minuteOf(found.scheduled) ?: 0
                val passed = found.status == DEPARTED || scheduled + (found.delayMin ?: 0) < nowMinute - GONE_MIN
                val again = scheduled + 24 * 60 - nowMinute
                tomorrow = passed && (again <= TOMORROW_MAX_MIN || "내일" in words.orEmpty())
                lastRefreshAt = System.currentTimeMillis()
                if (passed && !tomorrow) {
                    departureMinute = null
                    flight = null
                    say(gone(found))
                    return
                }
                if (found.cancelled && !tomorrow) {
                    flight = found
                    departureMinute = null
                    say(cancelled(found))
                    return
                }
                flight = if (tomorrow) asTimetabled(found) else found
                pendingDay = if (tomorrow) Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone).toLocalDate().plusDays(1) else null
                kind = if (found.international) Kind.INTERNATIONAL else Kind.DOMESTIC
            }
            val shown = flight.takeIf { found != null }
            val departure = shown?.let(::departureOf) ?: minute ?: return
            departureMinute = departure
            val k = kind ?: return
            // Tonight's queue and crowd are not tomorrow morning's.
            val access = if (tomorrow) k.accessMin else accessFrom(plan, k)
            accessMin = access
            val slack = slackMinutes(departure, nowMinute, latest.remainSeconds, k, access)
            picture = pictureOf(slack)
            // At most three sentences before the slack: what the flight is doing, a wrong terminal, a full car park.
            // The queue and crowd went into the slack already; they are shown, not said.
            val extras = listOfNotNull(
                shown?.let { if (tomorrow) tomorrowFacts(it) else flightFacts(it) },
                shown?.let { wrongTerminal(it, latest.name) },
                plan?.let { parkingNote(it.parking, found?.international ?: (k == Kind.INTERNATIONAL)) },
            )
            queue = if (tomorrow) null else plan?.let(::queueText)
            say(status(k, slack, latest, s, extras, statedBasis = found == null))
        }
    }

    /**
     * Checks a found flight again while driving, at most every [REFRESH_MS]; a new time or a cancellation is said at
     * the next evaluation. The service calls this every minute.
     */
    suspend fun refresh(now: Long) {
        val (query, before) = synchronized(lock) {
            val f = flight ?: return
            val code = airport ?: return
            if (departureMinute == null || now - lastRefreshAt < REFRESH_MS) return
            // Tomorrow's flight: until midnight the operators still show today's, which has left.
            if (pendingDay?.let { Instant.ofEpochMilli(now).atZone(zone).toLocalDate() < it } == true) return
            lastRefreshAt = now
            FlightQuery(code, f.flightId) to f
        }
        val after = runCatching { lookup(query) }.getOrNull()?.flight ?: return
        synchronized(lock) {
            if (flight?.flightId != after.flightId) return
            if (pendingDay != null) {
                // Just after midnight the list may not have turned over yet.
                if (after.status == DEPARTED) return
                pendingDay = null
            }
            flight = after
            when {
                after.cancelled && !before.cancelled -> {
                    departureMinute = null
                    news = cancelled(after)
                }
                abs(clockDiff(departureOf(after), departureOf(before))) >= CHANGE_MIN -> {
                    departureMinute = departureOf(after)
                    // Re-announce the slack with the new time.
                    picture = null
                    news = RiskEvent(
                        key = "ontime-changed-${after.flightId}-${departureOf(after)}",
                        level = RiskLevel.INFO,
                        speech = spokenClock(departureOf(after)).let { "${after.flightId}편 출발이 $it${toward(it)} 바뀌었어요." },
                        screenText = "${after.flightId} 출발 변경 · ${clockText(departureOf(after))}",
                        cooldownMs = 0L,
                        ttlMs = WAIT_MS,
                    )
                }
            }
        }
    }

    /** "LJ6301편은 내일 오전 6시 5분 출발로 계산했어요. 지연 여부는 자정이 지나면 확인할게요." */
    private fun tomorrowFacts(f: FlightStatus) =
        "${f.flightId}편은 내일 ${spokenClock(departureOf(f))} 출발로 계산했어요. 지연 여부는 자정이 지나면 확인할게요."

    private fun gone(f: FlightStatus) = RiskEvent(
        key = "ontime-gone-${f.flightId}",
        level = RiskLevel.INFO,
        speech = "${f.flightId}편은 오늘 ${spokenClock(departureOf(f))}에 이미 출발한 편이에요. 편명을 확인해 주세요.",
        screenText = "${f.flightId} 이미 출발 · 편명 확인",
        cooldownMs = 0L,
        ttlMs = WAIT_MS,
    )

    private fun cancelled(f: FlightStatus) = RiskEvent(
        key = "ontime-cancelled-${f.flightId}",
        level = RiskLevel.INFO,
        speech = "${f.airline} ${f.flightId}편이 결항이에요. 서두르지 마시고 정차한 뒤 항공사 안내를 확인하세요.",
        screenText = "${f.flightId} 결항 · 항공사 안내 확인",
        cooldownMs = 0L,
        ttlMs = WAIT_MS,
    )

    private fun status(
        k: Kind,
        slack: Int,
        place: RoutePlace,
        s: VehicleSnapshot,
        extras: List<String> = emptyList(),
        statedBasis: Boolean = flight == null,
    ): RiskEvent {
        val deadline = if (k == Kind.TRAIN) "출발" else "수속 마감"
        val basis = if (!statedBasis) "" else when (k) {
            Kind.INTERNATIONAL -> " 국제선 출발 1시간 전 마감 기준이에요."
            Kind.DOMESTIC -> " 국내선 출발 30분 전 마감 기준이에요."
            Kind.TRAIN -> ""
        }
        val (speech, screen) = when (pictureOf(slack)) {
            Picture.PLENTY -> "지금대로 가면 $deadline ${duration(slack)} 전에 도착해요. 서두르지 않으셔도 돼요.$basis" to
                "$deadline ${duration(slack)} 전 도착 예상 · 여유"
            Picture.TIGHT -> "$deadline ${slack}분 전 도착이라 빠듯해요. ${if (k == Kind.TRAIN) "역과 가까운 주차장" else "터미널 가까운 단기주차장"}을 이용하세요.$basis" to
                "$deadline ${slack}분 전 도착 예상 · 빠듯"
            Picture.LATE -> {
                val gain = speedingGainMinutes(place, s)
                val gainText = gain?.let { " 시속 20킬로미터 더 내도 약 ${it}분 빨라질 뿐이에요." } ?: ""
                "이대로면 $deadline 시간보다 ${-slack}분 늦어요.$gainText 정차한 뒤 ${if (k == Kind.TRAIN) "열차 변경" else "항공사"}를 확인해 보세요.$basis" to
                    "$deadline ${-slack}분 늦음 예상"
            }
        }
        val flightScreen = flight?.let { f ->
            listOfNotNull(f.flightId, f.terminal?.let { "제${it.last()}터미널" }, f.counters?.let { "카운터 $it" }, f.gate?.let { "게이트 $it" })
                .joinToString(" ") + " · "
        }.orEmpty()
        return RiskEvent(
            key = "ontime-status-${pictureOf(slack)}-$slack",
            level = RiskLevel.INFO,
            speech = (extras + speech).joinToString(" "),
            screenText = flightScreen + screen + (queue?.let { " · $it" } ?: ""),
            cooldownMs = 0L,
            ttlMs = WAIT_MS,
        )
    }

    /** Speeding with time to spare, or speeding when it can't help: the safety point of the whole coach. */
    private fun calm(s: VehicleSnapshot, slack: Int, place: RoutePlace, now: Long): RiskEvent? {
        val limit = s.roadSpeedLimitKmh ?: return null
        val speed = s.speedKmh ?: return null
        if (speed <= limit + SPEEDING_MARGIN_KMH) {
            overSince = null
            return null
        }
        val since = overSince ?: now.also { overSince = it }
        if (now - since < SPEEDING_HOLD_MS) return null
        val speech = when (pictureOf(slack)) {
            Picture.PLENTY -> "${duration(slack)} 여유가 있어요. 속도를 줄여도 늦지 않아요."
            Picture.TIGHT -> "빠듯하지만 과속해도 몇 분 줄지 않아요. 제한속도를 지켜 주세요."
            Picture.LATE -> speedingGainMinutes(place, s)?.let { "과속해도 약 ${it}분 줄어들 뿐이에요. 안전하게 가세요." }
                ?: "과속해도 시간은 많이 줄지 않아요. 안전하게 가세요."
        }
        return RiskEvent(
            key = "ontime-calm",
            level = RiskLevel.WEATHER,
            speech = speech,
            screenText = "제시간 도착 · 감속해도 괜찮아요",
            cooldownMs = CALM_COOLDOWN_MS,
        )
    }

    private fun minuteOfDay(s: VehicleSnapshot, now: Long): Int =
        s.minuteOfDayOverride ?: Instant.ofEpochMilli(now).atZone(zone).let { it.hour * 60 + it.minute }

    companion object {
        private const val TAG = "SafeGuard/OnTime"
        private const val REPLY_TIMEOUT_MS = 8_000L
        private const val WAIT_MS = 60_000L
        private const val TIGHT_MIN = 15
        private const val SPEEDING_MARGIN_KMH = 10
        private const val SPEEDING_HOLD_MS = 5_000L
        private const val CALM_COOLDOWN_MS = 5 * 60_000L
        private const val SPEEDING_EXTRA_KMH = 20.0
        private const val CRUISING_KMH = 60.0
        const val REFRESH_MS = 5 * 60_000L
        /** A departure moved by less than this is not worth interrupting for. */
        private const val CHANGE_MIN = 10
        /** A delay below this is "on time". */
        private const val DELAY_SAID_MIN = 5
        /** From the car park to the check-in queue, when the queue itself is measured. */
        private const val PARK_TO_COUNTER_MIN = 10
        private const val BUSY_EXTRA_MIN = 10
        /** Flight status of one that has left (both operators). */
        private const val DEPARTED = "출발"
        /** A departure this far in the past has gone (or is tomorrow's: see [TOMORROW_MAX_MIN]). */
        private const val GONE_MIN = 30
        /** A flight that has left today and comes round again within this is taken as tomorrow's. */
        private const val TOMORROW_MAX_MIN = 12 * 60
        /** A car park with this share or fewer spaces left counts as full. */
        private const val FULL_SHARE = 0.02
        private const val FULL_MIN_SPACES = 5
        private const val WORTH_SAYING_SPACES = 30

        /** KTX·SRT stops a driver would park at; "역" alone would also match subway stations. */
        private val STATIONS = setOf(
            "서울역", "용산역", "영등포역", "광명역", "수서역", "동탄역", "평택지제역", "천안아산역", "오송역", "대전역",
            "김천구미역", "동대구역", "서대구역", "신경주역", "울산역", "부산역", "구포역", "밀양역", "창원역", "창원중앙역",
            "마산역", "진주역", "포항역", "익산역", "전주역", "정읍역", "광주송정역", "나주역", "목포역", "여수엑스포역",
            "순천역", "강릉역", "평창역", "진부역", "만종역", "원주역", "청량리역", "상봉역", "행신역", "공주역",
        )

        /** Airports by the name navigation shows → IATA code (인천: 인천국제공항공사, the rest: 한국공항공사). */
        private val AIRPORTS = linkedMapOf(
            "인천" to "ICN", "김포" to "GMP", "김해" to "PUS", "제주" to "CJU", "대구" to "TAE", "청주" to "CJJ",
            "무안" to "MWX", "광주" to "KWJ", "여수" to "RSU", "울산" to "USN", "포항" to "KPO", "사천" to "HIN",
            "군산" to "KUV", "원주" to "WJU", "양양" to "YNY",
        )

        /** Airline names a driver says → IATA code, for "대한항공 1215". */
        private val AIRLINES = linkedMapOf(
            "대한항공" to "KE", "아시아나" to "OZ", "제주항공" to "7C", "진에어" to "LJ", "티웨이" to "TW",
            "에어부산" to "BX", "에어서울" to "RS", "이스타" to "ZE", "에어로케이" to "RF", "에어프레미아" to "YP",
        )

        fun kindOf(name: String): Kind? = when {
            "공항" in name -> if ("인천" in name) Kind.INTERNATIONAL else Kind.DOMESTIC
            STATIONS.any { it in name } || "KTX" in name || "SRT" in name -> Kind.TRAIN
            else -> null
        }

        fun airportCodeOf(name: String): String? = AIRPORTS.entries.firstOrNull { it.key in name }?.value

        /** "KE1215", "ke 1215", "대한항공 1215편", "제주항공 칠씨 112" is not understood (digits only). */
        fun parseFlightNumber(text: String): String? {
            val compact = text.replace(" ", "").uppercase()
            AIRLINES.entries.firstOrNull { it.key in compact }?.let { (name, code) ->
                Regex("${Regex.escape(name.uppercase())}(\\d{1,4})").find(compact)?.let { return code + it.groupValues[1] }
            }
            return Regex("(?<![A-Z0-9])([A-Z][A-Z0-9]|[0-9][A-Z])(\\d{1,4})(?![0-9])").find(compact)?.let { it.groupValues[1] + it.groupValues[2] }
        }

        /** Minutes between arriving at the counter (or platform) and the deadline; negative when late. */
        fun slackMinutes(departureMinute: Int, nowMinute: Int, remainSeconds: Int, k: Kind, accessMin: Int = k.accessMin): Int {
            val untilDeparture = Math.floorMod(departureMinute - nowMinute, 24 * 60)
            return untilDeparture - k.leadMin - accessMin - (remainSeconds / 60.0).roundToInt()
        }

        private fun pictureOf(slack: Int) = when {
            slack >= TIGHT_MIN -> Picture.PLENTY
            slack >= 0 -> Picture.TIGHT
            else -> Picture.LATE
        }

        /**
         * How many minutes 20 km/h more than the car is doing now would save over the rest of the route. Only at
         * cruising speed: in a jam the average is low and the arithmetic would promise a big gain no driver can get,
         * so it returns null there (and without the distance).
         */
        fun speedingGainMinutes(place: RoutePlace, s: VehicleSnapshot): Int? {
            val meters = place.remainMeters?.takeIf { it > 0 } ?: return null
            val kmh = s.speedKmh?.takeIf { it >= CRUISING_KMH } ?: return null
            val now = meters / (kmh / 3.6)
            val faster = meters / ((kmh + SPEEDING_EXTRA_KMH) / 3.6)
            return ((now - faster) / 60).roundToInt().coerceAtLeast(1)
        }

        private fun minuteOf(hhmm: String?): Int? =
            hhmm?.takeIf { it.length >= 4 }?.let { (it.substring(0, 2).toIntOrNull() ?: return null) * 60 + (it.substring(2, 4).toIntOrNull() ?: return null) }

        /** The time the flight now leaves: the changed time when there is one. */
        fun departureOf(f: FlightStatus): Int = (if (f.delayMin != null) minuteOf(f.estimated) else null) ?: minuteOf(f.scheduled) ?: 0

        private fun clockDiff(a: Int, b: Int) = Math.floorMod(a - b + 12 * 60, 24 * 60) - 12 * 60

        /** Today's record as tomorrow's timetable: the scheduled time, nothing that happened to today's flight. */
        private fun asTimetabled(f: FlightStatus) =
            f.copy(estimated = null, delayMin = null, status = "", cancelled = false, gate = null, counters = null)

        /** 56 → "56분", 315 → "5시간 15분": long waits are easier to take in as hours. */
        internal fun duration(minutes: Int): String = when {
            minutes < 120 -> "${minutes}분"
            minutes % 60 == 0 -> "${minutes / 60}시간"
            else -> "${minutes / 60}시간 ${minutes % 60}분"
        }

        internal fun hhmm(minute: Int) = "%02d%02d".format(minute / 60, minute % 60)

        internal fun clockText(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

        /** 15:46 → "오후 3시 46분", 09:00 → "오전 9시". */
        internal fun spokenClock(minute: Int): String {
            val h = minute / 60
            val m = minute % 60
            val half = if (h < 12) "오전" else "오후"
            val h12 = if (h % 12 == 0) 12 else h % 12
            return "$half ${h12}시" + if (m > 0) " ${m}분" else ""
        }

        private fun accessFrom(plan: FlightPlan?, k: Kind): Int {
            val base = plan?.waitMin?.let { PARK_TO_COUNTER_MIN + it } ?: k.accessMin
            return base + if (plan?.busy == true) BUSY_EXTRA_MIN else 0
        }

        /** "KE705편은 21분 늦어진 오후 7시 16분 출발, 제2여객터미널이에요." (the terminal only at Incheon). */
        internal fun flightFacts(f: FlightStatus): String {
            val delay = f.delayMin ?: 0
            val at = spokenClock(departureOf(f))
            val time = when {
                delay >= DELAY_SAID_MIN -> "${delay}분 늦어진 $at 출발"
                delay <= -DELAY_SAID_MIN -> "${-delay}분 앞당겨진 $at 출발"
                else -> "$at 출발 예정"
            }
            val terminal = f.terminal?.let { ", 제${it.last()}여객터미널" }.orEmpty()
            return "${f.flightId}편은 $time${terminal}이에요."
        }

        /** Incheon: navigation heading for the other terminal (they are kilometres apart). */
        internal fun wrongTerminal(f: FlightStatus, destinationName: String): String? {
            val n = f.terminal?.last() ?: return null
            val navT1 = "제1" in destinationName || "T1" in destinationName
            val navT2 = "제2" in destinationName || "T2" in destinationName
            return if ((n == '1' && navT2) || (n == '2' && navT1)) "목적지가 다른 터미널이에요. 제${n}여객터미널로 바꾸세요." else null
        }

        /** A full car park for this flight's side and the one with room; nothing when parking is easy. */
        internal fun parkingNote(lots: List<ParkingLot>, international: Boolean): String? {
            if (lots.isEmpty()) return null
            val side = if (international) "국제선" else "국내선"
            val relevant = lots.filter { side in it.name }.ifEmpty { lots.filterNot { "화물" in it.name } }
            val full = relevant.filter { it.free <= maxOf(FULL_MIN_SPACES, (it.total * FULL_SHARE).roundToInt()) }
            if (full.isEmpty()) return null
            val roomy = relevant.filter { it !in full }.maxByOrNull { it.free }
            return if (roomy != null && roomy.free >= WORTH_SAYING_SPACES) {
                "${full.first().name}${topic(full.first().name)} 만차예요. ${roomy.name}에 ${roomy.free}면 남았어요."
            } else {
                "$side 주차장이 거의 다 찼어요. 주차에 시간을 더 잡으세요."
            }
        }

        internal fun queueText(plan: FlightPlan): String? = listOfNotNull(
            plan.waitMin?.let { "수속·보안검색 약 ${it}분" },
            if (plan.busy == true) "출국장 혼잡 +${BUSY_EXTRA_MIN}분" else null,
        ).joinToString(" · ").ifEmpty { null }

        /** 으로 after a final consonant other than ㄹ, 로 after a vowel or ㄹ ("3시로", "50분으로"). */
        internal fun toward(word: String): String {
            val last = word.lastOrNull() ?: return "로"
            if (last !in '가'..'힣') return "로"
            val coda = (last - '가') % 28
            return if (coda == 0 || coda == 8) "로" else "으로"
        }

        /** 은 after a final consonant, 는 after a vowel; digits and Latin letters count as consonant-final. */
        private fun topic(word: String): String {
            val last = word.lastOrNull() ?: return "은"
            return if (last in '가'..'힣' && (last - '가') % 28 == 0) "는" else "은"
        }

        private val NATIVE_HOURS = listOf(
            "열두" to 12, "열한" to 11, "열" to 10, "아홉" to 9, "여덟" to 8, "일곱" to 7, "여섯" to 6, "다섯" to 5,
            "네" to 4, "세" to 3, "두" to 2, "한" to 1,
        )
        private val SINO = mapOf('일' to 1, '이' to 2, '삼' to 3, '사' to 4, '오' to 5, '육' to 6, '칠' to 7, '팔' to 8, '구' to 9)

        /**
         * "세 시 반", "3시 30분", "오후 3시", "15시 20분", "15:20", "열한 시 사십 분" → minute of day.
         * Without 오전/오후 an hour up to 12 is taken as the next such time after [nowMinute].
         */
        fun parseDeparture(text: String, nowMinute: Int): Int? {
            val t = text.replace(" ", "")
            Regex("(\\d{1,2}):(\\d{2})").find(t)?.let { m ->
                return clock(m.groupValues[1].toInt(), m.groupValues[2].toInt(), null, nowMinute)
            }
            val hourPart = Regex("(\\d{1,2}|[가-힣]{1,2})시").find(t) ?: return null
            val hourWord = hourPart.groupValues[1]
            val hour = hourWord.toIntOrNull() ?: NATIVE_HOURS.firstOrNull { hourWord.endsWith(it.first) }?.second ?: return null
            val after = t.substring(hourPart.range.last + 1)
            val minute = when {
                after.startsWith("반") -> 30
                else -> Regex("^(\\d{1,2}|[일이삼사오육칠팔구십]{1,4})분").find(after)?.groupValues?.get(1)?.let(::minuteValue) ?: 0
            }
            val pm = when {
                listOf("오후", "저녁", "밤").any { it in t } -> true
                listOf("오전", "아침", "새벽").any { it in t } -> false
                else -> null
            }
            return clock(hour, minute, pm, nowMinute)
        }

        private fun minuteValue(word: String): Int? {
            word.toIntOrNull()?.let { return it.takeIf { m -> m in 0..59 } }
            // 사십, 십오, 사십오, 십
            val tens = word.indexOf('십')
            val value = if (tens < 0) SINO[word.singleOrNull() ?: return null] ?: return null else {
                val high = if (tens == 0) 1 else SINO[word[tens - 1]] ?: return null
                val low = word.getOrNull(tens + 1)?.let { SINO[it] ?: return null } ?: 0
                high * 10 + low
            }
            return value.takeIf { it in 0..59 }
        }

        private fun clock(hour: Int, minute: Int, pm: Boolean?, nowMinute: Int): Int? {
            if (hour !in 0..24 || minute !in 0..59) return null
            val h = hour % 24
            if (pm != null || h > 12) {
                val h24 = if (pm == true && h < 12) h + 12 else if (pm == false && h == 12) 0 else h
                return h24 * 60 + minute
            }
            // "세 시" while it is 14:00 means 15:00, not 03:00: the nearer of the two to come.
            val am = h % 12 * 60 + minute
            val pmMinute = am + 12 * 60
            return listOf(am, pmMinute).minBy { Math.floorMod(it - nowMinute, 24 * 60) }
        }
    }
}
