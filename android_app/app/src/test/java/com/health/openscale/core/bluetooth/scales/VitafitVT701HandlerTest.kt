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
package com.health.openscale.core.bluetooth.scales

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.StandardImpedanceLib
import com.health.openscale.core.data.EvaluationState
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Test
import java.util.Calendar
import java.util.Date
import java.util.UUID

/**
 * Unit tests for [VitafitVT701Handler] frame parsing, command building and the weigh-in flow.
 *
 * Vectors are taken verbatim from HCI snoops of Vitafit-app weigh-ins: the VT701 stable weight
 * frame was confirmed against the scale's displayed 85.00 kg reading; the VT715 sequences come
 * from the captures of 2025-08-09 and 2025-08-13.
 */
class VitafitVT701HandlerTest {

    private fun hex(s: String): ByteArray =
        s.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // --- frame parsing -------------------------------------------------------------------------

    @Test
    fun `extracts 85_00 kg from the real stable frame`() {
        val raw = VitafitVT701Handler.parseStableWeightRaw(hex("5a 0a 26 10 02 00 00 21 21 34 0a aa"))
        assertThat(raw).isEqualTo(8500)
        assertThat(raw!! / 100.0f).isWithin(1e-3f).of(85.00f)
    }

    @Test
    fun `ignores settling frames whose phase is neither stable nor final`() {
        // phase 0x01, still measuring (85.15 kg preview).
        assertThat(VitafitVT701Handler.parseStableWeightRaw(hex("5a 0a 26 10 01 00 00 21 21 43 7e aa")))
            .isNull()
        // phase 0x00, idle/zero (VT715: 9.80 kg ramp while stepping on).
        assertThat(VitafitVT701Handler.parseStableWeightRaw(hex("5a 0a 26 10 00 00 00 21 03 d4 ca aa")))
            .isNull()
    }

    @Test
    fun `accepts the final phase frame the VT715 sends after a weigh-in`() {
        // VT715, 2025-08-13: phase 0x03 with 21 ed = 86.85 kg, repeated until acked.
        val frame = hex("5a 0a 26 10 03 00 00 21 21 ed d2 aa")
        assertThat(VitafitVT701Handler.parseStableWeightRaw(frame)).isEqualTo(8685)
        assertThat(VitafitVT701Handler.decodeWeight(frame))
            .isEqualTo(VitafitVT701Handler.WeightReading(phase = 3, attribute = 0x21, weightRaw = 8685))
    }

    @Test
    fun `extracts impedance from the type 0x11 frames of both models`() {
        // VT701, 85.00 kg weigh-in: 01 89 = 393 Ohm.
        assertThat(VitafitVT701Handler.parseImpedanceOhm(hex("5a 0b 26 11 00 00 00 00 00 01 89 b4 aa")))
            .isEqualTo(393)
        // VT701, 84.95 kg weigh-in: 01 85 = 389 Ohm.
        assertThat(VitafitVT701Handler.parseImpedanceOhm(hex("5a 0b 26 11 00 00 00 00 00 01 85 b8 aa")))
            .isEqualTo(389)
        // VT715, 88.20 kg weigh-in: 01 eb = 491 Ohm.
        assertThat(VitafitVT701Handler.parseImpedanceOhm(hex("5a 0b 26 11 00 00 00 00 00 01 eb d6 aa")))
            .isEqualTo(491)
    }

    @Test
    fun `reports 0xFFFF as an impedance frame without a value`() {
        val frame = hex("5a 0b 26 11 00 00 00 00 00 ff ff 3c aa")
        assertThat(VitafitVT701Handler.parseImpedanceOhm(frame)).isNull()
        assertThat(VitafitVT701Handler.decodeImpedance(frame))
            .isEqualTo(VitafitVT701Handler.ImpedanceReading(ohm = null))
    }

