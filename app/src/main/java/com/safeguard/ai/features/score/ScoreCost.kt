package com.safeguard.ai.features.score

import kotlin.math.roundToInt

/** The score's money side: where the habits of this trip and this month would cost or save the driver. */
data class InsuranceOutlook(
    /** This month's average so far. */
    val monthAverage: Int,
    /** Months of the last twelve (this one included) whose average reached [ScoreCost.INSURANCE_MIN_SCORE]. */
    val goodMonths: Int,
    /** The discount on the premium the driver entered; null when no premium is set. */
    val savingWon: Int?,
)

/**
 * Turns the score into won, from published rules only.
 *
 * - Speeding fines: the camera fine (과태료) for a passenger car by how far over the legal limit, from the
 *   도로교통법 시행령 table as 법제처 생활법령 lists it (2026-09-15). In a school zone between 08:00 and 20:00 the
 *   higher table applies. Nobody is fined by this app: it says what a camera at that spot would have cost.
 * - Insurance: 현대해상's monthly safe-driving rider for Bluelink, Kia Connect and Genesis Connected cars gives
 *   5 % off when at least 9 of the 12 months before renewal score 70 or more (from 2025-07). This score is not
 *   the insurer's score; it only tracks the same threshold so the driver sees where they stand.
 */
object ScoreCost {
    const val INSURANCE_MIN_SCORE = 70
    const val INSURANCE_MIN_MONTHS = 9
    const val INSURANCE_DISCOUNT_PERCENT = 5

    private val GENERAL_FINES = intArrayOf(40_000, 70_000, 100_000, 130_000)
    private val SCHOOL_ZONE_FINES = intArrayOf(70_000, 100_000, 130_000, 160_000)

    /** The camera fine for a passenger car [excessKmh] over the limit. */
    fun fineWon(excessKmh: Double, schoolZone: Boolean): Int {
        if (excessKmh <= 0) return 0
        val band = when {
            excessKmh <= 20 -> 0
            excessKmh <= 40 -> 1
            excessKmh <= 60 -> 2
            else -> 3
        }
        return (if (schoolZone) SCHOOL_ZONE_FINES else GENERAL_FINES)[band]
    }

    fun insurance(monthAverages: List<Int>, monthAverage: Int, annualPremiumWon: Int?) = InsuranceOutlook(
        monthAverage = monthAverage,
        goodMonths = monthAverages.count { it >= INSURANCE_MIN_SCORE },
        savingWon = annualPremiumWon?.takeIf { it > 0 }?.let { roundToThousand(it * INSURANCE_DISCOUNT_PERCENT / 100.0) },
    )

    /** "4만 원", "3만 7천 원", "8천 원": how an amount is said aloud, to the nearest thousand. */
    fun spoken(won: Int): String {
        val rounded = roundToThousand(won.toDouble())
        val man = rounded / 10_000
        val thousand = rounded % 10_000 / 1_000
        return when {
            man > 0 && thousand > 0 -> "${man}만 ${thousand}천 원"
            man > 0 -> "${man}만 원"
            thousand > 0 -> "${thousand}천 원"
            else -> "${won}원"
        }
    }

    private fun roundToThousand(won: Double) = (won / 1_000).roundToInt() * 1_000
}
