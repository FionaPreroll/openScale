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

import com.health.openscale.core.data.ConnectionStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Lifecycle of one background connection, decided from the connector's [ConnectionStatus]. */
object BackgroundSession {

    enum class Outcome {
        /** The connection started and ended (measurement done, or the scale went away). */
        ENDED,
        /** The connection never started within the start timeout. */
        NEVER_STARTED,
        /** The connector kept searching for the scale without connecting (it went back to sleep). */
        NOT_CONNECTED,
        /** The connection was still open when the session timeout ran out. */
        TIMED_OUT,
    }

    private val ACTIVE = setOf(
        ConnectionStatus.CONNECTING,
        ConnectionStatus.CONNECTED,
        ConnectionStatus.DISCONNECTING,
        ConnectionStatus.BROADCAST_LISTENING,
    )
    private val FINISHED = setOf(ConnectionStatus.DISCONNECTED, ConnectionStatus.FAILED)
    private val UP = setOf(ConnectionStatus.CONNECTED, ConnectionStatus.BROADCAST_LISTENING)

    /** True if a connection is under way, e.g. one the user started in the app. */
    fun isActive(status: ConnectionStatus): Boolean = status in ACTIVE

    /**
     * True while the link to the scale is being set up or is up. A presence event in that state is
     * the system reporting our own connection (EVENT_BT_CONNECTED), not the scale coming back.
     */
    fun isLinkUp(status: ConnectionStatus): Boolean =
        status == ConnectionStatus.CONNECTING || status == ConnectionStatus.CONNECTED

    /**
     * Waits until the connection that was just requested has started and then ended.
     * Call it right after asking the connector to connect: the status before that may be a
     * stale DISCONNECTED/FAILED from an earlier connection, so the start is detected first.
     */
    suspend fun awaitEnd(
        status: StateFlow<ConnectionStatus>,
        startTimeoutMs: Long,
        connectTimeoutMs: Long,
        sessionTimeoutMs: Long,
    ): Outcome {
        withTimeoutOrNull(startTimeoutMs) { status.first { it in ACTIVE } }
            ?: return Outcome.NEVER_STARTED
        // The connector scans for the scale without a limit of its own.
        val reached = withTimeoutOrNull(connectTimeoutMs) { status.first { it in UP || it in FINISHED } }
            ?: return Outcome.NOT_CONNECTED
        if (reached in FINISHED) return Outcome.ENDED
        withTimeoutOrNull(sessionTimeoutMs) { status.first { it in FINISHED } }
            ?: return Outcome.TIMED_OUT
        return Outcome.ENDED
    }
}
