package com.safeguard.ai.features.logbook

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The logbook kept on the car only, as one JSON array. */
class PrefsTripLogStore(context: Context) : TripLogStore {

    private val prefs = context.getSharedPreferences("trip_log", Context.MODE_PRIVATE)

    override fun load(): List<TripEntry> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { i ->
            array.getJSONObject(i).let {
                TripEntry(
                    startedAt = it.getLong("startedAt"),
                    endedAt = it.getLong("endedAt"),
                    startKm = it.getDouble("startKm"),
                    endKm = it.getDouble("endKm"),
                    from = it.optString("from"),
                    to = it.optString("to"),
                    purpose = TripPurpose.entries.firstOrNull { p -> p.name == it.optString("purpose") } ?: TripPurpose.UNSET,
                )
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized
    override fun save(entries: List<TripEntry>) {
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject()
                    .put("startedAt", it.startedAt).put("endedAt", it.endedAt)
                    .put("startKm", it.startKm).put("endKm", it.endKm)
                    .put("from", it.from).put("to", it.to).put("purpose", it.purpose.name)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "entries"
    }
}
