package com.safeguard.ai.data.publicdata

import android.content.Context
import android.util.Log
import org.json.JSONObject

/** A place the car can be sent to: a drowsiness rest area or a service centre. */
data class Place(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val detail: String = "",
    val demo: Boolean = false,
)

/**
 * Bundled place lists:
 * - `public/rest_areas.json` — 한국도로공사 전국졸음쉼터표준데이터 (data.go.kr 15028203), built by `tools/fetch_rest_areas.sh`.
 * - `public/service_centers.json` — demo seed; real service-centre locations need Hyundai/Kia data.
 */
object Places {
    private const val TAG = "SafeGuard/Places"

    fun load(context: Context, assetPath: String): List<Place> =
        runCatching { context.assets.open(assetPath).bufferedReader().use { it.readText() } }
            .map(::parse)
            .onSuccess { Log.i(TAG, "$assetPath: ${it.size} places") }
            .onFailure { Log.w(TAG, "cannot load $assetPath: ${it.message}") }
            .getOrDefault(emptyList())

    fun parse(json: String): List<Place> {
        val root = JSONObject(json)
        val demo = root.optJSONObject("_meta")?.optBoolean("demo") == true
        val points = root.optJSONArray("points") ?: return emptyList()
        return (0 until points.length()).map { i ->
            points.getJSONObject(i).let {
                Place(
                    name = it.getString("name"),
                    latitude = it.getDouble("lat"),
                    longitude = it.getDouble("lon"),
                    detail = listOf(it.optString("road"), it.optString("direction")).filter(String::isNotBlank).joinToString(" "),
                    demo = demo,
                )
            }
        }
    }

    /**
     * The nearest place roughly ahead of the car (within [coneDeg] of its heading), between [minMeters] and
     * [maxMeters] away. Straight-line distance: rest areas sit beside the road, so this is close enough to pick one.
     */
    fun nearestAhead(
        places: List<Place>,
        lat: Double,
        lon: Double,
        headingDeg: Double,
        minMeters: Double = 300.0,
        maxMeters: Double = 30_000.0,
        coneDeg: Double = 45.0,
    ): Pair<Place, Double>? = places
        .map { it to Geo.distanceMeters(lat, lon, it.latitude, it.longitude) }
        .filter { (p, d) ->
            d in minMeters..maxMeters && Geo.headingDelta(headingDeg, Geo.bearingDeg(lat, lon, p.latitude, p.longitude)) <= coneDeg
        }
        .minByOrNull { it.second }

    fun nearest(places: List<Place>, lat: Double, lon: Double, maxMeters: Double): Pair<Place, Double>? = places
        .map { it to Geo.distanceMeters(lat, lon, it.latitude, it.longitude) }
        .filter { it.second <= maxMeters }
        .minByOrNull { it.second }
}
