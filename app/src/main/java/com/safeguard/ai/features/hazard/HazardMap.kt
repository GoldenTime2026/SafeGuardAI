package com.safeguard.ai.features.hazard

import com.safeguard.ai.data.publicdata.Geo
import com.safeguard.ai.data.publicdata.HazardKind
import com.safeguard.ai.data.publicdata.HazardPoint
import com.safeguard.ai.data.publicdata.HazardSource
import com.safeguard.ai.data.publicdata.PublicDataAdapter
import android.util.Log

/** A road event reported by a car (this one, or others via scenario now and the server in stage 6). */
enum class CrowdEventKind { SLIP, ICE, HAZARD_LIGHTS }

/**
 * Feature ② local version: the hazard map without a server.
 *
 * - Public points (TAAS hotspots, road-ice points) are loaded from bundled seeds.
 * - A slip/ice event near a public ice point raises that point's confidence (cross-validation).
 * - Events away from any public point accumulate; [CROWD_MIN_EVENTS] within [CROWD_WINDOW_MS]
 *   create a "crowd-detected point" — covering roads without weather stations.
 * - A hazard-lights stop becomes a point immediately (digital warning triangle).
 */
class HazardMap(private val clock: () -> Long = System::currentTimeMillis) {

    private data class Pending(val lat: Double, val lon: Double, val at: Long, val kind: CrowdEventKind)

    private val points = linkedMapOf<String, HazardPoint>()
    private val pending = mutableListOf<Pending>()
    private var crowdSeq = 0

    @Synchronized
    fun load(adapters: List<PublicDataAdapter>) {
        adapters.flatMap { runCatching { it.load() }.getOrDefault(emptyList()) }.forEach { points[it.id] = it }
        Log.i(TAG, "loaded ${points.size} public points")
    }

    @Synchronized
    fun add(point: HazardPoint) {
        points[point.id] = point
    }

    /** Replaces every point received from the sharing server with the latest answer. */
    @Synchronized
    fun replaceShared(shared: List<HazardPoint>) {
        points.values.removeAll { it.kind == HazardKind.SHARED }
        shared.forEach { points[it.id] = it }
    }

    @Synchronized
    fun active(now: Long = clock()): List<HazardPoint> {
        points.values.removeAll { it.expiresAt != null && it.expiresAt < now }
        return points.values.toList()
    }

    @Synchronized
    fun report(kind: CrowdEventKind, lat: Double, lon: Double, at: Long = clock()) {
        if (kind == CrowdEventKind.HAZARD_LIGHTS) {
            val id = "crowd-hazard-${crowdSeq++}"
            points[id] = HazardPoint(
                id = id, kind = HazardKind.CROWD_HAZARD_LIGHTS, source = HazardSource.CROWD,
                latitude = lat, longitude = lon, radiusMeters = 50.0, name = "비상등 정차 차량",
                updatedAt = at, expiresAt = at + HAZARD_TTL_MS,
            )
            return
        }

        // Cross-validation: a real slip near a public ice point confirms it.
        points.values.firstOrNull { it.kind == HazardKind.ICE_RISK && it.contains(lat, lon, MATCH_MARGIN_M) }?.let { p ->
            points[p.id] = p.copy(confirmations = p.confirmations + 1, updatedAt = at)
            Log.i(TAG, "confirmed public ice point ${p.id} (${p.confirmations + 1})")
            return
        }

        // Refresh an existing crowd point instead of creating a duplicate.
        points.values.firstOrNull { it.kind == HazardKind.CROWD_SLIP && it.contains(lat, lon, MATCH_MARGIN_M) }?.let { p ->
            points[p.id] = p.copy(confirmations = p.confirmations + 1, updatedAt = at, expiresAt = at + CROWD_TTL_MS)
            return
        }

        pending.removeAll { at - it.at > CROWD_WINDOW_MS }
        pending += Pending(lat, lon, at, kind)
        val cluster = pending.filter { Geo.distanceMeters(lat, lon, it.lat, it.lon) <= CLUSTER_RADIUS_M }
        if (cluster.size >= CROWD_MIN_EVENTS) {
            val id = "crowd-slip-${crowdSeq++}"
            points[id] = HazardPoint(
                id = id, kind = HazardKind.CROWD_SLIP, source = HazardSource.CROWD,
                latitude = cluster.map { it.lat }.average(), longitude = cluster.map { it.lon }.average(),
                radiusMeters = CLUSTER_RADIUS_M, name = if (cluster.any { it.kind == CrowdEventKind.ICE }) "결빙 감지 구간" else "미끄러짐 감지 구간",
                confirmations = cluster.size, updatedAt = at, expiresAt = at + CROWD_TTL_MS,
            )
            pending.removeAll(cluster)
            Log.i(TAG, "crowd-detected point $id from ${cluster.size} events")
        }
    }

    private fun HazardPoint.contains(lat: Double, lon: Double, margin: Double) =
        Geo.distanceMeters(latitude, longitude, lat, lon) <= radiusMeters + margin

    companion object {
        private const val TAG = "SafeGuard/HazardMap"
        const val CROWD_MIN_EVENTS = 3
        const val CROWD_WINDOW_MS = 2 * 60 * 60_000L
        const val CROWD_TTL_MS = 2 * 60 * 60_000L
        const val HAZARD_TTL_MS = 60 * 60_000L
        private const val CLUSTER_RADIUS_M = 150.0
        private const val MATCH_MARGIN_M = 100.0
    }
}
