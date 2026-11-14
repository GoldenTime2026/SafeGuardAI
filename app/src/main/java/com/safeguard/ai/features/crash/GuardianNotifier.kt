package com.safeguard.ai.features.crash

import android.content.Context
import android.util.Log

/** Sends the crash alert to the driver's registered guardian. Returns true only when it was actually delivered. */
interface GuardianNotifier {
    val name: String
    suspend fun notify(message: String): Boolean
    /** Why the last alert was not delivered, for the crash screen. */
    val undeliveredNote: String get() = "시연 모드라 발송하지 않음"
}

/** The person to alert after a crash, entered on the parked settings screen. */
data class GuardianContact(val name: String, val phone: String) {
    /** "010-****-5678": enough for the driver to recognise without showing the whole number on screen. */
    val maskedPhone: String
        get() = if (phone.length < 8) phone else "${phone.take(3)}-****-${phone.takeLast(4)}"

    companion object {
        /** Korean mobile numbers only (01x + 7–8 digits); null for anything else. */
        fun parse(name: String, phone: String): GuardianContact? {
            val digits = phone.filter { it.isDigit() }
            if (!Regex("01[016789]\\d{7,8}").matches(digits)) return null
            return GuardianContact(name.trim().ifEmpty { "보호자" }, digits)
        }
    }
}

/** Kept on the car only; the number leaves the car only inside a crash alert. */
class GuardianStore(context: Context) {
    private val prefs = context.getSharedPreferences("guardian", Context.MODE_PRIVATE)

    fun get(): GuardianContact? {
        val phone = prefs.getString("phone", null) ?: return null
        return GuardianContact(prefs.getString("name", null) ?: "보호자", phone)
    }

    fun set(contact: GuardianContact?) {
        prefs.edit().apply {
            if (contact == null) clear() else putString("name", contact.name).putString("phone", contact.phone)
        }.apply()
    }
}

/**
 * Without the sharing server: the head unit has no SMS and the Pleos SDK no messaging API, so the message
 * is only shown on screen for the driver or a passer-by to send.
 */
class DemoGuardianNotifier(private val contact: () -> GuardianContact? = { null }) : GuardianNotifier {
    override val name: String get() = contact()?.name ?: "보호자"
    override val undeliveredNote: String
        get() = if (contact() == null) "연락처 없음 · 정차 중 설정에서 등록" else "문자 서버 없음 · 화면의 문자를 대신 보내 주세요"

    override suspend fun notify(message: String): Boolean {
        Log.i("SafeGuard/Guardian", "no server, not sent: $message")
        return false
    }
}
