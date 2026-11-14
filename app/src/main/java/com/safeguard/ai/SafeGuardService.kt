package com.safeguard.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * Keeps SafeGuard running while the driver looks at navigation or another app.
 * Scenarios can be started from adb:
 * `am start-foreground-service --user 10 -n com.safeguard.ai/.SafeGuardService --es scenario stage1_priority`
 */
class SafeGuardService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        core = SafeGuardCore(applicationContext).also {
            it.bringToFront = ::showScreen
            it.start()
        }
        Log.i(TAG, "started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_SCENARIO)?.let { name ->
            if (name == STOP_SCENARIO) core?.scenarios?.stop() else core?.scenarios?.play(name)
        }
        if (intent?.getBooleanExtra(EXTRA_LOCATION_GRANTED, false) == true) {
            core?.location?.onLocationPermissionGranted()
        }
        if (intent?.getBooleanExtra(EXTRA_SPEED_GRANTED, false) == true) {
            core?.carProperties?.onPermissionGranted()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        core?.stop()
        core = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * After a crash the 119 location must be visible. Android 14 blocks a background service from opening
     * an activity (BAL_BLOCK, seen on the emulator with navigation in front), so this posts an urgent
     * notification with a full-screen intent: the system opens the screen, or at least shows an alert that
     * opens it with one tap.
     */
    private fun showScreen() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CRASH_CHANNEL_ID, getString(R.string.crash_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val alert = Notification.Builder(this, CRASH_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(getString(R.string.crash_title))
            .setContentText(getString(R.string.crash_text))
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(CRASH_NOTIFICATION_ID, alert) }
            .onSuccess { Log.i(TAG, "crash alert posted (full-screen: ${manager.canUseFullScreenIntent()})") }
            .onFailure { Log.w(TAG, "crash alert failed: ${it.message}") }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "SafeGuard/Service"
        private const val CHANNEL_ID = "safeguard"
        private const val NOTIFICATION_ID = 1
        private const val CRASH_CHANNEL_ID = "crash"
        private const val CRASH_NOTIFICATION_ID = 2
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_LOCATION_GRANTED = "location_granted"
        const val EXTRA_SPEED_GRANTED = "speed_granted"
        const val STOP_SCENARIO = "stop"

        /** The running core, or null when the service is not up. */
        @Volatile var core: SafeGuardCore? = null
            private set

        fun start(context: Context, configure: Intent.() -> Unit = {}) {
            context.startForegroundService(Intent(context, SafeGuardService::class.java).apply(configure))
        }
    }
}
