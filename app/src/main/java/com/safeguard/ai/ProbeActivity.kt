package com.safeguard.ai

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.DrivingModeType
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatAirbagDeployedListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stage-0 probe: checks V1–V14 against the Pleos emulator and Virtual Console.
 * Every result is shown on screen and logged under [TAG] so it can be verified from adb.
 * Control probes can also be triggered from adb: `am start -n com.safeguard.ai/.ProbeActivity --es probe V9`.
 */
class ProbeActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    // Vehicle IPC can be slow on a loaded emulator; keep it off the UI thread to avoid ANRs.
    private val workerThread = HandlerThread("vehicle-probe").apply { start() }
    private val worker = Handler(workerThread.looper)
    private val rows = linkedMapOf<String, TextView>()

    private lateinit var vehicle: Vehicle
    private lateinit var fusedLocation: FusedLocation
    private lateinit var naviHelper: NaviHelper
    private var tts: TextToSpeech? = null

    private val frontWindshield = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)

    private var lastFix: LocationData? = null
    private var ambientIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        vehicle = Vehicle(this).also { it.initialize() }
        fusedLocation = FusedLocation(this).also { it.initialize() }
        naviHelper = NaviHelper(this)

        // Vehicle.initialize() has no completion callback and calls made before
        // ServiceConnected are dropped, so give the bind time to finish first.
        worker.postDelayed({
            startReadProbes()
            handleProbeIntent(intent)
        }, SERVICE_BIND_DELAY_MS)
        main.postDelayed({ requestLocationPermission() }, SERVICE_BIND_DELAY_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        worker.post { handleProbeIntent(intent) }
    }

    override fun onDestroy() {
        worker.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        tts?.release()
        naviHelper.release()
        fusedLocation.release()
        vehicle.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): ScrollView {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }
        column.addView(TextView(this).apply {
            text = "SafeGuard AI · Stage 0 Probe"
            textSize = 30f
            setTextColor(Color.WHITE)
        })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "V2 제상 ON" to "V2",
            "V9 조명 색" to "V9",
            "V13 등받이" to "V13",
            "V7 음성" to "V7",
            "V8 네트워크" to "V8",
        ).forEach { (label, probe) ->
            buttons.addView(Button(this).apply {
                text = label
                textSize = 20f
                setOnClickListener { launchControl(probe) }
            })
        }
        column.addView(buttons)

        listOf(
            "V1" to "와이퍼", "V2" to "제상(HVAC set)", "V3" to "경고등", "V4" to "에어백",
            "V5" to "위치", "V6" to "내비 이벤트", "V7" to "TTS", "V8" to "네트워크",
            "V9" to "앰비언트 라이트", "V10" to "핸들각", "V11" to "비상등", "V12" to "주행 모드",
            "V13" to "등받이", "V14" to "속도(위치 기반)", "GEAR" to "기어",
        ).forEach { (key, label) ->
            val row = TextView(this).apply {
                text = "$key $label: 대기"
                textSize = 22f
                setTextColor(Color.LTGRAY)
                setPadding(0, 12, 0, 12)
            }
            rows[key] = row
            column.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        return ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(column)
        }
    }

    private val lastReportAt = ConcurrentHashMap<String, Long>()
    private var lastReconnectAt = 0L

    private fun report(key: String, ok: Boolean?, message: String) {
        val mark = when (ok) { true -> "OK"; false -> "FAIL"; null -> "INFO" }
        lastReportAt[key] = System.currentTimeMillis()
        Log.i(TAG, "$key $mark $message")
        main.post {
            rows[key]?.apply {
                text = "$key [$mark] $message"
                setTextColor(when (ok) { true -> Color.GREEN; false -> Color.RED; null -> Color.YELLOW })
            }
        }
    }

    private fun fail(key: String, e: Exception) {
        report(key, false, "${e.javaClass.simpleName}: ${e.message}")
        if (e.message == ERROR_SERVICE_CONNECTION_FAILURE) scheduleReconnect()
    }

    /** The vehicle service drops under load (40001); rebind and re-subscribe, at most every 20 s. */
    private fun scheduleReconnect() {
        val now = System.currentTimeMillis()
        if (now - lastReconnectAt < RECONNECT_MIN_INTERVAL_MS) return
        lastReconnectAt = now
        report("INFO", null, "vehicle service lost (40001) → reconnecting")
        worker.post {
            runCatching { vehicle.release() }
            vehicle = Vehicle(this).also { it.initialize() }
            worker.postDelayed({ startReadProbes() }, SERVICE_BIND_DELAY_MS)
        }
    }

    /** Runs a control probe on its own thread so one hung IPC call can't block the others. */
    private fun launchControl(probe: String) {
        val key = probe.removeSuffix("OFF")
        val start = System.currentTimeMillis()
        Thread({ guarded(key) { runControlProbe(probe) } }, "control-$probe").start()
        main.postDelayed({
            if ((lastReportAt[key] ?: 0L) < start) report(key, false, "TIMEOUT: ${CONTROL_TIMEOUT_MS / 1000}초 내 응답 없음")
        }, CONTROL_TIMEOUT_MS)
    }

    // ---------------------------------------------------------- read probes

    private fun startReadProbes() {
        // Each probe is isolated so one SecurityException doesn't stop the others.
        guarded("V1") {
            vehicle.getWiper().getCurrentWiperSwitch(frontWindshield, { report("V1", true, "switch=$it") }, { fail("V1", it) })
            vehicle.getWiper().registerWiperState(frontWindshield, object : WiperStateListener {
                override fun onWiperStateUpdated(state: WiperState?) {
                    vehicle.getWiper().getCurrentWiperSwitch(frontWindshield,
                        { report("V1", true, "state=$state switch=$it") },
                        { report("V1", true, "state=$state (switch read failed: ${it.message})") })
                }
                override fun onFailed(e: Exception) = fail("V1", e)
            })
        }

        // V3 telltales + V11 hazard (left and right turn-signal telltales blinking together).
        // registerTelltales needs PRIVATE_CAR_SAFETY, so poll getTelltalesInfo (CAR_SAFETY) instead.
        pollTelltales()
        guarded("V11") {
            vehicle.getTurnSignal().registerTurnSignalState(object : TurnSignalStateListener {
                override fun onTurnSignalStateUpdated(value: TurnSignalValue?) = report("V11", null, "turnSignal=$value")
                override fun onFailed(e: Exception) = fail("V11", e)
            })
        }

        guarded("V4") {
            vehicle.getSafety().hasSeatAirbagBeenDeployed(driverSeat, { report("V4", true, "deployed=$it") }, { fail("V4", it) })
            vehicle.getSafety().registerSeatAirbagDeployed(driverSeat, object : SeatAirbagDeployedListener {
                override fun onSeatAirbagDeployUpdated(deployed: Boolean?) = report("V4", true, "deployed=$deployed (event)")
                override fun onFailed(e: Exception) = fail("V4", e)
            })
        }

        guarded("V10") {
            vehicle.getSteeringWheel().registerSteeringWheelAngle(object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(angle: Float?) = report("V10", true, "angle=$angle°")
                override fun onFailed(e: Exception) = fail("V10", e)
            })
        }

        guarded("V12") {
            vehicle.getDrivingMode().getCurrentDrivingModeState({ report("V12", true, "mode=$it") }, { fail("V12", it) })
            vehicle.getDrivingMode().registerDrivingModeState(object : DrivingModeStateListener {
                override fun onDrivingModeStateUpdated(mode: DrivingModeType?) = report("V12", true, "mode=$mode (event)")
                override fun onFailed(e: Exception) = fail("V12", e)
            })
        }

        // Gear (used later to confirm the car is parked before power nap)
        guarded("GEAR") {
            vehicle.getGear().getCurrentGearSelectionState({ report("GEAR", true, "gear=$it") }, { fail("GEAR", it) })
            vehicle.getGear().registerGearSelection(object : GearSelectionListener {
                override fun onSelected(gear: VehicleGear?) = report("GEAR", true, "gear=$gear (event)")
                override fun onFailed(e: Exception) = fail("GEAR", e)
            })
        }

        guarded("V6") {
            naviHelper.initialize({
                report("V6", null, "NaviHelper connected, waiting for events")
                naviHelper.addListener(object : NaviHelperEventListener {
                    override fun onCurrentRoadSpeedLimit(limit: Int) = report("V6", true, "speedLimit=$limit")
                    override fun onCautionPointInfo(points: List<CautionPoint>) = report("V6", true, "caution=$points")
                    override fun onCameraAlert(alerts: List<CameraAlert>) = report("V6", true, "camera=$alerts")
                    override fun onRouteStateInfo(info: RouteStateInfo) = report("V6", true, "routeState=$info")
                    override fun onCurrentLocationInfo(info: CurrentLocationInfo) = report("V6", null, "location=$info")
                    override fun onError(code: NaviErrorCode) = report("V6", false, "error=$code")
                })
            }, { report("V6", false, "init failed: ${it.message}") })
        }

        report("V14", null, "Vehicle SDK에 속도 API 없음 → 위치 변화로 계산 (V5 대기)")
    }

    private inline fun guarded(key: String, block: () -> Unit) {
        val start = System.currentTimeMillis()
        Log.d(TAG, "$key -> call on ${Thread.currentThread().name}")
        try {
            block()
        } catch (e: Exception) {
            fail(key, e)
        }
        Log.d(TAG, "$key <- returned in ${System.currentTimeMillis() - start}ms")
    }

    private var lastTelltaleSummary: String? = null

    private fun pollTelltales() {
        vehicle.getSafety().getTelltalesInfo({ info -> onTelltales(info) }, { fail("V3", it) })
        worker.postDelayed({ pollTelltales() }, TELLTALE_POLL_MS)
    }

    private fun onTelltales(info: Telltales?) {
        val active = info?.telltales.orEmpty().filter { it.state != TelltaleState.OFF }
        val summary = if (active.isEmpty()) "all off" else active.joinToString { "${it.type}=${it.state}" }
        if (summary == lastTelltaleSummary) return
        lastTelltaleSummary = summary
        report("V3", true, "$summary (poll, total=${info?.telltales?.size ?: 0})")
        val left = active.any { it.type.name == "TURN_SIGNAL_LEFT" && it.state == TelltaleState.BLINK }
        val right = active.any { it.type.name == "TURN_SIGNAL_RIGHT" && it.state == TelltaleState.BLINK }
        report("V11", true, if (left && right) "HAZARD ON (좌우 동시 점멸)" else "hazard off (L=$left R=$right)")
    }

    // V5 location + V14 derived speed
    private fun startLocationProbe() {
        report("V5", null, "supported=${fusedLocation.isSupported()}")
        fusedLocation.registerFusedLocationCallback(object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                if (location == null) {
                    report("V5", false, "location=null (no fix yet or access blocked)")
                    return
                }
                report("V5", true, "lat=%.6f lon=%.6f heading=%.0f".format(location.latitude, location.longitude, location.heading))
                lastFix?.let { prev ->
                    val dt = (location.timestampMillis - prev.timestampMillis) / 1000.0
                    if (dt > 0) {
                        val kmh = distanceMeters(prev, location) / dt * 3.6
                        report("V14", true, "%.1f km/h (위치 기반)".format(kmh))
                    }
                }
                lastFix = location
            }
            override fun onError(e: Exception) = fail("V5", e)
        })
    }

    private fun requestLocationPermission() {
        if (checkSelfPermission(FUSED_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationProbe()
        } else {
            requestPermissions(arrayOf(FUSED_LOCATION), REQ_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOCATION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startLocationProbe()
        else report("V5", false, "FUSED_LOCATION permission denied")
    }

    // -------------------------------------------------------- control probes

    private fun handleProbeIntent(intent: Intent?) {
        intent?.getStringExtra("probe")?.let { launchControl(it) }
    }

    private fun runControlProbe(probe: String) {
        when (probe) {
            "V2" -> vehicle.getHVAC().setHvacDefroster(frontWindshield, true,
                { _, on -> report("V2", true, "defroster set=$on") }, { fail("V2", it) })

            "V2OFF" -> vehicle.getHVAC().setHvacDefroster(frontWindshield, false,
                { _, on -> report("V2", true, "defroster set=$on") }, { fail("V2", it) })

            "V9" -> {
                // The driver seat area isn't supported for ambient light on the emulator; use whatever the car reports.
                val (name, color) = AMBIENT_COLORS[ambientIndex++ % AMBIENT_COLORS.size]
                vehicle.getLight().checkAmbientLightBrightnessColorCapabilities({ config, areas ->
                    report("V9", null, "supported areas=${areas.map { it.values.toList() }} config=$config")
                    val area = areas.firstOrNull() ?: return@checkAmbientLightBrightnessColorCapabilities report("V9", false, "no supported area")
                    vehicle.getLight().setAmbientLightBrightnessColor(area, color,
                        { _, applied -> report("V9", true, "color=$name (0x%08X) area=${area.values.toList()}".format(applied)) },
                        { fail("V9", it) })
                }, { fail("V9", it) })
            }

            "V13" -> vehicle.getSeat().moveSeatBackrestAngle(driverSeat, MoveControl.BACKWARD, { _, _ ->
                report("V13", null, "backrest moving backward")
                main.postDelayed({
                    vehicle.getSeat().moveSeatBackrestAngle(driverSeat, MoveControl.STOP, { _, _ ->
                        vehicle.getSeat().getCurrentSeatBackrestAnglePosition(driverSeat,
                            { report("V13", true, "stopped, position=$it") }, { fail("V13", it) })
                    }, { fail("V13", it) })
                }, 2000)
            }, { fail("V13", it) })

            // Stage-0 V15: which standard AAOS properties a 3rd-party app can actually read.
            "V15" -> Thread {
                val car = android.car.Car.createCar(this)
                val props = car.getCarManager(android.car.Car.PROPERTY_SERVICE) as android.car.hardware.property.CarPropertyManager
                val ids = android.car.VehiclePropertyIds::class.java
                listOf(
                    "PERF_VEHICLE_SPEED", "ENV_OUTSIDE_TEMPERATURE", "NIGHT_MODE", "GEAR_SELECTION", "PARKING_BRAKE_ON",
                    "IGNITION_STATE", "FUEL_LEVEL", "EV_BATTERY_LEVEL", "RANGE_REMAINING", "WHEEL_TICK",
                    "ABS_ACTIVE", "TRACTION_CONTROL_ACTIVE", "HAZARD_LIGHTS_STATE", "FORWARD_COLLISION_WARNING_STATE",
                    "HANDS_ON_DETECTION_DRIVER_STATE", "CRITICALLY_LOW_TIRE_PRESSURE", "PERF_ODOMETER",
                ).forEach { name ->
                    val id = runCatching { ids.getField(name).getInt(null) }.getOrNull()
                    if (id == null) {
                        report("V15", null, "$name: not in this SDK")
                        return@forEach
                    }
                    val result = runCatching {
                        val config = props.getCarPropertyConfig(id) ?: error("no config (unsupported or not permitted)")
                        val area = config.areaIds.firstOrNull() ?: 0
                        props.getProperty<Any>(id, area)?.value
                    }
                    result.onSuccess { report("V15", true, "$name = $it") }
                        .onFailure { report("V15", false, "$name: ${it.javaClass.simpleName} ${it.message?.take(80)}") }
                }
                car.disconnect()
            }.start()

            "V7" -> speak("세이프가드 테스트입니다. 음성 안내가 정상입니다.")

            "V8" -> Thread {
                try {
                    val conn = URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    report("V8", conn.responseCode in 200..299, "HTTP ${conn.responseCode}")
                    conn.disconnect()
                } catch (e: Exception) {
                    fail("V8", e)
                }
            }.start()

            else -> report("INFO", null, "unknown probe $probe")
        }
    }

    private fun speak(text: String) {
        val engine = tts
        if (engine != null) {
            engine.speak(text, false)
            report("V7", true, "speak requested")
            return
        }
        TextToSpeech(this, Mode.ON_DEVICE).also { created ->
            tts = created
            created.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    created.speak(text, false)
                    report("V7", true, "ON_DEVICE initialized, speak requested")
                }
                override fun onFailure(error: ErrorType) = report("V7", false, "init failed: $error")
            })
        }
    }

    private fun distanceMeters(a: LocationData, b: LocationData): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).let { it * it }
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    companion object {
        private const val TAG = "SGPROBE"
        private const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
        private const val REQ_LOCATION = 1
        private const val TELLTALE_POLL_MS = 1000L
        private const val SERVICE_BIND_DELAY_MS = 3000L
        private const val CONTROL_TIMEOUT_MS = 15_000L
        private const val RECONNECT_MIN_INTERVAL_MS = 20_000L
        private const val ERROR_SERVICE_CONNECTION_FAILURE = "40001"
        private val AMBIENT_COLORS = listOf(
            "BLUE(비)" to 0xFF2979FF.toInt(),
            "AMBER(전방 위험)" to 0xFFFFAB00.toInt(),
            "RED(즉시 위험)" to 0xFFFF1744.toInt(),
            "WHITE(각성)" to 0xFFFFFFFF.toInt(),
        )
    }
}
