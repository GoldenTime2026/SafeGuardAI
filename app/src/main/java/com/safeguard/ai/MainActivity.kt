package com.safeguard.ai

import com.safeguard.ai.core.CarProperties
import com.safeguard.ai.core.Gear
import com.safeguard.ai.core.LocationSource
import com.safeguard.ai.core.VehicleGateway
import com.safeguard.ai.core.WiperLevel
import com.safeguard.ai.risk.RiskLevel
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Driving screen. Follows the Pleos glanceability rules: one large message, one number,
 * high contrast, no animation. Everything is driven by [SafeGuardService]; this only renders.
 */
class MainActivity : Activity() {

    private val scope = MainScope()

    private lateinit var status: TextView
    private lateinit var band: View
    private lateinit var message: TextView
    private lateinit var speed: TextView
    private lateinit var details: TextView
    private lateinit var guide: TextView
    private lateinit var crash: TextView
    private lateinit var report: TextView
    private lateinit var settings: Button
    private lateinit var scenarioRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        SafeGuardService.start(this)
        requestPermissionsIfNeeded()
        scope.launch { observe() }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun observe() {
        var core = SafeGuardService.core
        while (core == null) {
            delay(200)
            core = SafeGuardService.core
        }
        buildScenarioButtons(core.scenarios.available())
        core.ui.collect(::render)
    }

