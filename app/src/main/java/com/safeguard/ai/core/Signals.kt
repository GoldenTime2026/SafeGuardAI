package com.safeguard.ai.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WiperLevel { UNKNOWN, OFF, INTERMITTENT, LOW, HIGH }

enum class Gear { UNKNOWN, PARK, REVERSE, NEUTRAL, DRIVE }

enum class TelltaleLevel { ON, BLINK }

/** SDK TelltaleColor, in the SDK's order (TelltaleData.color is the ordinal). */
enum class TelltaleColor { WHITE, GREEN, BLUE, YELLOW, RED, GRAY }

/**
 * The SDK has no hazard-light property (stage-0 V11), but hazard lights light both turn-signal
 * telltales at once, which a single turn signal never does.
 */
fun hazardFromTelltales(telltales: Map<String, TelltaleLevel>): Boolean =
    "TURN_SIGNAL_LEFT" in telltales && "TURN_SIGNAL_RIGHT" in telltales

/** A congested stretch on the active route. Units assumed: metres, seconds, km/h ([확인 필요] — not documented). */
data class Congestion(
    val offsetMeters: Double,
    val lengthMeters: Double,
    val timeSeconds: Long,
    val avgKmh: Double,
    val road: String = "",
    val fromCross: String = "",
    val toCross: String = "",
)

/** A traffic incident reported by navigation (ACCIDENT, REPAIRING, DISASTER, ROAD_DAMAGE, EVENT, ETC). */
data class TrafficIncident(val type: String, val latitude: Double, val longitude: Double, val description: String = "")

/** Route traffic from NaviHelper; null when no route is being guided. */
data class TrafficInfo(val routing: Boolean, val congestions: List<Congestion>, val incidents: List<TrafficIncident>)

/** A navigation caution point ahead, e.g. type "SCHOOL_ZONE" 280 m away. */
data class Caution(val type: String, val remainMeters: Int)

/** Everything the risk rules can see about the car at one moment. */
data class VehicleSnapshot(
    val wiper: WiperLevel = WiperLevel.UNKNOWN,
    val steeringDeg: Float? = null,
    val gear: Gear = Gear.UNKNOWN,
    val turnSignal: String = "OFF",
    /** Active telltales by SDK TelltaleType name, e.g. "ESC" → BLINK. */
    val telltales: Map<String, TelltaleLevel> = emptyMap(),
    /** Colour of the active telltales; driver-assist lights turn yellow when the system is off or blocked. */
    val telltaleColors: Map<String, TelltaleColor> = emptyMap(),
    val airbagDeployed: Boolean = false,
    /** Derived from the turn-signal telltales ([hazardFromTelltales]), or set by scenarios. */
    val hazardOn: Boolean = false,
    val lowBeamOn: Boolean? = null,
    val frontFogOn: Boolean? = null,
    /** SDK DrivingModeType name (NORMAL / ECO / SPORT); not provided by the emulator. */
    val drivingMode: String? = null,
    val washerFluidLow: Boolean? = null,
    val cautions: List<Caution> = emptyList(),
    val speedKmh: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val headingDeg: Float? = null,
    val roadSpeedLimitKmh: Int? = null,
    /** Outside temperature in °C; not in the Vehicle SDK, so from scenarios now and weather data later. */
    val outsideTempC: Double? = null,
    /** Demo overrides so time- and holiday-conditional hotspots can be shown at any hour. */
    val minuteOfDayOverride: Int? = null,
    val holidayOverride: Boolean? = null,
    val traffic: TrafficInfo? = null,
    /** Minutes driven since the engine was started (Vehicle Odometer trip time). */
    val drivingMinutes: Int? = null,
    /** Cruise control engaged (SDK CruiseControl ACTIVATED) and its set speed; read-only for 3rd-party apps. */
    val cruiseActive: Boolean? = null,
    val cruiseTargetKmh: Double? = null,
    /** Total distance driven (Vehicle Odometer), km. */
    val odometerKm: Double? = null,
    /** SDK OilLevelState LOW / CRITICALLY_LOW. */
    val engineOilLow: Boolean? = null,
    val brakeFluidLow: Boolean? = null,
    /** Driver seat-belt warning. */
    val seatBeltUnbuckled: Boolean? = null,
    /** EV battery state of charge, % (Vehicle EvBattery); null on a combustion car. */
    val evSocPercent: Double? = null,
    /** Windows and sunroof panels that are not closed, by SDK WindowValue name ("ROW_1_RIGHT", "ROOF_TOP_1"). */
    val openWindows: Set<String>? = null,
) {
    val isParked: Boolean get() = gear == Gear.PARK && (speedKmh ?: 0.0) < 1.0
    val isMoving: Boolean get() = (speedKmh ?: 0.0) >= 5.0
    fun has(telltale: String) = telltales.containsKey(telltale)
}

