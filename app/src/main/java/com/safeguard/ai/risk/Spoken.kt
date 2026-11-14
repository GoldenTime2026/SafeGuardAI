package com.safeguard.ai.risk

import java.util.Locale
import kotlin.math.roundToInt

/** How distances are read out: "2.3킬로미터", "800미터". */
fun spokenDistance(meters: Double): String =
    if (meters >= 1_000) "${"%.1f".format(Locale.KOREA, meters / 1_000).removeSuffix(".0")}킬로미터"
    else "${((meters / 100).roundToInt() * 100).coerceAtLeast(100)}미터"

/** "문 열림" → "문 열림이", "타이어 공기압 경고" → "타이어 공기압 경고가": the subject particle that fits the last syllable. */
fun String.withSubjectParticle(): String {
    val last = trimEnd().lastOrNull() ?: return this
    val hasFinalConsonant = last in '가'..'힣' && (last - '가') % 28 != 0
    return this + if (hasFinalConsonant) "이" else "가"
}