    @Test
    fun `does not confuse weight and impedance frame types`() {
        // A weight frame is not an impedance frame and vice versa.
        assertThat(VitafitVT701Handler.parseImpedanceOhm(hex("5a 0a 26 10 02 00 00 21 21 34 0a aa")))
            .isNull()
        assertThat(VitafitVT701Handler.parseStableWeightRaw(hex("5a 0b 26 11 00 00 00 00 00 01 89 b4 aa")))
            .isNull()
        // The ack of a 0x15 results page is valid but neither.
        val ack = hex("5a 05 26 15 01 37 aa")
        assertThat(VitafitVT701Handler.isValidScaleFrame(ack)).isTrue()
        assertThat(VitafitVT701Handler.decodeWeight(ack)).isNull()
        assertThat(VitafitVT701Handler.decodeImpedance(ack)).isNull()
    }

    @Test
    fun `rejects malformed and bad-checksum frames`() {
        // corrupted checksum byte.
        assertThat(VitafitVT701Handler.parseStableWeightRaw(hex("5a 0a 26 10 02 00 00 21 21 34 ff aa")))
            .isNull()
        assertThat(VitafitVT701Handler.parseImpedanceOhm(hex("5a 0b 26 11 00 00 00 00 00 01 89 ff aa")))
            .isNull()
        // truncated.
        assertThat(VitafitVT701Handler.parseStableWeightRaw(ByteArray(5))).isNull()
        // VT715: stale half impedance frame followed by the start of a weight frame, as seen as
        // the first notification of several connections.
        assertThat(VitafitVT701Handler.isValidScaleFrame(hex("5a 0b 26 11 00 00 5a 0a 26 10 03 00 00")))
            .isFalse()
    }

    @Test
    fun `builds the observed hello commands with correct length and checksum`() {
        assertThat(VitafitVT701Handler.buildCommand(0x33, byteArrayOf(0x00)))
            .isEqualTo(hex("a5 05 26 33 00 10 aa"))
        assertThat(VitafitVT701Handler.buildCommand(0x44, byteArrayOf()))
            .isEqualTo(hex("a5 04 26 44 66 aa"))
        assertThat(VitafitVT701Handler.buildCommand(0x17, byteArrayOf(0x01)))
            .isEqualTo(hex("a5 05 26 17 01 35 aa"))
    }

    @Test
    fun `builds the observed acks and results page`() {
        assertThat(VitafitVT701Handler.buildCommand(0x10, byteArrayOf(0x03)))
            .isEqualTo(hex("a5 05 26 10 03 30 aa"))
        assertThat(VitafitVT701Handler.buildCommand(0x11, byteArrayOf(0x00)))
            .isEqualTo(hex("a5 05 26 11 00 32 aa"))
        // Results page 2 the Vitafit app sent for the 88.20 kg / 491 Ohm weigh-in.
        assertThat(VitafitVT701Handler.buildCommand(0x15, hex("02 40 be 21 8a 80 14 26 53 71 5a")))
            .isEqualTo(hex("a5 0f 26 15 02 40 be 21 8a 80 14 26 53 71 5a a1 aa"))
    }

    @Test
    fun `treats an equal weight within a minute as the same weigh-in`() {
        val t0 = 1_000_000L
        assertThat(VitafitVT701Handler.isSameWeighIn(8685, t0 + 30_000, 8685, t0)).isTrue()
        assertThat(VitafitVT701Handler.isSameWeighIn(8686, t0 + 30_000, 8685, t0)).isTrue()
        assertThat(VitafitVT701Handler.isSameWeighIn(8675, t0 + 30_000, 8685, t0)).isFalse()
        // 2026-11-19: 80.30 kg was weighed five times within four minutes; those are real.
        assertThat(VitafitVT701Handler.isSameWeighIn(8030, t0 + 2 * 60_000, 8030, t0)).isFalse()
        assertThat(VitafitVT701Handler.isSameWeighIn(8685, t0, -1, 0L)).isFalse()
        val stable = VitafitVT701Handler.STABLE_REPLAY_WINDOW_MS
        assertThat(VitafitVT701Handler.isSameWeighIn(8685, t0 + 10_000, 8685, t0, stable)).isTrue()
        assertThat(VitafitVT701Handler.isSameWeighIn(8685, t0 + 30_000, 8685, t0, stable)).isFalse()
    }

