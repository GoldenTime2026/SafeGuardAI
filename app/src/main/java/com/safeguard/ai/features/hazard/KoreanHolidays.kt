package com.safeguard.ai.features.hazard

import java.time.LocalDate

/**
 * Seollal and Chuseok holiday periods (lunar, so listed per year).
 * Needs a yearly update; a public holiday API can replace this in stage 6.
 */
object KoreanHolidays {
    private val periods = listOf(
        LocalDate.of(2026, 2, 16) to LocalDate.of(2026, 2, 18), // 설날
        LocalDate.of(2026, 9, 24) to LocalDate.of(2026, 9, 26), // 추석
        LocalDate.of(2027, 2, 6) to LocalDate.of(2027, 2, 9),   // 설날 (대체공휴일 포함)
        LocalDate.of(2027, 9, 14) to LocalDate.of(2027, 9, 16), // 추석
    )

    fun isSeollalOrChuseok(date: LocalDate) = periods.any { (from, to) -> !date.isBefore(from) && !date.isAfter(to) }
}
