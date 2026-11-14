package com.safeguard.ai.core

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.MirrorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.MirrorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.DrivingModeType
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.CruiseControlStateListener
import ai.pleos.playground.vehicle.listener.CruiseControlTargetSpeedListener
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.OilLevelStateListener
import ai.pleos.playground.vehicle.listener.SeatAirbagDeployedListener
import ai.pleos.playground.vehicle.listener.SeatBeltNotBuckledWarningListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the Pleos Vehicle SDK connection and turns it into plain signals and suspend commands.
 *
 * Rules learned in stage 0:
 * - Vehicle.initialize() has no completion callback and calls before ServiceConnected are dropped,
 *   so readiness is detected by retrying a cheap read.
 * - Every call runs off the main thread with a timeout; a hung IPC call must not block anything else.
 * - Error 40001 means the vehicle service connection dropped: rebind and re-subscribe.
 * - Control areas come from capability queries (the driver-only area is not always supported).
 */
class VehicleGateway(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSignals: (SignalPatch) -> Unit,
) {
    enum class State { CONNECTING, READY, RECONNECTING, FAILED }

    private val _state = MutableStateFlow(State.CONNECTING)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var vehicle: Vehicle = Vehicle(context)
    private val reconnectLock = Mutex()
    private var telltaleJob: Job? = null
    private var drivingTimeJob: Job? = null
    private var cabinStateJob: Job? = null
    /** Window and sunroof travel per panel, from the capability query; empty until it answers. */
    @Volatile private var windowBounds: Map<WindowArea, AreaBounds<Int>>? = null
    private var ambientArea: SeatArea? = null
    private var recirculationArea: SeatArea? = null
    private val inFlight = Semaphore(MAX_IN_FLIGHT)
    private val unsupported = mutableSetOf<String>()

    private val frontWindshield = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    /** The fan is one zone for the front row (VHAL HVAC_FAN_SPEED area 0x5), not per seat. */
    private val frontRow = SeatArea(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT)
    private val sideMirrors = MirrorArea(MirrorValue.DRIVER_LEFT, MirrorValue.DRIVER_RIGHT)

    fun start() {
        scope.launch(Dispatchers.IO) { connect(State.CONNECTING) }
    }

    fun stop() {
        telltaleJob?.cancel()
        drivingTimeJob?.cancel()
        cabinStateJob?.cancel()
        runCatching { vehicle.release() }
    }

    // ------------------------------------------------------------------ connection

    private suspend fun connect(phase: State) {
        _state.value = phase
        withContext(Dispatchers.Main) { vehicle.initialize() }
        repeat(READY_ATTEMPTS) {
            val probe = call<WiperSwitch?>("ready-probe", READY_PROBE_TIMEOUT_MS) { ok, err ->
                vehicle.getWiper().getCurrentWiperSwitch(frontWindshield, ok, err)
            }
            if (probe.isSuccess) {
                Log.i(TAG, "vehicle service ready")
                _state.value = State.READY
                subscribe()
                return
            }
            delay(READY_RETRY_DELAY_MS)
        }
        Log.w(TAG, "vehicle service not ready after $READY_ATTEMPTS attempts")
        _state.value = State.FAILED
    }

    private fun reconnect() {
        scope.launch(Dispatchers.IO) {
            if (!reconnectLock.tryLock()) return@launch
            try {
                Log.w(TAG, "vehicle service lost (40001), reconnecting")
                telltaleJob?.cancel()
                runCatching { vehicle.release() }
                vehicle = Vehicle(context)
                connect(State.RECONNECTING)
            } finally {
                reconnectLock.unlock()
            }
        }
    }

    // ------------------------------------------------------------------ signals

    private fun subscribe() {
        val v = vehicle
        guarded("wiper") {
            v.getWiper().registerWiperState(frontWindshield, object : WiperStateListener {
                override fun onWiperStateUpdated(state: WiperState?) {
                    scope.launch(Dispatchers.IO) { refreshWiper(state) }
                }
                override fun onFailed(e: Exception) = onError("wiper", e)
            })
        }
        scope.launch(Dispatchers.IO) { refreshWiper(null) }

        guarded("steering") {
            v.getSteeringWheel().registerSteeringWheelAngle(object : SteeringWheelAngleListener {
                // The SDK reports the opposite sign of the Virtual Console input (stage-0 V10).
                override fun onSteeringWheelAngleUpdated(angle: Float?) {
                    angle?.let { onSignals(SignalPatch(steeringDeg = -it)) }
                }
                override fun onFailed(e: Exception) = onError("steering", e)
            })
        }

        guarded("gear") {
            @Suppress("DEPRECATION")
            v.getGear().registerGearSelection(object : GearSelectionListener {
                override fun onSelected(gear: VehicleGear?) {
                    onSignals(SignalPatch(gear = gear.toGear()))
                }
                override fun onFailed(e: Exception) = onError("gear", e)
            })
        }

        guarded("turnSignal") {
            v.getTurnSignal().registerTurnSignalState(object : TurnSignalStateListener {
                override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
                    onSignals(SignalPatch(turnSignal = state?.name ?: "OFF"))
                }
                override fun onFailed(e: Exception) = onError("turnSignal", e)
            })
        }

        guarded("airbag") {
            v.getSafety().registerSeatAirbagDeployed(driverSeat, object : SeatAirbagDeployedListener {
                override fun onSeatAirbagDeployUpdated(deployed: Boolean?) {
                    if (deployed == true) onSignals(SignalPatch(airbagDeployed = true))
                }
                override fun onFailed(e: Exception) = onError("airbag", e)
            })
        }

        guarded("lowBeam") {
            v.getLight().registerLowBeamLightState(object : LightStateListener {
                override fun onLightStateUpdated(state: LightState?) {
                    state?.let { onSignals(SignalPatch(lowBeamOn = it == LightState.ON)) }
                }
                override fun onFailed(e: Exception) = onError("lowBeam", e)
            })
        }

        guarded("frontFog") {
            v.getLight().registerFrontFogLightState(object : LightStateListener {
                override fun onLightStateUpdated(state: LightState?) {
                    state?.let { onSignals(SignalPatch(frontFogOn = it == LightState.ON)) }
                }
                override fun onFailed(e: Exception) = onError("frontFog", e)
            })
        }

        guarded("drivingMode") {
            v.getDrivingMode().registerDrivingModeState(object : DrivingModeStateListener {
                override fun onDrivingModeStateUpdated(state: DrivingModeType?) {
                    state?.let { onSignals(SignalPatch(drivingMode = it.name)) }
                }
                override fun onFailed(e: Exception) = onError("drivingMode", e)
            })
        }

        // Cruise is read-only for 3rd-party apps: the coach compares its set speed, it never changes it.
        guarded("cruiseState") {
            v.getDrivingMode().registerCruiseControlState(object : CruiseControlStateListener {
                override fun onCruiseControlStateUpdated(state: CruiseControl?) {
                    state?.let { onSignals(SignalPatch(cruiseActive = it == CruiseControl.ACTIVATED)) }
                }
                override fun onFailed(e: Exception) = onError("cruiseState", e)
            })
        }
        guarded("cruiseSpeed") {
            v.getDrivingMode().registerCruiseControlTargetSpeed(object : CruiseControlTargetSpeedListener {
                override fun onCruiseControlTargetSpeedUpdated(speed: Float?) {
                    // AAOS CRUISE_CONTROL_TARGET_SPEED is m/s.
                    speed?.let { onSignals(SignalPatch(cruiseTargetKmh = it * 3.6)) }
                }
                override fun onFailed(e: Exception) = onError("cruiseSpeed", e)
            })
        }

        guarded("engineOil") {
            v.getSafety().registerEngineOilLevelState(object : OilLevelStateListener {
                override fun onOilLevelUpdated(state: OilLevelState?) {
                    state?.isLow()?.let { onSignals(SignalPatch(engineOilLow = it)) }
                }
                override fun onFailed(e: Exception) = onError("engineOil", e)
            })
        }
        guarded("brakeFluid") {
            v.getSafety().registerBrakeFluidLevelState(object : OilLevelStateListener {
                override fun onOilLevelUpdated(state: OilLevelState?) {
                    state?.isLow()?.let { onSignals(SignalPatch(brakeFluidLow = it)) }
                }
                override fun onFailed(e: Exception) = onError("brakeFluid", e)
            })
        }
        guarded("seatBelt") {
            v.getSafety().registerSeatBeltNotBuckledWarning(driverSeat, object : SeatBeltNotBuckledWarningListener {
                override fun onSeatBeltNotBuckledWarningUpdated(warning: Boolean?) {
                    warning?.let { onSignals(SignalPatch(seatBeltUnbuckled = it)) }
                }
                override fun onFailed(e: Exception) = onError("seatBelt", e)
            })
        }

        // Prefetch so the first warning's light isn't held up by the capability query.
        scope.launch(Dispatchers.IO) { ambientArea() }
        scope.launch(Dispatchers.IO) { refreshWasherFluid() }
        startTelltalePolling()
        startDrivingTimePolling()
        startCabinStatePolling()
    }

    /**
     * EV charge and open windows matter when the car is parked (cold night, rain on an open window), so a read
     * every few seconds is plenty. Either may be unsupported on a given car; the parking briefing then skips it.
     */
    private fun startCabinStatePolling() {
        cabinStateJob?.cancel()
        cabinStateJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                call<Int?>("evSoc") { ok, err -> vehicle.getEvBattery().getEvBatteryStateOfCharge(ok, err) }
                    .onSuccess { soc -> soc?.let { onSignals(SignalPatch(evSocPercent = it.toDouble())) } }
                openWindows()?.let { onSignals(SignalPatch(openWindows = it)) }
                delay(CABIN_STATE_POLL_MS)
            }
        }
    }

    /** Panels whose position is above their closed (minimum) position; null when the car can't say. */
    private suspend fun openWindows(): Set<String>? {
        val bounds = windowBounds ?: call<Map<WindowArea, AreaBounds<Int>>?>("window-capability") { ok, err ->
            vehicle.getWindow().checkWindowPositionCapabilities({ map: Map<WindowArea, AreaBounds<Int>> -> ok(map) }, err)
        }.getOrNull()?.also { windowBounds = it } ?: return null
        val open = mutableSetOf<String>()
        for ((area, range) in bounds) {
            val position = call<Int?>("window-position") { ok, err -> vehicle.getWindow().getCurrentWindowPosition(area, ok, err) }
                .getOrNull() ?: continue
            if (position > range.minValue) area.values.forEach { open += it.name }
        }
        return open
    }

    /** Trip driving time changes slowly; one read a minute is enough for the drowsiness care. */
    private fun startDrivingTimePolling() {
        drivingTimeJob?.cancel()
        drivingTimeJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                call<Int?>("drivingTime") { ok, err -> vehicle.getOdometer().getTripCurrentDrivingTime(ok, err) }
                    .onSuccess { raw ->
                        // The SDK docs don't give the unit and the emulator doesn't provide the property
                        // (NOT_SUPPORTED); seconds assumed ([확인 필요] on a real car). Scenarios set drivingMinutes.
                        Log.d(TAG, "trip driving time raw=$raw")
                        raw?.let { onSignals(SignalPatch(drivingMinutes = it / 60)) }
                    }
                // The maintenance reminder only needs the odometer to the kilometre.
                call<Float?>("odometer") { ok, err -> vehicle.getOdometer().getCurrentDistanceDriven(ok, err) }
                    .onSuccess { km -> km?.let { onSignals(SignalPatch(odometerKm = it.toDouble())) } }
                delay(DRIVING_TIME_POLL_MS)
            }
        }
    }

    /** The vehicle identification number; the maintenance care reads the maker and model year from it. */
    suspend fun vin(): String? =
        call<String?>("vin") { ok, err -> vehicle.getCarInfo().getVINInfo(ok, err) }.getOrNull()?.takeIf { it.isNotBlank() }

    suspend fun refreshWasherFluid() {
        call<Boolean?>("washerFluid") { ok, err -> vehicle.getSafety().hasLowWasherFluidWarning(ok, err) }
            .onSuccess { low -> low?.let { onSignals(SignalPatch(washerFluidLow = it)) } }
    }

    private suspend fun refreshWiper(state: WiperState?) {
        call<WiperSwitch?>("wiper-switch") { ok, err -> vehicle.getWiper().getCurrentWiperSwitch(frontWindshield, ok, err) }
            .onSuccess { switch -> onSignals(SignalPatch(wiper = switch.toWiperLevel(state))) }
    }

    /** registerTelltales/getTelltalesInfo both require PRIVATE_CAR_SAFETY; poll only if it is granted. */
    private fun startTelltalePolling() {
        if (context.checkSelfPermission(PRIVATE_CAR_SAFETY) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "PRIVATE_CAR_SAFETY not granted: telltale-based features disabled")
            return
        }
        telltaleJob?.cancel()
        telltaleJob = scope.launch(Dispatchers.IO) {
            var last: Map<String, TelltaleLevel>? = null
            var lastColors: Map<String, TelltaleColor>? = null
            var lastHazard = false
            while (isActive) {
                val result = call<Telltales?>("telltales") { ok, err -> vehicle.getSafety().getTelltalesInfo(ok, err) }
                result.onSuccess { info ->
                        val lit = info?.telltales.orEmpty().filter { it.state != TelltaleState.OFF }
                        val active = lit.associate { it.type.name to if (it.state == TelltaleState.BLINK) TelltaleLevel.BLINK else TelltaleLevel.ON }
                        val colors = lit.mapNotNull { d -> TelltaleColor.entries.getOrNull(d.color)?.let { d.type.name to it } }.toMap()
                        if (active != last || colors != lastColors) {
                            last = active
                            lastColors = colors
                            Log.d(TAG, "telltales $active colors $colors")
                            // Only report hazard on a change, so a scenario-injected hazard isn't cleared.
                            val hazard = hazardFromTelltales(active)
                            onSignals(SignalPatch(telltales = active, telltaleColors = colors, hazardOn = hazard.takeIf { it != lastHazard }))
                            lastHazard = hazard
                        }
                    }
                // Back off while the service is slow so polling doesn't add to the stall.
                delay(if (result.isSuccess) TELLTALE_POLL_MS else TELLTALE_BACKOFF_MS)
            }
        }
    }

    // ------------------------------------------------------------------ commands

    suspend fun setDefroster(on: Boolean): Result<Unit> =
        call("defroster") { ok, err -> vehicle.getHVAC().setHvacDefroster(frontWindshield, on, { _, _ -> ok(Unit) }, err) }

    suspend fun getDefroster(): Result<Boolean?> =
        call("defroster-get") { ok, err -> vehicle.getHVAC().getHvacDefrosterState(frontWindshield, ok, err) }

    suspend fun getRecirculation(): Result<Boolean?> {
        val area = recirculationArea()
        return call("recirculation-get") { ok, err -> vehicle.getHVAC().getHvacRecirculationState(area, ok, err) }
            .onSuccess { recirculationArea = area }
    }

    /** Recirculation is defined on a cabin zone, not the driver seat (stage 2: area 0x1 not supported). */
    private suspend fun recirculationArea(): SeatArea {
        recirculationArea?.let { return it }
        val result = call<SeatArea?>("recirculation-capability") { ok, err ->
            vehicle.getHVAC().checkHvacRecirculationCapabilities({ _, areas -> ok(areas.firstOrNull()) }, err)
        }
        result.getOrNull()?.let { return it.also { recirculationArea = it } }
        // The emulator times out or reports "not supported" here, yet its VHAL defines HVAC_RECIRC_ON on the whole
        // cabin (0x75). The capability answer isn't reliable, so let the command itself decide; a command that
        // succeeds on this area caches it, so later calls skip the slow query.
        return CABIN_AREA
    }

    suspend fun getMirrorHeat(): Result<Int?> =
        call("mirrorHeat-get") { ok, err -> vehicle.getSideMirror().getHvacSideMirrorHeatState(sideMirrors, ok, err) }

    suspend fun setMirrorHeat(level: Int): Result<Unit> =
        call("mirrorHeat") { ok, err -> vehicle.getSideMirror().setHvacSideMirrorHeat(sideMirrors, level, { _, _ -> ok(Unit) }, err) }

    suspend fun setRecirculation(on: Boolean): Result<Unit> {
        val area = recirculationArea()
        return call("recirculation") { ok, err -> vehicle.getHVAC().setHvacRecirculation(area, on, { _, _ -> ok(Unit) }, err) }
            .onSuccess { recirculationArea = area }
    }

    suspend fun setFanSpeed(level: Int): Result<Unit> =
        call("fanSpeed") { ok, err -> vehicle.getHVAC().setHvacFanSpeed(frontRow, level, { _, _ -> ok(Unit) }, err) }

    /** Fan and temperature are unavailable while the climate system is off (emulator: error code 2). */
    suspend fun getHvacPower(): Result<Boolean?> =
        call("hvacPower-get") { ok, err -> vehicle.getHVAC().getHvacPowerState(CABIN_AREA, ok, err) }

    suspend fun setHvacPower(on: Boolean): Result<Unit> =
        call("hvacPower") { ok, err -> vehicle.getHVAC().setHvacPower(CABIN_AREA, on, { _, _ -> ok(Unit) }, err) }

    suspend fun getFanSpeed(): Result<Int?> =
        call("fanSpeed-get") { ok, err -> vehicle.getHVAC().getHvacFanSpeed(frontRow, ok, err) }

    suspend fun getTemperature(): Result<Float?> =
        call("temperature-get") { ok, err -> vehicle.getHVAC().getHvacTemperature(driverSeat, TemperatureUnit.CELSIUS, ok, err) }

    suspend fun setTemperature(celsius: Float): Result<Unit> =
        call("temperature") { ok, err ->
            vehicle.getHVAC().setHvacTemperature(driverSeat, celsius, TemperatureUnit.CELSIUS, { _, _ -> ok(Unit) }, err)
        }

    suspend fun getSeatVentilation(): Result<Int?> =
        call("seatVentilation-get") { ok, err -> vehicle.getSeat().getHvacSeatVentilationState(driverSeat, ok, err) }

    suspend fun setSeatVentilation(level: Int): Result<Unit> =
        call("seatVentilation") { ok, err -> vehicle.getSeat().setHvacSeatVentilation(driverSeat, level, { _, _ -> ok(Unit) }, err) }

    /** Refused with 50001 while driving; callers must only use this when parked. */
    suspend fun moveBackrest(control: MoveControl): Result<Unit> =
        call("backrest") { ok, err -> vehicle.getSeat().moveSeatBackrestAngle(driverSeat, control, { _, _ -> ok(Unit) }, err) }

    suspend fun getAmbientColor(): Result<Int?> {
        val area = ambientArea()
        return call("ambient-get") { ok, err -> vehicle.getLight().getAmbientLightBrightnessColor(area, ok, err) }
    }

    suspend fun setAmbientColor(argb: Int): Result<Unit> {
        val area = ambientArea()
        return call("ambient-set") { ok, err -> vehicle.getLight().setAmbientLightBrightnessColor(area, argb, { _, _ -> ok(Unit) }, err) }
    }

    private suspend fun ambientArea(): SeatArea {
        ambientArea?.let { return it }
        val result = call<SeatArea?>("ambient-capability") { ok, err ->
            vehicle.getLight().checkAmbientLightBrightnessColorCapabilities({ _, areas -> ok(areas.firstOrNull()) }, err)
        }
        result.getOrNull()?.let { return it.also { ambientArea = it } }
        // A failed capability query shouldn't cost a warning its light: try the cabin-wide area seen in stage 0 (V9).
        return CABIN_AREA
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Bridges an SDK async call into a suspend call with a timeout.
     * Synchronous throws (e.g. SecurityException) and late callbacks are both handled.
     */
    private suspend fun <T> call(
        name: String,
        timeoutMs: Long = CALL_TIMEOUT_MS,
        block: (ok: (T) -> Unit, err: (Exception) -> Unit) -> Unit,
    ): Result<T> {
        repeat(TRY_AGAIN_RETRIES) {
            val result = attempt(name, timeoutMs, block)
            // VHAL "error code: 3" is STATUS_TRY_AGAIN (busy right after boot); a short pause usually clears it.
            if (result.exceptionOrNull()?.message?.contains("error code: 3") != true) return result
            delay(TRY_AGAIN_DELAY_MS)
        }
        return attempt(name, timeoutMs, block)
    }

    private suspend fun <T> attempt(
        name: String,
        timeoutMs: Long,
        block: (ok: (T) -> Unit, err: (Exception) -> Unit) -> Unit,
    ): Result<T> {
        // When the vehicle service stalls, don't pile more requests onto it: cap what is in flight.
        if (withTimeoutOrNull(timeoutMs) { inFlight.acquire() } == null) {
            val busy = VehicleBusyException(name)
            onError(name, busy)
            return Result.failure(busy)
        }
        val outcome = CompletableDeferred<Result<T>>()
        // Some SDK calls block the calling thread instead of calling back; a timeout around a blocked
        // thread can't fire, so the call runs on its own thread and only the waiting side is timed.
        scope.launch(Dispatchers.IO) {
            try {
                block({ value -> outcome.complete(Result.success(value)) }, { e -> outcome.complete(Result.failure(e)) })
            } catch (e: Exception) {
                outcome.complete(Result.failure(e))
            }
        }
        val result = try {
            withTimeoutOrNull(timeoutMs) { outcome.await() } ?: Result.failure(VehicleTimeoutException(name, timeoutMs))
        } finally {
            inFlight.release()
        }
        result.exceptionOrNull()?.let { if (it is Exception) onError(name, it) }
        return result
    }

    private inline fun guarded(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            onError(name, e)
        }
    }

    private fun onError(name: String, e: Exception) {
        val message = e.message.orEmpty()
        when {
            message == ERROR_SERVICE_CONNECTION_FAILURE -> reconnect()
            message.contains("not supported") -> if (unsupported.add(name)) Log.i(TAG, "$name not supported on this vehicle")
            name == "ready-probe" -> Unit
            else -> Log.w(TAG, "$name failed: ${e.javaClass.simpleName} $message")
        }
    }

    class VehicleTimeoutException(name: String, timeoutMs: Long) : Exception("$name timed out after ${timeoutMs}ms")
    class VehicleBusyException(name: String) : Exception("$name skipped: vehicle service busy")

    companion object {
        private const val TAG = "SafeGuard/Vehicle"
        private const val PRIVATE_CAR_SAFETY = "pleos.car.permission.PRIVATE_CAR_SAFETY"
        private const val ERROR_SERVICE_CONNECTION_FAILURE = "40001"
        private const val CALL_TIMEOUT_MS = 5_000L
        private const val READY_PROBE_TIMEOUT_MS = 2_000L
        private const val READY_ATTEMPTS = 15
        private const val READY_RETRY_DELAY_MS = 1_000L
        private const val TELLTALE_POLL_MS = 1_000L
        private const val TRY_AGAIN_RETRIES = 2
        private const val MAX_IN_FLIGHT = 3
        private const val TELLTALE_BACKOFF_MS = 5_000L
        private const val DRIVING_TIME_POLL_MS = 60_000L
        private const val CABIN_STATE_POLL_MS = 5_000L
        private const val TRY_AGAIN_DELAY_MS = 600L
        private val CABIN_AREA = SeatArea(
            SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT,
        )
    }
}

