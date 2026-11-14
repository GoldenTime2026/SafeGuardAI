package com.safeguard.ai.features.maintenance

import com.safeguard.ai.core.Gear
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.risk.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class MaintenanceCareTest {

    private class MemoryStore : MaintenanceStore {
        val records = mutableMapOf<ServiceItem, Double>()
        var baseline: Double? = null
        override fun lastServiceKm(item: ServiceItem) = records[item]
        override fun record(item: ServiceItem, km: Double) { records[item] = km }
        override fun baselineKm() = baseline
        override fun setBaselineKm(km: Double) { baseline = km }
    }

    private val store = MemoryStore()
    private val care = MaintenanceCare(store, ZoneOffset.UTC)
    private val settle = MaintenanceCare.SETTLE_MS

    private fun parked(km: Double) = VehicleSnapshot(gear = Gear.PARK, speedKmh = 0.0, odometerKm = km)
    private fun moving(km: Double) = VehicleSnapshot(gear = Gear.DRIVE, speedKmh = 60.0, odometerKm = km)

    @Test
    fun `nothing is said when no item is due`() {
        store.records[ServiceItem.ENGINE_OIL] = 30_000.0
        store.baseline = 30_000.0
        care.evaluate(parked(31_000.0), 0L)
        assertTrue(care.evaluate(parked(31_000.0), settle).isEmpty())
    }

    @Test
    fun `the most urgent item is said once a day at a stop`() {
        store.baseline = 20_000.0
        store.records[ServiceItem.ENGINE_OIL] = 20_000.0
        store.records[ServiceItem.TIRE_ROTATION] = 21_000.0
        care.evaluate(parked(29_200.0), 0L)
        val event = care.evaluate(parked(29_200.0), settle).single()
        assertEquals(RiskLevel.INFO, event.level)
        assertEquals("엔진오일 교체까지 800킬로미터 남았어요.", event.speech)
        assertTrue(care.evaluate(parked(29_200.0), settle + 1_000).isEmpty())
    }

    @Test
    fun `an overdue item recommends a service centre and counts the others`() {
        store.baseline = 0.0
        care.evaluate(parked(10_500.0), 0L)
        val event = care.evaluate(parked(10_500.0), settle).single()
        assertTrue(event.speech.startsWith("엔진오일 교체 시기가 500킬로미터 지났어요."))
        assertTrue(event.speech.contains("점검할 항목이 1개 더 있어요."))
    }

    @Test
    fun `logging a service resets the item`() {
        store.baseline = 0.0
        care.evaluate(parked(10_500.0), 0L)
        care.recordService(ServiceItem.ENGINE_OIL)
        val oil = care.status.value.first { it.item == ServiceItem.ENGINE_OIL }
        assertEquals(10_000, oil.remainingKm)
        assertTrue(oil.recorded)
        assertFalse(oil.due)
    }

    @Test
    fun `the first odometer reading becomes the baseline`() {
        care.evaluate(parked(42_000.0), 0L)
        assertEquals(42_000.0, store.baseline!!, 0.0)
        assertEquals(10_000, care.status.value.first { it.item == ServiceItem.ENGINE_OIL }.remainingKm)
    }

    @Test
    fun `low brake fluid is a critical warning while driving, once per trip`() {
        store.baseline = 0.0
        val s = moving(100.0).copy(brakeFluidLow = true, engineOilLow = true)
        val events = care.evaluate(s, 0L)
        assertEquals(listOf(RiskLevel.CRITICAL_WARNING, RiskLevel.MAINTENANCE), events.map { it.level })
        assertTrue(care.evaluate(s, 1_000L).isEmpty())
    }

    @Test
    fun `maker and model year come from the VIN`() {
        assertEquals("현대 2024년식", MaintenanceCare.describeVin("KMHL241CBRA123456"))
        assertEquals("기아 2022년식", MaintenanceCare.describeVin("KNAE551CBNA123456"))
        assertNull(MaintenanceCare.describeVin("SHORT"))
    }
}
