package com.safeguard.ai.features.ontime

import org.json.JSONObject

/** What the car asks the server about the flight the driver is driving to. */
data class FlightQuery(
    /** IATA code of the airport navigation is heading for ("GMP", "ICN"). */
    val airport: String,
    /** "KE1215" when the driver said a flight number. */
    val flight: String? = null,
    /** "1530" when the driver said a time. */
    val time: String? = null,
    /** The driver's words, so the server can match the destination ("세 시 반 제주"). */
    val words: String = "",
    /** The car's local hour, for Incheon's departure-hall crowd forecast. */
    val hour: Int? = null,
)

data class FlightStatus(
    val flightId: String,
    val airline: String,
    val dest: String,
    /** "1530": the operators give clock times without a date. */
    val scheduled: String,
    val estimated: String?,
    /** Minutes later than scheduled (negative: moved earlier); null when not changed. */
    val delayMin: Int?,
    val status: String,
    val cancelled: Boolean,
    val international: Boolean,
    val gate: String?,
    /** Incheon only: "T1" or "T2" (the concourse checks in at T1). */
    val terminal: String?,
    val counters: String?,
)

data class ParkingLot(val name: String, val total: Int, val free: Int)

/** The server's answer (`GET /v1/flight`): the flight if one was found, and what matters on the way. */
data class FlightPlan(
    val flight: FlightStatus?,
    /** Flights that fitted the spoken time; more than one without a flight means the number is needed. */
    val candidates: Int = 0,
    val parking: List<ParkingLot> = emptyList(),
    /** Check-in to security cleared now, in minutes (한국공항공사 airports, while they are measured). */
    val waitMin: Int? = null,
    /** Incheon: this hour is near the day's busiest at the flight's terminal. */
    val busy: Boolean? = null,
) {
    companion object {
        fun parse(json: String): FlightPlan? {
            val o = JSONObject(json)
            if (!o.optBoolean("configured", true)) return null
            val f = o.optJSONObject("flight")
            val lots = o.optJSONArray("parking")
            return FlightPlan(
                flight = f?.let {
                    FlightStatus(
                        flightId = it.optString("flightId"),
                        airline = it.optString("airline"),
                        dest = it.optString("dest"),
                        scheduled = it.optString("scheduled"),
                        estimated = it.optStringOrNull("estimated"),
                        delayMin = if (it.isNull("delayMin")) null else it.optInt("delayMin"),
                        status = it.optString("status"),
                        cancelled = it.optBoolean("cancelled"),
                        international = it.optString("line") == "I",
                        gate = it.optStringOrNull("gate"),
                        terminal = it.optStringOrNull("terminal"),
                        counters = it.optStringOrNull("counters"),
                    )
                },
                candidates = o.optInt("candidates"),
                parking = lots?.let { arr ->
                    (0 until arr.length()).map { i ->
                        arr.getJSONObject(i).let { ParkingLot(it.optString("name"), it.optInt("total"), it.optInt("free")) }
                    }
                }.orEmpty(),
                waitMin = if (o.isNull("waitMin") || !o.has("waitMin")) null else o.optInt("waitMin"),
                busy = if (o.isNull("busy") || !o.has("busy")) null else o.optBoolean("busy"),
            )
        }

        private fun JSONObject.optStringOrNull(key: String) = if (has(key) && !isNull(key)) getString(key).ifBlank { null } else null
    }
}
