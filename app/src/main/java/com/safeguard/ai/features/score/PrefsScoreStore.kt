package com.safeguard.ai.features.score

import android.content.Context
import kotlin.math.roundToInt

/** Scores and contribution times kept on the car only, as "time:score" lists trimmed to the last [KEEP]. */
class PrefsScoreStore(context: Context) : ScoreStore {

    private val prefs = context.getSharedPreferences("safety_score", Context.MODE_PRIVATE)

    @Synchronized
    override fun saveTrip(at: Long, score: Int) = append(TRIPS, "$at:$score")

    override fun tripsSince(since: Long): List<Int> =
        read(TRIPS).mapNotNull { entry ->
            val (at, score) = entry.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
            score.toIntOrNull()?.takeIf { (at.toLongOrNull() ?: 0L) >= since }
        }

    @Synchronized
    override fun addContribution(at: Long) = append(CONTRIBUTIONS, at.toString())

    override fun contributionsSince(since: Long): Int = read(CONTRIBUTIONS).count { (it.toLongOrNull() ?: 0L) >= since }

    /** "2026-10:sum:count" per month, the last [KEEP_MONTHS]. */
    @Synchronized
    override fun addToMonth(month: String, score: Int) {
        val months = read(MONTHS).associate { it.substringBefore(":") to it.substringAfter(":") }.toMutableMap()
        val (sum, count) = months[month]?.split(":")?.map { it.toIntOrNull() ?: 0 } ?: listOf(0, 0)
        months[month] = "${sum + score}:${count + 1}"
        val kept = months.toSortedMap().entries.toList().takeLast(KEEP_MONTHS).map { "${it.key}:${it.value}" }
        prefs.edit().putString(MONTHS, kept.joinToString(",")).apply()
    }

    override fun monthAverage(month: String): Int? {
        val entry = read(MONTHS).firstOrNull { it.startsWith("$month:") } ?: return null
        val (sum, count) = entry.substringAfter(":").split(":").map { it.toIntOrNull() ?: 0 }
        return if (count > 0) (sum.toDouble() / count).roundToInt() else null
    }

    private fun read(key: String): List<String> = prefs.getString(key, "").orEmpty().split(",").filter { it.isNotBlank() }

    private fun append(key: String, entry: String) {
        prefs.edit().putString(key, (read(key) + entry).takeLast(KEEP).joinToString(",")).apply()
    }

    private companion object {
        const val TRIPS = "trips"
        const val CONTRIBUTIONS = "contributions"
        const val MONTHS = "months"
        const val KEEP = 100
        const val KEEP_MONTHS = 13
    }
}
