package com.safeguard.ai.data.publicdata

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "SafeGuard/PublicData"

private fun Context.readAsset(path: String): String? =
    runCatching { assets.open(path).bufferedReader().use { it.readText() } }
        .onFailure { Log.w(TAG, "seed $path missing: ${it.message}") }
        .getOrNull()

private fun JSONObject.isDemo() = optJSONObject("_meta")?.optBoolean("demo") == true

/**
 * TAAS (한국도로교통공단) accident-hotspot datasets: school-zone child (15058311), holiday (15058759),
 * pedestrian child (15058925). Reads a bundled seed in either our format (`items` array) or the raw API
 * response (`items.item`). Fields used: `afos_fid`, `spot_nm`, `la_crd`, `lo_crd`, `geom_json`.
 * The polygon in `geom_json` gives the centre and radius; without it the point coordinates are used.
 */
class TaasHotspotAdapter(
    private val context: Context,
    private val assetPath: String,
    private val kind: HazardKind,
    private val source: HazardSource,
) : PublicDataAdapter {
    override val name = "TAAS:$assetPath"

    override fun load(): List<HazardPoint> {
        val root = context.readAsset(assetPath)?.let(::JSONObject) ?: return emptyList()
        val demo = root.isDemo()
        val items: JSONArray = root.optJSONArray("items")
            ?: root.optJSONObject("items")?.optJSONArray("item")
            ?: return emptyList()
        return (0 until items.length()).mapNotNull { i -> parse(items.getJSONObject(i), demo) }
            .also { Log.i(TAG, "$name: ${it.size} points${if (demo) " (demo seed)" else ""}") }
    }

    private fun parse(item: JSONObject, demo: Boolean): HazardPoint? {
        val circle = item.optString("geom_json").takeIf { it.isNotBlank() }?.let { Geo.circleOf(it) }
        val lat = circle?.lat ?: item.optDouble("la_crd").takeIf { !it.isNaN() } ?: return null
        val lon = circle?.lon ?: item.optDouble("lo_crd").takeIf { !it.isNaN() } ?: return null
        return HazardPoint(
            id = "${source.name}-${item.optString("afos_fid", "$lat,$lon")}",
            kind = kind,
            source = source,
            latitude = lat,
            longitude = lon,
            radiusMeters = circle?.radiusM ?: DEFAULT_RADIUS_M,
            name = item.optString("spot_nm").shortPlaceName(),
            confirmations = item.optInt("occrrnc_cnt", 1).coerceAtLeast(1),
            demo = demo,
        )
    }

    companion object {
        private const val DEFAULT_RADIUS_M = 100.0

        /** TAAS spot names look like "경남 창원시 의창구 ○○동(○○초교 부근)"; keep the landmark for speech. */
        internal fun String.shortPlaceName(): String {
            val landmark = substringAfter('(', "").substringBefore(')').trim()
            return landmark.ifEmpty { trim().split(' ').takeLast(1).joinToString(" ") }
        }
    }
}

/**
 * Possible black-ice points. Intended source is the KMA road-hazard weather info (도로 살얼음 발생 가능 정보);
 * until its API is confirmed the KEC freezing-section data (15151918 / 15151921) is the fallback.
 * Seed format: `{ "_meta": {...}, "points": [ { "id", "name", "lat", "lon", "radiusM" } ] }`.
 */
class RoadIceAdapter(
    private val context: Context,
    private val assetPath: String,
) : PublicDataAdapter {
    override val name = "RoadIce:$assetPath"

    override fun load(): List<HazardPoint> {
        val root = context.readAsset(assetPath)?.let(::JSONObject) ?: return emptyList()
        val demo = root.isDemo()
        val source = runCatching { HazardSource.valueOf(root.optJSONObject("_meta")?.optString("source").orEmpty()) }
            .getOrDefault(HazardSource.KEC_ICE_SECTION)
        val points = root.optJSONArray("points") ?: return emptyList()
        return (0 until points.length()).map { i ->
            val p = points.getJSONObject(i)
            HazardPoint(
                id = "${source.name}-${p.getString("id")}",
                kind = HazardKind.ICE_RISK,
                source = source,
                latitude = p.getDouble("lat"),
                longitude = p.getDouble("lon"),
                radiusMeters = p.optDouble("radiusM", 150.0),
                name = p.optString("name"),
                demo = demo,
            )
        }.also { Log.i(TAG, "$name: ${it.size} points${if (demo) " (demo seed)" else ""}") }
    }
}
