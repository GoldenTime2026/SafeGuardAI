package com.safeguard.ai.features.maintenance

import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import com.safeguard.ai.risk.RiskRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Regular service items with typical intervals under normal conditions ([확인 필요] per model: the owner's
 * manual wins; severe conditions halve most of them). Hyundai/Kia service data would replace this table.
 */
enum class ServiceItem(val label: String, val intervalKm: Int) {
    ENGINE_OIL("엔진오일", 10_000),
    TIRE_ROTATION("타이어 위치 교환", 10_000),
    CABIN_FILTER("에어컨 필터", 15_000),
    BRAKE_FLUID("브레이크액", 40_000),
}

/** Where each item stands, for the parked screen. [recorded] is false until the driver logs a service. */
data class ServiceStatus(val item: ServiceItem, val remainingKm: Int, val recorded: Boolean) {
    val due: Boolean get() = remainingKm <= MaintenanceCare.DUE_SOON_KM
}

/** Odometer readings at the last service of each item, kept on the car. */
interface MaintenanceStore {
    fun lastServiceKm(item: ServiceItem): Double?
    fun record(item: ServiceItem, km: Double)
    /** The first odometer value the app saw; the baseline for items never logged. */
    fun baselineKm(): Double?
    fun setBaselineKm(km: Double)
}

/**
 * Maintenance care (plan 7) — the part a 3rd-party app can do without Hyundai service records:
 * - Low engine oil or brake fluid (Vehicle SDK level sensors) is said while driving, once per trip.
 * - Once a day, at a stop, the most urgent service item that is due within [DUE_SOON_KM] is said in one
 *   sentence; the full list stays on the parked screen, where the driver logs a service.
 */
class MaintenanceCare(
    private val store: MaintenanceStore,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : RiskRule {

    private val _status = MutableStateFlow<List<ServiceStatus>>(emptyList())
    val status: StateFlow<List<ServiceStatus>> = _status.asStateFlow()

    private var remindedOn: LocalDate? = null
    private var parkedSince: Long? = null
    private val saidThisTrip = mutableSetOf<String>()
    @Volatile private var lastOdometerKm: Double? = null

    override fun evaluate(snapshot: VehicleSnapshot, now: Long): List<RiskEvent> {
        val s = snapshot
        s.odometerKm?.let { km ->
            lastOdometerKm = km
            if (store.baselineKm() == null) store.setBaselineKm(km)
            _status.value = statusAt(km)
        }
        return buildList {
            if (s.isMoving) {
                parkedSince = null
                fluidWarnings(s).filter { saidThisTrip.add(it.key) }.forEach(::add)
                return@buildList
            }
            if (!s.isParked) return@buildList
            val since = parkedSince ?: now.also { parkedSince = it }
            // Let the departure briefing and warning-light guide speak first.
            if (now - since < SETTLE_MS) return@buildList
            saidThisTrip.clear()
            reminder(now)?.let(::add)
        }
    }

    /** The driver logged a service on the parked screen. */
    fun recordService(item: ServiceItem) {
        lastOdometerKm?.let { recordServiceAt(item, it) }
    }

    fun recordServiceAt(item: ServiceItem, km: Double) {
        store.record(item, km)
        // The records changed, so today's reminder is decided again.
        remindedOn = null
        lastOdometerKm?.let { _status.value = statusAt(it) }
    }

    internal fun statusAt(km: Double): List<ServiceStatus> = ServiceItem.entries.map { item ->
        val last = store.lastServiceKm(item)
        val from = last ?: store.baselineKm() ?: km
        ServiceStatus(item, (from + item.intervalKm - km).roundToInt(), recorded = last != null)
    }.sortedBy { it.remainingKm }

    private fun fluidWarnings(s: VehicleSnapshot) = buildList {
        if (s.brakeFluidLow == true) add(
            RiskEvent(
                key = "maintenance-brake-fluid",
                level = RiskLevel.CRITICAL_WARNING,
                speech = "브레이크액이 부족해요. 안전한 곳에 세우고 점검을 받으세요.",
                screenText = "브레이크액 부족 · 점검 필요",
                cooldownMs = TRIP_COOLDOWN_MS,
            )
        )
        if (s.engineOilLow == true) add(
            RiskEvent(
                key = "maintenance-engine-oil",
                level = RiskLevel.MAINTENANCE,
                speech = "엔진오일이 부족해요. 차를 세운 뒤 오일 양을 확인하세요.",
                screenText = "엔진오일 부족",
                cooldownMs = TRIP_COOLDOWN_MS,
            )
        )
    }

    private fun reminder(now: Long): RiskEvent? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        if (remindedOn == today) return null
        val due = _status.value.filter { it.due }
        val top = due.firstOrNull() ?: return null
        remindedOn = today
        val first = if (top.remainingKm > 0) {
            "${top.item.label} 교체까지 ${top.remainingKm.km()} 남았어요."
        } else {
            "${top.item.label} 교체 시기가 ${(-top.remainingKm).km()} 지났어요. 가까운 블루핸즈 방문을 권해요."
        }
        val more = (due.size - 1).takeIf { it > 0 }?.let { " 점검할 항목이 ${it}개 더 있어요. 화면에서 확인하세요." }.orEmpty()
        return RiskEvent(
            key = "maintenance-reminder-$today",
            level = RiskLevel.INFO,
            speech = first + more,
            screenText = "정비 알림 · ${top.item.label}",
            cooldownMs = 0L,
            ttlMs = REMINDER_TTL_MS,
        )
    }

    companion object {
        const val DUE_SOON_KM = 1_000
        const val SETTLE_MS = 20_000L
        private const val TRIP_COOLDOWN_MS = 6 * 60 * 60_000L
        private const val REMINDER_TTL_MS = 60_000L

        /** "800킬로미터", "1,200킬로미터". */
        private fun Int.km() = "%,d킬로미터".format(this)

        /** Maker and model year from a VIN (ISO 3779 WMI and year code), e.g. "현대 2024년식"; null if unknown. */
        fun describeVin(vin: String): String? {
            if (vin.length != 17) return null
            val maker = when {
                vin.startsWith("KMH") -> "현대"
                vin.startsWith("KMT") -> "제네시스"
                vin.startsWith("KNA") || vin.startsWith("KNC") || vin.startsWith("KND") -> "기아"
                else -> null
            }
            val yearIndex = YEAR_CODES.indexOf(vin[9].uppercaseChar())
            val year = if (yearIndex >= 0) " ${2010 + yearIndex}년식" else ""
            return maker?.plus(year) ?: year.trim().ifEmpty { null }
        }

        /** Model-year codes from 2010 (A) to 2039; I, O, Q, U, Z and 0 are never used. */
        private const val YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"
    }
}
