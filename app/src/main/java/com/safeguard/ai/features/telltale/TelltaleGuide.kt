package com.safeguard.ai.features.telltale

import com.safeguard.ai.core.TelltaleLevel
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.data.publicdata.Place
import com.safeguard.ai.data.publicdata.Places
import com.safeguard.ai.risk.Confirm
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import com.safeguard.ai.risk.RiskRule
import com.safeguard.ai.risk.spokenDistance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the parked screen shows for the most urgent active warning light. */
data class TelltaleCard(val name: String, val severity: Severity, val steps: List<String>)

/**
 * Feature ⑤ — warning-light guide (benchmark: BMW Proactive Care).
 * - Moving: one sentence per newly lit light, severity first (STOP lights interrupt like critical warnings).
 * - Parked after driving: the steps go on screen ([card]) and, if a service centre is needed, a spoken offer
 *   to route there. At departure the card is shown silently; the departure briefing already mentions it.
 * A light must stay on [SETTLE_MS] before it counts, so the bulb check at ignition isn't announced.
 */
class TelltaleGuide(
    private val serviceCenters: () -> List<Place>,
    private val routeTo: (Place) -> Unit,
) : RiskRule {

    private val _card = MutableStateFlow<TelltaleCard?>(null)
    val card: StateFlow<TelltaleCard?> = _card.asStateFlow()

    private val firstSeen = mutableMapOf<String, Long>()
    private val announced = mutableSetOf<String>()
    private var drove = false
    private var guidedAtThisStop = emptySet<String>()

    override fun evaluate(snapshot: VehicleSnapshot, now: Long): List<RiskEvent> {
        val s = snapshot
        val active = s.telltales.mapNotNull { (type, level) ->
            TelltaleCatalog.entries[type]?.takeUnless { it.steadyOnly && level == TelltaleLevel.BLINK }?.let { Lit(it, level) }
        }
        val activeKeys = active.map { it.key }.toSet()
        firstSeen.keys.retainAll(activeKeys)
        announced.retainAll(activeKeys)
        active.forEach { firstSeen.putIfAbsent(it.key, now) }
        val settled = active.filter { now - firstSeen.getValue(it.key) >= SETTLE_MS }.sortedBy { it.severity.ordinal }

        if (s.isMoving) {
            drove = true
            guidedAtThisStop = emptySet()
            _card.value = null
            return settled.filter { announced.add(it.key) }.map(::drivingEvent)
        }
        if (!s.isParked) return emptyList()

        val top = settled.firstOrNull()
        _card.value = top?.let { TelltaleCard(it.entry.name, it.severity, it.entry.steps) }
        if (top == null || !drove) return emptyList()
        val keys = settled.map { it.key }.toSet()
        if (keys == guidedAtThisStop) return emptyList()
        guidedAtThisStop = keys
        return listOf(parkedEvent(top, s))
    }

    private fun drivingEvent(lit: Lit) = RiskEvent(
        key = "telltale-${lit.key}",
        level = if (lit.severity == Severity.STOP) RiskLevel.CRITICAL_WARNING else RiskLevel.MAINTENANCE,
        speech = lit.speech,
        screenText = "${lit.severity.label} · ${lit.entry.name}",
        cooldownMs = TRIP_COOLDOWN_MS,
    )

    private fun parkedEvent(lit: Lit, s: VehicleSnapshot): RiskEvent {
        val intro = "차를 세우셨네요. ${lit.entry.name} 조치 방법을 화면에 띄웠어요."
        val center = if (lit.entry.needsService && s.latitude != null && s.longitude != null) {
            Places.nearest(serviceCenters(), s.latitude, s.longitude, SERVICE_RADIUS_M)
        } else {
            null
        }
        val base = RiskEvent(
            key = "telltale-guide-${lit.key}",
            level = RiskLevel.INFO,
            speech = intro,
            screenText = "${lit.entry.name} · 조치 방법",
            cooldownMs = 0L,
        )
        val (place, meters) = center ?: return base
        return base.copy(
            speech = "$intro ${spokenDistance(meters)} 거리 블루핸즈로 안내할까요?",
            ttlMs = 20_000L,
            confirm = Confirm(
                yesSpeech = "블루핸즈로 안내를 시작할게요.",
                noSpeech = "알겠어요. 화면의 안내를 확인하세요.",
                onYes = { routeTo(place) },
            ),
        )
    }

    private class Lit(val entry: TelltaleEntry, level: TelltaleLevel) {
        private val blinking = level == TelltaleLevel.BLINK && entry.blinkSpeech != null
        val key = if (blinking) "${entry.type}-BLINK" else entry.type
        val severity = if (blinking) entry.blinkSeverity ?: entry.severity else entry.severity
        val speech = if (blinking) entry.blinkSpeech!! else entry.drivingSpeech
    }

    companion object {
        const val SETTLE_MS = 4_000L
        private const val TRIP_COOLDOWN_MS = 6 * 60 * 60_000L
        private const val SERVICE_RADIUS_M = 30_000.0
    }
}
