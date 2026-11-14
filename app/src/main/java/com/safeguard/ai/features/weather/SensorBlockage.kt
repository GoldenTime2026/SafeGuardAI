package com.safeguard.ai.features.weather

import com.safeguard.ai.core.TelltaleColor
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import com.safeguard.ai.risk.RiskRule

/**
 * Feature ① extension — driver-assist sensors blocked by the weather.
 *
 * Heavy rain, snow or frost on the front camera and radar switch off collision avoidance and lane keeping; the car
 * shows a yellow light and a short message, and a driver used to the steering help keeps relying on it. Pleos gives
 * apps the assist telltales (not the ADAS state itself, which is system-only), so a light turning yellow while the
 * wipers say it is pouring is read as "blocked by the weather" and said in one sentence.
 *
 * In dry weather a yellow assist light is more likely the driver switching the assist off, so that is said only
 * after it stays on for a while, as a check-the-sensor hint. Which light turns which colour on a real car follows
 * the owner's manual ([확인 필요] on a real car); the emulator's Virtual Console has no assist lights, so scenarios
 * inject them.
 */
class SensorBlockage(private val weather: () -> WeatherStatus) : RiskRule {

    /** Assist lights already announced as off; cleared when they all come back. */
    private var announced: Set<String> = emptySet()
    private var announcedWithCause = false
    private var offSince: Long? = null

    fun reset() {
        announced = emptySet()
        announcedWithCause = false
        offSince = null
    }

    override fun evaluate(snapshot: VehicleSnapshot, now: Long): List<RiskEvent> {
        val off = ASSISTS.keys.filter { snapshot.telltaleColors[it] == TelltaleColor.YELLOW }.toSet()
        if (off.isEmpty()) {
            val restored = announcedWithCause && snapshot.isMoving
            reset()
            return if (restored) listOf(restoredEvent()) else emptyList()
        }
        if (!snapshot.isMoving) return emptyList()
        val since = offSince ?: now.also { offSince = it }
        if (announced.containsAll(off)) return emptyList()

        val cause = cause(snapshot, weather().condition)
        if (cause == null && now - since < DRY_HOLD_MS) return emptyList()
        announced = off
        announcedWithCause = cause != null
        return listOf(if (cause != null) blockedEvent(off, cause) else dryEvent(off))
    }

    private fun blockedEvent(off: Set<String>, cause: String): RiskEvent {
        val front = off.any { it !in REAR_ASSISTS }
        val sensor = if (front) "전방 센서" else "후측방 센서"
        val advice = if (front) "직접 조향하고 앞차와 거리를 넓히세요." else "차로를 바꿀 때 직접 돌아보세요."
        return RiskEvent(
            key = "sensor-blocked-${off.sorted().joinToString("-")}",
            level = RiskLevel.FORWARD_HAZARD,
            speech = "$cause ${sensor}가 가려진 것 같아요. ${labels(off)} 기능이 꺼졌어요. $advice",
            screenText = "$sensor 가림 · ${labels(off)} 꺼짐",
            cooldownMs = COOLDOWN_MS,
        )
    }

    private fun dryEvent(off: Set<String>) = RiskEvent(
        key = "sensor-off-${off.sorted().joinToString("-")}",
        level = RiskLevel.MAINTENANCE,
        speech = "${labels(off)} 기능이 꺼져 있어요. 직접 끄신 게 아니라면 정차한 뒤 앞유리와 범퍼의 센서 주변을 닦아 보세요.",
        screenText = "${labels(off)} 꺼짐 · 센서 확인",
        cooldownMs = COOLDOWN_MS,
    )

    private fun restoredEvent() = RiskEvent(
        key = "sensor-restored",
        level = RiskLevel.INFO,
        speech = "운전자 보조 기능이 다시 켜졌어요.",
        screenText = "운전자 보조 기능 복구",
        cooldownMs = COOLDOWN_MS,
    )

    companion object {
        /** SDK telltale → what the driver knows it as (현대차·기아 names). */
        val ASSISTS = linkedMapOf(
            "FCA" to "전방 충돌방지 보조",
            "LFA" to "차로 유지 보조",
            "LKA" to "차로 이탈방지 보조",
            "ELK" to "긴급 차로 유지 보조",
            "ICC" to "스마트 크루즈",
            "LCA_LEFT" to "후측방 보조",
            "LCA_RIGHT" to "후측방 보조",
        )
        private val REAR_ASSISTS = setOf("LCA_LEFT", "LCA_RIGHT")
        private const val DRY_HOLD_MS = 30_000L
        private const val COOLDOWN_MS = 10 * 60_000L

        /** Why a sensor would be covered now; null when the weather gives no reason. */
        internal fun cause(s: VehicleSnapshot, condition: RoadCondition): String? = when {
            condition == RoadCondition.ICE || ((s.outsideTempC ?: 99.0) <= 0.0 && condition.isAdverse) -> "눈이나 성에로"
            condition == RoadCondition.HEAVY_RAIN -> "폭우로"
            condition == RoadCondition.FOG -> "안개로"
            condition == RoadCondition.RAIN -> "비로"
            condition == RoadCondition.WET -> "물보라로"
            else -> null
        }

        internal fun labels(off: Set<String>) = ASSISTS.filterKeys { it in off }.values.distinct().joinToString("·")
    }
}