    // --- weigh-in flow -------------------------------------------------------------------------

    /** VT715, 2025-08-09 10:29:11: live weigh-in of 88.20 kg with 484 Ohm. */
    private val liveWeighInWithImpedance = listOf(
        "5a 0a 26 10 00 00 00 21 02 99 86 aa",   // 6.65 kg, idle/zero (stepping on)
        "5a 0a 26 10 01 00 00 21 0b d1 c6 aa",   // 30.25 kg, measuring
        "5a 0a 26 10 01 00 00 21 21 6b 56 aa",   // 85.55 kg
        "5a 0a 26 10 01 00 00 21 22 60 5e aa",   // 88.00 kg
        "5a 0a 26 10 02 00 00 21 22 74 49 aa",   // 88.20 kg, stable (repeated 8x by the scale)
        "5a 0a 26 10 02 00 00 21 22 74 49 aa",
        "5a 0a 26 10 02 00 00 21 22 74 49 aa",
        "5a 0b 26 11 00 00 00 00 00 01 e4 d9 aa", // 484 Ohm
        "5a 0b 26 11 00 00 00 00 00 01 e4 d9 aa", // repeated until acked
    )

    @Test
    fun `publishes one weigh-in with impedance and body composition`() {
        val s = Setup(user(heightCm = 170f, age = 40))
        s.connect()
        liveWeighInWithImpedance.forEach { s.notify(it) }

        assertThat(s.published).hasSize(1)
        val m = s.published.single()
        assertThat(m[MeasurementType.WEIGHT]!!.value).isWithin(1e-3f).of(88.20f)
        assertThat(m[MeasurementType.IMPEDANCE]!!.value).isWithin(1e-3f).of(484f)
        assertThat(m[MeasurementType.BODY_FAT]).isNotNull()
        assertThat(m[MeasurementType.WATER]).isNotNull()
        // The results pages for the LCD go out first; the link closes once page 2 is acked.
        assertThat(s.writes.takeLast(2).map { it.second.take(10) })
            .containsExactly("a50f261501", "a50f261502").inOrder()
        assertThat(s.disconnects).isEqualTo(0)
        s.notify("5a 05 26 15 01 37 aa")
        assertThat(s.disconnects).isEqualTo(0)
        s.notify("5a 05 26 15 02 34 aa")
        assertThat(s.disconnects).isEqualTo(1)
    }

    @Test
    fun `sends the hello sequence and acks weight and impedance frames like the app`() {
        val s = Setup(user())
        s.connect()
        liveWeighInWithImpedance.take(5).forEach { s.notify(it) }
        s.notify("5a 0b 26 11 00 00 00 00 00 01 e4 d9 aa")

        assertThat(s.notifyEnabled).containsExactly(uuid16(0xFFF1))
        assertThat(s.writes.map { it.first }.toSet()).containsExactly(uuid16(0xFFF2))
        assertThat(s.writes.map { it.second }.take(9)).containsExactly(
            "a50526330010aa", "a504264466aa", "a50526170135aa",   // hello, query, unit
            "a50526100033aa", "a50526100132aa", "a50526100132aa", "a50526100132aa", // phase acks
            "a50526100231aa",
            "a50526110032aa",                                       // impedance ack
        ).inOrder()
        assertThat(s.writes.drop(9).map { it.second.take(10) })    // results pages for the LCD
            .containsExactly("a50f261501", "a50f261502").inOrder()
    }

