package com.safeguard.ai.core

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A street address and the position navigation resolved it for. */
data class NaviAddress(val address: String, val latitude: Double, val longitude: Double)

/** A waypoint or the destination of the current route, with navigation's remaining time to it. */
data class RoutePlace(val name: String, val address: String, val remainSeconds: Int, val remainMeters: Int? = null)

/**
 * Position, derived speed and the current road speed limit.
 * The Vehicle SDK has no speed API (stage-0 V14); the car's own speed comes from AAOS ([CarProperties]) and the
 * speed derived from successive location fixes is only the fallback.
 */
class LocationSource(
    private val context: Context,
    private val onSignals: (SignalPatch) -> Unit,
    /** False once the car reports its own speed ([CarProperties]); position still comes from here. */
    private val useGpsSpeed: () -> Boolean = { true },
) {
    private val fused = FusedLocation(context)
    private val navi = NaviHelper(context)
    private var lastFix: LocationData? = null
    private var smoothedKmh: Double? = null

    /** Called with the waypoint's name when navigation reports arriving at it (e.g. a drowsiness rest stop). */
    var onWaypointArrived: (String) -> Unit = {}

    @Volatile private var addressRequest: CompletableDeferred<NaviAddress?>? = null

    /**
     * The waypoints and destination still ahead, nearest first; empty without a route. Navigation gives no route
     * line to apps, so these addresses are what the app knows about where the car is going.
     */
    val routeAhead: List<RoutePlace> get() = (waypoints + listOfNotNull(destination)).sortedBy { it.remainSeconds }
    @Volatile private var routing = false
    /** Where the route ends; the on-time coach reads its name ("인천국제공항") and time to arrival. */
    @Volatile var destination: RoutePlace? = null
        private set
    @Volatile private var waypoints: List<RoutePlace> = emptyList()

    private fun clearRoute() {
        destination = null
        waypoints = emptyList()
    }

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            onSignals(
                SignalPatch(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    headingDeg = location.heading,
                    speedKmh = updateSpeed(location).takeIf { useGpsSpeed() },
                )
            )
        }
        override fun onError(exception: Exception) {
            Log.w(TAG, "location error: ${exception.message}")
        }
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
            onSignals(SignalPatch(roadSpeedLimitKmh = speedLimit.takeIf { it > 0 }))
        }
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
            onSignals(SignalPatch(cautions = cautionPointList.map { Caution(it.type.name, it.remainDistance) }))
        }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            routing = routeStateInfo.isRouting
            if (!routing) clearRoute()
            onSignals(SignalPatch(traffic = routeStateInfo.toTrafficInfo()))
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            destination = destinationInfo.takeIf { it.address.isNotBlank() }
                ?.let { RoutePlace(it.name, it.address, it.remainTime, it.remainDistance) }
        }
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
            waypoints = waypointInfoList.filter { it.address.isNotBlank() }.map { RoutePlace(it.name, it.address, it.remainTime) }
        }
        override fun onRouteEnded() = clearRoute()
        override fun onRouteCancelled() = clearRoute()
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = clearRoute()
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            addressRequest?.complete(
                currentLocationInfo.address.takeIf { it.isNotBlank() }
                    ?.let { NaviAddress(it, currentLocationInfo.latitude, currentLocationInfo.longitude) }
            )
        }
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            Log.i(TAG, "arrived at waypoint ${waypointArrivedInfo.name}")
            onWaypointArrived(waypointArrivedInfo.name)
        }
        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "navi error: $errorCode")
        }
    }

    private val poller = Handler(Looper.getMainLooper())
    private val pollRouteState = object : Runnable {
        override fun run() {
            // Route traffic changes without a guaranteed callback, so ask for it periodically.
            runCatching { navi.getRouteStateInfo() }
            if (routing) runCatching {
                navi.getDestinationInfo()
                navi.getWaypointInfo()
            }
            poller.postDelayed(this, ROUTE_POLL_MS)
        }
    }

    fun start() {
        fused.initialize()
        if (context.checkSelfPermission(FUSED_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fused.registerFusedLocationCallback(locationListener)
        } else {
            Log.w(TAG, "FUSED_LOCATION not granted; speed unavailable until the activity requests it")
        }
        navi.initialize({
            navi.addListener(naviListener)
            poller.post(pollRouteState)
        }, { Log.w(TAG, "navi init failed: ${it.message}") })
    }

    /** Asks navigation for a new route (accepted detour). */
    fun requestReRoute() = onNavi("reroute") { navi.requestReRoute() }

    /** Re-plans the current route with navigation's school-zone-avoiding option (accepted offer). */
    fun avoidSchoolZones() = onNavi("avoid school zones") { navi.changeRouteOption(RouteOption.SCHOOL_ZONE) }

    private fun RouteStateInfo.toTrafficInfo(): TrafficInfo? {
        if (!isRouting) return null
        val status = trafficStatus ?: return TrafficInfo(routing = true, congestions = emptyList(), incidents = emptyList())
        return TrafficInfo(
            routing = true,
            congestions = status.congestionItemList.map {
                Congestion(it.offset, it.distance, it.trafficCongestionTime, it.avrSpeed, it.roadName, it.startCrossName, it.endCrossName)
            },
            incidents = status.trafficEventList.map { TrafficIncident(it.eventType.name, it.latitude, it.longitude, it.description) },
        )
    }

    /** Adds a stop before the destination (accepted rest-stop offer). */
    fun addWaypoint(name: String, latitude: Double, longitude: Double) = onNavi("add waypoint $name") {
        navi.addWaypoint(RequestWaypointInfo(latitude, longitude, name, WaypointIndex.FIRST, "", "", "", ""))
    }

    /** Starts guidance to a place (accepted service-centre offer). */
    fun routeTo(name: String, latitude: Double, longitude: Double) = onNavi("route to $name") {
        navi.requestRoute(RouteInfo(latitude, longitude, null, null, name, "", "", "", RouteOption.RECOMMENDED, "", false))
    }

    /** NaviHelper calls run on the main thread, like its listener callbacks. */
    private fun onNavi(what: String, block: () -> Unit) {
        poller.post {
            runCatching(block)
                .onSuccess { Log.i(TAG, "$what requested") }
                .onFailure { Log.w(TAG, "$what failed: ${it.message}") }
        }
    }

    /** The navigation's street address for where it thinks the car is, or null if it doesn't answer in time. */
    suspend fun currentAddress(timeoutMs: Long = ADDRESS_TIMEOUT_MS): NaviAddress? {
        val request = CompletableDeferred<NaviAddress?>().also { addressRequest = it }
        onNavi("address lookup") { navi.getCurrentLocationInfo() }
        return withTimeoutOrNull(timeoutMs) { request.await() }.also { addressRequest = null }
    }

    /** Called again after the user grants FUSED_LOCATION. */
    fun onLocationPermissionGranted() {
        fused.registerFusedLocationCallback(locationListener)
    }

    fun stop() {
        poller.removeCallbacksAndMessages(null)
        runCatching { fused.unregisterFusedLocationCallback(locationListener) }
        runCatching { navi.removeListener(naviListener) }
        fused.release()
        navi.release()
    }

    private fun updateSpeed(fix: LocationData): Double? {
        val prev = lastFix
        lastFix = fix
        prev ?: return smoothedKmh
        val dt = (fix.timestampMillis - prev.timestampMillis) / 1000.0
        if (dt < MIN_FIX_INTERVAL_S) return smoothedKmh
        val kmh = (distanceMeters(prev, fix) / dt * 3.6).coerceAtMost(MAX_PLAUSIBLE_KMH)
        smoothedKmh = smoothedKmh?.let { it + SMOOTHING * (kmh - it) } ?: kmh
        return smoothedKmh
    }

    private fun distanceMeters(a: LocationData, b: LocationData): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    companion object {
        const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
        private const val TAG = "SafeGuard/Location"
        private const val EARTH_RADIUS_M = 6_371_000.0
        private const val MIN_FIX_INTERVAL_S = 0.5
        private const val MAX_PLAUSIBLE_KMH = 250.0
        private const val SMOOTHING = 0.4
        private const val ROUTE_POLL_MS = 30_000L
        private const val ADDRESS_TIMEOUT_MS = 3_000L
    }
}