private fun VehicleGear?.toGear(): Gear = when (this) {
    VehicleGear.GEAR_PARK -> Gear.PARK
    VehicleGear.GEAR_REVERSE -> Gear.REVERSE
    VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
    null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
    else -> Gear.DRIVE
}

/** null for ERROR: an unreadable sensor must not raise or clear a warning. */
private fun OilLevelState.isLow(): Boolean? = when (this) {
    OilLevelState.LOW, OilLevelState.CRITICALLY_LOW -> true
    OilLevelState.NORMAL, OilLevelState.HIGH -> false
    else -> null
}

/** Stage-0 V1: Console Low → CONTINUOUS_LEVEL_1, High → CONTINUOUS_LEVEL_3. */
private fun WiperSwitch?.toWiperLevel(state: WiperState?): WiperLevel {
    val name = this?.name ?: return WiperLevel.UNKNOWN
    return when {
        name == "OFF" -> WiperLevel.OFF
        name == "MIST" || name.startsWith("INTERMITTENT") -> WiperLevel.INTERMITTENT
        name == "CONTINUOUS_LEVEL_1" || name == "CONTINUOUS_LEVEL_2" -> WiperLevel.LOW
        name.startsWith("CONTINUOUS") -> WiperLevel.HIGH
        name == "AUTO" -> if (state == WiperState.ON) WiperLevel.LOW else WiperLevel.OFF
        else -> WiperLevel.UNKNOWN
    }
}