    @Test
    fun `publishes weight-only when the scale reports no impedance`() {
        // VT715, 2025-08-09 10:36: stable 88.10 kg, final frame, then 0xFFFF.
        val s = Setup(user())
        s.connect()
        s.notify("5a 0a 26 10 01 00 00 21 22 6a 54 aa")
        repeat(6) { s.notify("5a 0a 26 10 02 00 00 21 22 6a 57 aa") }
        s.notify("5a 0a 26 10 03 00 00 21 22 6a 56 aa")
        s.notify("5a 0b 26 11 00 00 00 00 00 ff ff 3c aa")
        s.notify("5a 0b 26 11 00 00 00 00 00 ff ff 3c aa")

        assertThat(s.published).hasSize(1)
        val m = s.published.single()
        assertThat(m[MeasurementType.WEIGHT]!!.value).isWithin(1e-3f).of(88.10f)
        assertThat(m[MeasurementType.IMPEDANCE]).isNull()
        assertThat(m[MeasurementType.BODY_FAT]).isNull()
    }

    @Test
    fun `stores a weigh-in that finished before the phone connected`() {
        // VT715, 2025-08-13 14:21:59: the first frames after connecting are final (phase 3).
        val s = Setup(user())
        s.connect()
        s.notify("5a 0b 26 11 00 00 5a 0a 26 10 03 00 00")   // stale fragment, dropped
        repeat(4) { s.notify("5a 0a 26 10 03 00 00 21 21 ed d2 aa") }
        s.notify("5a 0b 26 11 00 00 00 00 00 ff ff 3c aa")

        assertThat(s.published).hasSize(1)
        assertThat(s.published.single()[MeasurementType.WEIGHT]!!.value).isWithin(1e-3f).of(86.85f)
    }

    @Test
    fun `publishes a weigh-in whose connection starts in the stable phase`() {
        // VT715 via scale_lab on macOS, 2026-11-19 09:33: connected while standing still, so the
        // first frames are stable; the impedance frame then repeats every 2.5 s.
        val s = Setup(user(heightCm = 180f, age = 40))
        s.connect()
        repeat(4) { s.notify("5a 0a 26 10 02 00 00 21 1f 5e 5e aa") }   // 80.30 kg
        repeat(2) { s.notify("5a 0b 26 11 00 00 00 00 00 02 0d 33 aa") } // 525 Ohm

        assertThat(s.published).hasSize(1)
        val m = s.published.single()
        assertThat(m[MeasurementType.WEIGHT]!!.value).isWithin(1e-3f).of(80.30f)
        assertThat(m[MeasurementType.IMPEDANCE]!!.value).isWithin(1e-3f).of(525f)
        s.notify("5a 05 26 15 02 34 aa")
        assertThat(s.disconnects).isEqualTo(1)
    }

    @Test
    fun `drops the final result the scale repeats after a reconnect`() {
        val s = Setup(user())
        s.connect()
        s.notify("5a 0a 26 10 03 00 00 21 21 ed d2 aa")
        s.notify("5a 0b 26 11 00 00 00 00 00 ff ff 3c aa")
        assertThat(s.published).hasSize(1)

        // Reconnect while the scale is still awake: it replays the same final result.
        s.disconnected()
        s.connect()
        repeat(3) { s.notify("5a 0a 26 10 03 00 00 21 21 ed d2 aa") }
        s.notify("5a 0b 26 11 00 00 00 00 00 ff ff 3c aa")
        assertThat(s.published).hasSize(1)

        // A new weigh-in in the same connection is stored again (2025-08-13 14:22:44, 86.75 kg).
        s.notify("5a 0a 26 10 00 00 00 21 03 d4 ca aa")
        s.notify("5a 0a 26 10 01 00 00 21 21 e3 de aa")
        s.notify("5a 0a 26 10 02 00 00 21 21 e3 dd aa")
        s.notify("5a 0b 26 11 00 00 00 00 00 ff ff 3c aa")
        assertThat(s.published).hasSize(2)
        assertThat(s.published.last()[MeasurementType.WEIGHT]!!.value).isWithin(1e-3f).of(86.75f)
    }

