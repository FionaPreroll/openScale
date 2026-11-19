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
package com.health.openscale.ui.screen.settings

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.openscale.core.bluetooth.presence.ScalePresenceManager
import com.health.openscale.core.facade.SettingsFacade
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State and actions of the "Measure in the background" switch in the Bluetooth settings. */
@HiltViewModel
class BackgroundMeasurementViewModel @Inject constructor(
    private val presence: ScalePresenceManager,
    settingsFacade: SettingsFacade,
) : ViewModel() {

    val isSupported: Boolean = presence.isSupported

    private val savedAddress: StateFlow<String?> = settingsFacade.observeSavedDevice()
        .map { it?.address }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _confirmation = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)
    /** The system association dialog to launch with StartIntentSenderForResult. */
    val confirmation: SharedFlow<IntentSender> = _confirmation.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    init {
        viewModelScope.launch { savedAddress.collect { refresh() } }
    }

    fun setEnabled(on: Boolean) {
        val address = savedAddress.value ?: return
        if (!on) {
            presence.disable(address)
            refresh()
            return
        }
        _busy.value = true
        presence.enable(address) { event ->
            when (event) {
                is ScalePresenceManager.SetupEvent.ConfirmationRequired -> _confirmation.tryEmit(event.intentSender)
                ScalePresenceManager.SetupEvent.Enabled -> {
                    _busy.value = false
                    refresh()
                }
                is ScalePresenceManager.SetupEvent.Failed -> {
                    _busy.value = false
                    _errors.tryEmit(event.reason)
                    refresh()
                }
            }
        }
    }

    /** Result of the system dialog; on success the association callback follows. */
    fun onConfirmationResult(accepted: Boolean) {
        if (!accepted) {
            _busy.value = false
            refresh()
        }
    }

    /** The saved scale is being removed: drop its association as well. */
    fun onSavedDeviceRemoved() {
        presence.disable(null)
        refresh()
    }

    private fun refresh() {
        val address = savedAddress.value
        _enabled.value = address != null && presence.isEnabled(address)
    }
}
