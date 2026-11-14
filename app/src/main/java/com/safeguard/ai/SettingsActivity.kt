package com.safeguard.ai

import com.safeguard.ai.features.crash.GuardianContact
import com.safeguard.ai.features.logbook.TripEntry
import com.safeguard.ai.features.logbook.TripLog
import com.safeguard.ai.features.logbook.TripPurpose
import com.safeguard.ai.features.maintenance.ServiceStatus
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Parked-only settings: the guardian to alert after a crash, logging a service so the maintenance reminder
 * counts from it, which guidance to hear, and the insurance premium the score converts into won. Not marked distraction-optimized, so the car blocks it while driving; it also
 * closes itself if the car starts moving.
 */
class SettingsActivity : Activity() {

    private val scope = MainScope()
    private lateinit var guardianLine: TextView
    private lateinit var nameInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var serviceList: LinearLayout
    private lateinit var premiumLine: TextView
    private lateinit var premiumInput: EditText
    private lateinit var logbookLine: TextView
    private lateinit var logbookList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = SafeGuardService.core ?: return finish()
        setContentView(buildUi(core))
        showGuardian(core.guardianStore.get())
        scope.launch {
            core.ui.collect { state ->
                if (state.snapshot.isMoving) finish()
                showServices(core, state.maintenance)
            }
        }
        scope.launch { core.tripLog.entries.collect { showLogbook(core, it) } }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(core: SafeGuardCore): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(64, 40, 64, 40)
        }
        root.addView(text(40f, Color.WHITE, bold = true).apply { text = "세이프가드 설정 (정차 중)" })
        core.vehicleLabel?.let { root.addView(text(24f, Color.GRAY).apply { text = "차량: $it" }) }

        root.addView(section("사고 시 알릴 보호자"))
        guardianLine = text(26f, Color.LTGRAY).also { root.addView(it) }
        nameInput = input("이름 (예: 엄마)", InputType.TYPE_CLASS_TEXT).also { root.addView(it, fill()) }
        phoneInput = input("휴대폰 번호 (010으로 시작)", InputType.TYPE_CLASS_PHONE).also { root.addView(it, fill()) }
        val buttons = LinearLayout(this)
        buttons.addView(button("저장") {
            val contact = GuardianContact.parse(nameInput.text.toString(), phoneInput.text.toString())
            if (contact == null) {
                guardianLine.text = "휴대폰 번호를 확인해 주세요. 010으로 시작하는 번호만 저장할 수 있어요."
                guardianLine.setTextColor(WARN_COLOR)
                return@button
            }
            core.guardianStore.set(contact)
            showGuardian(contact)
        })
        buttons.addView(button("삭제") {
            core.guardianStore.set(null)
            showGuardian(null)
        })
        root.addView(buttons)
        root.addView(text(20f, Color.GRAY).apply {
            text = "번호는 이 차에만 저장되고, 사고가 났을 때 알림 문자와 함께만 서버로 전송돼요."
        })

        root.addView(section("정비 기록 (교체한 항목을 눌러 주세요)"))
        serviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { root.addView(it) }

        root.addView(section("듣고 싶은 안내"))
        root.addView(text(20f, Color.GRAY).apply {
            text = "끄면 그 안내만 말하지 않아요. 사고·비상 정차 케어, 위급한 경고, 앞차가 확인한 위험은 끌 수 없어요."
        })
        Feature.entries.forEach { feature ->
            root.addView(Switch(this).apply {
                text = "${feature.label}\n${feature.note}"
                textSize = 24f
                setTextColor(Color.WHITE)
                isChecked = core.settings.enabled(feature)
                setPadding(0, 12, 0, 12)
                setOnCheckedChangeListener { _, on -> core.settings.set(feature, on) }
            }, fill())
        }

        root.addView(section("자동차보험료 (운전 점수 환산용)"))
        premiumLine = text(24f, Color.LTGRAY).also { root.addView(it) }
        premiumInput = input("1년 보험료 (원, 예: 800000)", InputType.TYPE_CLASS_NUMBER).also { root.addView(it, fill()) }
        root.addView(button("보험료 저장") {
            core.settings.annualPremiumWon = premiumInput.text.toString().filter(Char::isDigit).toIntOrNull()
            showPremium(core)
        })
        root.addView(text(20f, Color.GRAY).apply {
            text = "이 차에만 저장돼요. 커넥티드카 안전운전 특약 기준(월 70점, 1년 9달)으로 할인액을 미리 계산해요. " +
                "보험사 점수와 다를 수 있어요."
        })
        showPremium(core)

        root.addView(section("업무 운행기록부 (이번 달)"))
        logbookLine = text(24f, Color.LTGRAY).also { root.addView(it) }
        root.addView(button("국세청 서식 CSV로 저장") { exportLogbook(core) })
        logbookList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { root.addView(it) }
        root.addView(text(20f, Color.GRAY).apply {
            text = "위 '업무 운행기록부' 안내를 켜면 주차할 때마다 업무 여부를 묻고, 계기판 주행거리로 기록해요. 이 차에만 저장돼요."
        })

        root.addView(button("닫기") { finish() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 32 })
        return ScrollView(this).apply { addView(root) }
    }

    private fun showGuardian(contact: GuardianContact?) {
        guardianLine.setTextColor(Color.LTGRAY)
        guardianLine.text = contact?.let { "등록됨: ${it.name} · ${it.maskedPhone}" } ?: "등록된 보호자가 없어요."
        nameInput.setText(contact?.name.orEmpty())
        phoneInput.setText("")
    }

    private fun showPremium(core: SafeGuardCore) {
        val premium = core.settings.annualPremiumWon
        premiumLine.text = premium?.let { "등록됨: 1년 %,d원 · 5%% 할인 시 약 %,d원".format(it, it / 20) } ?: "보험료를 넣으면 할인액을 원으로 알려 드려요."
        premiumInput.setText("")
    }

    private fun showLogbook(core: SafeGuardCore, entries: List<TripEntry>) {
        val now = System.currentTimeMillis()
        val month = TripLog.summary(entries, now, ZoneId.systemDefault())
        logbookLine.text = if (month.totalKm == 0.0) "이번 달 기록이 없어요." else
            "총 %.1f km · 업무 %.1f km (%.0f%%)%s".format(
                month.totalKm, month.businessKm, month.businessRatio * 100,
                if (month.unsetCount > 0) " · 미분류 ${month.unsetCount}건" else "",
            )
        logbookList.removeAllViews()
        // The last few drives, so an unanswered one can be classified here.
        entries.takeLast(RECENT_TRIPS).reversed().forEach { e ->
            val row = LinearLayout(this)
            row.addView(text(22f, if (e.purpose == TripPurpose.UNSET) WARN_COLOR else Color.WHITE).apply {
                text = "%s · %.1f km · %s".format(
                    LocalDate.ofEpochDay(e.startedAt / 86_400_000L).toString(), e.km, e.purpose.label,
                )
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(button("업무") { core.tripLog.setPurpose(e.startedAt, TripPurpose.BUSINESS) })
            row.addView(button("비업무") { core.tripLog.setPurpose(e.startedAt, TripPurpose.PERSONAL) })
            logbookList.addView(row, fill())
        }
    }

    private fun exportLogbook(core: SafeGuardCore) {
        val now = System.currentTimeMillis()
        val month = LocalDate.now().toString().take(7)
        val file = File(getExternalFilesDir(null) ?: filesDir, "운행기록부_$month.csv")
        // A byte-order mark so Excel opens the Korean headers correctly.
        runCatching { file.writeText("\uFEFF" + core.tripLog.csv(now), Charsets.UTF_8) }
            .onSuccess { logbookLine.text = "저장했어요: ${file.absolutePath}" }
            .onFailure { logbookLine.text = "저장하지 못했어요: ${it.message}" }
    }

    private fun showServices(core: SafeGuardCore, items: List<ServiceStatus>) {
        serviceList.removeAllViews()
        if (items.isEmpty()) {
            serviceList.addView(text(24f, Color.GRAY).apply { text = "차량 주행거리를 아직 받지 못했어요." })
            return
        }
        items.forEach { status ->
            val row = LinearLayout(this)
            row.addView(text(26f, if (status.due) WARN_COLOR else Color.WHITE).apply {
                text = describe(status)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(button("교체 완료") { core.maintenance.recordService(status.item) })
            serviceList.addView(row, fill())
        }
    }

    private fun describe(s: ServiceStatus): String {
        val distance = if (s.remainingKm >= 0) "%,d km 남음".format(s.remainingKm) else "%,d km 지남".format(-s.remainingKm)
        val note = if (s.recorded) "" else " (기록 없음, 앱 설치 시점부터 계산)"
        return "${s.item.label} · $distance$note"
    }

    private fun section(title: String) = text(30f, Color.WHITE, bold = true).apply {
        text = title
        setPadding(0, 40, 0, 12)
    }

    private fun input(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        isSingleLine = true
        // The head unit's keyboard otherwise takes the whole landscape screen and hides which field is edited.
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        textSize = 26f
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 22f
        setOnClickListener { onClick() }
    }

    private fun fill() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private companion object {
        const val WARN_COLOR = 0xFFFFAB00.toInt()
        const val RECENT_TRIPS = 5
    }
}