    @Test
    fun `drops a replay in the stable phase and shows the stored results again`() {
        // Background mode reconnects right after a weigh-in, possibly while the user still stands.
        val s = Setup(user(heightCm = 170f, age = 40))
        s.connect()
        liveWeighInWithImpedance.forEach { s.notify(it) }
        s.notify("5a 05 26 15 02 34 aa")
        assertThat(s.published).hasSize(1)
        assertThat(s.disconnects).isEqualTo(1)
        val pages = s.writes.map { it.second }.filter { it.startsWith("a50f2615") }
        assertThat(pages).hasSize(2)

        s.disconnected()
        s.connect()
        s.writes.clear()
        repeat(3) { s.notify("5a 0a 26 10 02 00 00 21 22 74 49 aa") }   // 88.20 kg again
        repeat(2) { s.notify("5a 0b 26 11 00 00 00 00 00 01 e4 d9 aa") }

        assertThat(s.published).hasSize(1)
        // Same pages as for the original weigh-in, sent once; the link stays open.
        assertThat(s.writes.map { it.second }.filter { it.startsWith("a50f2615") })
            .containsExactlyElementsIn(pages).inOrder()
        assertThat(s.disconnects).isEqualTo(1)

        // The next weigh-in in this connection is stored, even with the same weight.
        liveWeighInWithImpedance.forEach { s.notify(it) }
        assertThat(s.published).hasSize(2)
    }

    @Test
    fun `stores a late-connected weigh-in with the same weight as one a minute ago`() {
        // Device log 2026-11-20 12:38:51: the first connection attempt failed, the second one
        // started with stable frames of 79.45 kg, 67 s after a stored 79.45 kg weigh-in.
        val stored = ScaleMeasurement().apply {
            dateTime = Date(System.currentTimeMillis() - 30_000)
            this[MeasurementType.WEIGHT] = Kg(79.45f)
        }
        val s = Setup(user(), lastStored = stored)
        s.connect()
        repeat(3) { s.notify("5a 0a 26 10 02 00 00 21 1f 09 09 aa") }
        s.notify("5a 0b 26 11 00 00 00 00 00 ff ff 3c aa")

        assertThat(s.published).hasSize(1)
        assertThat(s.published.single()[MeasurementType.WEIGHT]!!.value).isWithin(1e-3f).of(79.45f)
    }

    @Test
    fun `drops stable frames of the measurement stored seconds ago`() {
        val stored = ScaleMeasurement().apply {
            dateTime = Date(System.currentTimeMillis() - 5_000)
            this[MeasurementType.WEIGHT] = Kg(79.45f)
        }
        val s = Setup(user(), lastStored = stored)
        s.connect()
        repeat(3) { s.notify("5a 0a 26 10 02 00 00 21 1f 09 09 aa") }
        s.notify("5a 0b 26 11 00 00 00 00 00 ff ff 3c aa")

        assertThat(s.published).isEmpty()
    }

