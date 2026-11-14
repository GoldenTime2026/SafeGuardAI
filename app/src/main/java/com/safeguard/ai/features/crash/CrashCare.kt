package com.safeguard.ai.features.crash

import com.safeguard.ai.core.NaviAddress
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.data.publicdata.Geo
import com.safeguard.ai.data.publicdata.Place
import com.safeguard.ai.data.publicdata.Places
import com.safeguard.ai.risk.Confirm
import com.safeguard.ai.risk.Reply
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import com.safeguard.ai.risk.RiskRule
import com.safeguard.ai.risk.spokenDistance
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the screen shows after a crash: where to tell 119, and what happened with the guardian alert. */
data class CrashCard(
    val time: String,
    val location: List<String>,
    val guardianStatus: String,
    val guardianMessage: String? = null,
    val title: String = "119에 말할 위치 · 사고",
    /** The line kept on screen between spoken messages. */
    val headline: String = "사고 감지 · 아래 위치를 119에 알려 주세요",
)

/**
 * Feature ⑥ — crash golden-time care (benchmark: BMW Intelligent Emergency Call).
 * It complements the car's own SOS call: it checks on the driver, alerts the guardian, shows the location to
 * read out to 119, and tells everyone how to avoid a second crash.
 *
 * Airbag deployed → "괜찮으세요?" (10 s) → silence → asked again (20 s) → silence or a call for help:
 * guardian alert + 119 location on screen + evacuation steps. Any other reply means the driver is conscious:
 * evacuation steps, location on screen, and the guardian alert is offered.
 * While active, every other feature is muted (see SafeGuardCore). It ends once the car drives on for 30 s.
 */
