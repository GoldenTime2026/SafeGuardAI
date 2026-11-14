package com.safeguard.ai.features.maintenance

import android.content.Context

class PrefsMaintenanceStore(context: Context) : MaintenanceStore {

    private val prefs = context.getSharedPreferences("maintenance", Context.MODE_PRIVATE)

    override fun lastServiceKm(item: ServiceItem): Double? = prefs.getFloat(item.name, -1f).takeIf { it >= 0 }?.toDouble()

    override fun record(item: ServiceItem, km: Double) {
        prefs.edit().putFloat(item.name, km.toFloat()).apply()
    }

    override fun baselineKm(): Double? = prefs.getFloat(BASELINE, -1f).takeIf { it >= 0 }?.toDouble()

    override fun setBaselineKm(km: Double) {
        prefs.edit().putFloat(BASELINE, km.toFloat()).apply()
    }

    private companion object {
        const val BASELINE = "baseline"
    }
}