    private fun render(state: UiState) {
        status.text = when (state.vehicle) {
            VehicleGateway.State.CONNECTING -> "차량 연결 중…"
            VehicleGateway.State.READY -> "차량 연결됨"
            VehicleGateway.State.RECONNECTING -> "차량 재연결 중…"
            VehicleGateway.State.FAILED -> "차량 연결 실패"
        } + when (state.serverOnline) {
            true -> "   ·   위험 공유 연결됨"
            false -> "   ·   위험 공유 오프라인 (차량 기능은 정상)"
            null -> ""
        }

        val event = state.active
        val crashed = state.crash != null
        val color = event?.level?.ambientColor ?: when {
            crashed -> RiskLevel.CRASH.ambientColor!!
            event == null -> SAFE_COLOR
            else -> NEUTRAL_COLOR
        }
        band.setBackgroundColor(color)
        // Between spoken crash messages the screen must not fall back to "safe".
        message.text = event?.screenText ?: if (crashed) state.crash!!.headline else "안전 주행 중"
        val alarming = crashed || event?.level == RiskLevel.CRASH || event?.level == RiskLevel.CRITICAL_WARNING
        message.setTextColor(if (alarming) color else Color.WHITE)

        val s = state.snapshot
        val w = state.weather
        speed.text = s.speedKmh?.let { "%.0f".format(it) } ?: "--"
        val over = w.recommendedKmh != null && (s.speedKmh ?: 0.0) > w.recommendedKmh
        speed.setTextColor(if (over) OVER_COLOR else Color.WHITE)
        val limit = if (w.condition.isAdverse && w.recommendedKmh != null) {
            "${w.condition.label} 권장 ${w.recommendedKmh} (제한 ${s.roadSpeedLimitKmh})"
        } else {
            "${w.condition.label} · 제한 ${s.roadSpeedLimitKmh ?: "--"}"
        }
        details.text = listOf(
            limit,
            s.outsideTempC?.let { "외기 ${"%.0f".format(it)}℃" },
            "와이퍼 ${s.wiper.label()}",
            "기어 ${s.gear.label()}",
            "경고등 ${if (s.telltales.isEmpty()) "없음" else s.telltales.keys.joinToString()}",
        ).filterNotNull().joinToString("   ·   ")

        // Step-by-step guidance is reading material: parked only (Pleos driver-distraction rules).
        // After a crash the 119 location is the only thing worth reading; it replaces every other card.
        val crashCard = state.crash
        crash.visibility = if (crashCard == null) View.GONE else View.VISIBLE
        if (crashCard != null) {
            crash.text = buildString {
                append("${crashCard.title} ${crashCard.time}")
                crashCard.location.forEach { append("\n· $it") }
                append("\n\n보호자 알림: ${crashCard.guardianStatus}")
                crashCard.guardianMessage?.let { append("\n$it") }
            }
        }

        val card = state.guide?.takeIf { s.isParked && crashCard == null }
        guide.visibility = if (card == null) View.GONE else View.VISIBLE
        if (card != null) {
            guide.text = buildString {
                append("[${card.severity.label}] ${card.name}")
                card.steps.forEachIndexed { i, step -> append("\n${i + 1}. $step") }
            }
        }

        // The trip summary and the maintenance line are reading material too: parked only.
        val parkedInfo = s.isParked && crashCard == null && card == null
        val trip = state.trip?.takeIf { parkedInfo }
        val due = state.maintenance.filter { it.due }.takeIf { parkedInfo }.orEmpty()
        report.visibility = if (trip == null && due.isEmpty()) View.GONE else View.VISIBLE
        report.text = buildString {
            if (trip != null) {
                append("세이프가드 스코어 ${trip.score}점 · ${trip.minutes}분 주행")
                trip.weekAverage?.let { append(" · 이번 주 평균 ${it}점") }
                append("\n급정거 ${trip.hardBrakes} · 과속 ${trip.overspeedSeconds}초 · 급조작 ${trip.sharpTurns}")
                append(" · 위험 대응 ${trip.warningsHeeded}/${trip.warningsHeeded + trip.warningsIgnored}")
                if (trip.seatBeltUnbuckled) append(" · 안전벨트 미착용")
                if (trip.fineWon > 0) append("\n과속 ${trip.speedingEpisodes}구간 · 단속됐다면 과태료 ${"%,d".format(trip.fineWon)}원")
                trip.insurance?.let { o ->
                    append("\n이번 달 평균 ${o.monthAverage}점 · 보험 할인 기준(월 70점) 넘은 달 ${o.goodMonths}/9")
                    o.savingWon?.let { append(" · 달성 시 약 ${"%,d".format(it)}원 절약") }
                }
                if (trip.weekContributions > 0) append("\n이번 주 뒤차에 알린 위험 ${trip.weekContributions}건")
            }
            due.forEach {
                if (isNotEmpty()) append("\n")
                val km = if (it.remainingKm >= 0) "%,d km 남음".format(it.remainingKm) else "%,d km 지남".format(-it.remainingKm)
                append("정비: ${it.item.label} $km")
            }
        }
        settings.visibility = if (s.isParked || s.speedKmh == null) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ layout

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(64, 40, 64, 32)
        }
        status = text(22f, Color.GRAY).also { root.addView(it) }
        band = View(this).also {
            root.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, 16).apply { topMargin = 24 })
        }
        message = text(56f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 24)
        }.also { root.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)) }

        val speedRow = LinearLayout(this).apply { gravity = Gravity.CENTER or Gravity.BOTTOM }
        speed = text(120f, Color.WHITE, bold = true).also { speedRow.addView(it) }
        speedRow.addView(text(32f, Color.GRAY).apply { text = " km/h"; setPadding(0, 0, 0, 28) })
        root.addView(speedRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        details = text(26f, Color.LTGRAY).apply { gravity = Gravity.CENTER }
            .also { root.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 16 }) }

        guide = text(28f, Color.WHITE).apply {
            setBackgroundColor(GUIDE_BACKGROUND)
            setPadding(32, 24, 32, 24)
            setLineSpacing(8f, 1f)
            visibility = View.GONE
        }.also { root.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 16 }) }

        crash = text(30f, Color.WHITE).apply {
            setBackgroundColor(CRASH_BACKGROUND)
            setPadding(36, 28, 36, 28)
            setLineSpacing(10f, 1f)
            visibility = View.GONE
        }.also { root.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 16 }) }

        report = text(26f, Color.WHITE).apply {
            setBackgroundColor(GUIDE_BACKGROUND)
            setPadding(32, 20, 32, 20)
            setLineSpacing(6f, 1f)
            visibility = View.GONE
        }.also { root.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 16 }) }

        settings = Button(this).apply {
            text = "설정 · 보호자 · 정비 기록"
            textSize = 18f
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }

        scenarioRow = LinearLayout(this)
        root.addView(
            HorizontalScrollView(this).apply { addView(scenarioRow) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 24 },
        )
        return root
    }

    /** Demo controls; scenarios also run from adb so the screen stays clean during recording. */
    private fun buildScenarioButtons(names: List<String>) {
        scenarioRow.removeAllViews()
        scenarioRow.addView(settings)
        (names + SafeGuardService.STOP_SCENARIO).forEach { name ->
            scenarioRow.addView(Button(this).apply {
                text = if (name == SafeGuardService.STOP_SCENARIO) "시나리오 정지" else name
                textSize = 18f
                setOnClickListener { SafeGuardService.start(this@MainActivity) { putExtra(SafeGuardService.EXTRA_SCENARIO, name) } }
            })
        }
    }

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    // ------------------------------------------------------------------ permission

    private fun requestPermissionsIfNeeded() {
        // Notifications carry the crash alert that opens the 119 location screen over other apps.
        val missing = listOf(LocationSource.FUSED_LOCATION, CarProperties.CAR_SPEED, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_LOCATION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOCATION) return
        val granted = permissions.filterIndexed { i, _ -> grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED }
        SafeGuardService.start(this) {
            putExtra(SafeGuardService.EXTRA_LOCATION_GRANTED, LocationSource.FUSED_LOCATION in granted)
            putExtra(SafeGuardService.EXTRA_SPEED_GRANTED, CarProperties.CAR_SPEED in granted)
        }
    }

    companion object {
        private const val REQ_LOCATION = 1
        private const val SAFE_COLOR = 0xFF00C853.toInt()
        private const val NEUTRAL_COLOR = 0xFF616161.toInt()
        private const val OVER_COLOR = 0xFFFFAB00.toInt()
        private const val GUIDE_BACKGROUND = 0xFF263238.toInt()
        private const val CRASH_BACKGROUND = 0xFF7F0000.toInt()
    }
}

private fun WiperLevel.label() = when (this) {
    WiperLevel.UNKNOWN -> "--"
    WiperLevel.OFF -> "꺼짐"
    WiperLevel.INTERMITTENT -> "간헐"
    WiperLevel.LOW -> "저속"
    WiperLevel.HIGH -> "고속"
}

private fun Gear.label() = when (this) {
    Gear.UNKNOWN -> "--"
    Gear.PARK -> "P"
    Gear.REVERSE -> "R"
    Gear.NEUTRAL -> "N"
    Gear.DRIVE -> "D"
}
