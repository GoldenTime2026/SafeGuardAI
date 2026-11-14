package com.safeguard.ai.features.traffic

import com.safeguard.ai.core.Congestion
import com.safeguard.ai.core.Gear
import com.safeguard.ai.core.TrafficIncident
import com.safeguard.ai.core.TrafficInfo
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.data.publicdata.Geo
import com.safeguard.ai.risk.RiskLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CongestionCoachTest {

    private val lat = 35.2000
    private val lon = 128.6800
    private val jam = Congestion(2_300.0, 1_200.0, 540, 12.0, "창원대로", "명곡교차로", "도청교차로")

    private fun incidentAt(meters: Double, type: String = "ACCIDENT") =
        Geo.offset(lat, lon, 0.0, meters).let { (a, b) -> TrafficIncident(type, a, b, "") }

    private fun driving(traffic: TrafficInfo?) =
        VehicleSnapshot(gear = Gear.DRIVE, speedKmh = 60.0, latitude = lat, longitude = lon, headingDeg = 0f, traffic = traffic)

    @Test
    fun `jam ahead with an accident is explained and offers a detour`() {
        var rerouted = false
        val coach = CongestionCoach { rerouted = true }
        val event = coach.evaluate(driving(TrafficInfo(true, listOf(jam), listOf(incidentAt(2_900.0)))), 0L).single()
        assertEquals(RiskLevel.TRAFFIC, event.level)
        assertEquals("2.3킬로미터 앞 사고로 정체 중입니다. 평균 12킬로미터, 통과에 약 9분 걸려요. 우회할까요?", event.speech)
        val confirm = event.confirm!!
        runBlocking { confirm.onYes() }
        assertTrue(rerouted)
    }

    @Test
    fun `an incident far from the jammed stretch is not named as the cause`() {
        assertNull(CongestionCoach.causeOf(jam, listOf(incidentAt(6_000.0)), driving(null)))
        assertNull(CongestionCoach.causeOf(jam, listOf(incidentAt(1_000.0)), driving(null)))
        assertNotNull(CongestionCoach.causeOf(jam, listOf(incidentAt(1_900.0)), driving(null)))
    }

    @Test
    fun `short delays, far jams, parked cars and no route stay silent`() {
        val coach = CongestionCoach {}
        val short = jam.copy(timeSeconds = 120)
        val far = jam.copy(offsetMeters = 4_000.0)
        assertTrue(coach.evaluate(driving(TrafficInfo(true, listOf(short), emptyList())), 0L).isEmpty())
        assertTrue(coach.evaluate(driving(TrafficInfo(true, listOf(far), emptyList())), 0L).isEmpty())
        assertTrue(coach.evaluate(driving(TrafficInfo(false, listOf(jam), emptyList())), 0L).isEmpty())
        assertTrue(coach.evaluate(driving(TrafficInfo(true, listOf(jam), emptyList())).copy(gear = Gear.PARK, speedKmh = 0.0), 0L).isEmpty())
    }

    @Test
    fun `the nearest qualifying jam is announced, without a cause when none matches`() {
        val near = jam.copy(offsetMeters = 800.0, road = "원이대로")
        val event = CongestionCoach {}.evaluate(driving(TrafficInfo(true, listOf(jam, near), emptyList())), 0L).single()
        assertTrue(event.speech, event.speech.startsWith("800미터 앞 정체 중입니다."))
    }

    @Test
    fun `distances are spoken naturally`() {
        assertEquals("2킬로미터", CongestionCoach.distance(2_000.0))
        assertEquals("1.5킬로미터", CongestionCoach.distance(1_520.0))
        assertEquals("800미터", CongestionCoach.distance(780.0))
        assertEquals("100미터", CongestionCoach.distance(20.0))
    }
}
