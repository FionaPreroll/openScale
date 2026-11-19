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

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.os.Build
import androidx.annotation.RequiresApi
import com.health.openscale.core.utils.LogManager

/**
 * Bound by the system while an associated scale is in BLE range (see [ScalePresenceManager]).
 *
 * The system reports a scale that starts advertising through [onDeviceAppeared]. On Android 13+
 * it calls the [AssociationInfo] variant, whose default implementation would forward to the
 * deprecated String variant. Android 16+ marks it deprecated but still calls it for every
 * appearance (DevicePresenceProcessor.legacyNotifyDevicePresenceEvent, checked in the Android 17
 * framework), next to `onDevicePresenceEvent`, which is left at its no-op default so every
 * appearance is handled exactly once.
 */
class ScalePresenceService : CompanionDeviceService() {

    private val TAG = "ScalePresenceService"

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Suppress("OVERRIDE_DEPRECATION") // deprecated in API 36, still called there (see class doc)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        onScaleAppeared(associationInfo.deviceMacAddress?.toString())
    }

    /** Only called on Android 12, where the feature is not offered; kept for completeness. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        onScaleAppeared(address)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        LogManager.d(TAG, "Scale ${associationInfo.deviceMacAddress} out of range")
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(address: String) {
        LogManager.d(TAG, "Scale $address out of range")
    }

    private fun onScaleAppeared(address: String?) {
        if (address == null) return
        LogManager.i(TAG, "Scale $address appeared; starting background measurement")
        BackgroundMeasurementService.start(this, address.uppercase())
    }
}
