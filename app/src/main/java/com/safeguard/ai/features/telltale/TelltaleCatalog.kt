package com.safeguard.ai.features.telltale

/** How urgent a warning light is, said first so the driver knows what to do. */
enum class Severity(val label: String) {
    STOP("즉시 정차"),
    SERVICE("정비소 방문 권장"),
    CHECK("주행 가능"),
}

/**
 * One warning light: [drivingSpeech] is the single sentence said while moving; [steps] are shown on screen
 * once parked. [needsService] adds the service-centre offer.
 */
data class TelltaleEntry(
    val type: String,
    val name: String,
    val severity: Severity,
    val drivingSpeech: String,
    val steps: List<String>,
    val needsService: Boolean = true,
    /** Some lights mean something more urgent when blinking (e.g. the engine light). */
    val blinkSpeech: String? = null,
    val blinkSeverity: Severity? = null,
    /** ESC blinks while it is working normally; only a steady light is a fault. */
    val steadyOnly: Boolean = false,
)

/**
 * The warning lights the guide covers (MVP: the common ones, by SDK TelltaleType name).
 * Wording follows the general guidance of Hyundai/Kia owner's manuals; it is kept generic on purpose and must be
 * checked against each model's manual before release ([확인 필요]).
 * Lights handled by other features are left out: ICY_ROAD (① weather), DRIVER_ATTENTION (④ drowsiness),
 * AIRBAG deployment (⑥ uses the deploy signal, not the light).
 */
object TelltaleCatalog {