    @Test
    fun `handles the replay and re-weigh of the device log of 2026-11-20 12_51`() {
        val s = Setup(user())
        // 12:50:48: connected while standing still; 79.35 kg, no impedance (socks).
        s.connect()
        repeat(3) { s.notify("5a 0a 26 10 02 00 00 21 1e ff fe aa") }
        s.notify("5a 0b 26 11 00 00 00 00 00 ff ff 3c aa")
        s.notify("5a 05 26 15 01 37 aa")
        s.notify("5a 05 26 15 02 34 aa")
        assertThat(s.published).hasSize(1)
        assertThat(s.disconnects).isEqualTo(1)
        s.disconnected()

        // 12:51:33: reconnect; the scale replays the result as phase 3, then the impedance frame.
        s.connect()
        s.writes.clear()
        repeat(2) { s.notify("5a 0a 26 10 03 00 00 21 1e ff ff aa") }
        repeat(2) { s.notify("5a 0b 26 11 00 00 00 00 00 ff ff 3c aa") }
        s.notify("5a 05 26 15 01 37 aa")
        s.notify("5a 05 26 15 02 34 aa")
        assertThat(s.published).hasSize(1)
        assertThat(s.writes.count { it.second.startsWith("a50f2615") }).isEqualTo(2)
        assertThat(s.disconnects).isEqualTo(1)

        // 12:51:39: the user steps on again; same weight, stored as a new weigh-in.
        s.notify("5a 0a 26 10 00 00 00 21 07 21 3b aa")
        s.notify("5a 0a 26 10 01 00 00 21 1e 46 44 aa")
        repeat(7) { s.notify("5a 0a 26 10 02 00 00 21 1e ff fe aa") }
        s.notify("5a 0b 26 11 00 00 00 00 00 ff ff 3c aa")
        assertThat(s.published).hasSize(2)
        assertThat(s.published.last()[MeasurementType.WEIGHT]!!.value).isWithin(1e-3f).of(79.35f)
        s.notify("5a 05 26 15 02 34 aa")
        assertThat(s.disconnects).isEqualTo(2)
    }

    @Test
    fun `drops a replay of the last stored measurement after an app restart`() {
        val stored = ScaleMeasurement().apply {
            dateTime = Date(System.currentTimeMillis() - 20_000)
            this[MeasurementType.WEIGHT] = Kg(86.85f)
        }
        val s = Setup(user(), lastStored = stored)
        s.connect()
        s.notify("5a 0a 26 10 03 00 00 21 21 ed d2 aa")
        s.notify("5a 0b 26 11 00 00 00 00 00 ff ff 3c aa")

        assertThat(s.published).isEmpty()
    }

    @Test
    fun `publishes weight-only after the impedance timeout`() {
        val s = Setup(user())
        s.connect()
        repeat(3) { s.notify("5a 0a 26 10 02 00 00 21 22 74 49 aa") }

        s.advanceBy(VitafitVT701Handler.IMPEDANCE_TIMEOUT_MS - 100)
        assertThat(s.published).isEmpty()
        // Another stable frame restarts the timer.
        s.notify("5a 0a 26 10 02 00 00 21 22 74 49 aa")
        s.advanceBy(VitafitVT701Handler.IMPEDANCE_TIMEOUT_MS - 100)
        assertThat(s.published).isEmpty()

        s.advanceBy(200)
        assertThat(s.published).hasSize(1)
        assertThat(s.published.single()[MeasurementType.IMPEDANCE]).isNull()
    }

    @Test
    fun `keeps the weight when the link drops before the impedance frame`() {
        val s = Setup(user())
        s.connect()
        s.notify("5a 0a 26 10 02 00 00 21 22 74 49 aa")
        s.disconnected()

        assertThat(s.published).hasSize(1)
        assertThat(s.disconnects).isEqualTo(0)
        assertThat(s.writes.none { it.second.startsWith("a50f2615") }).isTrue()   // no pages
    }

    @Test
    fun `stores impedance without body composition when the profile has no height`() {
        val s = Setup(user(heightCm = -1f))
        s.connect()
        liveWeighInWithImpedance.forEach { s.notify(it) }

        val m = s.published.single()
        assertThat(m[MeasurementType.IMPEDANCE]!!.value).isWithin(1e-3f).of(484f)
        assertThat(m[MeasurementType.BODY_FAT]).isNull()
    }

    @Test
    fun `claims the VT715 by its advertised name`() {
        val support = VitafitVT701Handler().supportFor(
            com.health.openscale.core.service.ScannedDeviceInfo(
                name = "Vitafit Body Fat", address = "ED:67:3B:48:34:16", rssi = -60,
                serviceUuids = emptyList(), manufacturerData = null,
            )
        )
        assertThat(support).isNotNull()
        assertThat(support!!.displayName).isEqualTo("Vitafit VT701 / VT715")
    }

    // --- results pages for the LCD -------------------------------------------------------------