class CrashCare(
    private val scope: CoroutineScope,
    private val say: (RiskEvent) -> Unit,
    private val address: suspend () -> NaviAddress?,
    private val landmarks: () -> List<Place>,
    private val guardian: GuardianNotifier,
    /** Called the moment the airbag deploys, before asking the driver: the screen may not survive the crash. */
    private val onCrash: (VehicleSnapshot) -> Unit = {},
    /** The car's own clock, so the time matches what the driver sees on the head unit. */
    private val zone: ZoneId = ZoneId.systemDefault(),
) : RiskRule {

    private val _card = MutableStateFlow<CrashCard?>(null)
    val card: StateFlow<CrashCard?> = _card.asStateFlow()

    @Volatile var active = false
        private set
    private var wasDeployed = false
    private var movingSince: Long? = null
    private var crashAt = 0L
    private var crashSnapshot = VehicleSnapshot()
    private var resolvedAddress: String? = null
    private var guardianAlerted = false

    override fun evaluate(snapshot: VehicleSnapshot, now: Long): List<RiskEvent> {
        val deployedNow = snapshot.airbagDeployed && !wasDeployed
        wasDeployed = snapshot.airbagDeployed
        if (!active) return if (deployedNow) listOf(start(snapshot, now)) else emptyList()

        // Driving on means the episode is over (minor collision, or the car has been moved).
        if (snapshot.isMoving) {
            val since = movingSince ?: now.also { movingSince = it }
            if (now - since >= DRIVE_ON_MS) reset()
        } else {
            movingSince = null
        }
        return emptyList()
    }

    /** Ends the episode (car driven on, or a scenario reset). */
    fun reset() {
        active = false
        movingSince = null
        guardianAlerted = false
        resolvedAddress = null
        _card.value = null
    }

    private fun start(s: VehicleSnapshot, now: Long): RiskEvent {
        active = true
        crashAt = now
        crashSnapshot = s
        Log.w(TAG, "airbag deployed at ${s.latitude},${s.longitude}")
        onCrash(s)
        _card.value = CrashCard(clock(now), describeLocation(s, null, landmarks()), "운전자 응답 확인 중")
        scope.launch {
            resolvedAddress = address()?.takeIf { matches(it, crashSnapshot) }?.address
            val shown = resolvedAddress ?: ADDRESS_UNKNOWN
            _card.value = _card.value?.copy(location = describeLocation(crashSnapshot, shown, landmarks()))
        }
        return askEvent(first = true)
    }

    private fun askEvent(first: Boolean) = RiskEvent(
        key = if (first) "crash-ask" else "crash-ask-again",
        level = RiskLevel.CRASH,
        speech = if (first) "사고가 감지됐어요. 괜찮으세요? 말씀해 주세요." else "괜찮으세요? 대답이 없으면 보호자에게 알릴게요.",
        screenText = "사고 감지 · 괜찮으세요?",
        cooldownMs = 0L,
        ttlMs = EVENT_TTL_MS,
        reply = Reply(if (first) FIRST_LISTEN_MS else SECOND_LISTEN_MS) { heard -> onReply(heard, first) },
    )

    private suspend fun onReply(heard: String?, first: Boolean) {
        if (!active) return
        when (classifyReply(heard)) {
            CrashReply.HELP -> emergency("도움 요청")
            CrashReply.SILENT -> if (first) say(askEvent(first = false)) else emergency("응답 없음")
            CrashReply.CONSCIOUS -> {
                _card.value = _card.value?.copy(guardianStatus = "운전자 응답 있음 · 보호자 알림 대기")
                say(
                    RiskEvent(
                        key = "crash-conscious",
                        level = RiskLevel.CRASH,
                        speech = "다행이에요. $EVACUATION 신고할 위치는 화면에 있어요. 보호자에게 알릴까요?",
                        screenText = "2차 사고 예방 · 119 신고 위치",
                        cooldownMs = 0L,
                        ttlMs = EVENT_TTL_MS,
                        confirm = Confirm(
                            yesSpeech = "보호자에게 알릴게요.",
                            noSpeech = "알겠어요. 위치는 화면에 계속 띄워 둘게요.",
                            onYes = { alertGuardian("응답 있음") },
                        ),
                    )
                )
            }
        }
    }

    private suspend fun emergency(reason: String) {
        val delivered = alertGuardian(reason)
        val guardianLine = if (delivered) "보호자에게 사고 위치를 보냈어요." else "보호자에게 보낼 사고 위치 문자를 화면에 띄웠어요."
        say(
            RiskEvent(
                key = "crash-emergency",
                level = RiskLevel.CRASH,
                speech = "$guardianLine 119에 신고할 위치도 화면에 있어요. $EVACUATION",
                screenText = "119 신고 위치 · 대피하세요",
                cooldownMs = 0L,
                ttlMs = EVENT_TTL_MS,
            )
        )
    }

    private suspend fun alertGuardian(reason: String): Boolean {
        if (guardianAlerted) return true
        guardianAlerted = true
        val message = guardianMessage(crashSnapshot, resolvedAddress, landmarks(), clock(crashAt), reason)
        val delivered = guardian.notify(message)
        _card.value = _card.value?.copy(
            guardianStatus = if (delivered) "${guardian.name}에게 보냄 ($reason)" else "${guardian.name} 알림 · ${guardian.undeliveredNote} ($reason)",
            guardianMessage = message,
        )
        return delivered
    }

    private fun clock(now: Long) = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(now).atZone(zone))

    enum class CrashReply { CONSCIOUS, HELP, SILENT }

    companion object {
        private const val TAG = "SafeGuard/Crash"
        private const val FIRST_LISTEN_MS = 10_000L
        private const val SECOND_LISTEN_MS = 20_000L
        private const val EVENT_TTL_MS = 60_000L
        private const val DRIVE_ON_MS = 30_000L
        private const val LANDMARK_RADIUS_M = 20_000.0
        private const val ADDRESS_MATCH_M = 300.0
        private const val ADDRESS_UNKNOWN = "주소를 확인하지 못했어요 (아래 위치로 알려 주세요)"

        /** 한국도로공사 2차 사고 예방 행동요령 '비트밖스': 비상등 → 트렁크 → 밖으로 대피 → 스마트폰 신고. */
        const val EVACUATION = "움직일 수 있으면 비상등을 켜고, 트렁크를 열고, 가드레일 밖으로 대피한 뒤 신고하세요."

        private val HELP_WORDS = listOf("도와", "살려", "아파", "아프", "다쳤", "다쳐", "119", "구급", "신고해", "못 움직", "못움직")

        /**
         * After "괜찮으세요?", any words mean the driver is conscious — "괜찮아" here is good news, unlike a yes/no
         * question. Words asking for help, or silence, raise the alert.
         */
        fun classifyReply(text: String?): CrashReply {
            val t = text?.trim().orEmpty()
            return when {
                t.isEmpty() -> CrashReply.SILENT
                HELP_WORDS.any { t.contains(it) } -> CrashReply.HELP
                else -> CrashReply.CONSCIOUS
            }
        }

        /** Lines a person can read out to 119: address, a nearby landmark, direction of travel, coordinates. */
        fun describeLocation(s: VehicleSnapshot, address: String?, landmarks: List<Place>): List<String> {
            val lat = s.latitude
            val lon = s.longitude
            if (lat == null || lon == null) return listOf("위치를 아직 받지 못했어요")
            return listOfNotNull(
                address ?: "주소 확인 중",
                Places.nearest(landmarks, lat, lon, LANDMARK_RADIUS_M)?.let { (p, d) ->
                    val from = compass(Geo.bearingDeg(p.latitude, p.longitude, lat, lon))
                    "${p.name} 졸음쉼터${p.detail.substringBefore(' ').takeIf { it.isNotBlank() }?.let { "($it)" } ?: ""}에서 ${from}쪽 ${spokenDistance(d)}"
                },
                s.headingDeg?.let { "${compass(it.toDouble())}쪽으로 가던 중" },
                "좌표 ${"%.5f".format(Locale.US, lat)}, ${"%.5f".format(Locale.US, lon)}",
            )
        }

        fun guardianMessage(
            s: VehicleSnapshot,
            address: String?,
            landmarks: List<Place>,
            time: String,
            reason: String,
            what: String = "차량 사고 감지(에어백 전개)",
        ): String {
            val where = describeLocation(s, address, landmarks).filterNot { it == "주소 확인 중" }.joinToString(" / ")
            val map = if (s.latitude != null && s.longitude != null) {
                " 지도: https://map.kakao.com/link/map/사고위치,${"%.5f".format(Locale.US, s.latitude)},${"%.5f".format(Locale.US, s.longitude)}"
            } else {
                ""
            }
            return "[세이프가드] $time $what. 운전자: $reason. 위치: $where.$map"
        }

        /**
         * Navigation answers for its own idea of the car's position; only trust the address if that is where the
         * crash happened (a scenario's injected position, or a stale fix, would otherwise name another town).
         */
        fun matches(a: NaviAddress, s: VehicleSnapshot): Boolean {
            val lat = s.latitude ?: return false
            val lon = s.longitude ?: return false
            return Geo.distanceMeters(lat, lon, a.latitude, a.longitude) <= ADDRESS_MATCH_M
        }

        /** Eight-point compass in Korean, for speech and screen. */
        fun compass(deg: Double): String {
            val names = listOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")
            return names[(((deg % 360) + 360) % 360 / 45.0 + 0.5).toInt() % 8]
        }
    }
}
