/*
 * Copyright (C) 2021-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.doze

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import java.util.concurrent.Executors

class PickupSensor(
    private val context: Context,
    sensorType: String,
    private val sensorValue: Float,
) : SensorEventListener {
    private val powerManager = context.getSystemService(PowerManager::class.java)!!
    private val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)

    private val sensorManager = context.getSystemService(SensorManager::class.java)!!
    private val sensor = Utils.getSensor(sensorManager, sensorType)
    private val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private val executorService = Executors.newSingleThreadExecutor()
    private var entryTimestamp = 0L
    @Volatile private var proximityNear = false

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values[0]
            val maxRange = proximitySensor?.maximumRange ?: PROXIMITY_NEAR_THRESHOLD
            proximityNear = distance < PROXIMITY_NEAR_THRESHOLD && distance < maxRange
            if (DEBUG) Log.d(TAG, "Proximity: $distance near=$proximityNear")
            return
        }

        if (DEBUG) Log.d(TAG, "Got sensor event: ${event.values[0]}")
        val delta = SystemClock.elapsedRealtime() - entryTimestamp
        if (delta < MIN_PULSE_INTERVAL_MS) {
            return
        }
        entryTimestamp = SystemClock.elapsedRealtime()
        if (event.values[0] == sensorValue) {
            if (proximityNear) {
                if (DEBUG) Log.d(TAG, "Ignoring pickup; proximity is near")
                return
            }
            // This doze service only runs while Always-on is OFF. A doze pulse
            // then often leaves a dim wallpaper-only frame (no clock / UDFPS
            // icon) on Oplus panels. Always wake to the interactive keyguard
            // so clock + fingerprint affordance are visible; FP still works.
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS)
            powerManager.wakeUpWithProximityCheck(
                SystemClock.uptimeMillis(),
                PowerManager.WAKE_REASON_GESTURE,
                TAG,
                Display.DEFAULT_DISPLAY,
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    fun enable() {
        if (sensor != null) {
            Log.d(TAG, "Enabling")
            executorService.submit {
                entryTimestamp = SystemClock.elapsedRealtime()
                proximityNear = false
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                proximitySensor?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }
        }
    }

    fun disable() {
        if (sensor != null) {
            Log.d(TAG, "Disabling")
            executorService.submit { sensorManager.unregisterListener(this) }
        }
    }

    companion object {
        private const val TAG = "PickupSensor"
        private const val DEBUG = false

        private const val MIN_PULSE_INTERVAL_MS = 8000L
        private const val WAKELOCK_TIMEOUT_MS = 300L
        private const val PROXIMITY_NEAR_THRESHOLD = 5.0f
    }
}
