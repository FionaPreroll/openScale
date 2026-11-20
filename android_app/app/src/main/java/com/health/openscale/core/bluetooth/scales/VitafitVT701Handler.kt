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

import com.health.openscale.R
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.StandardImpedanceLib
import com.health.openscale.core.data.EvaluationState
import com.health.openscale.core.service.MeasurementEvaluator
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID
import kotlin.math.abs
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent

/**
 * Vitafit "Body Fat" smart scales (VT701, VT715), sold with the Vitafit / Fitdays app.
 *
 * Both models advertise as "Vitafit Body Fat" (manufacturer string "SHINE") and speak the same
 * protocol. It was reverse-engineered from HCI snoops of Vitafit-app weigh-ins (VT701: 85.00 /
 * 84.95 kg; VT715: four sessions with 86.75–88.20 kg, see the VT715 protocol notes in
 * github.com/FionaPreroll/Vitafit-VT715-ReverseEngineering, PROTOCOL.md):
 *  - Service 0xFFF0, notify characteristic 0xFFF1, write characteristic 0xFFF2.
 *  - Both directions use the frame `[hdr][len][0x26][cmd][data…][chk][0xAA]`, where
 *    `hdr` = 0x5A for scale→phone and 0xA5 for phone→scale, `len` counts every byte after
 *    itself (0x26 through 0xAA), and `chk` = XOR of `len` through the last data byte.
 *  - On connect the app sends 0x33 (hello), 0x44 (query) and 0x17 01 (presumably "unit = kg").
 *  - Cmd 0x10 streams the weight: `[phase][00][00][attr][w_hi][w_lo]`, weight in 0.01 kg.
 *    `phase` is 0 = idle/zero, 1 = measuring, 2 = stable (impedance is being measured),
 *    3 = final. The phone acks every frame with `a5 05 26 10 [phase]`; a final frame is
 *    repeated until it is acked, so a weigh-in finished before the phone connected still
 *    arrives (as phase 3 only). `attr` was 0x21 in every capture (2 decimals, kg).
 *  - About 2.3 s after the last stable/final frame the scale sends cmd 0x11 with the raw
 *    whole-body impedance: `[00 00 00 00 00][z_hi][z_lo]` in Ω, or 0xFFFF if no impedance
 *    could be measured (shoes/socks, stepped off). The phone acks with `a5 05 26 11 00`;
 *    the scale still repeats a real impedance every 2.5 s until it gets results page 2.
 *  - The phone then sends the results for the scale's LCD (cmd 0x15, two pages of five 16-bit
 *    slots: rating level in bits 13–15, value below). The LCD shows them verbatim (BF, BW, MM,
 *    PR, BMI with an arrow-down / circle / arrow-up symbol per level); page 2 also ends the
 *    impedance repeats. Without page 2 the LCD shows "Err".
 *
 * The scale itself sends only weight + impedance; body composition (fat/water/muscle) is left
 * to openScale's estimator, which needs the raw impedance published here. openScale's results
 * are sent back for the scale's display, rated with [MeasurementEvaluator] like in the app.
 *
 * One weigh-in is published at most once per connection, after which the link is closed. The
 * scale may repeat its last result (as stable or final frames) when it is reconnected while still
 * awake; such a replay of the measurement that was just stored is recognised and dropped, and the
 * stored results are sent to the LCD again.
 */
class VitafitVT701Handler : ScaleDeviceHandler() {

    private val service = uuid16(0xFFF0)
    private val notifyCharacteristic = uuid16(0xFFF1)
    private val writeCharacteristic = uuid16(0xFFF2)

    // --- per-connection state (reset in onConnected) ---
    private var latchedWeightRaw = -1
    private var sawLiveFrames = false
    private var ignoringReplay = false
    private var published = false
    private var warnedAttribute = false
    private var fallbackJob: Job? = null
    private var awaitingResultsAck = false
    private var resultsAckJob: Job? = null
    private var answeredReplay = false

    // --- kept across connections: ScaleFactory reuses one handler instance ---
    private var lastPublishedRaw = -1
    private var lastPublishedAtMs = 0L
    private var lastResultPages: List<ByteArray>? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        if (!device.name.startsWith("Vitafit", ignoreCase = true)) return null

