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

import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.health.openscale.core.utils.LogManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Background measurement: lets Android wake openScale when the saved scale starts advertising.
 *
 * The scale is associated with openScale through the [CompanionDeviceManager] (one system
 * dialog), and openScale asks the system to observe its presence. When the scale wakes up and
 * advertises, the system binds [ScalePresenceService], which starts
 * [BackgroundMeasurementService] to connect and store the measurement — also while openScale is
 * not running. Companion apps may start that foreground service from the background
 * (REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND), and the system exempts them
 * from power restrictions while the device is present.
 *
 * Needs Android 13 (association callbacks with [AssociationInfo]). The association is the only
 * persisted state: it survives reboots and is removed by [disable] or by uninstalling the app.
 */
@Singleton
class ScalePresenceManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val TAG = "ScalePresenceManager"

    /** Outcome of [enable]. */
    sealed interface SetupEvent {
        /** The system dialog must be shown with this sender (StartIntentSenderForResult). */
        data class ConfirmationRequired(val intentSender: IntentSender) : SetupEvent
        data object Enabled : SetupEvent
        data class Failed(val reason: String) : SetupEvent
    }

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    /** True if background measurement is set up for the scale with [address]. */
    fun isEnabled(address: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !isSupported) return false
        return associationFor(address) != null
    }

    /**
     * Associates the scale with [address] and starts presence observation. The system dialog
     * scans for the scale, so it has to be awake (step on it) while the dialog is open.
     */
    fun enable(address: String, onEvent: (SetupEvent) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !isSupported) {
            onEvent(SetupEvent.Failed("companion devices are not supported on this device"))
            return
        }
        val cdm = manager() ?: return onEvent(SetupEvent.Failed("CompanionDeviceManager unavailable"))

        associationFor(address)?.let { existing ->
            onEvent(if (startObserving(cdm, existing)) SetupEvent.Enabled else SetupEvent.Failed("presence observation failed"))
            return
        }

        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setDeviceAddress(address.uppercase()).build())
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build()

        cdm.associate(request, context.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                onEvent(SetupEvent.ConfirmationRequired(intentSender))
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                LogManager.i(TAG, "Associated ${associationInfo.deviceMacAddress} (id ${associationInfo.id})")
                if (startObserving(cdm, associationInfo)) {
                    onEvent(SetupEvent.Enabled)
                } else {
                    runCatching { cdm.disassociate(associationInfo.id) }
                    onEvent(SetupEvent.Failed("presence observation failed"))
                }
            }

            override fun onFailure(error: CharSequence?) {
                LogManager.w(TAG, "Association failed: $error")
                onEvent(SetupEvent.Failed(error?.toString() ?: "association failed"))
            }
        })
    }

    /**
     * Stops presence observation and removes the association for [address], or for every scale
     * when [address] is null (e.g. after the saved scale was removed).
     */
    fun disable(address: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !isSupported) return
        val cdm = manager() ?: return
        val associations = runCatching { cdm.myAssociations }.getOrDefault(emptyList())
        for (info in associations) {
            if (address != null && !matches(info, address)) continue
            stopObserving(cdm, info)
            runCatching { cdm.disassociate(info.id) }
                .onSuccess { LogManager.i(TAG, "Removed association ${info.id} (${info.deviceMacAddress})") }
                .onFailure { LogManager.w(TAG, "disassociate(${info.id}) failed", it) }
        }
    }

    // --- internals ---------------------------------------------------------------------------

    private fun manager(): CompanionDeviceManager? =
        context.getSystemService(CompanionDeviceManager::class.java)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun associationFor(address: String): AssociationInfo? {
        val cdm = manager() ?: return null
        return runCatching { cdm.myAssociations }.getOrDefault(emptyList()).firstOrNull { matches(it, address) }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun matches(info: AssociationInfo, address: String): Boolean =
        info.deviceMacAddress?.toString().equals(address, ignoreCase = true)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun startObserving(cdm: CompanionDeviceManager, info: AssociationInfo): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            cdm.startObservingDevicePresence(
                ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build()
            )
        } else {
            val mac = info.deviceMacAddress?.toString()?.uppercase() ?: error("association without MAC address")
            @Suppress("DEPRECATION")
            cdm.startObservingDevicePresence(mac)
        }
        LogManager.i(TAG, "Observing presence of ${info.deviceMacAddress}")
    }.onFailure { LogManager.e(TAG, "startObservingDevicePresence failed", it) }.isSuccess

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun stopObserving(cdm: CompanionDeviceManager, info: AssociationInfo) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                cdm.stopObservingDevicePresence(
                    ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build()
                )
            } else {
                val mac = info.deviceMacAddress?.toString()?.uppercase() ?: return
                @Suppress("DEPRECATION")
                cdm.stopObservingDevicePresence(mac)
            }
        }.onFailure { LogManager.w(TAG, "stopObservingDevicePresence failed", it) }
    }
}
