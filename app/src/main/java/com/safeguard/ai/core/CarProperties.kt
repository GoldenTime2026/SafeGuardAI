package com.safeguard.ai.core

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Standard vehicle data the Pleos SDK leaves to AAOS (Pleos "SDK API policy": speed, outside temperature, …).
 * Stage-0 V15 confirmed a 3rd-party app can read these; ABS, hazard lights and ADAS states are system-only.
 *
 * - Vehicle speed replaces the GPS-derived speed: right in tunnels and from the first second of driving.
 * - Outside temperature makes the ice check real (it was scenario-only before).
 * Either can be missing on a given car; the GPS speed and scenario values remain the fallback.
 */
class CarProperties(
    private val context: Context,
    private val onSignals: (SignalPatch) -> Unit,
) {
    private var car: Car? = null
    private var manager: CarPropertyManager? = null

    /** True once the car reports its own speed; the GPS speed then stays out of the snapshot. */
    @Volatile var hasVehicleSpeed = false
        private set

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.status != CarPropertyValue.STATUS_AVAILABLE) return
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> (value.value as? Float)?.let {
                    hasVehicleSpeed = true
                    // AAOS reports m/s; reversing gives a negative value.
                    onSignals(SignalPatch(speedKmh = kotlin.math.abs(it) * 3.6))
                }
                VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE -> (value.value as? Float)?.let {
                    onSignals(SignalPatch(outsideTempC = it.toDouble()))
                }
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "property ${VehiclePropertyIds.toString(propertyId)} error")
        }
    }

    fun start() {
        if (manager != null) return
        runCatching {
            val connected = Car.createCar(context)
            car = connected
            manager = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        }.onFailure {
            Log.w(TAG, "car service unavailable: ${it.message}")
            return
        }
        subscribe()
    }

    /** CAR_SPEED is a runtime permission; called again after the driver grants it. */
    fun onPermissionGranted() = subscribe()

    private fun subscribe() {
        val props = manager ?: return
        if (context.checkSelfPermission(CAR_SPEED) == PackageManager.PERMISSION_GRANTED) {
            register(props, VehiclePropertyIds.PERF_VEHICLE_SPEED, SPEED_RATE_HZ)
        } else {
            Log.w(TAG, "CAR_SPEED not granted: using GPS speed")
        }
        register(props, VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, CarPropertyManager.SENSOR_RATE_ONCHANGE)
    }

    private fun register(props: CarPropertyManager, id: Int, rate: Float) {
        val name = VehiclePropertyIds.toString(id)
        runCatching {
            if (props.getCarPropertyConfig(id) == null) error("not supported or not permitted")
            props.registerCallback(callback, id, rate)
        }.onSuccess { Log.i(TAG, "subscribed $name") }
            .onFailure { Log.w(TAG, "$name unavailable: ${it.message}") }
    }

    fun stop() {
        runCatching { manager?.unregisterCallback(callback) }
        car?.disconnect()
        manager = null
        car = null
    }

    companion object {
        private const val TAG = "SafeGuard/CarProps"
        const val CAR_SPEED = "android.car.permission.CAR_SPEED"
        private const val SPEED_RATE_HZ = 2f
    }
}
