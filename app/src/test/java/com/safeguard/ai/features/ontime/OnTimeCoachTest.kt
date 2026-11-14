package com.safeguard.ai.features.ontime

import com.safeguard.ai.core.Gear
import com.safeguard.ai.core.RoutePlace
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.features.ontime.OnTimeCoach.Kind
import com.safeguard.ai.risk.RiskEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnTimeCoachTest {

    private var place: RoutePlace? = RoutePlace("인천국제공항 제1여객터미널", "인천광역시 중구 공항로 272", 50 * 60, 55_000)
    private val said = mutableListOf<RiskEvent>()
    private val coach = OnTimeCoach(destination = { place }, say = { said += it })
    /** 12:30, limit 100. */
    private val driving = VehicleSnapshot(gear = Gear.DRIVE, speedKmh = 95.0, roadSpeedLimitKmh = 100, minuteOfDayOverride = 12 * 60 + 30)

    private fun answer(event: RiskEvent, words: String?) = runBlocking { event.reply!!.onReply(words) }

    @Test
    fun `destinations that are worth a question`() {
        assertEquals(Kind.INTERNATIONAL, OnTimeCoach.kindOf("인천국제공항 제2여객터미널"))
        assertEquals(Kind.DOMESTIC, OnTimeCoach.kindOf("김포국제공항 국내선"))
        assertEquals(Kind.TRAIN, OnTimeCoach.kindOf("광명역 KTX"))
        assertEquals(Kind.TRAIN, OnTimeCoach.kindOf("동대구역"))
        assertNull(OnTimeCoach.kindOf("강남역 2호선"))
        assertNull(OnTimeCoach.kindOf("코엑스"))
    }

    @Test
    fun `spoken departure times`() {
        val noon = 12 * 60 + 30
        assertEquals(15 * 60 + 30, OnTimeCoach.parseDeparture("세 시 반", noon))
        assertEquals(15 * 60 + 30, OnTimeCoach.parseDeparture("3시 30분 비행기야", noon))
        assertEquals(15 * 60 + 20, OnTimeCoach.parseDeparture("15시 20분", noon))
        assertEquals(15 * 60, OnTimeCoach.parseDeparture("오후 3시", noon))
        assertEquals(9 * 60, OnTimeCoach.parseDeparture("오전 아홉 시", noon))
        assertEquals(23 * 60 + 40, OnTimeCoach.parseDeparture("열한 시 사십 분", noon))
        assertEquals(18 * 60 + 5, OnTimeCoach.parseDeparture("18:05", noon))
        // At 8 in the morning "열 시" is 10:00, not 22:00.
        assertEquals(10 * 60, OnTimeCoach.parseDeparture("열 시", 8 * 60))
        assertNull(OnTimeCoach.parseDeparture("몰라", noon))
    }

    @Test
    fun `slack counts check-in close and the walk to the counter`() {
        // 15:30 international, now 12:30, 50 min drive: 180 - 60 - 20 - 50 = 50.
        assertEquals(50, OnTimeCoach.slackMinutes(15 * 60 + 30, 12 * 60 + 30, 50 * 60, Kind.INTERNATIONAL))
        // Past midnight: 00:30 departure at 23:00.
        assertEquals(90 - 30 - 20 - 10, OnTimeCoach.slackMinutes(30, 23 * 60, 10 * 60, Kind.DOMESTIC))
    }

    @Test
    fun `asks once on the way, then says there is time and not to hurry`() {
        val ask = coach.evaluate(driving, 0).single()
        assertTrue(ask.speech.contains("공항으로 가시네요. 몇 시 비행기예요?"))
        assertTrue(coach.evaluate(driving, 1_000).isEmpty())
        answer(ask, "세 시 반")
        val status = said.single()
        assertTrue(status.speech.contains("수속 마감 50분 전에 도착해요. 서두르지 않으셔도 돼요."))
        assertTrue(status.speech.contains("국제선"))
    }

    @Test
    fun `speeding with time to spare is told it need not`() {
        answer(coach.evaluate(driving, 0).single(), "세 시 반")
        val fast = driving.copy(speedKmh = 125.0)
        assertTrue(coach.evaluate(fast, 1_000).isEmpty())
        val calm = coach.evaluate(fast, 7_000).single()
        assertTrue(calm.speech.contains("50분 여유가 있어요. 속도를 줄여도 늦지 않아요."))
    }

    @Test
    fun `late says how little speeding would gain`() {
        answer(coach.evaluate(driving, 0).single(), "한 시 오십 분")
        val late = said.single()
        // 13:50 at 12:30: 80 - 60 - 20 - 50 = -50.
        assertTrue(late.speech.contains("50분 늦어요"))
        assertTrue(late.speech.contains("시속 20킬로미터 더 내도 약"))
        assertTrue(late.speech.contains("항공사"))
    }

    @Test
    fun `speeding gain is counted at cruising speed and not promised in a jam`() {
        val route = RoutePlace("인천국제공항", "", 115 * 60, 48_000)
        // 48 km at 90 km/h is 32 min, at 110 km/h 26 min.
        assertEquals(6, OnTimeCoach.speedingGainMinutes(route, driving.copy(speedKmh = 90.0)))
        assertNull(OnTimeCoach.speedingGainMinutes(route, driving.copy(speedKmh = 20.0)))
    }

    @Test
    fun `a new picture is said when the drive gets longer`() {
        answer(coach.evaluate(driving, 0).single(), "세 시 반")
        place = place!!.copy(remainSeconds = 90 * 60)
        val tight = coach.evaluate(driving, 60_000).single()
        assertTrue(tight.speech.contains("10분 전 도착이라 빠듯해요"))
    }

    @Test
    fun `no answer leaves the driver alone`() {
        answer(coach.evaluate(driving, 0).single(), null)
        assertTrue(said.isEmpty())
        assertTrue(coach.evaluate(driving.copy(speedKmh = 130.0), 10_000).isEmpty())
    }

    @Test
    fun `ordinary destinations are ignored`() {
        place = RoutePlace("코엑스", "서울 강남구 영동대로 513", 1_800)
        assertTrue(coach.evaluate(driving, 0).isEmpty())
    }

    // ------------------------------------------------------------------ with the airport data

    private val gimpo = RoutePlace("김포국제공항 국내선", "서울 강서구 하늘길 112", 50 * 60, 40_000)

    private fun flight(
        id: String = "KE1359", scheduled: String = "1520", estimated: String? = "1546", delay: Int? = 26,
        cancelled: Boolean = false, international: Boolean = false, terminal: String? = null,
    ) = FlightStatus(id, "대한항공", "제주", scheduled, estimated, delay, if (cancelled) "결항" else "", cancelled, international, "7", terminal, null)

    private val parking = listOf(ParkingLot("국내선 제1주차장", 2279, 0), ParkingLot("국내선 제2주차장", 1733, 364), ParkingLot("국제선 주차빌딩", 567, 70))

    private fun coachWith(answer: (FlightQuery) -> FlightPlan?): Pair<OnTimeCoach, MutableList<FlightQuery>> {
        val queries = mutableListOf<FlightQuery>()
        val c = OnTimeCoach(destination = { place }, say = { said += it }, lookup = { q -> queries += q; answer(q) })
        return c to queries
    }

    @Test
    fun `a found flight brings its delay, full car parks and the queue`() {
        place = gimpo
        val (c, queries) = coachWith { FlightPlan(flight(), candidates = 1, parking = parking, waitMin = 12) }
        answer(c.evaluate(driving, 0).single(), "세 시 반 제주")
        assertEquals(FlightQuery("GMP", null, "1530", "세 시 반 제주", 12), queries.single())
        val speech = said.single().speech
        assertTrue(speech, speech.startsWith("KE1359편은 26분 늦어진 오후 3시 46분 출발이에요."))
        assertTrue(speech, speech.contains("국내선 제1주차장은 만차예요. 국내선 제2주차장에 364면 남았어요."))
        assertTrue(said.single().screenText, said.single().screenText.contains("수속·보안검색 약 12분"))
        // 15:46 at 12:30 = 196 - 30 domestic - (10 + 12) queue - 50 drive = 94.
        assertTrue(speech, speech.contains("수속 마감 94분 전에 도착해요"))
        assertTrue("the rule of thumb is not stated once the flight is known", !speech.contains("기준이에요"))
    }

    @Test
    fun `several flights at that time ask for the number`() {
        place = gimpo
        val (c, queries) = coachWith { q -> if (q.flight == null) FlightPlan(null, candidates = 16) else FlightPlan(flight(id = q.flight!!, delay = null, estimated = null, scheduled = "1530")) }
        answer(c.evaluate(driving, 0).single(), "세 시 반 제주")
        val question = said.single()
        assertTrue(question.speech, question.speech.contains("16편이 있어요. 편명을 말씀해 주시면"))
        answer(question, "대한항공 1215")
        assertEquals("KE1215", queries.last().flight)
        assertTrue(said.last().speech, said.last().speech.startsWith("KE1215편은 오후 3시 30분 출발 예정이에요."))
    }

    @Test
    fun `a cancelled flight stops the coaching`() {
        place = gimpo
        val (c, _) = coachWith { FlightPlan(flight(cancelled = true)) }
        answer(c.evaluate(driving, 0).single(), "KE1359")
        assertTrue(said.single().speech.contains("KE1359편이 결항이에요"))
        assertTrue(c.evaluate(driving.copy(speedKmh = 130.0), 10_000).isEmpty())
    }

    @Test
    fun `incheon names the terminal and catches the wrong one`() {
        val (c, _) = coachWith { FlightPlan(flight(id = "KE705", scheduled = "1855", estimated = null, delay = null, international = true, terminal = "T2")) }
        answer(c.evaluate(driving, 0).single(), "KE705")
        val speech = said.single().speech
        assertTrue(speech, speech.startsWith("KE705편은 오후 6시 55분 출발 예정, 제2여객터미널이에요. 목적지가 다른 터미널이에요. 제2여객터미널로 바꾸세요."))
    }

    @Test
    fun `a delay found on the way is said and the slack recomputed`() = runBlocking {
        place = gimpo
        var delay = 0
        val (c, _) = coachWith { FlightPlan(flight(estimated = "%04d".format(1520 + delay), delay = delay)) }
        answer(c.evaluate(driving, 0).single(), "KE1359")
        said.clear()
        delay = 30
        c.refresh(System.currentTimeMillis() + OnTimeCoach.REFRESH_MS + 1)
        val events = c.evaluate(driving, 60_000)
        assertTrue(events.first().speech, events.first().speech.contains("KE1359편 출발이 오후 3시 50분으로 바뀌었어요."))
        assertEquals(2, events.size)
    }

    @Test
    fun `a flight that already left today is not taken for tomorrow`() {
        place = gimpo
        val (c, _) = coachWith { FlightPlan(flight(scheduled = "0605", estimated = "0624", delay = 19).copy(status = "출발")) }
        answer(c.evaluate(driving, 0).single(), "진에어 6301")
        assertTrue(said.single().speech, said.single().speech.contains("오늘 오전 6시 24분에 이미 출발한 편이에요"))
        assertTrue(c.evaluate(driving, 10_000).isEmpty())
    }

    @Test
    fun `late at night a flight that left this morning is tomorrow's, on its timetable time`() {
        place = gimpo
        val night = driving.copy(minuteOfDayOverride = 23 * 60)
        val today = flight(id = "LJ6301", scheduled = "0605", estimated = "0624", delay = 19).copy(status = "출발")
        var answerWith = today
        val (c, _) = coachWith { FlightPlan(answerWith, parking = parking) }
        answer(c.evaluate(night, 0).single(), "진에어 6301")
        val speech = said.single().speech
        assertTrue(speech, speech.startsWith("LJ6301편은 내일 오전 6시 5분 출발로 계산했어요."))
        assertTrue(speech, speech.contains("국내선 제1주차장은 만차예요."))
        // 23:00 → 06:05 is 425 minutes; less 30 (domestic close), 20 (access) and 50 (driving).
        assertTrue(speech, speech.contains("수속 마감 5시간 25분 전에 도착해요"))
        assertTrue(said.single().screenText, said.single().screenText.startsWith("LJ6301 · "))

        // Before midnight the operators still show today's flight: no "changed" or "cancelled" from it.
        said.clear()
        answerWith = today.copy(status = "결항", cancelled = true)
        val now = System.currentTimeMillis()
        runBlocking { c.refresh(now + OnTimeCoach.REFRESH_MS) }
        assertTrue(c.evaluate(night, 10_000).isEmpty())

        // After midnight tomorrow's own record counts.
        answerWith = flight(id = "LJ6301", scheduled = "0605", estimated = "0640", delay = 35)
        runBlocking { c.refresh(now + 24 * 3_600_000L) }
        val changed = c.evaluate(night.copy(minuteOfDayOverride = 30), 20_000)
        assertTrue(changed.joinToString { it.speech }, changed.first().speech.startsWith("LJ6301편 출발이 오전 6시 40분으로 바뀌었어요."))
    }

    @Test
    fun `this morning's cancellation is not tomorrow's`() {
        place = gimpo
        val (c, _) = coachWith { FlightPlan(flight(id = "KE1201", scheduled = "0900", estimated = null, delay = null, cancelled = true)) }
        answer(c.evaluate(driving.copy(minuteOfDayOverride = 23 * 60), 0).single(), "대한항공 1201")
        val speech = said.single().speech
        assertTrue(speech, speech.startsWith("KE1201편은 내일 오전 9시 출발로 계산했어요."))
        assertTrue(speech, !speech.contains("결항"))
    }

    @Test
    fun `durations over two hours are said in hours`() {
        assertEquals("56분", OnTimeCoach.duration(56))
        assertEquals("2시간", OnTimeCoach.duration(120))
        assertEquals("5시간 25분", OnTimeCoach.duration(325))
    }

    @Test
    fun `without the server it falls back to the spoken time`() {
        place = gimpo
        val (c, _) = coachWith { null }
        answer(c.evaluate(driving, 0).single(), "세 시 반")
        assertTrue(said.single().speech.contains("국내선 출발 30분 전 마감 기준이에요."))
    }

    @Test
    fun `flight numbers, airports and clock words`() {
        assertEquals("KE1215", OnTimeCoach.parseFlightNumber("대한항공 1215편이야"))
        assertEquals("7C112", OnTimeCoach.parseFlightNumber("7c 112"))
        assertEquals("OZ8957", OnTimeCoach.parseFlightNumber("아시아나 8957"))
        assertNull(OnTimeCoach.parseFlightNumber("세 시 반 제주"))
        assertNull(OnTimeCoach.parseFlightNumber("15시 30분"))
        assertEquals("GMP", OnTimeCoach.airportCodeOf("김포국제공항 국내선"))
        assertEquals("ICN", OnTimeCoach.airportCodeOf("인천국제공항 제2여객터미널"))
        assertEquals("오후 3시 46분", OnTimeCoach.spokenClock(15 * 60 + 46))
        assertEquals("오전 12시 5분", OnTimeCoach.spokenClock(5))
        assertEquals("으로", OnTimeCoach.toward("50분"))
        assertEquals("로", OnTimeCoach.toward("3시"))
    }

    @Test
    fun `the server answer is parsed`() {
        val plan = FlightPlan.parse(
            """{"configured": true, "airport": "GMP", "candidates": 1, "waitMin": null, "busy": null,
               "flight": {"flightId": "LJ6301", "airline": "진에어", "dest": "제주", "scheduled": "0605", "estimated": "0624",
                          "delayMin": 19, "status": "출발", "cancelled": false, "line": "D", "gate": "3", "terminal": null, "counters": null},
               "parking": [{"name": "국내선 제1주차장", "total": 2279, "parked": 2279, "free": 0}]}"""
        )!!
        assertEquals(19, plan.flight!!.delayMin)
        assertEquals(false, plan.flight!!.international)
        assertNull(plan.flight!!.terminal)
        assertNull(plan.waitMin)
        assertEquals(0, plan.parking.single().free)
        assertNull(FlightPlan.parse("""{"configured": false}"""))
    }
}

