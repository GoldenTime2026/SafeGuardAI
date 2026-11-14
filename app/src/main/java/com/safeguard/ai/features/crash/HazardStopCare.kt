package com.safeguard.ai.features.crash

import com.safeguard.ai.core.NaviAddress
import com.safeguard.ai.core.VehicleSnapshot
import com.safeguard.ai.data.publicdata.Place
import com.safeguard.ai.data.publicdata.Places
import com.safeguard.ai.risk.Confirm
import com.safeguard.ai.risk.RiskEvent
import com.safeguard.ai.risk.RiskLevel
import com.safeguard.ai.risk.RiskRule
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Feature ⑥ extended — a stop on a fast road with hazard lights on and no airbag.
 *
 * The OEM services (Hyundai Bluelink SOS/airbag SMS, Kia/Genesis airbag auto-notification) act only when an
 * airbag deploys; rear-end, glancing and rollover collisions, breakdowns and minor bumps are outside them, yet a
 * car standing on a highway is exactly where second collisions happen. The cars behind are already warned by the
 * hazard rule (STOPPED_VEHICLE share); this rule looks after the people in the stopped car:
 * evacuation steps, the location for 112/119 and the expressway tow line, and the guardian alert — offered,
 * never automatic, because a hazard-light stop may be nothing more than a jam.
 */
class HazardStopCare(
    private val scope: CoroutineScope,
    private val address: suspend () -> NaviAddress?,
    private val landmarks: () -> List<Place>,
    private val guardian: GuardianNotifier,
    /** An airbag episode takes over: it has its own questions and its own alert. */
    private val crashActive: () -> Boolean,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : RiskRule {

    private val _card = MutableStateFlow<CrashCard?>(null)
    val card: StateFlow<CrashCard?> = _card.asStateFlow()

    @Volatile var active = false
        private set
    private var stoppedSince: Long? = null
    private var movingSince: Long? = null
    private var stopAt = 0L
    private var stopSnapshot = VehicleSnapshot()
    private var resolvedAddress: String? = null
    private var guardianAlerted = false

    override fun evaluate(snapshot: VehicleSnapshot, now: Long): List<RiskEvent> {
        if (crashActive()) {
            if (active || stoppedSince != null) reset()
            return emptyList()
        }
        if (active) {
            // Driving on ends it; the hazard lights going off alone does not (the driver may have walked away).
            if (snapshot.isMoving) {
                val since = movingSince ?: now.also { movingSince = it }
                if (now - since >= DRIVE_ON_MS) reset()
            } else {
                movingSince = null
            }
            return emptyList()
        }
        if (!isEmergencyStop(snapshot, landmarks())) {
            stoppedSince = null
            return emptyList()
        }
        val since = stoppedSince ?: now.also { stoppedSince = it }
        return if (now - since >= HOLD_MS) listOf(start(snapshot, now)) else emptyList()
    }

    fun reset() {
        active = false
        stoppedSince = null
        movingSince = null
        guardianAlerted = false
        resolvedAddress = null
        _card.value = null
    }

    private fun start(s: VehicleSnapshot, now: Long): RiskEvent {
        active = true
        stopAt = now
        stopSnapshot = s
        Log.w(TAG, "hazard-light stop on a ${s.roadSpeedLimitKmh} km/h road at ${s.latitude},${s.longitude}")
        _card.value = CrashCard(clock(now), location(null), "보호자 알림 대기", title = TITLE, headline = HEADLINE)
        scope.launch {
            resolvedAddress = address()?.takeIf { CrashCare.matches(it, stopSnapshot) }?.address
            _card.value = _card.value?.copy(location = location(resolvedAddress ?: ADDRESS_UNKNOWN))
        }
        return RiskEvent(
            key = "hazard-stop",
            level = RiskLevel.CRITICAL_WARNING,
            speech = "고속도로에 비상등을 켜고 멈춰 있어요. 이럴 때는 뒤차에 받히는 2차 사고가 가장 위험해요. " +
                "트렁크를 열고, 가드레일 밖으로 대피한 뒤 신고하세요. 신고할 위치는 화면에 있어요. 보호자에게 위치를 알릴까요?",
            screenText = "비상 정차 · 가드레일 밖으로 대피하세요",
            cooldownMs = 0L,
            ttlMs = EVENT_TTL_MS,
            confirm = Confirm(
                yesSpeech = "보호자에게 알릴게요.",
                noSpeech = "알겠어요. 위치는 화면에 계속 띄워 둘게요.",
                onYes = { alertGuardian() },
            ),
        )
    }

    private suspend fun alertGuardian() {
        if (guardianAlerted) return
        guardianAlerted = true
        val message = CrashCare.guardianMessage(
            stopSnapshot, resolvedAddress, landmarks(), clock(stopAt), "운전자 요청", what = MESSAGE_WHAT,
        )
        val delivered = guardian.notify(message)
        _card.value = _card.value?.copy(
            guardianStatus = if (delivered) "${guardian.name}에게 보냄" else "${guardian.name} 알림 · ${guardian.undeliveredNote}",
            guardianMessage = message,
        )
    }

    private fun location(address: String?) = CrashCare.describeLocation(stopSnapshot, address, landmarks()) + TOW_LINE

    private fun clock(now: Long) = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(now).atZone(zone))

    companion object {
        private const val TAG = "SafeGuard/HazardStop"
        /** Long enough that hazard lights flashed at the tail of a jam, then switched off, don't count. */
        private const val HOLD_MS = 30_000L
        private const val EVENT_TTL_MS = 60_000L
        private const val DRIVE_ON_MS = 30_000L
        /** Expressways and motorway-grade roads; a stop on a 50 km/h street is not a second-collision trap. */
        private const val FAST_ROAD_KMH = 80
        /** Stopping inside a rest area with hazards on is a safe stop. */
        private const val REST_AREA_M = 300.0
        private const val TITLE = "비상 정차 · 신고할 위치"
        private const val HEADLINE = "비상 정차 · 가드레일 밖으로 대피하세요"
        private const val ADDRESS_UNKNOWN = "주소를 확인하지 못했어요 (아래 위치로 알려 주세요)"
        private const val TOW_LINE = "고속도로 긴급견인(무료) 1588-2504"
        private const val MESSAGE_WHAT = "고속도로 비상 정차(에어백 미전개)"

        fun isEmergencyStop(s: VehicleSnapshot, restAreas: List<Place>): Boolean {
            if (!s.hazardOn || s.isMoving || s.airbagDeployed) return false
            if ((s.roadSpeedLimitKmh ?: 0) < FAST_ROAD_KMH) return false
            val lat = s.latitude ?: return false
            val lon = s.longitude ?: return false
            return Places.nearest(restAreas, lat, lon, REST_AREA_M) == null
        }
    }
}