    @Test
    fun `builds the results pages with values and rating levels`() {
        val pages = VitafitVT701Handler.buildResultPages(
            VitafitVT701Handler.DisplayValues(
                fatPercent = 31.1f, fatLevel = 3, waterPercent = 49.6f, waterLevel = 2,
                musclePercent = 35.3f, muscleLevel = 1, boneKg = 3.46f, proteinPercent = 15.4f,
                bmi = 30.5f, bmiLevel = 3, bmrKcal = 1682f,
            )
        )
        // Page 1: fat 0x6137 (level 3, 311), water 0x41f0 (level 2, 496), muscle 0x2161
        // (level 1, 353), bone 346, BMI 0x6131 (level 3, 305).
        assertThat(pages[0]).isEqualTo(hex("a5 0f 26 15 01 61 37 41 f0 21 61 01 5a 61 31 91 aa"))
        // Page 2: protein 154, skeletal muscle 353, subcutaneous fat --, BMR 1682, VF/age --.
        assertThat(pages[1]).isEqualTo(hex("a5 0f 26 15 02 00 9a 01 61 00 00 06 92 00 00 50 aa"))
    }

    @Test
    fun `sends only the BMI when there is no impedance`() {
        val pages = VitafitVT701Handler.buildResultPages(
            VitafitVT701Handler.DisplayValues(bmi = 27.2f, bmiLevel = 3)
        )
        assertThat(pages[0]).isEqualTo(hex("a5 0f 26 15 01 00 00 00 00 00 00 00 00 61 10 4c aa"))
        // An all-zero page 2 still completes the result (the LCD shows "--" instead of "Err").
        assertThat(pages[1]).isEqualTo(hex("a5 0f 26 15 02 00 00 00 00 00 00 00 00 00 00 3e aa"))
    }

    @Test
    fun `maps openScale's evaluation to the LCD symbol`() {
        assertThat(VitafitVT701Handler.levelFor(EvaluationState.LOW)).isEqualTo(1)       // arrow down
        assertThat(VitafitVT701Handler.levelFor(EvaluationState.NORMAL)).isEqualTo(2)    // circle
        assertThat(VitafitVT701Handler.levelFor(EvaluationState.HIGH)).isEqualTo(3)      // arrow up
        assertThat(VitafitVT701Handler.levelFor(EvaluationState.UNDEFINED)).isEqualTo(0)
    }

    @Test
    fun `rates the values like the app and sends the protein`() {
        // Male, 35 years, 180 cm, 88.20 kg, 484 Ohm: body fat ~26 % and BMI 27.2 are high,
        // water ~53 % (50–65) and skeletal muscle ~38 % (34.1–44.1) are normal; bone is unrated.
        val s = Setup(user(heightCm = 180f, age = 35))
        s.connect()
        liveWeighInWithImpedance.forEach { s.notify(it) }

        fun words(prefix: String) = s.writes.first { it.second.startsWith(prefix) }.second
            .let { frame -> (0 until 5).map { frame.substring(10 + 4 * it, 14 + 4 * it).toInt(16) } }
        val page1 = words("a50f261501")
        assertThat(page1.map { it shr 13 }).containsExactly(3, 2, 2, 0, 3).inOrder()
        assertThat((page1[4] and 0x1FFF) / 10f).isWithin(0.051f).of(88.20f / (1.8f * 1.8f))

        // Protein = (FFM - water - bone) / weight, from the same StandardImpedanceLib values.
        val lib = StandardImpedanceLib(GenderType.MALE, 35, 88.20, 1.8, 484.0)
        val expected = (lib.fatFreeMassKg - lib.totalBodyWaterKg - lib.boneMassKg) / 88.20 * 100
        val page2 = words("a50f261502")
        assertThat(page2[0] / 10.0).isWithin(0.051).of(expected)
        assertThat(page2[0] shr 13).isEqualTo(0)
    }

