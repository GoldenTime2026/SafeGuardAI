package com.safeguard.ai.features.telltale

import com.safeguard.ai.core.Gear
import com.safeguard.ai.core.TelltaleLevel
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.data.publicdata.Place
import com.safeguard.ai.risk.RiskLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelltaleGuideTest {

    private val center = Place("블루핸즈 (데모 지점)", 35.2410, 128.6950, demo = true)
    private var routed: Place? = null
    private val guide = TelltaleGuide(serviceCenters = { listOf(center) }, routeTo = { routed = it })

    private fun moving(vararg lights: Pair<String, TelltaleLevel>) =
        VehicleSnapshot(gear = Gear.DRIVE, speedKmh = 60.0, latitude = 35.2300, longitude = 128.6800, telltales = mapOf(*lights))

    private fun parked(vararg lights: Pair<String, TelltaleLevel>) =
        moving(*lights).copy(gear = Gear.PARK, speedKmh = 0.0)

    private val settle = TelltaleGuide.SETTLE_MS

    @Test
    fun `the bulb check at ignition is not announced`() {
        assertTrue(guide.evaluate(moving("TPMS" to TelltaleLevel.ON), 0L).isEmpty())
        assertTrue(guide.evaluate(moving(), 2_000L).isEmpty())
        assertTrue(guide.evaluate(moving("TPMS" to TelltaleLevel.ON), settle + 2_000L).isEmpty())
    }

    @Test
    fun `a service light is said once while driving`() {
        guide.evaluate(moving("TPMS" to TelltaleLevel.ON), 0L)
        val event = guide.evaluate(moving("TPMS" to TelltaleLevel.ON), settle).single()
        assertEquals(RiskLevel.MAINTENANCE, event.level)
        assertTrue(event.speech.startsWith("타이어 공기압 경고예요."))
        assertEquals("정비소 방문 권장 · 타이어 공기압 경고", event.screenText)
        assertTrue(guide.evaluate(moving("TPMS" to TelltaleLevel.ON), settle + 1_000L).isEmpty())
    }

    @Test
    fun `a stop light interrupts like a critical warning`() {
        guide.evaluate(moving("ENGINE_TEMPERATURE" to TelltaleLevel.ON), 0L)
        val event = guide.evaluate(moving("ENGINE_TEMPERATURE" to TelltaleLevel.ON), settle).single()
        assertEquals(RiskLevel.CRITICAL_WARNING, event.level)
    }

    @Test
    fun `a blinking ESC is the system working, a steady one is a fault`() {
        guide.evaluate(moving("ESC" to TelltaleLevel.BLINK), 0L)
        assertTrue(guide.evaluate(moving("ESC" to TelltaleLevel.BLINK), settle).isEmpty())
        guide.evaluate(moving("ESC" to TelltaleLevel.ON), settle + 1)
        assertEquals(1, guide.evaluate(moving("ESC" to TelltaleLevel.ON), 2 * settle + 1).size)
    }

    @Test
    fun `a blinking engine light is more urgent`() {
        guide.evaluate(moving("ENGINE" to TelltaleLevel.BLINK), 0L)
        val event = guide.evaluate(moving("ENGINE" to TelltaleLevel.BLINK), settle).single()
        assertEquals(RiskLevel.CRITICAL_WARNING, event.level)
        assertTrue(event.speech.contains("깜빡여요"))
    }

    @Test
    fun `parking after driving shows the steps and offers the service centre`() {
        guide.evaluate(moving("TPMS" to TelltaleLevel.ON), 0L)
        guide.evaluate(moving("TPMS" to TelltaleLevel.ON), settle)
        val event = guide.evaluate(parked("TPMS" to TelltaleLevel.ON), settle + 10_000L).single()
        val card = guide.card.value!!
        assertEquals("타이어 공기압 경고", card.name)
        assertEquals(3, card.steps.size)
        assertTrue(event.speech, event.speech.endsWith("거리 블루핸즈로 안내할까요?"))
        runBlocking { event.confirm!!.onYes() }
        assertEquals(center, routed)
        assertTrue(guide.evaluate(parked("TPMS" to TelltaleLevel.ON), settle + 11_000L).isEmpty())
    }

    @Test
    fun `at departure the card is shown without speaking`() {
        guide.evaluate(parked("LOW_FUEL" to TelltaleLevel.ON), 0L)
        assertTrue(guide.evaluate(parked("LOW_FUEL" to TelltaleLevel.ON), settle).isEmpty())
        assertEquals("연료 부족", guide.card.value?.name)
        guide.evaluate(moving("LOW_FUEL" to TelltaleLevel.ON), settle + 1)
        assertNull(guide.card.value)
    }

    @Test
    fun `lights handled by other features are not in the catalog`() {
        listOf("ICY_ROAD", "DRIVER_ATTENTION", "TURN_SIGNAL_LEFT").forEach { assertNull(TelltaleCatalog.entries[it]) }
        assertTrue(TelltaleCatalog.entries.size >= 15)
    }
}
