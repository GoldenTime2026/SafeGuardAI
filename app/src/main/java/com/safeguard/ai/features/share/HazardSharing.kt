package com.safeguard.ai.features.share

import android.util.Log

/** A hazard this car found or its driver reported, to be shared with the cars behind. */
data class HazardReport(
    /** DEBRIS, ACCIDENT, ICE, POTHOLE, STOPPED_VEHICLE, OTHER (voice) or SLIP, HARD_BRAKING, CRASH (vehicle). */
    val type: String,
    val latitude: Double,
    val longitude: Double,
    /** Direction of travel, so only cars going the same way are warned. */
    val headingDeg: Float?,
    val at: Long,
    val source: Source,
) {
    enum class Source { VOICE, VEHICLE }
}

/** Sends reports out of the car. Without a server the report stays local; the app works the same either way. */
fun interface HazardSharing {
    fun share(report: HazardReport)
}

class LocalOnlySharing : HazardSharing {
    override fun share(report: HazardReport) {
        Log.i("SafeGuard/Share", "no server configured, kept local: $report")
    }
}
