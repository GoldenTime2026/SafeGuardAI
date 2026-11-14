package com.safeguard.ai.features.logbook

import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.risk.Confirm
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import com.safeguard.ai.risk.RiskRule
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

enum class TripPurpose(val label: String) { UNSET("미분류"), BUSINESS("업무"), PERSONAL("비업무") }

/** One drive from leaving a parking spot to parking again, by the car's own odometer. */
data class TripEntry(
    val startedAt: Long,
    val endedAt: Long,
    val startKm: Double,
    val endKm: Double,
    val from: String = "",
    val to: String = "",
    val purpose: TripPurpose = TripPurpose.UNSET,
) {
    val km: Double get() = endKm - startKm
}

interface TripLogStore {
    fun load(): List<TripEntry>
    fun save(entries: List<TripEntry>)
}

/**
 * Business-use logbook — the 국세청 「업무용승용차 운행기록부」 filled in by the car.
 *
 * The form asks for the odometer before and after each drive. Phone apps estimate the distance from GPS (and lose
 * it in underground car parks); the car reads its own odometer. When a drive ends the app asks one yes/no
 * question — "was this for work?" — and keeps the answer; silence leaves it unclassified, to be fixed on the
 * settings screen. The CSV follows the form's columns so it can go straight to an accountant.
 *
 * Off by default: only drivers of a business car switch it on.
 */
class TripLog(
    private val scope: CoroutineScope,
    private val store: TripLogStore,
    private val enabled: () -> Boolean,
    /** The navigation's address of the car's position now, resolved off the evaluation thread. */
    private val address: suspend () -> String?,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : RiskRule {

    private data class Start(val at: Long, val km: Double, var from: String = "")

    private val lock = Any()
    private var start: Start? = null
    private val _entries = MutableStateFlow(store.load())
    val entries: StateFlow<List<TripEntry>> = _entries.asStateFlow()

    override fun evaluate(snapshot: VehicleSnapshot, now: Long): List<RiskEvent> = synchronized(lock) {
        if (!enabled()) {
            start = null
            return emptyList()
        }
        val km = snapshot.odometerKm
        if (snapshot.isMoving) {
            if (start == null && km != null) {
                val begun = Start(now, km)
                start = begun
                scope.launch { address()?.let { synchronized(lock) { begun.from = it } } }
            }
            return emptyList()
        }
        val begun = start ?: return emptyList()
        if (!snapshot.isParked || km == null) return emptyList()
        start = null
        val entry = TripEntry(begun.at, now, begun.km, km, from = begun.from)
        if (entry.km < MIN_KM) return emptyList()
        add(entry)
        scope.launch { address()?.let { to -> update(entry.startedAt) { it.copy(to = to) } } }
        listOf(ask(entry))
    }

    private fun ask(entry: TripEntry) = RiskEvent(
        key = "logbook-${entry.startedAt}",
        level = RiskLevel.INFO,
        speech = "이번 운행 ${spokenKm(entry.km)}, 업무로 기록할까요?",
        screenText = "운행기록 · ${"%.1f".format(Locale.KOREA, entry.km)} km · 업무 여부",
        cooldownMs = 0L,
        ttlMs = WAIT_MS,
        confirm = Confirm(
            yesSpeech = "업무 운행으로 기록했어요.",
            noSpeech = "개인 운행으로 기록했어요.",
            onNo = { update(entry.startedAt) { it.copy(purpose = TripPurpose.PERSONAL) } },
            onYes = { update(entry.startedAt) { it.copy(purpose = TripPurpose.BUSINESS) } },
        ),
    )

    /** Settings screen: change a drive's purpose afterwards. */
    fun setPurpose(startedAt: Long, purpose: TripPurpose) = update(startedAt) { it.copy(purpose = purpose) }

    private fun add(entry: TripEntry) {
        val list = (_entries.value + entry).takeLast(MAX_ENTRIES)
        _entries.value = list
        store.save(list)
        Log.i(TAG, "trip ${entry.startKm} → ${entry.endKm} km")
    }

    private fun update(startedAt: Long, change: (TripEntry) -> TripEntry) {
        val list = _entries.value.map { if (it.startedAt == startedAt) change(it) else it }
        _entries.value = list
        store.save(list)
    }

    /** The form's columns, for the month containing [monthOf]. */
    fun csv(monthOf: Long, user: String = ""): String = csv(_entries.value, monthOf, user, zone)

    companion object {
        private const val TAG = "SafeGuard/TripLog"
        private const val MIN_KM = 0.5
        private const val MAX_ENTRIES = 1_000
        private const val WAIT_MS = 60_000L

        private fun spokenKm(km: Double) = if (km < 10) "${"%.1f".format(Locale.KOREA, km)}킬로미터" else "${km.roundToInt()}킬로미터"

        data class MonthSummary(val totalKm: Double, val businessKm: Double, val unsetCount: Int) {
            val businessRatio: Double get() = if (totalKm > 0) businessKm / totalKm else 0.0
        }

        fun summary(entries: List<TripEntry>, monthOf: Long, zone: ZoneId): MonthSummary {
            val month = inMonth(entries, monthOf, zone)
            return MonthSummary(
                totalKm = month.sumOf { it.km },
                businessKm = month.filter { it.purpose == TripPurpose.BUSINESS }.sumOf { it.km },
                unsetCount = month.count { it.purpose == TripPurpose.UNSET },
            )
        }

        fun csv(entries: List<TripEntry>, monthOf: Long, user: String, zone: ZoneId): String {
            val day = DateTimeFormatter.ofPattern("yyyy-MM-dd(E)", Locale.KOREA)
            val rows = inMonth(entries, monthOf, zone).map { e ->
                val business = if (e.purpose == TripPurpose.BUSINESS) km(e.km) else "0"
                listOf(
                    day.format(Instant.ofEpochMilli(e.startedAt).atZone(zone)),
                    user,
                    km(e.startKm),
                    km(e.endKm),
                    km(e.km),
                    "0",
                    business,
                    listOf(e.from, e.to).filter { it.isNotBlank() }.joinToString(" → ") +
                        if (e.purpose == TripPurpose.UNSET) " (미분류)" else "",
                ).joinToString(",") { field -> "\"${field.replace("\"", "\"\"")}\"" }
            }
            val header = "사용일자(요일),사용자(성명),주행 전 계기판의 거리(km),주행 후 계기판의 거리(km),주행거리(km)," +
                "업무용 사용거리-출퇴근용(km),업무용 사용거리-일반 업무용(km),비고"
            return (listOf(header) + rows).joinToString("\r\n")
        }

        private fun inMonth(entries: List<TripEntry>, monthOf: Long, zone: ZoneId): List<TripEntry> {
            val target = Instant.ofEpochMilli(monthOf).atZone(zone)
            return entries.filter {
                val d = Instant.ofEpochMilli(it.startedAt).atZone(zone)
                d.year == target.year && d.month == target.month
            }
        }

        private fun km(v: Double) = "%.1f".format(Locale.US, v)
    }
}
