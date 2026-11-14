package com.safeguard.ai.scenario

import com.safeguard.ai.core.Caution
import com.safeguard.ai.core.Congestion
import com.safeguard.ai.core.Gear
import com.safeguard.ai.core.RoutePlace
import com.safeguard.ai.core.SignalHub
import com.safeguard.ai.core.SignalPatch
import com.safeguard.ai.core.TelltaleColor
import com.safeguard.ai.core.TelltaleLevel
import com.safeguard.ai.core.TrafficIncident
import com.safeguard.ai.core.TrafficInfo
import com.safeguard.ai.core.WiperLevel
import com.safeguard.ai.data.publicdata.Geo
import com.safeguard.ai.features.disaster.DisasterMessage
import com.safeguard.ai.features.hazard.CrowdEventKind
import com.safeguard.ai.features.ontime.FlightPlan
import com.safeguard.ai.output.Answer
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import kotlin.math.ceil

/** Hooks a scenario can use beyond signals. */
interface ScenarioHooks {
    fun injectRisk(event: RiskEvent)
    /** Another car's road event, as the server will deliver it in stage 6. */
    fun crowdEvent(kind: CrowdEventKind, lat: Double, lon: Double)
    fun restartBriefing()
    /** The driver's next spoken yes/no, so demos don't depend on the emulator microphone. */
    fun answer(answer: Answer)
    fun offerRelax()
    /** Drowsiness demo: skip the wait before the rest-stop offer, fake the navigation arrival, shorten the nap. */
    fun escalateDrowsy()
    fun arriveAtWaypoint(name: String)
    fun napSeconds(seconds: Int)
    /** The driver's next open reply ("괜찮아"); null means silence. */
    fun reply(text: String?)
    /** A scenario ended: clear any episode that outlives the injected signals (crash care). */
    fun onReset()
    /** Score demo: count a trip shorter than the usual two minutes. */
    fun scoreMinTripSeconds(seconds: Int)
    /** Maintenance demo: a service logged at [km] (ServiceItem name). */
    fun serviceRecord(item: String, km: Double)
    /** Disaster SMS demo: the car's 시군구 when the emulator's navigation gives no address; null clears it. */
    fun region(name: String?)
    /** Disaster SMS demo: a real past message replayed as if the server had just relayed it. */
    fun disaster(message: DisasterMessage)
    /** Disaster SMS demo: the waypoints and destination ahead, as navigation would give them; null clears. */
    fun routeAhead(places: List<RoutePlace>?)
    /** Logbook demo: record drives for this scenario even though the driver's switch is off (the default). */
    fun logbook(on: Boolean)
    /** On-time demo: the airport data as the server would answer (`GET /v1/flight`), instead of today's flights. */
    fun flight(plan: FlightPlan?)
}

/**
 * Replays a timed script from `assets/scenarios/<name>.json`.
 *
 * Used to inject signals the emulator can't produce (airbag, hazard, drowsiness telltale, other cars'
 * events) and to make demos repeat exactly. Injected signals go through the same rules as real ones.
 *
 * ```json
 * { "steps": [
 *   { "at": 0,    "signal": { "wiper": "HIGH", "gear": "DRIVE", "speedKmh": 90, "minuteOfDay": "08:10" } },
 *   { "at": 0,    "drive": { "from": [35.22, 128.68], "to": [35.23, 128.68], "speedKmh": 60 } },
 *   { "at": 2000, "telltale": { "ESC": "BLINK", "FCA": "ON:YELLOW" } },
 *   { "at": 3000, "crowd": { "kind": "ICE", "lat": 35.225, "lon": 128.68, "count": 3 } },
 *   { "at": 4000, "risk": { "level": "FORWARD_HAZARD", "key": "slip-1", "speech": "...", "screen": "..." } },
 *   { "at": 5000, "briefing": true },
 *   { "at": 6000, "region": "경상남도 김해시" },
 *   { "at": 6000, "ahead": [{ "name": "창원역", "address": "경상남도 창원시 의창구 의창대로 71", "remainMin": 25 }] },
 *   { "at": 7000, "disaster": { "sn": 266640, "createdAt": "2026-08-16T04:05:32Z", "regions": ["경상남도 김해시"], "step": "안전안내", "kind": "호우", "text": "..." } },
 *   { "at": 9000, "reset": true }
 * ] }
 * ```
 */
class ScenarioPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val hub: SignalHub,
    private val hooks: ScenarioHooks,
) {
    private var job: Job? = null
    private var driveJob: Job? = null

    fun available(): List<String> =
        context.assets.list(DIR).orEmpty().filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.sorted()

    fun play(name: String) {
        val steps = runCatching { load(name) }.getOrElse {
            Log.w(TAG, "cannot load scenario $name: ${it.message}")
            return
        }
        stop()
        Log.i(TAG, "play $name (${steps.size} steps)")
        job = scope.launch {
            var elapsed = 0L
            for (step in steps.sortedBy { it.optLong("at") }) {
                val at = step.optLong("at")
                if (at > elapsed) delay(at - elapsed)
                elapsed = at
                apply(step)
            }
        }
    }

    fun stop() {
        job?.cancel()
        driveJob?.cancel()
        // The route goes before the clock override: for a moment the coach would see the demo's airport at the real time.
        hooks.routeAhead(null)
        hub.clearOverrides()
        hooks.onReset()
    }

    private fun load(name: String): List<JSONObject> {
        val text = context.assets.open("$DIR/$name.json").bufferedReader().use { it.readText() }
        val array = JSONObject(text).getJSONArray("steps")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun apply(step: JSONObject) {
        if (step.optBoolean("reset")) {
            driveJob?.cancel()
            hooks.routeAhead(null)
            hub.clearOverrides()
            hooks.onReset()
            Log.i(TAG, "reset")
        }
        step.optJSONObject("signal")?.let { hub.override(parseSignal(it)) }
        step.optJSONObject("telltale")?.let { obj ->
            // "ON", "BLINK", or with the light's colour: "ON:YELLOW".
            val values = obj.keys().asSequence().map { key -> key to obj.optString(key).uppercase().split(":") }.toList()
            val map = values.mapNotNull { (key, v) -> v[0].toTelltaleLevel()?.let { key to it } }.toMap()
            val colors = values.mapNotNull { (key, v) ->
                v.getOrNull(1)?.let { c -> TelltaleColor.entries.firstOrNull { it.name == c } }?.let { key to it }
            }.toMap()
            hub.override(SignalPatch(telltales = map, telltaleColors = colors))
        }
        step.optJSONObject("drive")?.let(::drive)
        // Ends a drive leg without clearing the other injected signals (a stop before parking).
        if (step.optBoolean("stopDrive")) driveJob?.cancel()
        if (step.has("scoreMinTripSeconds")) hooks.scoreMinTripSeconds(step.getInt("scoreMinTripSeconds"))
        step.optJSONObject("serviceRecord")?.let { obj -> obj.keys().forEach { hooks.serviceRecord(it, obj.getDouble(it)) } }
        step.optJSONObject("crowd")?.let { obj ->
            val kind = CrowdEventKind.valueOf(obj.getString("kind"))
            repeat(obj.optInt("count", 1)) { hooks.crowdEvent(kind, obj.getDouble("lat"), obj.getDouble("lon")) }
            Log.i(TAG, "crowd ${obj.optInt("count", 1)}x $kind")
        }
        if (step.optBoolean("briefing")) hooks.restartBriefing()
        if (step.optBoolean("relax")) hooks.offerRelax()
        step.optStringOrNull("answer")?.let { hooks.answer(Answer.valueOf(it)) }
        if (step.optBoolean("escalateDrowsy")) hooks.escalateDrowsy()
        step.optStringOrNull("arrive")?.let { hooks.arriveAtWaypoint(it) }
        if (step.has("napSeconds")) hooks.napSeconds(step.getInt("napSeconds"))
        step.optStringOrNull("reply")?.let { hooks.reply(it) }
        if (step.optBoolean("silent")) hooks.reply(null)
        step.optStringOrNull("region")?.let { hooks.region(it) }
        if (step.has("logbook")) hooks.logbook(step.getBoolean("logbook"))
        step.optJSONObject("flight")?.let { hooks.flight(FlightPlan.parse(it.toString())) }
        step.optJSONArray("ahead")?.let { arr ->
            hooks.routeAhead((0 until arr.length()).map { i ->
                arr.getJSONObject(i).let {
                    RoutePlace(
                        it.getString("name"), it.getString("address"), it.getInt("remainMin") * 60,
                        remainMeters = it.optDoubleOrNull("remainKm")?.let { km -> (km * 1000).toInt() },
                    )
                }
            })
        }
        step.optJSONObject("disaster")?.let { obj ->
            val regions = obj.getJSONArray("regions")
            hooks.disaster(
                DisasterMessage(
                    sn = obj.getLong("sn"),
                    createdAt = Instant.parse(obj.getString("createdAt")).toEpochMilli(),
                    regions = (0 until regions.length()).map { regions.getString(it) },
                    step = obj.optString("step"),
                    kind = obj.optString("kind"),
                    text = obj.getString("text"),
                    replay = true,
                )
            )
        }
        step.optJSONObject("traffic")?.let { hub.override(SignalPatch(traffic = parseTraffic(it))) }
        step.optJSONObject("risk")?.let { obj ->
            hooks.injectRisk(
                RiskEvent(
                    key = obj.getString("key"),
                    level = RiskLevel.valueOf(obj.getString("level")),
                    speech = obj.getString("speech"),
                    screenText = obj.optString("screen", obj.getString("speech")),
                    cooldownMs = obj.optLong("cooldownMs", 0L),
                )
            )
        }
    }

    /** Moves the car along a straight line at a constant speed, one position update per second. */
    private fun drive(o: JSONObject) {
        val from = o.getJSONArray("from")
        val to = o.getJSONArray("to")
        val (lat1, lon1) = from.getDouble(0) to from.getDouble(1)
        val (lat2, lon2) = to.getDouble(0) to to.getDouble(1)
        val speedKmh = o.optDouble("speedKmh", 60.0)
        val heading = Geo.bearingDeg(lat1, lon1, lat2, lon2)
        val total = Geo.distanceMeters(lat1, lon1, lat2, lon2)
        val stepMeters = speedKmh / 3.6 * (DRIVE_TICK_MS / 1000.0)
        val ticks = ceil(total / stepMeters).toInt()
        driveJob?.cancel()
        driveJob = scope.launch {
            for (i in 0..ticks) {
                val (lat, lon) = Geo.offset(lat1, lon1, heading, (i * stepMeters).coerceAtMost(total))
                hub.override(SignalPatch(latitude = lat, longitude = lon, headingDeg = heading.toFloat(), speedKmh = speedKmh))
                delay(DRIVE_TICK_MS)
            }
        }
    }

    private fun parseTraffic(o: JSONObject) = TrafficInfo(
        routing = o.optBoolean("routing", true),
        congestions = o.optJSONArray("congestions")?.let { arr ->
            (0 until arr.length()).map { i ->
                arr.getJSONObject(i).let {
                    Congestion(
                        offsetMeters = it.getDouble("offsetM"),
                        lengthMeters = it.getDouble("lengthM"),
                        timeSeconds = it.getLong("timeSec"),
                        avgKmh = it.getDouble("avgKmh"),
                        road = it.optString("road"),
                        fromCross = it.optString("from"),
                        toCross = it.optString("to"),
                    )
                }
            }
        }.orEmpty(),
        incidents = o.optJSONArray("incidents")?.let { arr ->
            (0 until arr.length()).map { i ->
                arr.getJSONObject(i).let { TrafficIncident(it.getString("type"), it.getDouble("lat"), it.getDouble("lon"), it.optString("description")) }
            }
        }.orEmpty(),
    )

    private fun parseSignal(o: JSONObject) = SignalPatch(
        wiper = o.optStringOrNull("wiper")?.let { WiperLevel.valueOf(it) },
        steeringDeg = o.optDoubleOrNull("steeringDeg")?.toFloat(),
        gear = o.optStringOrNull("gear")?.let { Gear.valueOf(it) },
        turnSignal = o.optStringOrNull("turnSignal"),
        airbagDeployed = o.optBooleanOrNull("airbagDeployed"),
        hazardOn = o.optBooleanOrNull("hazardOn"),
        lowBeamOn = o.optBooleanOrNull("lowBeamOn"),
        frontFogOn = o.optBooleanOrNull("frontFogOn"),
        drivingMode = o.optStringOrNull("drivingMode"),
        washerFluidLow = o.optBooleanOrNull("washerFluidLow"),
        cautions = o.optJSONArray("cautions")?.let { arr ->
            (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Caution(it.getString("type"), it.getInt("remainMeters")) } }
        },
        speedKmh = o.optDoubleOrNull("speedKmh"),
        latitude = o.optDoubleOrNull("latitude"),
        longitude = o.optDoubleOrNull("longitude"),
        headingDeg = o.optDoubleOrNull("headingDeg")?.toFloat(),
        roadSpeedLimitKmh = o.optDoubleOrNull("roadSpeedLimitKmh")?.toInt(),
        outsideTempC = o.optDoubleOrNull("outsideTempC"),
        minuteOfDayOverride = o.optStringOrNull("minuteOfDay")?.toMinuteOfDay(),
        holidayOverride = o.optBooleanOrNull("holiday"),
        drivingMinutes = o.optDoubleOrNull("drivingMinutes")?.toInt(),
        cruiseActive = o.optBooleanOrNull("cruiseActive"),
        cruiseTargetKmh = o.optDoubleOrNull("cruiseTargetKmh"),
        odometerKm = o.optDoubleOrNull("odometerKm"),
        engineOilLow = o.optBooleanOrNull("engineOilLow"),
        brakeFluidLow = o.optBooleanOrNull("brakeFluidLow"),
        seatBeltUnbuckled = o.optBooleanOrNull("seatBeltUnbuckled"),
        evSocPercent = o.optDoubleOrNull("evSocPercent"),
        openWindows = o.optJSONArray("openWindows")?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() },
    )

    companion object {
        private const val TAG = "SafeGuard/Scenario"
        private const val DIR = "scenarios"
        private const val DRIVE_TICK_MS = 1_000L
    }
}

private fun String.toTelltaleLevel(): TelltaleLevel? = when (uppercase()) {
    "ON" -> TelltaleLevel.ON
    "BLINK" -> TelltaleLevel.BLINK
    else -> null
}

/** "08:10" → 490. */
private fun String.toMinuteOfDay(): Int? = split(":").takeIf { it.size == 2 }?.let { (h, m) -> h.toIntOrNull()?.times(60)?.plus(m.toIntOrNull() ?: 0) }

private fun JSONObject.optStringOrNull(key: String) = if (has(key) && !isNull(key)) getString(key) else null
private fun JSONObject.optDoubleOrNull(key: String) = if (has(key) && !isNull(key)) getDouble(key) else null
private fun JSONObject.optBooleanOrNull(key: String) = if (has(key) && !isNull(key)) getBoolean(key) else null
