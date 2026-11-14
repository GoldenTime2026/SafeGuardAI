package com.safeguard.ai.features.disaster

import com.safeguard.ai.core.RoutePlace
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.core.WiperLevel
import com.safeguard.ai.features.weather.RoadCondition
import com.safeguard.ai.features.weather.WeatherStatus
import com.safeguard.ai.risk.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class DisasterBriefingTest {

    private val now = 1_786_854_600_000L // 2026-08-16 13:30 KST
    private val kst = ZoneId.of("Asia/Seoul")
    private var region: String? = "경남 김해시"
    private var ahead = emptyList<RoutePlace>()
    private var weather = WeatherStatus()
    private var crowdRain = false
    private val briefing = DisasterBriefing({ region }, { ahead }, { weather }, { crowdRain }, kst)

    private fun message(sn: Long, vararg regions: String, minutesAgo: Long = 25, step: String = "안전안내", kind: String = "호우") = DisasterMessage(
        sn, now - minutesAgo * 60_000, regions.toList(), step, kind,
        "13시부로 김해시 호우주의보 발효. 하천변, 계곡, 급경사지, 농수로 등 위험지역 출입 금지, 대피권고를 받으면 즉시 대피하시기 바랍니다. [김해시]",
    )

    private fun tick(at: Long = now, s: VehicleSnapshot = VehicleSnapshot()) = briefing.evaluate(s, at)

    @Test
    fun `the message for the car's area is read word for word with its source`() {
        val m = message(266640, "경상남도 김해시")
        briefing.offer(listOf(m))
        val event = tick().single()
        assertEquals("행정안전부 안전안내 문자예요. ${m.text}", event.speech)
        assertEquals("재난문자 · 호우 · 13:05 행정안전부", event.screenText)
        assertEquals(RiskLevel.WEATHER, event.level)
        assertTrue(tick().isEmpty())
    }

    @Test
    fun `another area's message waits until the car gets there`() {
        briefing.offer(listOf(message(1, "경상남도 창원시 의창구", "경상남도 창원시 성산구")))
        assertTrue(tick().isEmpty())
        region = DisasterBriefing.regionOf("경상남도 창원시 의창구 동읍 남산리")
        assertEquals("disaster-1", tick().single().key)
    }

    @Test
    fun `a province-wide message reaches every city in it, and urgent ones rank as hazards`() {
        briefing.offer(listOf(message(2, "경상남도", step = "긴급재난")))
        assertEquals(RiskLevel.FORWARD_HAZARD, tick().single().level)
    }

    @Test
    fun `old messages are dropped and the same message is never read twice`() {
        // A fire is news for an hour; a heavy-rain warning stays in force for three.
        briefing.offer(listOf(message(3, "경상남도 김해시", minutesAgo = 61, kind = "화재"), message(7, "경상남도 김해시", minutesAgo = 181)))
        assertTrue(tick().isEmpty())
        briefing.offer(listOf(message(8, "경상남도 김해시", minutesAgo = 170)))
        assertEquals("disaster-8", tick().single().key)
        briefing.offer(listOf(message(4, "경상남도 김해시")))
        assertEquals(1, tick().size)
        briefing.offer(listOf(message(4, "경상남도 김해시")))
        assertTrue(tick().isEmpty())
    }

    @Test
    fun `nothing is read before the car knows its area`() {
        region = null
        briefing.offer(listOf(message(5, "경상남도")))
        assertTrue(tick().isEmpty())
    }

    @Test
    fun `a demo replay is read whatever its age and marked on screen`() {
        briefing.offer(listOf(message(6, "경상남도 김해시", minutesAgo = 100_000).copy(replay = true)))
        assertTrue(tick().single().screenText.endsWith("시연 재생"))
    }

    @Test
    fun `addresses and messages name provinces differently`() {
        assertEquals("경남 김해시", DisasterBriefing.regionOf("경상남도 김해시 진례면 신월리 123"))
        assertEquals("경기 수원시 장안구", DisasterBriefing.regionOf("경기 수원시 장안구 정자동"))
        assertEquals("세종", DisasterBriefing.regionOf("세종특별자치시 한누리대로 2130"))
        assertTrue(DisasterBriefing.covers(listOf("강원도 춘천시"), "강원특별자치도 춘천시"))
        assertTrue(DisasterBriefing.covers(listOf("충청북도"), "충북 청주시 상당구"))
        assertTrue(DisasterBriefing.covers(listOf("경기도 수원시 장안구"), "경기 수원시"))
        assertFalse(DisasterBriefing.covers(listOf("경상북도 김천시"), "경남 김해시"))
        assertFalse(DisasterBriefing.covers(listOf("경상남도 창원시 의창구"), "경남 김해시"))
    }

    @Test
    fun `a message where the route goes is read ahead with the time to get there, then only a reminder on entering`() {
        region = "경남 창원시 의창구"
        ahead = listOf(RoutePlace("진례 졸음쉼터", "경상남도 김해시 진례면 신월리", remainSeconds = 20 * 60))
        val m = message(9, "경상남도 김해시")
        briefing.offer(listOf(m))
        val preview = tick().single()
        assertEquals("경로 앞 김해시, 약 20분 뒤 지나갈 곳에 재난문자가 있어요. 행정안전부 안전안내 문자예요. ${m.text}", preview.speech)
        assertEquals("경로 앞 재난문자 · 김해시 · 약 20분 · 호우", preview.screenText)
        assertTrue(tick().isEmpty())
        region = "경남 김해시"
        assertEquals("앞서 들려드린 호우 재난문자 지역, 김해시에 들어왔어요.", tick().single().speech)
    }

    @Test
    fun `heavy rain in an area under a rain message becomes advice for now, once`() {
        briefing.offer(listOf(message(10, "경상남도 김해시")))
        tick()
        val driving = VehicleSnapshot(speedKmh = 90.0, wiper = WiperLevel.HIGH)
        weather = WeatherStatus(RoadCondition.HEAVY_RAIN, recommendedKmh = 50)
        crowdRain = true
        val advice = tick(s = driving).single()
        assertEquals(
            "김해시에 호우 재난문자가 나온 상태에서 지금 비가 거세요. 앞쪽 차들도 폭우를 만나고 있어요. " +
                "지하차도와 하천변 도로는 피하고, 물이 고인 길로는 들어가지 마세요. 속도는 50킬로미터 아래로 줄이세요.",
            advice.speech,
        )
        assertEquals(RiskLevel.FORWARD_HAZARD, advice.level)
        assertTrue(tick(s = driving).isEmpty())
    }

    @Test
    fun `no advice without the car's own signal, or when parked`() {
        briefing.offer(listOf(message(11, "경상남도 김해시"), message(12, "경상남도 김해시", kind = "대설")))
        tick(); tick()
        assertTrue(tick(s = VehicleSnapshot(speedKmh = 90.0, wiper = WiperLevel.OFF, outsideTempC = 8.0)).isEmpty())
        weather = WeatherStatus(RoadCondition.HEAVY_RAIN)
        assertTrue(tick(s = VehicleSnapshot(speedKmh = 0.0, wiper = WiperLevel.HIGH)).isEmpty())
        weather = WeatherStatus()
        val cold = tick(s = VehicleSnapshot(speedKmh = 60.0, outsideTempC = -2.0)).single()
        assertEquals("disaster-car-12-snow", cold.key)
        assertTrue(cold.speech.contains("바깥 기온이 -2도예요."))
    }
}
