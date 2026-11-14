package com.safeguard.ai.features.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoreCostTest {
    @Test
    fun `camera fines follow the passenger-car table`() {
        assertEquals(0, ScoreCost.fineWon(0.0, schoolZone = false))
        assertEquals(40_000, ScoreCost.fineWon(20.0, schoolZone = false))
        assertEquals(70_000, ScoreCost.fineWon(20.5, schoolZone = false))
        assertEquals(100_000, ScoreCost.fineWon(60.0, schoolZone = false))
        assertEquals(130_000, ScoreCost.fineWon(61.0, schoolZone = false))
        assertEquals(70_000, ScoreCost.fineWon(5.0, schoolZone = true))
        assertEquals(160_000, ScoreCost.fineWon(70.0, schoolZone = true))
    }

    @Test
    fun `amounts are said to the nearest thousand`() {
        assertEquals("4만 원", ScoreCost.spoken(40_000))
        assertEquals("3만 3천 원", ScoreCost.spoken(32_500))
        assertEquals("8천 원", ScoreCost.spoken(7_600))
        assertEquals("11만 원", ScoreCost.spoken(110_000))
    }

    @Test
    fun `the insurance outlook counts months at 70 or more and prices 5 percent of the premium`() {
        val o = ScoreCost.insurance(listOf(70, 69, 85, 90), monthAverage = 90, annualPremiumWon = 912_000)
        assertEquals(3, o.goodMonths)
        assertEquals(46_000, o.savingWon)
        assertNull(ScoreCost.insurance(emptyList(), 80, null).savingWon)
    }
}
