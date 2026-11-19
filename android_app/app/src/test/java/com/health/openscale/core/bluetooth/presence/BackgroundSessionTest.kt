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

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.ConnectionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Unit tests for [BackgroundSession], the lifecycle of one background measurement. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundSessionTest {

    private val start = 15_000L
    private val session = 120_000L

    @Test
    fun `ends once the started connection is closed again`() = runTest {
        val status = MutableStateFlow(ConnectionStatus.DISCONNECTED)
        val outcome = async { BackgroundSession.awaitEnd(status, start, session) }
        runCurrent()

        status.value = ConnectionStatus.CONNECTING
        runCurrent()
        status.value = ConnectionStatus.CONNECTED
        runCurrent()
        assertThat(outcome.isCompleted).isFalse()

        status.value = ConnectionStatus.DISCONNECTED   // handler disconnects after the weigh-in
        assertThat(outcome.await()).isEqualTo(BackgroundSession.Outcome.ENDED)
    }

    @Test
    fun `a stale failure from an earlier connection does not end the session`() = runTest {
        val status = MutableStateFlow(ConnectionStatus.FAILED)
        val outcome = async { BackgroundSession.awaitEnd(status, start, session) }
        runCurrent()
        assertThat(outcome.isCompleted).isFalse()

        status.value = ConnectionStatus.CONNECTING
        runCurrent()
        status.value = ConnectionStatus.FAILED
        assertThat(outcome.await()).isEqualTo(BackgroundSession.Outcome.ENDED)
    }

    @Test
    fun `reports a connection that never starts`() = runTest {
        val status = MutableStateFlow(ConnectionStatus.DISCONNECTED)
        val outcome = async { BackgroundSession.awaitEnd(status, start, session) }

        advanceTimeBy(start + 1)
        assertThat(outcome.await()).isEqualTo(BackgroundSession.Outcome.NEVER_STARTED)
    }

    @Test
    fun `times out when the connection stays open`() = runTest {
        val status = MutableStateFlow(ConnectionStatus.CONNECTED)
        val outcome = async { BackgroundSession.awaitEnd(status, start, session) }

        advanceTimeBy(session - 1)
        runCurrent()
        assertThat(outcome.isCompleted).isFalse()
        advanceTimeBy(2)
        assertThat(outcome.await()).isEqualTo(BackgroundSession.Outcome.TIMED_OUT)
    }

    @Test
    fun `treats broadcast listening as a running session`() = runTest {
        val status = MutableStateFlow(ConnectionStatus.BROADCAST_LISTENING)
        val outcome = async { BackgroundSession.awaitEnd(status, start, session) }
        runCurrent()
        status.value = ConnectionStatus.DISCONNECTED
        assertThat(outcome.await()).isEqualTo(BackgroundSession.Outcome.ENDED)
    }

    @Test
    fun `knows which states mean a connection is already active`() {
        assertThat(BackgroundSession.isActive(ConnectionStatus.CONNECTED)).isTrue()
        assertThat(BackgroundSession.isActive(ConnectionStatus.CONNECTING)).isTrue()
        assertThat(BackgroundSession.isActive(ConnectionStatus.DISCONNECTED)).isFalse()
        assertThat(BackgroundSession.isActive(ConnectionStatus.FAILED)).isFalse()
        assertThat(BackgroundSession.isActive(ConnectionStatus.NONE)).isFalse()
    }
}
