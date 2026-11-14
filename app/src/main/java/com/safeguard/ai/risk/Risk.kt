package com.safeguard.ai.risk

import com.safeguard.ai.core.VehicleSnapshot

/**
 * Warning priority from the plan (5-3): lower [priority] wins.
 * Values are spaced by 10 so levels can be slotted in between (e.g. HOTSPOT = plan's 3.5).
 * [ambientColor] is the cabin light signal; null leaves the user's lighting alone.
 * [urgent] levels skip the gap between warnings and pause other audio instead of ducking it.
 */
enum class RiskLevel(val priority: Int, val ambientColor: Int?, val blink: Boolean = false, val urgent: Boolean = false) {
    CRASH(0, 0xFFFF1744.toInt(), blink = true, urgent = true),
    CRITICAL_WARNING(10, 0xFFFF1744.toInt(), blink = true, urgent = true),
    FORWARD_HAZARD(20, 0xFFFFAB00.toInt()),
    DROWSY(30, 0xFFFFFFFF.toInt()),
    /** Statistical public-data hotspot under its active condition; promoted to FORWARD_HAZARD in rain. */
    HOTSPOT(35, 0xFFFFAB00.toInt()),
    WEATHER(40, 0xFF2979FF.toInt()),
    /** Warning light that needs a service visit or a check, not an immediate stop. */
    MAINTENANCE(45, null),
    TRAFFIC(50, null),
    INFO(90, null),
}

/**
 * One thing worth telling the driver.
 * [key] identifies the same situation across evaluations so it is not repeated within [cooldownMs].
 */
data class RiskEvent(
    val key: String,
    val level: RiskLevel,
    val speech: String,
    val screenText: String,
    val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    val ttlMs: Long = DEFAULT_TTL_MS,
    val createdAt: Long = System.currentTimeMillis(),
    /** When set, the speech ends with a question and the driver's spoken yes/no decides what happens. */
    val confirm: Confirm? = null,
    /** When set, the speech is an open question and whatever the driver says (or silence) goes to [Reply.onReply]. */
    val reply: Reply? = null,
) {
    fun isExpired(now: Long) = now - createdAt > ttlMs

    companion object {
        const val DEFAULT_COOLDOWN_MS = 120_000L
        const val DEFAULT_TTL_MS = 10_000L
    }
}

/** A yes/no follow-up: on "yes" [onYes] runs and [yesSpeech] is said; on "no" [noSpeech] (if any) and [onNo]. */
class Confirm(
    val yesSpeech: String,
    val noSpeech: String? = null,
    val onNo: suspend () -> Unit = {},
    val onYes: suspend () -> Unit,
)

/** An open question: [onReply] gets the driver's words, or null if nothing was said within [timeoutMs]. */
class Reply(
    val timeoutMs: Long,
    val onReply: suspend (String?) -> Unit,
)

/** A detection rule. Rules are pure: they look at the snapshot and return what should be said. */
fun interface RiskRule {
    fun evaluate(snapshot: VehicleSnapshot, now: Long): List<RiskEvent>
}