    @Test
    fun `computes the protein like the vendor app`() {
        // Vendor app, 2025-08-09: FFM 61.56 kg, water 47.3 % of 88.20 kg, bone 3.11 kg -> 19.0 %.
        val protein = VitafitVT701Handler.proteinPercent(61.56, 0.473 * 88.20, 3.11, 88.20)
        assertThat(protein!!.toDouble()).isWithin(0.05).of(19.0)
        assertThat(VitafitVT701Handler.proteinPercent(40.0, 45.0, 3.0, 80.0)).isNull()
        assertThat(VitafitVT701Handler.proteinPercent(60.0, 40.0, 3.0, 0.0)).isNull()
    }

    @Test
    fun `disconnects after a timeout when the scale does not ack page 2`() {
        val s = Setup(user())
        s.connect()
        liveWeighInWithImpedance.forEach { s.notify(it) }
        assertThat(s.disconnects).isEqualTo(0)

        s.advanceBy(VitafitVT701Handler.RESULTS_ACK_TIMEOUT_MS + 100)
        assertThat(s.disconnects).isEqualTo(1)
    }

    // --- harness ---------------------------------------------------------------------------------

    private fun uuid16(short: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    private fun user(heightCm: Float = 180f, age: Int = 35) = ScaleUser().apply {
        id = 1
        bodyHeight = heightCm
        gender = GenderType.MALE
        birthday = Calendar.getInstance().apply { add(Calendar.YEAR, -age); add(Calendar.DAY_OF_YEAR, -1) }.time
    }

    private inner class Setup(private val user: ScaleUser, private val lastStored: ScaleMeasurement? = null) {
        val handler = VitafitVT701Handler()
        val dispatcher = StandardTestDispatcher()
        val published = mutableListOf<ScaleMeasurement>()
        val writes = mutableListOf<Pair<UUID, String>>()
        val notifyEnabled = mutableListOf<UUID>()
        var disconnects = 0

        private val transport = object : ScaleDeviceHandler.Transport {
            override fun setNotifyOn(service: UUID, characteristic: UUID) { notifyEnabled += characteristic }
            override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
                writes += characteristic to payload.joinToString("") { "%02x".format(it) }
            }
            override fun read(service: UUID, characteristic: UUID) = Unit
            override fun disconnect() { disconnects++ }
            override fun hasCharacteristic(service: UUID, characteristic: UUID) = true
        }

        private val callbacks = object : ScaleDeviceHandler.Callbacks {
            override fun onPublish(measurement: ScaleMeasurement) { published += measurement }
            override fun resolveString(resId: Int, vararg args: Any) = "res:$resId"
        }

        private val settings = object : ScaleDeviceHandler.DriverSettings {
            private val ints = mutableMapOf<String, Int>()
            private val strings = mutableMapOf<String, String>()
            override fun getInt(key: String, default: Int) = ints[key] ?: default
            override fun putInt(key: String, value: Int) { ints[key] = value }
            override fun getString(key: String, default: String?) = strings[key] ?: default
            override fun putString(key: String, value: String) { strings[key] = value }
            override fun remove(key: String) { ints.remove(key); strings.remove(key) }
        }

        private val data = object : ScaleDeviceHandler.DataProvider {
            override fun currentUser() = user
            override fun usersForDevice() = listOf(user)
            override fun lastMeasurementFor(userId: Int) = published.lastOrNull() ?: lastStored
        }

        fun connect() {
            handler.attach(transport, callbacks, settings, data, CoroutineScope(dispatcher))
            handler.handleConnected(user)
        }

        fun notify(frame: String) {
            handler.handleNotification(uuid16(0xFFF1), hex(frame))
            dispatcher.scheduler.runCurrent()
        }

        fun disconnected() {
            handler.handleDisconnected()
            handler.detach()
        }

        fun advanceBy(ms: Long) {
            dispatcher.scheduler.advanceTimeBy(ms)
            dispatcher.scheduler.runCurrent()
        }
    }
}
