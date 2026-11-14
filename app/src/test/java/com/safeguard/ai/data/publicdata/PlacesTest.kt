package com.safeguard.ai.data.publicdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlacesTest {

    private val jinrye = Place("진례", 35.249324, 128.758979)

    @Test
    fun `the bundled rest area data parses with real coordinates`() {
        val places = Places.parse(File("src/main/assets/public/rest_areas.json").readText())
        assertTrue(places.size > 200)
        assertTrue(places.all { it.latitude in 33.0..39.0 && it.longitude in 124.0..132.0 })
        assertTrue(places.any { it.name == "진례" })
    }

    @Test
    fun `only places ahead count`() {
        val (lat, lon) = Geo.offset(jinrye.latitude, jinrye.longitude, 270.0, 5_000.0)
        assertEquals(jinrye, Places.nearestAhead(listOf(jinrye), lat, lon, 90.0)?.first)
        assertNull(Places.nearestAhead(listOf(jinrye), lat, lon, 270.0))
    }

    @Test
    fun `places just behind or too far are skipped`() {
        val (lat, lon) = Geo.offset(jinrye.latitude, jinrye.longitude, 270.0, 40_000.0)
        assertNull(Places.nearestAhead(listOf(jinrye), lat, lon, 90.0))
        assertNull(Places.nearestAhead(listOf(jinrye), jinrye.latitude, jinrye.longitude, 90.0))
    }
}
