/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.presence

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.health.openscale.MainActivity
import com.health.openscale.R
import com.health.openscale.core.facade.BluetoothFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.utils.LogManager
import com.health.openscale.ui.shared.SnackbarEvent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service (type connectedDevice) that runs one measurement when the saved scale
 * appears: connect through the normal [BluetoothFacade] path, let the scale handler store the
 * measurement, wait for the link to close, then stop. Started by [ScalePresenceService].
 */
class BackgroundMeasurementService : Service() {

    private val TAG = "BackgroundMeasurement"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (session?.isActive == true) {
            LogManager.d(TAG, "Session already running; ignoring start")
            return START_NOT_STICKY
        }
        val address = intent?.getStringExtra(EXTRA_ADDRESS)
        session = scope.launch {
            try {
                runSession(address)
            } catch (t: Throwable) {
                LogManager.e(TAG, "Background measurement failed", t)
            } finally {
                ServiceCompat.stopForeground(this@BackgroundMeasurementService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runSession(address: String?) {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, BackgroundMeasurementEntryPoint::class.java)
        val bluetooth = entryPoint.bluetoothFacade()
        val saved = entryPoint.settingsFacade().observeSavedDevice().first()

        if (address == null || saved == null || !saved.address.equals(address, ignoreCase = true)) {
            // The association belongs to a scale that is no longer the saved one.
            LogManager.i(TAG, "Scale $address is not the saved device (${saved?.address}); removing its association")
            entryPoint.scalePresenceManager().disable(address)
            return
        }
        if (BackgroundSession.isActive(bluetooth.connectionStatus.value)) {
            LogManager.i(TAG, "A connection is already active; nothing to do")
            return
        }

        val results = mutableListOf<String>()
        val collector = scope.launch {
            bluetooth.snackbarEventsFromConnector.collect { event ->
                if (event.messageResId in RESULT_MESSAGES) resolve(event)?.let { results += it }
            }
        }
        try {
            if (!bluetooth.connectToSavedDeviceInBackground()) {
                LogManager.w(TAG, "Saved device or user not available; not connecting")
                return
            }
            when (BackgroundSession.awaitEnd(bluetooth.connectionStatus, START_TIMEOUT_MS, SESSION_TIMEOUT_MS)) {
                BackgroundSession.Outcome.ENDED -> LogManager.i(TAG, "Background session ended")
                BackgroundSession.Outcome.NEVER_STARTED -> LogManager.w(TAG, "Connection did not start")
                BackgroundSession.Outcome.TIMED_OUT -> {
                    LogManager.w(TAG, "Session timed out; disconnecting")
                    bluetooth.disconnect()
                }
            }
            // The connector reports saved measurements debounced by 700 ms.
            delay(RESULT_GRACE_MS)
        } finally {
            collector.cancel()
        }
        results.lastOrNull()?.let { showResult(it) }
    }

    private fun resolve(event: SnackbarEvent): String? =
        event.messageResId?.let { id -> runCatching { getString(id, *event.messageFormatArgs.toTypedArray()) }.getOrNull() }
            ?: event.message

    // --- notifications ---------------------------------------------------------------------------

    private fun enterForeground(): Boolean {
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_foreground)
            .setContentTitle(getString(R.string.background_measurement_running))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return try {
            ServiceCompat.startForeground(this, ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            true
        } catch (e: Exception) {
            // E.g. ForegroundServiceStartNotAllowedException when started without the companion exemption.
            LogManager.e(TAG, "Could not start foreground service", e)
            false
        }
    }

    private fun showResult(text: String) {
        val allowed = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_foreground)
            .setContentTitle(getString(R.string.background_measurement_title))
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(RESULT_ID, notification)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.background_measurement_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val EXTRA_ADDRESS = "address"
        private const val CHANNEL_ID = "background_measurement"
        private const val ONGOING_ID = 0x5CA1E
        private const val RESULT_ID = 0x5CA1F

        /** Time for the connector to start connecting after the request. */
        const val START_TIMEOUT_MS = 15_000L

        /** Upper bound for one weigh-in; the scale itself switches off much earlier. */
        const val SESSION_TIMEOUT_MS = 120_000L
        private const val RESULT_GRACE_MS = 1_500L

        private val RESULT_MESSAGES = setOf(
            R.string.bluetooth_connector_measurement_saved,
            R.string.saved_measurements_message,
            R.string.bluetooth_connector_measurement_user_missing,
        )

        fun start(context: Context, address: String) {
            val intent = Intent(context, BackgroundMeasurementService::class.java).putExtra(EXTRA_ADDRESS, address)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { LogManager.e("BackgroundMeasurement", "startForegroundService failed", it) }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackgroundMeasurementEntryPoint {
    fun bluetoothFacade(): BluetoothFacade
    fun settingsFacade(): SettingsFacade
    fun scalePresenceManager(): ScalePresenceManager
}