        val caps = setOf(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.BODY_COMPOSITION
        )
        return DeviceSupport(
            displayName = "Vitafit VT701 / VT715",
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        latchedWeightRaw = -1
        sawLiveFrames = false
        ignoringReplay = false
        published = false
        warnedAttribute = false
        fallbackJob?.cancel()
        fallbackJob = null
        awaitingResultsAck = false
        resultsAckJob?.cancel()
        resultsAckJob = null
        answeredReplay = false

        setNotifyOn(service, notifyCharacteristic)

        // Replay the app's static hello sequence. These frames carry no timestamp/user data,
        // so they are safe to send verbatim; the scale then streams type 0x10 weight frames.
        writeTo(service, writeCharacteristic, buildCommand(CMD_HELLO, byteArrayOf(0x00)))
        writeTo(service, writeCharacteristic, buildCommand(CMD_QUERY, byteArrayOf()))
        writeTo(service, writeCharacteristic, buildCommand(CMD_SET_UNIT, byteArrayOf(UNIT_KG.toByte())))

        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != notifyCharacteristic) return

        if (!isValidScaleFrame(data)) {
            // The first notification after connecting is sometimes a stale, truncated frame.
            logD("Vitafit: dropping invalid frame ${data.toHexPreview(32)}")
            return
        }
        when (data[3].toInt() and 0xFF) {
            CMD_WEIGHT -> decodeWeight(data)?.let { onWeight(it, user) }
            CMD_IMPEDANCE -> decodeImpedance(data)?.let { onImpedance(it.ohm, user) }
            CMD_RESULTS -> onResultsAck(data)
            else -> logD("Vitafit: cmd 0x%02x data=%s".format(data[3].toInt() and 0xFF, data.toHexPreview(32)))
        }
    }

    override fun onDisconnected() {
        fallbackJob?.cancel()
        fallbackJob = null
        awaitingResultsAck = false
        resultsAckJob?.cancel()
        resultsAckJob = null
        // Link lost after a stable weight but before the impedance frame: keep the weight.
        if (!published && latchedWeightRaw >= 0) {
            logD("Vitafit: disconnected before impedance; publishing weight-only")
            publishMeasurement(latchedWeightRaw, null, currentAppUser(), disconnect = false)
        }
    }

    private fun onWeight(reading: WeightReading, user: ScaleUser) {
        if (published) return

        // Ack every weight frame with its phase like the Vitafit app; the scale keeps repeating a
        // final (phase 3) frame until it is acked and only then moves on to the impedance frame.
        writeTo(service, writeCharacteristic, buildCommand(CMD_WEIGHT, byteArrayOf(reading.phase.toByte())))

        if (reading.attribute != ATTR_KG_2_DECIMALS && !warnedAttribute) {
            warnedAttribute = true
            logW("Vitafit: unknown weight attribute 0x%02x, decoding as 0.01 kg".format(reading.attribute))
        }

        when (reading.phase) {
            PHASE_IDLE, PHASE_MEASURING -> {
                // A new weigh-in is under way; a replayed old result no longer applies.
                sawLiveFrames = true
                ignoringReplay = false
            }
            PHASE_STABLE, PHASE_FINAL -> {
                if (ignoringReplay) return
                // A connection that starts with stable/final frames equal to the measurement just
                // stored is the scale repeating it (e.g. the reconnect after a background weigh-in).
                val window = if (reading.phase == PHASE_FINAL) REPLAY_WINDOW_MS else STABLE_REPLAY_WINDOW_MS
                if (!sawLiveFrames && latchedWeightRaw < 0 &&
                    isReplay(reading.weightRaw, System.currentTimeMillis(), user, window)
                ) {
                    logI("Vitafit: ${reading.weightRaw / 100.0f} kg (phase ${reading.phase}) repeats the stored measurement; ignoring")
                    ignoringReplay = true
                    return
                }
                if (reading.phase == PHASE_STABLE) sawLiveFrames = true
                if (latchedWeightRaw != reading.weightRaw) {
                    logD("Vitafit: weight=${reading.weightRaw / 100.0f} kg (phase ${reading.phase})")
                }
                latchedWeightRaw = reading.weightRaw
                restartFallback(user)
            }
            else -> logD("Vitafit: unknown weight phase ${reading.phase}")
        }
    }

    private fun onImpedance(ohm: Int?, user: ScaleUser) {
        if (published) return
        writeTo(service, writeCharacteristic, buildCommand(CMD_IMPEDANCE, byteArrayOf(0x00)))
        // Impedance without a latched weight belongs to an ignored replay or a lost weigh-in.
        if (latchedWeightRaw < 0) {
            // Answer a replay like the original weigh-in: the pages keep the LCD on the results
            // (instead of "Err") and stop the impedance repeats. The link stays open.
            val pages = lastResultPages
            if (ignoringReplay && !answeredReplay && pages != null) {
                answeredReplay = true
                logD("Vitafit: sending the stored results again for the replay")
                pages.forEach { writeTo(service, writeCharacteristic, it) }
            }
            return
        }
        publishMeasurement(latchedWeightRaw, ohm, user)
    }

    /**
     * Safety net: if the impedance frame never arrives, publish weight-only. Restarted with every
     * stable/final frame, because the scale keeps measuring while it repeats them.
     */
    private fun restartFallback(user: ScaleUser) {
        fallbackJob?.cancel()
        fallbackJob = scope.launch {
            delay(IMPEDANCE_TIMEOUT_MS)
            if (!published && latchedWeightRaw >= 0) {
                logD("Vitafit: no impedance within ${IMPEDANCE_TIMEOUT_MS} ms; publishing weight-only")
                publishMeasurement(latchedWeightRaw, null, user)
            }
        }
    }

    private fun isReplay(weightRaw: Int, nowMs: Long, user: ScaleUser, windowMs: Long): Boolean {
        if (isSameWeighIn(weightRaw, nowMs, lastPublishedRaw, lastPublishedAtMs, windowMs)) return true
        // Also catches a replay after openScale was restarted between the two connections.
        val last = lastMeasurementFor(user.id) ?: return false
        val lastKg = last[MeasurementType.WEIGHT]?.value ?: return false
        val lastAt = last.dateTime?.time ?: return false
        return isSameWeighIn(weightRaw, nowMs, Math.round(lastKg * 100f), lastAt, windowMs)
    }

    private fun publishMeasurement(
        weightRaw: Int,
        impedanceOhm: Int?,
        user: ScaleUser,
        disconnect: Boolean = true,
    ) {
        if (published) return
        published = true
        fallbackJob?.cancel()
        fallbackJob = null
        lastPublishedRaw = weightRaw
        lastPublishedAtMs = System.currentTimeMillis()

        val weightKg = weightRaw / 100.0f
        val bmi = bmiOf(weightKg, user)
        var display = DisplayValues(
            bmi = bmi,
            bmiLevel = bmi?.let { levelFor(MeasurementEvaluator.evalBmi(it, user.age, user.gender).state) } ?: 0,
        )
        val measurement = ScaleMeasurement().apply {
            userId = user.id
            dateTime = Date(lastPublishedAtMs)
            this[MeasurementType.WEIGHT] = Kg(weightKg)
        }

        // The scale reports only weight + raw impedance; derive body composition the same way the
        // other impedance scales in this package do. The guard mirrors StandardImpedanceLib's note
        // that its formulas only hold for whole-body impedance in a sane range.
        if (impedanceOhm != null && impedanceOhm in 1 until MAX_PLAUSIBLE_IMPEDANCE) {
            measurement[MeasurementType.IMPEDANCE] = Ohm(impedanceOhm.toFloat())
            if (user.bodyHeight > 0f && user.age > 0) {
                val lib = StandardImpedanceLib(
                    gender = user.gender,
                    age = user.age,
                    weightKg = weightKg.toDouble(),
                    heightM = user.bodyHeight / 100.0,
                    impedance = impedanceOhm.toDouble(),
                )
                measurement[MeasurementType.BODY_FAT] = Percent(lib.totalFatPercentage.toFloat())
                measurement[MeasurementType.WATER] = Percent(lib.totalBodyWaterPercentage.toFloat())
                measurement[MeasurementType.MUSCLE] = Percent(lib.skeletalMusclePercentage.toFloat())
                measurement[MeasurementType.BONE] = Kg(lib.boneMassKg.toFloat())
                measurement[MeasurementType.BMR] = Kcal(lib.basalMetabolicRate.toFloat())
                val fat = lib.totalFatPercentage.toFloat()
                val water = lib.totalBodyWaterPercentage.toFloat()
                val muscle = lib.skeletalMusclePercentage.toFloat()
                display = display.copy(
                    fatPercent = fat,
                    fatLevel = levelFor(MeasurementEvaluator.evalBodyFat(fat, user.age, user.gender).state),
                    waterPercent = water,
                    waterLevel = levelFor(MeasurementEvaluator.evalWater(water, user.age, user.gender).state),
                    musclePercent = muscle,
                    muscleLevel = levelFor(MeasurementEvaluator.evalMuscle(muscle, user.age, user.gender).state),
                    boneKg = lib.boneMassKg.toFloat(),
                    proteinPercent = proteinPercent(lib.fatFreeMassKg, lib.totalBodyWaterKg, lib.boneMassKg, weightKg.toDouble()),
                    bmrKcal = lib.basalMetabolicRate.toFloat(),
                )
            } else {
                logW("Vitafit: user height/age missing; storing impedance without body composition")
            }
        }

        publish(measurement)
        logI("Vitafit publish weight=$weightKg kg impedance=${impedanceOhm ?: "none"} Ω")
        if (disconnect) showResultsAndDisconnect(display)
    }

    /**
     * Sends the results pages for the scale's LCD, then disconnects once the scale has acked
     * page 2. [requestDisconnect] cancels the link at once, so it must not run before the queued
     * page writes went out.
     */
    private fun showResultsAndDisconnect(values: DisplayValues) {
        val pages = buildResultPages(values)
        lastResultPages = pages
        pages.forEach { writeTo(service, writeCharacteristic, it) }
        awaitingResultsAck = true
        resultsAckJob?.cancel()
        resultsAckJob = scope.launch {
            delay(RESULTS_ACK_TIMEOUT_MS)
            if (awaitingResultsAck) {
                logD("Vitafit: results page 2 not acked within ${RESULTS_ACK_TIMEOUT_MS} ms; disconnecting")
                finishSession()
            }
        }
    }

    private fun onResultsAck(frame: ByteArray) {
        if (frame.size != 7) return
        val page = frame[4].toInt() and 0xFF
        logD("Vitafit: scale acked results page $page")
        if (awaitingResultsAck && page == 2) finishSession()
    }

    private fun finishSession() {
        awaitingResultsAck = false
        val job = resultsAckJob
        resultsAckJob = null
        requestDisconnect()
        job?.cancel()
    }

    /** A decoded cmd 0x10 frame. */
    data class WeightReading(val phase: Int, val attribute: Int, val weightRaw: Int)

    /** A decoded cmd 0x11 frame; [ohm] is `null` when the scale could not measure an impedance. */
    data class ImpedanceReading(val ohm: Int?)

    /**
     * Values for the scale's LCD. `null` = not available (the LCD shows "--"); levels are
     * 0 = none, 1 = low, 2 = normal, 3 = high, 4 = very high.
     */
    data class DisplayValues(
        val fatPercent: Float? = null,
        val fatLevel: Int = 0,
        val waterPercent: Float? = null,
        val waterLevel: Int = 0,
        val musclePercent: Float? = null,
        val muscleLevel: Int = 0,
        val boneKg: Float? = null,
        val proteinPercent: Float? = null,
        val bmi: Float? = null,
        val bmiLevel: Int = 0,
        val bmrKcal: Float? = null,
    )

    companion object {
        private const val HEADER_SCALE = 0x5A
        private const val HEADER_PHONE = 0xA5
        private const val PRODUCT_ID = 0x26
        private const val TRAILER = 0xAA

        private const val CMD_WEIGHT = 0x10
        private const val CMD_IMPEDANCE = 0x11
        private const val CMD_RESULTS = 0x15
        private const val CMD_SET_UNIT = 0x17
        private const val CMD_HELLO = 0x33
        private const val CMD_QUERY = 0x44
        private const val UNIT_KG = 0x01

        const val PHASE_IDLE = 0x00
        const val PHASE_MEASURING = 0x01
        const val PHASE_STABLE = 0x02
        const val PHASE_FINAL = 0x03

        /** Weight attribute seen in every capture: high nibble = 2 decimals, low nibble = kg. */
        private const val ATTR_KG_2_DECIMALS = 0x21

        /** Impedance value the scale sends when it could not measure one. */
        const val IMPEDANCE_NONE = 0xFFFF
        private const val MAX_PLAUSIBLE_IMPEDANCE = 1500

        /** The scale acks each results page within ~40 ms. */
        const val RESULTS_ACK_TIMEOUT_MS = 3000L

        /** Value bits of a results slot; body fat only has bits 0–9 (bits 10–11 unknown). */
        private const val SLOT_VALUE_MASK = 0x1FFF
        private const val BODY_FAT_VALUE_MASK = 0x3FF

        /** Maps openScale's evaluation to the scale's rating level (LCD symbol). */
        fun levelFor(state: EvaluationState): Int = when (state) {
            EvaluationState.LOW -> 1        // arrow down
            EvaluationState.NORMAL -> 2     // blue circle
            EvaluationState.HIGH -> 3       // arrow up
            EvaluationState.UNDEFINED -> 0
        }

        /** BMI from the profile height, or `null` without a height. */
        fun bmiOf(weightKg: Float, user: ScaleUser): Float? {
            val h = user.bodyHeight / 100f
            return if (h > 0f) weightKg / (h * h) else null
        }

        /**
         * Protein % for the LCD's PR screen: the fat-free mass that is neither water nor bone,
         * the same definition the Vitafit app uses (muscle % − water %). Only shown on the scale,
         * openScale does not store it. `null` if the inputs do not give a positive value.
         */
        fun proteinPercent(fatFreeMassKg: Double, waterKg: Double, boneKg: Double, weightKg: Double): Float? {
            if (weightKg <= 0.0) return null
            val pct = (fatFreeMassKg - waterKg - boneKg) / weightKg * 100.0
            return if (pct.isFinite() && pct > 0.0) pct.toFloat() else null
        }

        /**
         * The two cmd 0x15 frames for the LCD. Slots that openScale does not compute
         * (subcutaneous fat, visceral fat, metabolic age) are sent as 0. The muscle screen shows
         * openScale's MUSCLE value (skeletal muscle %).
         *
         * Page 1: body fat % ×10, water % ×10, muscle % ×10, bone kg ×100, BMI ×10.
         * Page 2: protein % ×10, skeletal muscle % ×10, subcutaneous fat, BMR kcal, visceral fat/metabolic age.
         */
        fun buildResultPages(v: DisplayValues): List<ByteArray> {
            val page1 = intArrayOf(
                slot(v.fatPercent, 10, v.fatLevel, BODY_FAT_VALUE_MASK),
                slot(v.waterPercent, 10, v.waterLevel),
                slot(v.musclePercent, 10, v.muscleLevel),
                slot(v.boneKg, 100),
                slot(v.bmi, 10, v.bmiLevel),
            )
            val page2 = intArrayOf(slot(v.proteinPercent, 10), slot(v.musclePercent, 10), 0, slot(v.bmrKcal, 1), 0)
            return listOf(resultsPage(1, page1), resultsPage(2, page2))
        }

        private fun slot(value: Float?, scale: Int, level: Int = 0, mask: Int = SLOT_VALUE_MASK): Int {
            if (value == null || !value.isFinite() || value <= 0f) return 0
            val raw = Math.round(value * scale).coerceIn(0, mask)
            return (level.coerceIn(0, 7) shl 13) or raw
        }

        private fun resultsPage(page: Int, words: IntArray): ByteArray {
            val data = ByteArray(11)
            data[0] = page.toByte()
            words.forEachIndexed { i, w ->
                data[1 + 2 * i] = (w shr 8).toByte()
                data[2 + 2 * i] = w.toByte()
            }
            return buildCommand(CMD_RESULTS, data)
        }

        /** Observed gap from the last stable/final frame to the impedance frame is ~2.3 s. */
        const val IMPEDANCE_TIMEOUT_MS = 5000L

        /**
         * A stable/final frame at the start of a connection equal to a measurement stored this
         * recently is a replay. A replay needs
         * the scale to be still awake (it switches off ~25 s after its last frame and repeats the
         * impedance frame every 2.5 s until then), while the same weight can genuinely recur
         * within minutes, so the window is kept short.
         */
        const val REPLAY_WINDOW_MS = 60 * 1000L

        /**
         * Shorter window for a connection that starts with stable frames. That is also how a
         * genuine weigh-in looks when the link came up late (2026-11-20 12:38:51: 79.45 kg again,
         * 67 s after the stored 79.45 kg, after a failed connection attempt), and the same weight
         * recurs easily. A replay in this form is expected from the background reconnect, which
         * starts within a few seconds of the stored measurement.
         */
        const val STABLE_REPLAY_WINDOW_MS = 20 * 1000L

        /**
         * Weight in raw 0.01 kg units from a valid type 0x10 frame whose phase is stable (0x02) or
         * final (0x03), or `null` if the frame is malformed, not a weight frame, or still settling.
         *
         * Layout: `5a 0a 26 10 [phase] 00 00 [attr] [w_hi] [w_lo] [chk] aa`; the weight is
         * the big-endian pair at bytes 8..9 (e.g. `21 34` = 8500 = 85.00 kg).
         */
        fun parseStableWeightRaw(frame: ByteArray): Int? {
            val reading = decodeWeight(frame) ?: return null
            return if (reading.phase == PHASE_STABLE || reading.phase == PHASE_FINAL) reading.weightRaw else null
        }

        /** Decodes any valid type 0x10 frame, whatever its phase. */
        fun decodeWeight(frame: ByteArray): WeightReading? {
            if (!isValidScaleFrame(frame)) return null
            if ((frame[3].toInt() and 0xFF) != CMD_WEIGHT) return null
            if (frame.size != 12) return null
            return WeightReading(
                phase = frame[4].toInt() and 0xFF,
                attribute = frame[7].toInt() and 0xFF,
                weightRaw = ((frame[8].toInt() and 0xFF) shl 8) or (frame[9].toInt() and 0xFF),
            )
        }

        /**
         * Decodes a valid type 0x11 frame, or returns `null` if the frame is malformed or not an
         * impedance frame. [ImpedanceReading.ohm] is `null` when the scale reports that it could
         * not measure an impedance (0xFFFF, or 0).
         *
         * Layout: `5a 0b 26 11 00 00 00 00 00 [z_hi] [z_lo] [chk] aa`; impedance is the big-endian
         * pair at bytes 9..10 (e.g. `01 89` = 393 Ω).
         */
        fun decodeImpedance(frame: ByteArray): ImpedanceReading? {
            if (!isValidScaleFrame(frame)) return null
            if ((frame[3].toInt() and 0xFF) != CMD_IMPEDANCE) return null
            if (frame.size != 13) return null
            val ohm = ((frame[9].toInt() and 0xFF) shl 8) or (frame[10].toInt() and 0xFF)
            return ImpedanceReading(if (ohm == 0 || ohm == IMPEDANCE_NONE) null else ohm)
        }

        /**
         * Whole-body impedance in ohms from a valid type 0x11 frame, or `null` if the frame is
         * malformed, not an impedance frame, or reports no impedance (0 or 0xFFFF).
         */
        fun parseImpedanceOhm(frame: ByteArray): Int? = decodeImpedance(frame)?.ohm

        /**
         * True if a stable/final frame with [weightRaw] at [nowMs] repeats a weigh-in that was
         * stored as [lastRaw] at [lastAtMs].
         */
        fun isSameWeighIn(
            weightRaw: Int,
            nowMs: Long,
            lastRaw: Int,
            lastAtMs: Long,
            windowMs: Long = REPLAY_WINDOW_MS,
        ): Boolean {
            if (lastRaw < 0) return false
            val age = nowMs - lastAtMs
            return abs(weightRaw - lastRaw) <= 1 && age in 0..windowMs
        }

        /** True if [frame] is a well-formed scale frame with a matching XOR checksum. */
        fun isValidScaleFrame(frame: ByteArray): Boolean {
            if (frame.size < 6) return false
            if ((frame[0].toInt() and 0xFF) != HEADER_SCALE) return false
            if ((frame[2].toInt() and 0xFF) != PRODUCT_ID) return false
            if ((frame[frame.size - 1].toInt() and 0xFF) != TRAILER) return false
            // len counts every byte after itself, i.e. index 2 through the trailer.
            if ((frame[1].toInt() and 0xFF) != frame.size - 2) return false
            return checksum(frame, 1, frame.size - 2) == (frame[frame.size - 2].toInt() and 0xFF)
        }

        /** Build a phone→scale command frame with the correct length and checksum. */
        fun buildCommand(type: Int, data: ByteArray): ByteArray {
            val len = data.size + 4 // 0x26, type, chk, trailer
            val frame = ByteArray(data.size + 6)
            frame[0] = HEADER_PHONE.toByte()
            frame[1] = len.toByte()
            frame[2] = PRODUCT_ID.toByte()
            frame[3] = type.toByte()
            data.copyInto(frame, 4)
            frame[frame.size - 2] = checksum(frame, 1, frame.size - 2).toByte()
            frame[frame.size - 1] = TRAILER.toByte()
            return frame
        }

        /** XOR of bytes in `[from, toExclusive)`. */
        private fun checksum(frame: ByteArray, from: Int, toExclusive: Int): Int {
            var acc = 0
            for (i in from until toExclusive) acc = acc xor (frame[i].toInt() and 0xFF)
            return acc and 0xFF
        }
    }
}
