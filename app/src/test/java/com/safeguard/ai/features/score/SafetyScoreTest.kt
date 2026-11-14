package com.safeguard.ai.features.score

import com.safeguard.ai.core.Gear
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class SafetyScoreTest {

    private class MemoryStore : ScoreStore {
        val trips = mutableListOf<Pair<Long, Int>>()
        val shared = mutableListOf<Long>()
        override fun saveTrip(at: Long, score: Int) { trips += at to score }
        override fun tripsSince(since: Long) = trips.filter { it.first >= since }.map { it.second }
        override fun addContribution(at: Long) { shared += at }
        override fun contributionsSince(since: Long) = shared.count { it >= since }
        val months = mutableMapOf<String, MutableList<Int>>()
        override fun addToMonth(month: String, score: Int) { months.getOrPut(month) { mutableListOf() } += score }
        override fun monthAverage(month: String) = months[month]?.average()?.toInt()
    }

    private val store = MemoryStore()
    private var recommended: Int? = null
    private var premium: Int? = null
    private val score = SafetyScore(store, recommendedKmh = { recommended }, zone = ZoneOffset.UTC, annualPremiumWon = { premium })

    /** Noon UTC, so the trip is not a late-night one. */
    private val start = 12 * 60 * 60_000L

    private fun moving(speed: Double, limit: Int = 100, steering: Float = 0f) =
        VehicleSnapshot(gear = Gear.DRIVE, speedKmh = speed, roadSpeedLimitKmh = limit, steeringDeg = steering)

    private val parked = VehicleSnapshot(gear = Gear.PARK, speedKmh = 0.0)

    /** Drives at [speed] once a second for [seconds], starting at [from]; returns the time after. */
    private fun cruise(from: Long, seconds: Int, speed: Double, limit: Int = 100): Long {
        var t = from
        repeat(seconds) {
            score.evaluate(moving(speed, limit), t)
            t += 1_000
        }
        return t
    }

    @Test
    fun `a calm trip scores 100 and is said once when parking`() {
        val t = cruise(start, 180, 80.0)
        val event = score.evaluate(parked, t).single()
        assertEquals(RiskLevel.INFO, event.level)
        assertTrue(event.speech.startsWith("이번 운전은 100점이에요."))
        assertEquals(100, score.report.value!!.score)
        assertTrue(score.evaluate(parked, t + 1_000).isEmpty())
    }

    @Test
    fun `a short hop is not scored`() {
        val t = cruise(start, 60, 40.0)
        assertTrue(score.evaluate(parked, t).isEmpty())
        assertNull(score.report.value)
    }

    @Test
    fun `hard braking and speeding cost points`() {
        var t = cruise(start, 100, 80.0)
        score.evaluate(moving(80.0), t); t += 1_000
        score.evaluate(moving(55.0), t); t += 1_000 // -25 km/h within a second
        t = cruise(t, 30, 115.0) // 30 s over 100 + 10
        for (speed in 110 downTo 80 step 5) t = cruise(t, 1, speed.toDouble()) // eases off, no hard braking
        t = cruise(t, 60, 80.0)
        score.evaluate(parked, t)
        val r = score.report.value!!
        assertEquals(1, r.hardBrakes)
        assertEquals(30, r.overspeedSeconds)
        assertEquals(100 - 5 - 3, r.score)
        assertEquals(1, r.speedingEpisodes)
        assertEquals(40_000, r.fineWon) // 15 km/h over: the lowest camera fine
    }

    @Test
    fun `in the rain the recommended speed is the limit`() {
        recommended = 80
        // Over 80 + 10, under the 100 limit; the first sample only starts the trip.
        val t = cruise(start, 151, 95.0)
        score.evaluate(parked, t)
        assertEquals(150, score.report.value!!.overspeedSeconds)
        assertEquals(100 - 15, score.report.value!!.score)
    }

    @Test
    fun `slowing down after a warning is a good response, ignoring it costs points`() {
        var t = cruise(start, 60, 80.0)
        val warning = RiskEvent("w1", RiskLevel.FORWARD_HAZARD, "앞에 사고", "사고")
        score.onWarning(warning, moving(80.0), t)
        t = cruise(t, 5, 65.0) // slowed below 90 %
        score.onWarning(warning.copy(key = "w2"), moving(65.0), t)
        t = cruise(t, 20, 65.0) // kept the same speed for 15 s
        t = cruise(t, 60, 65.0)
        score.evaluate(parked, t)
        val r = score.report.value!!
        assertEquals(1, r.warningsHeeded)
        assertEquals(1, r.warningsIgnored)
        assertEquals(97, r.score)
    }

    @Test
    fun `unbelted driving is the habit mentioned first`() {
        var t = start
        repeat(150) {
            score.evaluate(moving(60.0).copy(seatBeltUnbuckled = true), t)
            t += 1_000
        }
        val event = score.evaluate(parked, t).single()
        assertEquals(90, score.report.value!!.score)
        assertTrue(event.speech.contains("안전벨트를 꼭 매 주세요."))
    }

    @Test
    fun `the week average and shared hazards are included`() {
        store.saveTrip(start - 60_000, 80)
        score.onShared(start - 30_000)
        val t = cruise(start, 150, 70.0)
        val event = score.evaluate(parked, t).single()
        val r = score.report.value!!
        assertEquals(90, r.weekAverage)
        assertEquals(1, r.weekContributions)
        assertTrue(event.speech.contains("이번 주 평균은 90점이에요."))
    }

    @Test
    fun `a rest stop for drowsiness keeps the score on screen without speaking it`() {
        val resting = SafetyScore(store, recommendedKmh = { null }, zone = ZoneOffset.UTC, quiet = { true })
        var t = start
        repeat(150) { resting.evaluate(moving(80.0), t); t += 1_000 }
        assertTrue(resting.evaluate(parked, t).isEmpty())
        assertEquals(100, resting.report.value!!.score)
    }

    @Test
    fun `a sharp swing of the wheel at speed counts`() {
        var t = cruise(start, 100, 70.0)
        score.evaluate(moving(70.0, steering = -50f), t); t += 500
        score.evaluate(moving(70.0, steering = 50f), t); t += 500
        t = cruise(t, 60, 70.0)
        score.evaluate(parked, t)
        assertEquals(1, score.report.value!!.sharpTurns)
    }

    @Test
    fun `each speeding stretch is priced like a camera fine, and a blip past the limit is not`() {
        var t = cruise(start, 60, 90.0)
        t = cruise(t, 10, 135.0) // 35 over: 70,000
        t = cruise(t, 20, 90.0)
        t = cruise(t, 2, 125.0) // too short to count
        t = cruise(t, 20, 90.0)
        t = cruise(t, 5, 118.0) // 18 over: 40,000
        t = cruise(t, 60, 90.0)
        val event = score.evaluate(parked, t).single()
        val r = score.report.value!!
        assertEquals(2, r.speedingEpisodes)
        assertEquals(110_000, r.fineWon)
        assertTrue(event.speech.contains("제한 속도를 10킬로미터 넘게 달린 구간이 2번 있었어요. 단속 카메라였다면 과태료 11만 원이에요."))
        assertTrue(event.screenText.endsWith("과태료 환산 11만 원"))
    }

    @Test
    fun `speeding in a school zone by day is priced by the school-zone table`() {
        val tenAm = 10 * 60 * 60_000L
        var t = tenAm
        val school = listOf(com.safeguard.ai.core.Caution("SCHOOL_ZONE", 100))
        repeat(150) {
            score.evaluate(moving(45.0, limit = 30).copy(cautions = school), t)
            t += 1_000
        }
        score.evaluate(parked, t)
        assertEquals(70_000, score.report.value!!.fineWon) // 15 over in a school zone
    }

    @Test
    fun `the month is measured against the insurers' 70 points, in won once the premium is known`() {
        premium = 800_000
        for (back in 1..8) store.addToMonth(java.time.YearMonth.of(1970, 1).minusMonths(back.toLong()).toString(), 80)
        val t = cruise(start, 150, 80.0)
        val event = score.evaluate(parked, t).single()
        val o = score.report.value!!.insurance!!
        assertEquals(100, o.monthAverage)
        assertEquals(9, o.goodMonths)
        assertEquals(40_000, o.savingWon)
        assertTrue(event.speech.contains("이번 달 평균 100점으로 보험 안전운전 할인 기준 70점을 넘고 있어요. 최근 1년 중 9달이 기준을 넘어 보험료 약 4만 원을 아낄 수 있는 조건이에요."))
    }

    @Test
    fun `below the threshold the summary says how far there is to go`() {
        premium = 650_000
        store.addToMonth("1970-01", 40)
        var t = start
        repeat(150) {
            score.evaluate(moving(60.0).copy(seatBeltUnbuckled = true), t)
            t += 1_000
        }
        val event = score.evaluate(parked, t).single()
        assertTrue(event.speech.contains("이번 달 평균 65점이라 보험 안전운전 할인 기준 70점보다 낮아요. 1년에 9달을 넘기면 보험료 약 3만 3천 원을 아낄 수 있어요. 지금 0달이에요."))
    }
}
