package com.safeguard.ai.features.crash

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GuardianContactTest {

    @Test
    fun `mobile numbers are kept as digits, anything else is refused`() {
        assertEquals(GuardianContact("엄마", "01012345678"), GuardianContact.parse(" 엄마 ", "010-1234-5678"))
        assertEquals("보호자", GuardianContact.parse("", "011 123 4567")!!.name)
        assertNull(GuardianContact.parse("집", "02-123-4567"))
        assertNull(GuardianContact.parse("엄마", "010-12"))
    }

    @Test
    fun `the screen shows a masked number`() {
        assertEquals("010-****-5678", GuardianContact("엄마", "01012345678").maskedPhone)
    }

    @Test
    fun `without a server the alert is not sent and says why`() = runBlocking {
        var contact: GuardianContact? = null
        val notifier = DemoGuardianNotifier { contact }
        assertFalse(notifier.notify("사고"))
        assertEquals("보호자", notifier.name)
        assertEquals("연락처 없음 · 정차 중 설정에서 등록", notifier.undeliveredNote)
        contact = GuardianContact("엄마", "01012345678")
        assertEquals("엄마", notifier.name)
        assertEquals("문자 서버 없음 · 화면의 문자를 대신 보내 주세요", notifier.undeliveredNote)
    }
}
