package com.safeguard.ai.data.publicdata

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small geodesy helpers; accurate enough for warnings at a few hundred metres. */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial bearing from point 1 to point 2, 0–360° clockwise from north. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Smallest absolute difference between two headings, 0–180°. */
    fun headingDelta(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    /** Moves [meters] from a point along [bearingDeg]; used by the scenario "drive" step. */
    fun offset(lat: Double, lon: Double, bearingDeg: Double, meters: Double): Pair<Double, Double> {
        val d = meters / EARTH_RADIUS_M
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = Math.asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to Math.toDegrees(l2)
    }

    data class Circle(val lat: Double, val lon: Double, val radiusM: Double)

    /**
     * Centre and covering radius of a GeoJSON Polygon / MultiPolygon (EPSG:4326, [lon, lat] order),
     * as delivered in the TAAS `geom_json` field. Returns null if the geometry can't be read.
     */
    fun circleOf(geoJson: String, minRadiusM: Double = 50.0, maxRadiusM: Double = 500.0): Circle? = runCatching {
        val geometry = JSONObject(geoJson)
        val rings = when (geometry.getString("type")) {
            "Polygon" -> listOf(geometry.getJSONArray("coordinates").getJSONArray(0))
            "MultiPolygon" -> geometry.getJSONArray("coordinates").let { polys -> (0 until polys.length()).map { polys.getJSONArray(it).getJSONArray(0) } }
            else -> return null
        }
        val points = rings.flatMap { it.toLonLatList() }
        if (points.isEmpty()) return null
        val lat = points.sumOf { it.second } / points.size
        val lon = points.sumOf { it.first } / points.size
        val radius = points.maxOf { distanceMeters(lat, lon, it.second, it.first) }.coerceIn(minRadiusM, maxRadiusM)
        Circle(lat, lon, radius)
    }.getOrNull()

    private fun JSONArray.toLonLatList() = (0 until length()).map { i -> getJSONArray(i).let { it.getDouble(0) to it.getDouble(1) } }
}
