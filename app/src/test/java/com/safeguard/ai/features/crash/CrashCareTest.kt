package com.safeguard.ai.features.crash

import com.safeguard.ai.core.Gear
import com.safeguard.ai.core.NaviAddress
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.data.publicdata.Geo
import com.safeguard.ai.data.publicdata.Place
import com.safeguard.ai.features.crash.CrashCare.CrashReply
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

class CrashCareTest {

    private val rest = Place("진례", 35.249324, 128.758979, detail = "남해선 순천기점 + 부산종점")
    private val west5km = Geo.offset(rest.latitude, rest.longitude, 270.0, 5_000.0)

    private val said = mutableListOf<RiskEvent>()
    private val sent = mutableListOf<String>()
    private val guardian = object : GuardianNotifier {
        override val name = "보호자"
        override suspend fun notify(message: String): Boolean {
            sent += message
            return false
        }
    }
    private val care = CrashCare(
        CoroutineScope(Dispatchers.Unconfined),
        say = { said += it },
        address = { null },
        landmarks = { listOf(rest) },
        guardian = guardian,
    )

    private fun car(airbag: Boolean = false, speed: Double = 0.0) = VehicleSnapshot(
        gear = Gear.DRIVE, speedKmh = speed, latitude = west5km.first, longitude = west5km.second, headingDeg = 90f,
        airbagDeployed = airbag,
    )

    private fun crash(): RiskEvent {
        care.evaluate(car(speed = 90.0), 0L)
        return care.evaluate(car(airbag = true), 1_000L).single()
    }

    private fun RiskEvent.reply(text: String?) = runBlocking { reply!!.onReply(text) }

    @Test
    fun `an airbag deployment asks the driver once and interrupts everything`() {
        val ask = crash()
        assertEquals(RiskLevel.CRASH, ask.level)
        assertTrue(ask.speech.contains("괜찮으세요?"))
        assertTrue(care.active)
        assertTrue(care.evaluate(car(airbag = true), 2_000L).isEmpty())
        assertTrue(care.card.value!!.location.any { it.contains("진례 졸음쉼터") })
    }

    @Test
    fun `two silences alert the guardian and give evacuation steps`() {
        crash().reply(null)
        val again = said.single()
        assertTrue(again.speech.contains("대답이 없으면 보호자에게 알릴게요"))
        again.reply(null)
        val emergency = said.last()
        assertTrue(emergency.speech.contains("비상등"))
        assertTrue(emergency.speech.contains("화면에 띄웠어요"))
        assertEquals(1, sent.size)
        assertTrue(sent.single().contains("응답 없음"))
        assertTrue(care.card.value!!.guardianStatus.contains("시연 모드"))
    }

    @Test
    fun `a call for help alerts at once`() {
        crash().reply("도와주세요")
        assertEquals(1, sent.size)
        assertTrue(sent.single().contains("도움 요청"))
    }

    @Test
    fun `a conscious driver gets the evacuation steps and is asked before the guardian is told`() {
        crash().reply("괜찮아")
        val ok = said.single()
        assertTrue(ok.speech.startsWith("다행이에요."))
        assertTrue(sent.isEmpty())
        runBlocking { ok.confirm!!.onYes() }
        assertEquals(1, sent.size)
    }

    @Test
    fun `driving on ends the episode`() {
        crash()
        care.evaluate(car(airbag = true, speed = 40.0), 10_000L)
        assertTrue(care.active)
        care.evaluate(car(airbag = true, speed = 40.0), 40_000L)
        assertFalse(care.active)
        assertNull(care.card.value)
    }

    @Test
    fun `after a crash any words mean conscious, unlike a yes-no question`() {
        assertEquals(CrashReply.CONSCIOUS, CrashCare.classifyReply("괜찮아"))
        assertEquals(CrashReply.CONSCIOUS, CrashCare.classifyReply("네 괜찮아요"))
        assertEquals(CrashReply.HELP, CrashCare.classifyReply("다리가 아파"))
        assertEquals(CrashReply.HELP, CrashCare.classifyReply("119 불러줘"))
        assertEquals(CrashReply.SILENT, CrashCare.classifyReply(null))
        assertEquals(CrashReply.SILENT, CrashCare.classifyReply("  "))
    }

    @Test
    fun `the location reads out a landmark, direction and coordinates`() {
        val lines = CrashCare.describeLocation(car(), null, listOf(rest))
        assertEquals("진례 졸음쉼터(남해선)에서 서쪽 5킬로미터", lines[1])
        assertEquals("동쪽으로 가던 중", lines[2])
        assertTrue(lines[3].startsWith("좌표 35.24"))
    }

    @Test
    fun `the guardian message carries time, reason and a map link`() {
        val message = CrashCare.guardianMessage(car(), "경상남도 김해시", listOf(rest), "14:30", "응답 없음")
        assertTrue(message.startsWith("[세이프가드] 14:30 차량 사고 감지"))
        assertTrue(message.contains("경상남도 김해시"))
        assertTrue(message.contains("https://map.kakao.com/link/map/"))
    }

    @Test
    fun `an address resolved for another position is not used`() {
        val here = car()
        assertTrue(CrashCare.matches(NaviAddress("경상남도 김해시", here.latitude!!, here.longitude!! + 0.001), here))
        assertFalse(CrashCare.matches(NaviAddress("성남시 수정구 시흥동", 37.43, 127.10), here))
    }

    @Test
    fun `compass points`() {
        assertEquals("북", CrashCare.compass(0.0))
        assertEquals("동", CrashCare.compass(90.0))
        assertEquals("남서", CrashCare.compass(225.0))
        assertEquals("북", CrashCare.compass(359.0))
    }
}
