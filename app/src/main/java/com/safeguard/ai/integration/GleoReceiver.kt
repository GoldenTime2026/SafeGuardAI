package com.safeguard.ai.integration

import com.safeguard.ai.SafeGuardService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

/**
 * Receives Gleo AI voice commands declared in `assets/schema.json` ("세이프가드, 나 졸려").
 * Gleo sends each function as a broadcast whose action is the function name and whose `ACTION_PARAM` extra
 * holds the parameters as JSON; it expects an `APP_ACTION_RESPONSE` broadcast back within 7 seconds.
 * The app speaks for itself (same voice, ducking and priority as every warning), so the reply is silent.
 *
 * Test without Gleo: `adb shell am broadcast --user 10 -p com.safeguard.ai -a WAKE_ME_UP`
 */
class GleoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val params = parseParams(intent.getStringExtra(EXTRA_PARAM))
        Log.i(TAG, "gleo action $action $params")
        val core = SafeGuardService.core
        val reply = if (core == null) {
            SafeGuardService.start(context)
            GleoReply(action, GleoReply.Status.FAIL, "SafeGuard is starting. Please try again in a moment.", silent = false)
        } else {
            core.handleVoiceCommand(action, params)
        }
        respond(context, reply)
    }

    private fun respond(context: Context, reply: GleoReply) {
        val json = JSONObject()
            .put("functionName", reply.functionName)
            .put("status", reply.status.wire)
            .put("message", reply.message)
            .put("mode", if (reply.silent) "silent" else "voice")
        context.sendBroadcast(
            Intent(RESPONSE_ACTION)
                .putExtra("packageName", context.packageName)
                .putExtra("response", json.toString())
        )
        Log.i(TAG, "gleo response $json")
    }

    companion object {
        private const val TAG = "SafeGuard/Gleo"
        private const val EXTRA_PARAM = "ACTION_PARAM"
        private const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

        /** Gleo's examples wrap the JSON in quotes with escaped inner quotes; accept both forms. */
        fun parseParams(raw: String?): Map<String, String> {
            var text = raw?.trim().orEmpty()
            if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
                text = text.substring(1, text.length - 1).replace("\\\"", "\"")
            }
            if (text.isEmpty()) return emptyMap()
            return runCatching {
                val json = JSONObject(text)
                json.keys().asSequence().associateWith { json.optString(it) }
            }.getOrDefault(emptyMap())
        }
    }
}

/** What the app tells Gleo after a voice command; [message] is English, as the Gleo callback schema requires. */
data class GleoReply(val functionName: String, val status: Status, val message: String, val silent: Boolean = true) {
    enum class Status(val wire: String) { SUCCESS("success"), ASKING("asking"), FAIL("fail") }
}
