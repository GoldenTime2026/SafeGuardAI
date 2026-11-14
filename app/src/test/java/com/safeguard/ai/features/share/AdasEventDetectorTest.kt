package com.safeguard.ai.features.share

import com.safeguard.ai.core.Gear
import com.safeguard.ai.core.TelltaleColor
import com.safeguard.ai.core.TelltaleLevel
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.features.weather.RoadCondition
import org.junit.Assert.assertEquals
import org.junit.Test

class AdasEventDetectorTest {

    private var condition = RoadCondition.DRY
    private val reports = mutableListOf<String>()
    private val detector = AdasEventDetector(weather = { condition }, report = { type, _, _, _ -> reports += type })
    private val driving = VehicleSnapshot(gear = Gear.DRIVE, speedKmh = 80.0, latitude = 35.2, longitude = 128.7, headingDeg = 90f)

    private fun lane(color: TelltaleColor) = driving.copy(telltales = mapOf("LFA" to TelltaleLevel.ON), telltaleColors = mapOf("LFA" to color))

    @Test
    fun `a collision warning is reported once per warning`() {
        val warn = driving.copy(telltales = mapOf("FCA" to TelltaleLevel.BLINK))
        detector.evaluate(driving, 0)
        detector.evaluate(warn, 1_000)
        detector.evaluate(warn, 2_000)
        assertEquals(listOf("NEAR_MISS"), reports)
        // A second warning within the minute is the same moment.
        detector.evaluate(driving, 3_000)
        detector.evaluate(warn, 4_000)
        assertEquals(1, reports.size)
        detector.evaluate(driving, 70_000)
        detector.evaluate(driving.copy(telltaleColors = mapOf("ELK" to TelltaleColor.RED)), 71_000)
        assertEquals(listOf("NEAR_MISS", "NEAR_MISS"), reports)
    }

    @Test
    fun `lane following lost on a dry road is a faded-lane report`() {
        detector.evaluate(lane(TelltaleColor.GREEN), 0)
        detector.evaluate(lane(TelltaleColor.GREEN), 25_000)
        detector.evaluate(lane(TelltaleColor.WHITE), 26_000)
        assertEquals(listOf("LANE_FADE"), reports)
    }

    @Test
    fun `lane loss in rain, after a short hold or with a turn signal is not reported`() {
        condition = RoadCondition.RAIN
        detector.evaluate(lane(TelltaleColor.GREEN), 0)
        detector.evaluate(lane(TelltaleColor.WHITE), 25_000)
        condition = RoadCondition.DRY
        detector.evaluate(lane(TelltaleColor.GREEN), 30_000)
        detector.evaluate(lane(TelltaleColor.WHITE), 35_000)
        detector.evaluate(lane(TelltaleColor.GREEN), 40_000)
        detector.evaluate(lane(TelltaleColor.WHITE).copy(turnSignal = "LEFT"), 70_000)
        assertEquals(emptyList<String>(), reports)
    }
}
