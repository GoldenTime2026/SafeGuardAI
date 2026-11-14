package com.safeguard.ai

import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import com.safeguard.ai.risk.RiskRule
import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureSettingsTest {
    private val events = listOf(
        RiskEvent("hotspot", RiskLevel.HOTSPOT, "사고다발지역", "사고다발"),
        RiskEvent("shared", RiskLevel.FORWARD_HAZARD, "앞에 사고", "사고"),
        RiskEvent("critical", RiskLevel.CRITICAL_WARNING, "브레이크 경고등", "브레이크"),
    )
    private val rule = RiskRule { _, _ -> events }

    @Test
    fun `a switched-off feature is silent except for urgent warnings`() {
        val off = switchable(rule, on = { false })
        assertEquals(listOf("critical"), off.evaluate(com.safeguard.ai.core.VehicleSnapshot(), 0L).map { it.key })
    }

    @Test
    fun `a switch can cover only part of a rule`() {
        val hotspotsOff = switchable(rule, on = { false }) { it.level == RiskLevel.HOTSPOT }
        assertEquals(listOf("shared", "critical"), hotspotsOff.evaluate(com.safeguard.ai.core.VehicleSnapshot(), 0L).map { it.key })
        assertEquals(3, switchable(rule, on = { true }).evaluate(com.safeguard.ai.core.VehicleSnapshot(), 0L).size)
    }
}
