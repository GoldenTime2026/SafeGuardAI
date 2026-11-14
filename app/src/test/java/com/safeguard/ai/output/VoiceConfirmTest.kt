package com.safeguard.ai.output

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceConfirmTest {

    @Test
    fun `short yes replies`() {
        listOf("응", "어", "네", "넵", "예", "그래", "그래요", "좋아", "좋아요", "좋지", "콜", "해", "응 해줘", "우회해 줘").forEach {
            assertEquals(it, Answer.YES, VoiceConfirm.classify(it))
        }
    }

    @Test
    fun `everyday ways of agreeing`() {
        listOf(
            "오케이", "OK", "Okay.", "그렇게 해", "그렇게 해줘", "그러자", "부탁해", "부탁할게요",
            "알겠어", "알았어", "알겠습니다", "당연하지", "물론이지", "맞아", "고고", "가자", "쉬어 갈게", "좋습니다",
        ).forEach {
            assertEquals(it, Answer.YES, VoiceConfirm.classify(it))
        }
    }

    @Test
    fun `no wins over yes`() {
        listOf(
            "아니", "아니요", "괜찮아", "됐어", "응 아니야", "취소", "싫어", "안 해", "안해", "필요 없어",
            "그냥 가자", "나중에 할게", "노", "No", "우회 말고", "그건아니야",
        ).forEach {
            assertEquals(it, Answer.NO, VoiceConfirm.classify(it))
        }
    }

    @Test
    fun `unrelated speech is no answer`() {
        listOf("", "  ", "오늘 날씨 어때", "예약해 둔 거", "네비 켜", "노래 틀어 줘", "어디야", "편안해").forEach {
            assertEquals(it, Answer.NONE, VoiceConfirm.classify(it))
        }
    }
}
