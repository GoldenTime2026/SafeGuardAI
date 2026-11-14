package com.safeguard.ai.features.crash

import com.safeguard.ai.core.Gear
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.data.publicdata.Geo
import com.safeguard.ai.data.publicdata.Place
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardStopCareTest {

    private val rest = Place("진례", 35.249324, 128.758979, detail = "남해선 순천기점 + 부산종점")
    private val west5km = Geo.offset(rest.latitude, rest.longitude, 270.0, 5_000.0)

    private val sent = mutableListOf<String>()
    private var crashActive = false
    private val guardian = object : GuardianNotifier {
        override val name = "보호자"
        override suspend fun notify(message: String): Boolean {
            sent += message
            return true
        }
    }
    private val care = HazardStopCare(
        CoroutineScope(Dispatchers.Unconfined),
        address = { null },
        landmarks = { listOf(rest) },
        guardian = guardian,
        crashActive = { crashActive },
    )

    private fun car(
        hazard: Boolean = true,
        speed: Double = 0.0,
        limit: Int? = 100,
        at: Pair<Double, Double> = west5km,
        airbag: Boolean = false,
    ) = VehicleSnapshot(
        gear = Gear.DRIVE, speedKmh = speed, latitude = at.first, longitude = at.second, headingDeg = 90f,
        hazardOn = hazard, roadSpeedLimitKmh = limit, airbagDeployed = airbag,
    )

    /** Feeds the same snapshot every 5 s until [untilMs]; returns everything the rule said. */
    private fun hold(s: VehicleSnapshot, fromMs: Long = 0L, untilMs: Long = 35_000L): List<RiskEvent> =
        (fromMs..untilMs step 5_000L).flatMap { care.evaluate(s, it) }

    @Test
    fun `a hazard-light stop on an expressway for 30 s offers evacuation and the guardian alert`() {
        val said = hold(car())
        val event = said.single()
        assertEquals(RiskLevel.CRITICAL_WARNING, event.level)
        assertTrue(event.speech.contains("가드레일 밖으로 대피"))
        assertTrue(event.speech.contains("보호자에게 위치를 알릴까요?"))
        assertTrue(care.active)
        val card = care.card.value!!
        assertTrue(card.title.contains("비상 정차"))
        assertTrue(card.location.any { it.contains("진례 졸음쉼터") })
        assertTrue(card.location.any { it.contains("1588-2504") })
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the guardian is told only when the driver says yes`() {
        val event = hold(car()).single()
        runBlocking { event.confirm!!.onYes() }
        assertEquals(1, sent.size)
        assertTrue(sent.single().contains("고속도로 비상 정차(에어백 미전개)"))
        assertTrue(sent.single().contains("운전자 요청"))
        assertTrue(care.card.value!!.guardianStatus.contains("보냄"))
    }

    @Test
    fun `short flashes, city streets, moving cars and rest areas are not emergency stops`() {
        assertTrue(hold(car(), untilMs = 25_000L).isEmpty())
        care.reset()
        assertTrue(hold(car(limit = 50)).isEmpty())
        assertTrue(hold(car(limit = null)).isEmpty())
        assertTrue(hold(car(speed = 40.0)).isEmpty())
        assertTrue(hold(car(hazard = false)).isEmpty())
        assertTrue(hold(car(at = rest.latitude to rest.longitude)).isEmpty())
        assertFalse(care.active)
    }

    @Test
    fun `the hold restarts when the hazard lights go off`() {
        hold(car(), untilMs = 20_000L)
        care.evaluate(car(hazard = false), 25_000L)
        assertTrue(hold(car(), fromMs = 30_000L, untilMs = 55_000L).isEmpty())
        assertEquals(1, hold(car(), fromMs = 60_000L, untilMs = 60_000L).size)
    }

    @Test
    fun `an airbag episode takes over and driving on ends the stop`() {
        hold(car())
        crashActive = true
        care.evaluate(car(), 40_000L)
        assertFalse(care.active)
        assertNull(care.card.value)

        crashActive = false
        hold(car(), fromMs = 100_000L, untilMs = 135_000L)
        assertTrue(care.active)
        hold(car(hazard = false, speed = 60.0), fromMs = 140_000L, untilMs = 175_000L)
        assertFalse(care.active)
    }
}