/** A partial update; null means "leave as is". */
data class SignalPatch(
    val wiper: WiperLevel? = null,
    val steeringDeg: Float? = null,
    val gear: Gear? = null,
    val turnSignal: String? = null,
    val telltales: Map<String, TelltaleLevel>? = null,
    val telltaleColors: Map<String, TelltaleColor>? = null,
    val airbagDeployed: Boolean? = null,
    val hazardOn: Boolean? = null,
    val lowBeamOn: Boolean? = null,
    val frontFogOn: Boolean? = null,
    val drivingMode: String? = null,
    val washerFluidLow: Boolean? = null,
    val cautions: List<Caution>? = null,
    val speedKmh: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val headingDeg: Float? = null,
    val roadSpeedLimitKmh: Int? = null,
    val outsideTempC: Double? = null,
    val minuteOfDayOverride: Int? = null,
    val holidayOverride: Boolean? = null,
    val traffic: TrafficInfo? = null,
    val drivingMinutes: Int? = null,
    val cruiseActive: Boolean? = null,
    val cruiseTargetKmh: Double? = null,
    val odometerKm: Double? = null,
    val engineOilLow: Boolean? = null,
    val brakeFluidLow: Boolean? = null,
    val seatBeltUnbuckled: Boolean? = null,
    val evSocPercent: Double? = null,
    val openWindows: Set<String>? = null,
) {
    fun applyTo(s: VehicleSnapshot) = s.copy(
        wiper = wiper ?: s.wiper,
        steeringDeg = steeringDeg ?: s.steeringDeg,
        gear = gear ?: s.gear,
        turnSignal = turnSignal ?: s.turnSignal,
        telltales = telltales ?: s.telltales,
        telltaleColors = telltaleColors ?: s.telltaleColors,
        airbagDeployed = airbagDeployed ?: s.airbagDeployed,
        hazardOn = hazardOn ?: s.hazardOn,
        lowBeamOn = lowBeamOn ?: s.lowBeamOn,
        frontFogOn = frontFogOn ?: s.frontFogOn,
        drivingMode = drivingMode ?: s.drivingMode,
        washerFluidLow = washerFluidLow ?: s.washerFluidLow,
        cautions = cautions ?: s.cautions,
        speedKmh = speedKmh ?: s.speedKmh,
        latitude = latitude ?: s.latitude,
        longitude = longitude ?: s.longitude,
        headingDeg = headingDeg ?: s.headingDeg,
        roadSpeedLimitKmh = roadSpeedLimitKmh ?: s.roadSpeedLimitKmh,
        outsideTempC = outsideTempC ?: s.outsideTempC,
        minuteOfDayOverride = minuteOfDayOverride ?: s.minuteOfDayOverride,
        holidayOverride = holidayOverride ?: s.holidayOverride,
        traffic = traffic ?: s.traffic,
        drivingMinutes = drivingMinutes ?: s.drivingMinutes,
        cruiseActive = cruiseActive ?: s.cruiseActive,
        cruiseTargetKmh = cruiseTargetKmh ?: s.cruiseTargetKmh,
        odometerKm = odometerKm ?: s.odometerKm,
        engineOilLow = engineOilLow ?: s.engineOilLow,
        brakeFluidLow = brakeFluidLow ?: s.brakeFluidLow,
        seatBeltUnbuckled = seatBeltUnbuckled ?: s.seatBeltUnbuckled,
        evSocPercent = evSocPercent ?: s.evSocPercent,
        openWindows = openWindows ?: s.openWindows,
    )

    /** Later patch wins field by field. */
    operator fun plus(other: SignalPatch) = SignalPatch(
        wiper = other.wiper ?: wiper,
        steeringDeg = other.steeringDeg ?: steeringDeg,
        gear = other.gear ?: gear,
        turnSignal = other.turnSignal ?: turnSignal,
        telltales = other.telltales ?: telltales,
        telltaleColors = other.telltaleColors ?: telltaleColors,
        airbagDeployed = other.airbagDeployed ?: airbagDeployed,
        hazardOn = other.hazardOn ?: hazardOn,
        lowBeamOn = other.lowBeamOn ?: lowBeamOn,
        frontFogOn = other.frontFogOn ?: frontFogOn,
        drivingMode = other.drivingMode ?: drivingMode,
        washerFluidLow = other.washerFluidLow ?: washerFluidLow,
        cautions = other.cautions ?: cautions,
        speedKmh = other.speedKmh ?: speedKmh,
        latitude = other.latitude ?: latitude,
        longitude = other.longitude ?: longitude,
        headingDeg = other.headingDeg ?: headingDeg,
        roadSpeedLimitKmh = other.roadSpeedLimitKmh ?: roadSpeedLimitKmh,
        outsideTempC = other.outsideTempC ?: outsideTempC,
        minuteOfDayOverride = other.minuteOfDayOverride ?: minuteOfDayOverride,
        holidayOverride = other.holidayOverride ?: holidayOverride,
        traffic = other.traffic ?: traffic,
        drivingMinutes = other.drivingMinutes ?: drivingMinutes,
        cruiseActive = other.cruiseActive ?: cruiseActive,
        cruiseTargetKmh = other.cruiseTargetKmh ?: cruiseTargetKmh,
        odometerKm = other.odometerKm ?: odometerKm,
        engineOilLow = other.engineOilLow ?: engineOilLow,
        brakeFluidLow = other.brakeFluidLow ?: brakeFluidLow,
        seatBeltUnbuckled = other.seatBeltUnbuckled ?: seatBeltUnbuckled,
        evSocPercent = other.evSocPercent ?: evSocPercent,
        openWindows = other.openWindows ?: openWindows,
    )
}

/**
 * Merges real vehicle signals with scenario overrides.
 * While a scenario is running its fields win, so the same rules run on real and injected data.
 */
class SignalHub {
    private var real = VehicleSnapshot()
    private var override = SignalPatch()

    private val _snapshot = MutableStateFlow(VehicleSnapshot())
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()

    @Synchronized
    fun updateReal(patch: SignalPatch) {
        real = patch.applyTo(real)
        publish()
    }

    @Synchronized
    fun override(patch: SignalPatch) {
        override += patch
        publish()
    }

    @Synchronized
    fun clearOverrides() {
        override = SignalPatch()
        publish()
    }

    private fun publish() {
        _snapshot.value = override.applyTo(real)
    }
}
