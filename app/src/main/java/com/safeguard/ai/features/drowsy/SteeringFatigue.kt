package com.safeguard.ai.features.drowsy

import kotlin.math.abs
import kotlin.math.sign

enum class Fatigue { NORMAL, CAUTION, WARNING }

/**
 * Auxiliary fatigue signal from the steering wheel, in the spirit of DAW / ATTENTION ASSIST:
 * an alert driver makes small, frequent corrections; a tired one stops correcting, drifts, then jerks the wheel.
 *
 * Per one-minute window at cruising speed it counts
 * - corrections: direction reversals of at least [MOVE_DEG],
 * - jerks: a turn of at least [JERK_DEG] right after [QUIET_MS] of a still wheel.
 * The first [BASELINE_WINDOWS] windows set the driver's own correction rate. A window scores a point when
 * corrections fall below half the baseline, and a point per jerk (max 2). The last [SCORE_WINDOWS] windows
 * add up to [Fatigue].
 *
 * The thresholds are heuristics for the prototype, not validated values ([확인 필요] against real driving logs).
 */
class SteeringFatigue {

    private var anchor: Float? = null
    private var anchorAt = 0L
    private var lastDirection = 0f

    private var windowStart: Long? = null
    private var corrections = 0
    private var jerks = 0

    private val baselineWindows = mutableListOf<Int>()
    private val scores = ArrayDeque<Double>()

    var level = Fatigue.NORMAL
        private set

    /** Feeds one steering sample; samples below [MIN_SPEED_KMH] are ignored and break the quiet period. */
    fun add(timeMs: Long, angleDeg: Float, speedKmh: Double) {
        if (speedKmh < MIN_SPEED_KMH) {
            anchor = null
            return
        }
        rollWindows(timeMs)
        val a = anchor
        if (a == null) {
            anchor = angleDeg
            anchorAt = timeMs
            return
        }
        val delta = angleDeg - a
        if (abs(delta) < MOVE_DEG) return
        if (timeMs - anchorAt >= QUIET_MS && abs(delta) >= JERK_DEG) jerks++
        val direction = sign(delta)
        if (lastDirection != 0f && direction != lastDirection) corrections++
        lastDirection = direction
        anchor = angleDeg
        anchorAt = timeMs
    }

    fun reset() {
        anchor = null
        lastDirection = 0f
        windowStart = null
        corrections = 0
        jerks = 0
        baselineWindows.clear()
        scores.clear()
        level = Fatigue.NORMAL
    }

    private fun rollWindows(now: Long) {
        val start = windowStart ?: now.also { windowStart = it }
        if (now - start < WINDOW_MS) return
        closeWindow()
        windowStart = now
    }

    private fun closeWindow() {
        if (baselineWindows.size < BASELINE_WINDOWS) {
            baselineWindows += corrections
        } else {
            val baseline = baselineWindows.average()
            var score = jerks.coerceAtMost(2).toDouble()
            if (baseline >= MIN_BASELINE && corrections < baseline * LOW_CORRECTION_RATIO) score += 1.0
            scores.addLast(score)
            if (scores.size > SCORE_WINDOWS) scores.removeFirst()
            val total = scores.sum()
            level = when {
                total >= WARNING_SCORE -> Fatigue.WARNING
                total >= CAUTION_SCORE -> Fatigue.CAUTION
                else -> Fatigue.NORMAL
            }
        }
        corrections = 0
        jerks = 0
    }

    companion object {
        const val MIN_SPEED_KMH = 60.0
        const val WINDOW_MS = 60_000L
        const val MOVE_DEG = 0.5f
        const val JERK_DEG = 4f
        const val QUIET_MS = 4_000L
        const val BASELINE_WINDOWS = 5
        const val SCORE_WINDOWS = 5
        private const val MIN_BASELINE = 3.0
        private const val LOW_CORRECTION_RATIO = 0.5
        private const val CAUTION_SCORE = 2.0
        private const val WARNING_SCORE = 4.0
    }
}