    val entries: Map<String, TelltaleEntry> = listOf(
        TelltaleEntry(
            "ENGINE_OIL_PRESSURE", "엔진오일 압력 경고", Severity.STOP,
            "엔진오일 압력 경고예요. 안전한 곳에 차를 세우고 시동을 끄세요.",
            listOf(
                "평지에 세우고 시동을 끈 뒤 몇 분 기다리세요",
                "엔진오일 양을 확인하고 부족하면 보충하세요",
                "보충한 뒤에도 켜지면 운행하지 말고 긴급출동을 부르세요",
            ),
        ),
        TelltaleEntry(
            "ENGINE_TEMPERATURE", "엔진 과열 경고", Severity.STOP,
            "엔진 과열 경고예요. 안전한 곳에 차를 세우세요.",
            listOf(
                "안전한 곳에 세우고 에어컨을 끄세요",
                "엔진이 뜨거울 때는 냉각수 캡을 열지 마세요 (화상 위험)",
                "엔진이 식은 뒤 냉각수 양을 확인하세요",
                "계속 켜지면 운행하지 말고 점검을 받으세요",
            ),
        ),
        TelltaleEntry(
            "LOW_VOLT_BATTERY", "충전 장치 경고", Severity.STOP,
            "충전 장치 경고예요. 전기 장치를 줄이고 가까운 안전한 곳에 세우세요.",
            listOf(
                "열선, 오디오 등 필요 없는 전기 장치를 끄세요",
                "시동이 꺼질 수 있으니 오래 달리지 마세요",
                "가까운 정비소에서 충전 계통 점검을 받으세요",
            ),
        ),
        TelltaleEntry(
            "PARKING_BRAKE", "브레이크 경고", Severity.STOP,
            "브레이크 경고예요. 주차 브레이크가 풀렸는지 확인하고, 그래도 켜져 있으면 안전한 곳에 세우세요.",
            listOf(
                "주차 브레이크가 완전히 풀렸는지 확인하세요",
                "풀려 있는데도 켜져 있으면 브레이크액이 부족할 수 있어요",
                "브레이크액을 확인하고 점검을 받으세요",
            ),
        ),
        TelltaleEntry(
            "EV_SYSTEM", "전기차 시스템 경고", Severity.STOP,
            "전기차 시스템 경고예요. 안전한 곳에 세우고 점검을 받으세요.",
            listOf("안전한 곳에 세우세요", "다시 시동을 걸어도 켜져 있으면 운행하지 말고 긴급출동을 부르세요"),
        ),
        TelltaleEntry(
            "HEV_SYSTEM", "하이브리드 시스템 경고", Severity.STOP,
            "하이브리드 시스템 경고예요. 안전한 곳에 세우고 점검을 받으세요.",
            listOf("안전한 곳에 세우세요", "다시 시동을 걸어도 켜져 있으면 운행하지 말고 긴급출동을 부르세요"),
        ),
        TelltaleEntry(
            "DOOR_OPEN", "문 열림", Severity.STOP,
            "문이 열려 있어요. 안전한 곳에 세우고 닫으세요.",
            listOf("모든 문이 끝까지 닫혔는지 확인하세요"),
            needsService = false,
        ),
        TelltaleEntry(
            "TRUNK_OPEN", "트렁크 열림", Severity.STOP,
            "트렁크가 열려 있어요. 안전한 곳에 세우고 닫으세요.",
            listOf("트렁크가 끝까지 닫혔는지 확인하세요"),
            needsService = false,
        ),
        TelltaleEntry(
            "MDPS", "조향 장치 경고", Severity.SERVICE,
            "조향 장치 경고예요. 핸들이 무거워질 수 있으니 속도를 줄이고 점검을 받으세요.",
            listOf("핸들이 무거워질 수 있어요. 두 손으로 잡고 천천히 운전하세요", "가까운 정비소에서 점검을 받으세요"),
        ),
        TelltaleEntry(
            "ABS", "ABS 경고", Severity.SERVICE,
            "ABS 경고예요. 일반 제동은 되지만 급제동 때 바퀴가 잠길 수 있어요. 점검을 받으세요.",
            listOf("급제동·급출발을 피하고 차간 거리를 넓히세요", "가까운 정비소에서 점검을 받으세요"),
        ),
        TelltaleEntry(
            "ESC", "차체 자세 제어 장치 경고", Severity.SERVICE,
            "차체 자세 제어 장치 경고예요. 미끄러운 길에서 특히 조심하고 점검을 받으세요.",
            listOf("미끄러운 길에서는 속도를 줄이세요", "계속 켜져 있으면 점검을 받으세요"),
            steadyOnly = true,
        ),
        TelltaleEntry(
            "ELECTRONIC_PARKING_BRAKE", "전자식 주차 브레이크 경고", Severity.SERVICE,
            "전자식 주차 브레이크 경고예요. 가까운 정비소에서 점검을 받으세요.",
            listOf("주차할 때는 P단에 두고 경사로 주차를 피하세요", "가까운 정비소에서 점검을 받으세요"),
        ),
        TelltaleEntry(
            "AIRBAG", "에어백 경고", Severity.SERVICE,
            "에어백 경고예요. 사고 때 에어백이 작동하지 않을 수 있어요. 빨리 점검을 받으세요.",
            listOf("주행은 할 수 있지만 에어백이 작동하지 않을 수 있어요", "되도록 빨리 점검을 받으세요"),
        ),
        TelltaleEntry(
            "TPMS", "타이어 공기압 경고", Severity.SERVICE,
            "타이어 공기압 경고예요. 속도를 줄이고 가까운 곳에서 공기압을 확인하세요.",
            listOf(
                "안전한 곳에 세우고 타이어 네 개를 눈으로 확인하세요",
                "운전석 문 옆 라벨에 적힌 적정 공기압으로 채우세요",
                "펑크가 의심되면 무리해서 달리지 말고 긴급출동을 부르세요",
            ),
        ),
        TelltaleEntry(
            "ENGINE", "엔진 점검 경고", Severity.SERVICE,
            "엔진 점검 경고예요. 당장 멈출 필요는 없지만 빨리 점검을 받으세요.",
            listOf("주유구 캡이 잘 닫혔는지 확인하세요", "가까운 정비소에서 진단을 받으세요"),
            blinkSpeech = "엔진 점검 경고등이 깜빡여요. 속도를 줄이고 바로 점검을 받으세요.",
            blinkSeverity = Severity.STOP,
        ),
        TelltaleEntry(
            "PARTICLE_FILTER", "매연 필터 경고", Severity.CHECK,
            "매연 필터 경고예요. 계속 켜져 있으면 점검을 받으세요.",
            listOf("일정 시간 고속으로 달리면 필터가 재생되면서 꺼질 수 있어요", "계속 켜져 있으면 점검을 받으세요"),
        ),
        TelltaleEntry(
            "LOW_FUEL", "연료 부족", Severity.CHECK,
            "연료가 얼마 남지 않았어요. 가까운 주유소에 들르세요.",
            listOf("가까운 주유소에서 주유하세요"),
            needsService = false,
        ),
        TelltaleEntry(
            "LOW_EV_BATTERY", "배터리 부족", Severity.CHECK,
            "배터리가 얼마 남지 않았어요. 가까운 충전소에 들르세요.",
            listOf("가까운 충전소에서 충전하세요"),
            needsService = false,
        ),
        TelltaleEntry(
            "MASTER_WARNING", "주의 경고", Severity.CHECK,
            "계기판에 확인할 경고 메시지가 있어요. 차를 세운 뒤 확인하세요.",
            listOf("계기판의 경고 메시지를 확인하세요"),
            needsService = false,
        ),
    ).associateBy { it.type }
}
