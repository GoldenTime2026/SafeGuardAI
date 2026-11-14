package com.safeguard.ai.features.share

import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.data.publicdata.Geo
import com.safeguard.ai.data.publicdata.HazardKind
import com.safeguard.ai.data.publicdata.HazardPoint
import com.safeguard.ai.data.publicdata.HazardSource
import com.safeguard.ai.features.crash.GuardianContact
import com.safeguard.ai.features.crash.GuardianNotifier
import com.safeguard.ai.features.disaster.DisasterMessage
import com.safeguard.ai.features.hazard.HazardMap
import com.safeguard.ai.features.ontime.FlightPlan
import com.safeguard.ai.features.ontime.FlightQuery
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Talks to the hazard-sharing server (`server/safeguard_server.py`).
 * - [share]: this car's reports go up at once; failures wait in a small queue and retry with the next poll.
 * - [start]: every [POLL_MS] asks for confirmed hazards around the car and puts them on the [HazardMap],
 *   where the hazard rule warns about the ones ahead in the same direction, and for new disaster SMS.
 * The app never depends on it: with no server or no network, everything else works as before.
 *
 * Privacy: no VIN, account or name; a random device token that changes every day; the poll asks for a
 * ~5 km grid cell around the car, not its exact position.
 */
class SharingClient(
    context: Context,
    private val scope: CoroutineScope,
    private val baseUrl: String,
    private val map: HazardMap,
    private val snapshots: StateFlow<VehicleSnapshot>,
    private val guardianContact: () -> GuardianContact?,
) : HazardSharing {

    private val _online = MutableStateFlow<Boolean?>(null)
    /** Whether the last request reached the server; null before the first one. Shown on the driving screen. */
    val online: StateFlow<Boolean?> = _online.asStateFlow()

    private val prefs = context.getSharedPreferences("sharing", Context.MODE_PRIVATE)
    private val queue = ArrayDeque<HazardReport>()
    /** What this car sent, so the server doesn't warn it about its own stop or crash. */
    private val ownReports = ArrayDeque<HazardReport>()

    override fun share(report: HazardReport) {
        synchronized(ownReports) {
            if (ownReports.size >= MAX_QUEUE) ownReports.removeFirst()
            ownReports.addLast(report)
        }
        scope.launch(Dispatchers.IO) {
            if (!post(report)) synchronized(queue) {
                if (queue.size >= MAX_QUEUE) queue.removeFirst()
                queue.addLast(report)
            }
            poll()
        }
    }

    /** One lit warning light for the model statistics; only the model ("현대 2024년식") and the light type are sent. */
    fun reportTelltale(model: String, telltale: String) {
        scope.launch(Dispatchers.IO) {
            request("POST", "/v1/telltales", JSONObject().put("model", model).put("telltale", telltale).toString())
        }
    }

    /** New disaster SMS for the whole country; the car keeps those for its own area ([DisasterBriefing]). */
    @Volatile var onDisasterMessages: (List<DisasterMessage>) -> Unit = {}
    private var lastDisasterSn = 0L

    fun start() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                retryQueued()
                poll()
                pollDisasters()
                delay(POLL_MS)
            }
        }
    }

    /** Asks only for messages after the last one seen, so the request says nothing about where the car is. */
    private fun pollDisasters() {
        val answer = request("GET", "/v1/disaster-messages?after=$lastDisasterSn", null) ?: return
        val messages = parseDisasters(answer)
        if (messages.isEmpty()) return
        lastDisasterSn = messages.maxOf { it.sn }
        Log.i(TAG, "received ${messages.size} disaster SMS up to $lastDisasterSn")
        onDisasterMessages(messages)
    }

    private fun retryQueued() {
        val pending = synchronized(queue) { queue.toList().also { queue.clear() } }
        pending.filterNot(::post).forEach { synchronized(queue) { queue.addLast(it) } }
    }

    private fun post(report: HazardReport): Boolean {
        val body = JSONObject()
            .put("type", report.type)
            .put("lat", report.latitude)
            .put("lon", report.longitude)
            .put("heading", report.headingDeg?.toDouble() ?: JSONObject.NULL)
            .put("source", report.source.name)
            .put("device", deviceToken())
        return request("POST", "/v1/reports", body.toString())
            ?.also { Log.i(TAG, "shared ${report.type}: $it") } != null
    }

    private fun poll() {
        val s = snapshots.value
        val lat = s.latitude ?: return
        val lon = s.longitude ?: return
        val (cellLat, cellLon) = cellCenter(lat, lon)
        val answer = request("GET", "/v1/hazards?lat=$cellLat&lon=$cellLon&radius=$POLL_RADIUS_M", null) ?: return
        val hazards = JSONObject(answer).optJSONArray("hazards") ?: return
        val own = synchronized(ownReports) { ownReports.toList() }
        val now = System.currentTimeMillis()
        val points = (0 until hazards.length()).map { i ->
            hazards.getJSONObject(i).let {
                HazardPoint(
                    id = "shared-${it.getString("id")}",
                    kind = HazardKind.SHARED,
                    source = HazardSource.SHARED,
                    latitude = it.getDouble("lat"),
                    longitude = it.getDouble("lon"),
                    radiusMeters = SHARED_RADIUS_M,
                    name = it.getString("type"),
                    confirmations = it.optInt("cars", 1),
                    updatedAt = it.optLong("updatedAt"),
                    expiresAt = it.optLong("expiresAt").takeIf { t -> t > 0 },
                    headingDeg = if (it.isNull("heading")) null else it.getDouble("heading").toFloat(),
                )
            }
        }.filterNot { isOwnEcho(it, own, now) }
        map.replaceShared(points)
        if (points.isNotEmpty()) Log.i(TAG, "received ${points.size} shared hazards: ${points.map { it.name }}")
    }

    /**
     * Guardian alerts go through the same server, which holds the SMS provider. The number is sent only
     * with the alert itself; without an SMS provider the server records the alert and answers "not delivered".
     */
    val guardian = object : GuardianNotifier {
        @Volatile private var note = "시연 모드라 발송하지 않음"
        override val name: String get() = guardianContact()?.name ?: "보호자"
        override val undeliveredNote: String get() = note

        override suspend fun notify(message: String): Boolean = withContext(Dispatchers.IO) {
            val contact = guardianContact()
            if (contact == null) {
                note = "연락처 없음 · 정차 중 설정에서 등록"
                return@withContext false
            }
            val body = JSONObject().put("message", message).put("to", contact.phone).put("name", contact.name)
            // The server answers only after the carrier confirms the phone has the message (or gives up).
            val answer = request("POST", "/v1/guardian-alerts", body.toString(), GUARDIAN_TIMEOUT_MS)
            if (answer == null) {
                note = "서버 연결 실패 · 화면의 문자를 대신 보내 주세요"
                return@withContext false
            }
            val result = JSONObject(answer)
            note = result.optString("note").ifEmpty { "문자 서비스 미설정 · 서버에 기록만 됨" }
            result.optBoolean("delivered")
        }
    }

    /**
     * The flight the driver is driving to, from the airport operators' live data via the server; null when the server
     * is unreachable or has no airport key. Only the airport, the flight or time and the driver's words are sent.
     */
    suspend fun flight(q: FlightQuery): FlightPlan? = withContext(Dispatchers.IO) {
        val params = listOfNotNull(
            "airport" to q.airport,
            q.flight?.let { "flight" to it },
            q.time?.let { "time" to it },
            q.words.takeIf { it.isNotBlank() }?.let { "words" to it },
            q.hour?.let { "hour" to it.toString() },
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        request("GET", "/v1/flight?$params", null, FLIGHT_TIMEOUT_MS)?.let { runCatching { FlightPlan.parse(it) }.getOrNull() }
    }

    private fun request(method: String, path: String, body: String?, readTimeoutMs: Int = TIMEOUT_MS): String? = runCatching {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = readTimeoutMs
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = connection.responseCode
        _online.value = true
        val text = (if (code < 400) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }
        connection.disconnect()
        if (code >= 400) error("HTTP $code $text")
        text
    }.onFailure {
        // An HTTP error still means the server is reachable; only a network failure counts as offline.
        if (it is java.io.IOException) _online.value = false
        Log.w(TAG, "$method $path failed: ${it.message}")
    }.getOrNull()

    /** A random token per day: reports from one car can be counted once, but not followed across days. */
    private fun deviceToken(): String {
        val today = LocalDate.now().toString()
        if (prefs.getString("day", null) != today) {
            prefs.edit().putString("day", today).putString("token", UUID.randomUUID().toString()).apply()
        }
        return prefs.getString("token", null)!!
    }

    companion object {
        private const val TAG = "SafeGuard/Share"
        private const val OWN_ECHO_M = 150.0
        private const val OWN_ECHO_MS = 60 * 60_000L

        /** A shared hazard of the same type near a recent report of this car is that report coming back. */
        internal fun isOwnEcho(point: HazardPoint, own: List<HazardReport>, now: Long): Boolean = own.any {
            it.type == point.name && now - it.at < OWN_ECHO_MS &&
                Geo.distanceMeters(it.latitude, it.longitude, point.latitude, point.longitude) <= OWN_ECHO_M
        }
        private const val POLL_MS = 20_000L
        private const val TIMEOUT_MS = 4_000
        /** Above the server's 12 s wait for the carrier result, so a slow carrier is not taken as a dead server. */
        private const val GUARDIAN_TIMEOUT_MS = 20_000
        /** A spoken-time lookup reads a few pages of the airport's departures (cached for two minutes on the server). */
        private const val FLIGHT_TIMEOUT_MS = 15_000
        private const val POLL_RADIUS_M = 8_000
        private const val SHARED_RADIUS_M = 50.0
        private const val MAX_QUEUE = 20
        private const val CELL_DEG = 0.05

        internal fun parseDisasters(json: String): List<DisasterMessage> {
            val array = JSONObject(json).optJSONArray("messages") ?: return emptyList()
            return (0 until array.length()).map { i ->
                array.getJSONObject(i).let { m ->
                    val regions = m.getJSONArray("regions")
                    DisasterMessage(
                        sn = m.getLong("sn"),
                        createdAt = m.getLong("createdAt"),
                        regions = (0 until regions.length()).map { regions.getString(it) },
                        step = m.optString("step"),
                        kind = m.optString("kind"),
                        text = m.getString("text"),
                    )
                }
            }
        }

        /** Rounds a position to the centre of a ~5 km grid cell. */
        internal fun cellCenter(lat: Double, lon: Double): Pair<Double, Double> {
            fun snap(v: Double) = ((v / CELL_DEG).roundToInt() * CELL_DEG * 1e4).roundToInt() / 1e4
            return snap(lat) to snap(lon)
        }
    }
}
