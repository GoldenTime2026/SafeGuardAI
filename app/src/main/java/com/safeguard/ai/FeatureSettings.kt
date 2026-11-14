package com.safeguard.ai

import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import com.safeguard.ai.risk.RiskRule
import android.content.Context

/**
 * Guidance the driver can switch off when they don't want to hear it. Each switch silences what that feature
 * says; the feature keeps running underneath (the weather coach still feeds the recommended speed to the others).
 *
 * Never switchable: crash care, emergency-stop care and anything urgent ([RiskLevel.urgent]), and warnings about
 * hazards other cars confirmed ahead. A driver who mutes the statistics should still hear about a crash ahead.
 */
enum class Feature(val label: String, val note: String, val defaultOn: Boolean = true) {
    WEATHER("날씨·노면 안내", "비·안개·결빙 시작, 권장 속도"),
    HOTSPOT("사고다발지역 안내", "TAAS 통계 지점 (앞차가 확인한 위험은 항상 안내)"),
    BRIEFING("출발 브리핑", "시동 후 날씨·경고등·주변 주의 지점"),
    TRAFFIC("정체 브리핑", "정체 원인·우회 제안, 긴 정체 휴식 모드"),
    SCHOOL_ROUTE("스쿨존 회피 경로 제안", "등하교 시간 경로 변경 제안"),
    DROWSY("졸음 휴식 권유", "연속 주행·졸음 경고등 휴식 권유"),
    TELLTALE("경고등 안내", "정비가 필요한 경고등 (위급한 경고등은 항상 안내)"),
    MAINTENANCE("정비 알림", "엔진오일·타이어·필터 교체 주기"),
    DISASTER("재난문자 안내", "지역·경로 앞 재난문자, 차량 신호 결합"),
    SCORE("운전 점수 안내", "주차 때 점수·과태료·보험 환산 (점수 기록은 계속)"),
    SHARING("위험 자동 공유", "급정거·사고·빙판을 익명으로 뒤차와 Safety Mesh에 (음성 제보는 그대로)"),
    PARKING("주차 안내", "주차할 때 침수·대설·한파·창문 열림"),
    ONTIME("제시간 도착 안내", "공항·KTX역으로 갈 때 수속 마감까지 여유 시간"),
    LOGBOOK("업무 운행기록부", "주차할 때 업무 여부를 묻고 계기판 거리로 기록 (업무용 차량만)", defaultOn = false),
}

class FeatureSettings(context: Context) {

    private val prefs = context.getSharedPreferences("feature_settings", Context.MODE_PRIVATE)

    fun enabled(feature: Feature): Boolean = prefs.getBoolean(feature.name, feature.defaultOn)

    fun set(feature: Feature, on: Boolean) = prefs.edit().putBoolean(feature.name, on).apply()

    /** The yearly car-insurance premium in won, for the score's discount sentence; null when not entered. */
    var annualPremiumWon: Int?
        get() = prefs.getInt(PREMIUM, 0).takeIf { it > 0 }
        set(value) = prefs.edit().putInt(PREMIUM, value ?: 0).apply()

    private companion object {
        const val PREMIUM = "annual_premium_won"
    }
}

/**
 * A rule whose guidance follows a switch: while off, only what [mutable] picks is dropped and urgent events
 * always pass, so a switch can't silence a critical warning.
 */
fun switchable(
    rule: RiskRule,
    on: () -> Boolean,
    mutable: (RiskEvent) -> Boolean = { true },
) = RiskRule { s, now ->
    val events = rule.evaluate(s, now)
    if (on()) events else events.filterNot { mutable(it) && !it.level.urgent }
}
