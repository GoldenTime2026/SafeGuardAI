package com.safeguard.ai.integration

import com.safeguard.ai.risk.withSubjectParticle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GleoTest {

    @Test
    fun `parameters arrive as plain or quoted JSON`() {
        assertEquals(mapOf("hazardType" to "DEBRIS"), GleoReceiver.parseParams("""{"hazardType":"DEBRIS"}"""))
        assertEquals(mapOf("hazardType" to "ICE"), GleoReceiver.parseParams("\"{\\\"hazardType\\\":\\\"ICE\\\"}\""))
        assertEquals(emptyMap<String, String>(), GleoReceiver.parseParams(null))
        assertEquals(emptyMap<String, String>(), GleoReceiver.parseParams("not json"))
    }

    @Test
    fun `the schema follows the Gleo rules`() {
        val schema = JSONObject(File("src/main/assets/schema.json").readText())
        assertEquals("com.safeguard.ai", schema.getString("packageName"))
        val calls = schema.getJSONArray("actionCalls")
        assertTrue("at most 3 functions", calls.length() <= 3)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (i in 0 until calls.length()) {
            val name = calls.getJSONObject(i).getJSONObject("function").getString("name")
            assertTrue("$name must be UPPER_SNAKE", name.matches(Regex("[A-Z]+(_[A-Z]+)*")))
            assertTrue("$name needs an intent filter", manifest.contains("android:name=\"$name\""))
        }
    }

    @Test
    fun `subject particle follows the last syllable`() {
        assertEquals("타이어 공기압 경고가", "타이어 공기압 경고".withSubjectParticle())
        assertEquals("문 열림이", "문 열림".withSubjectParticle())
        assertEquals("ABS 경고가", "ABS 경고".withSubjectParticle())
    }
}
