/*
 * ZiMusicAudioProcessor.kt
 *
 * Copyright (c) 2026 JigenxOhtsusuki
 *
 * This file is part of the ZiMusic audio engine.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but without any warranty; without even the implied warranty of
 * merchantability or fitness for a particular purpose.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Author: JigenxOhtsusuki
 */

package dev.jigen.zimusic.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.*

@UnstableApi
class ZiMusicAudioProcessor : BaseAudioProcessor() {

    // ── tuning ────────────────────────────────────────────────────────────
    private val EXCITER_SHELF_HZ   = 6_000.0
    private val EXCITER_DRIVE      = 1.4
    private val EXCITER_MIX        = 0.10
    private val SPATIAL_WIDTH      = 1.10
    private val TRANSIENT_THRESH   = 1.30
    private val TRANSIENT_GAIN     = 1.18
    private val CROSSFEED_DELAY_MS = 0.28
    private val CROSSFEED_HP_HZ    = 250.0
    private val HEAD_SHADOW_HZ     = 700.0
    private val CROSSFEED_MIX      = 0.36
    private val OUTPUT_GAIN        = 0.88
    private val LIMITER_THRESHOLD  = 0.944   // −0.5 dBFS
    private val NS_F1              = -1.623  // Gerzon-Craven noise-shaping coefficient 1
    private val NS_F2              =  0.862  // Gerzon-Craven noise-shaping coefficient 2
    private val DITHER_AMP         =  1.5e-5 // ~0.5 LSB of 16-bit

    // ── state ─────────────────────────────────────────────────────────────
    private var sampleRate   = 48_000
    private var channelCount = 2

    // Stage 2 — exciter high-shelf filters in M/S
    private val hsMid  = BiquadFilter()
    private val hsSide = BiquadFilter()

    // Stage 3 — transient enhancer envelope state
    private var fastEnvL = 0.0; private var fastEnvR = 0.0
    private var slowEnvL = 0.0; private var slowEnvR = 0.0
    private var fastAtk  = 0.0; private var fastRel  = 0.0
    private var slowAtk  = 0.0; private var slowRel  = 0.0

    // Stage 5 — binaural crossfeed
    private var delaySamples = 0
    private var delayIdx     = 0
    private var delayBuf     = FloatArray(0)
    private val hpCross      = BiquadFilter()
    private val lpShadow     = BiquadFilter()

    // Stage 1 — noise shaping error history
    private var nsEL1 = 0.0; private var nsEL2 = 0.0
    private var nsER1 = 0.0; private var nsER2 = 0.0

    // Dither LFSRs
    private var lfsrA = 0x12345678L
    private var lfsrB = 0xABCDE012L

    // ── configure ─────────────────────────────────────────────────────────
    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        sampleRate   = input.sampleRate
        channelCount = input.channelCount
        setupFilters()
        return AudioProcessor.AudioFormat(sampleRate, channelCount, C.ENCODING_PCM_FLOAT)
    }

    private fun setupFilters() {
        val sr = sampleRate.toDouble()

        hsMid.setHighShelf(EXCITER_SHELF_HZ,  sr, 0.707)
        hsSide.setHighShelf(EXCITER_SHELF_HZ, sr, 0.707)

        // time constants: α = 1 − e^(−1 / (τ · sr))
        fastAtk  = 1.0 - exp(-1.0 / (0.0003 * sr))
        fastRel  = 1.0 - exp(-1.0 / (0.020  * sr))
        slowAtk  = 1.0 - exp(-1.0 / (0.020  * sr))
        slowRel  = 1.0 - exp(-1.0 / (0.200  * sr))

        delaySamples = max(1, (sr * CROSSFEED_DELAY_MS / 1000.0).roundToInt())
        delayBuf     = FloatArray(delaySamples)
        delayIdx     = 0
        hpCross.setHighPass(CROSSFEED_HP_HZ, sr, 0.707)
        lpShadow.setLowPass(HEAD_SHADOW_HZ,  sr, 0.707)
    }

    // ── hot path ──────────────────────────────────────────────────────────
    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val frames = remaining / (2 * channelCount)
        val output = replaceOutputBuffer(frames * channelCount * 4)

        repeat(frames) {

            // ── read ──────────────────────────────────────────────────────
            val rawL = inputBuffer.short.toDouble()
            val rawR = if (channelCount == 2) inputBuffer.short.toDouble() else rawL

            // ══ STAGE 1 — Noise-shaped dither ════════════════════════════
            val dL = triangularDither()
            val dR = triangularDither()

            val shapedL = rawL + dL + NS_F1 * nsEL1 + NS_F2 * nsEL2
            val shapedR = rawR + dR + NS_F1 * nsER1 + NS_F2 * nsER2

            val qL = shapedL.roundToInt().toDouble().coerceIn(-32768.0, 32767.0)
            val qR = shapedR.roundToInt().toDouble().coerceIn(-32768.0, 32767.0)

            nsEL2 = nsEL1; nsEL1 = shapedL - qL
            nsER2 = nsER1; nsER1 = shapedR - qR

            var sL = qL / 32768.0
            var sR = qR / 32768.0

            // ══ STAGE 2 — Harmonic exciter (M/S) ═════════════════════════
            val mid  = (sL + sR) * 0.5
            val side = (sL - sR) * 0.5

            val exMid  = mid  + fastTanh(hsMid.process(mid)   * EXCITER_DRIVE) * EXCITER_MIX
            val exSide = side + fastTanh(hsSide.process(side)  * EXCITER_DRIVE) * EXCITER_MIX

            sL = exMid + exSide
            sR = exMid - exSide

            // ══ STAGE 3 — Transient enhancer ═════════════════════════════
            val aL = abs(sL); val aR = abs(sR)

            fastEnvL += (if (aL > fastEnvL) fastAtk else fastRel) * (aL - fastEnvL)
            slowEnvL += (if (aL > slowEnvL) slowAtk else slowRel) * (aL - slowEnvL)
            fastEnvR += (if (aR > fastEnvR) fastAtk else fastRel) * (aR - fastEnvR)
            slowEnvR += (if (aR > slowEnvR) slowAtk else slowRel) * (aR - slowEnvR)

            val tL = if (slowEnvL > 1e-9) fastEnvL / slowEnvL else 1.0
            val tR = if (slowEnvR > 1e-9) fastEnvR / slowEnvR else 1.0

            sL *= if (tL > TRANSIENT_THRESH) 1.0 + (TRANSIENT_GAIN - 1.0) *
                ((tL - TRANSIENT_THRESH) / TRANSIENT_THRESH).coerceAtMost(1.0) else 1.0
            sR *= if (tR > TRANSIENT_THRESH) 1.0 + (TRANSIENT_GAIN - 1.0) *
                ((tR - TRANSIENT_THRESH) / TRANSIENT_THRESH).coerceAtMost(1.0) else 1.0

            // ══ STAGE 4 — Stereo width ════════════════════════════════════
            val wMid  = (sL + sR) * 0.5
            val wSide = (sL - sR) * 0.5 * SPATIAL_WIDTH
            sL = wMid + wSide
            sR = wMid - wSide

            // ══ STAGE 5 — Binaural crossfeed ═════════════════════════════
            val xMid  = (sL + sR) * 0.5
            val xSide = (sL - sR) * 0.5

            val sideHigh = hpCross.process(xSide).toFloat()
            val sideLow  = (xSide - sideHigh).toFloat()

            val delayed  = delayBuf[delayIdx]
            delayBuf[delayIdx] = sideHigh
            delayIdx = if (delayIdx + 1 >= delaySamples) 0 else delayIdx + 1

            val crossfed = lpShadow.process(delayed.toDouble()).toFloat() * CROSSFEED_MIX

            val fSide = sideLow + sideHigh + crossfed
            sL = (xMid + fSide) * OUTPUT_GAIN
            sR = (xMid - fSide) * OUTPUT_GAIN

            // ══ STAGE 6 — Soft limiter ════════════════════════════════════
            output.putFloat(softLimit(sL).toFloat())
            output.putFloat(softLimit(sR).toFloat())
        }

        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun softLimit(x: Double): Double {
        val a = abs(x)
        if (a <= LIMITER_THRESHOLD) return x
        val over = a - LIMITER_THRESHOLD
        val knee = LIMITER_THRESHOLD + (1.0 - LIMITER_THRESHOLD) *
            fastTanh(over / (1.0 - LIMITER_THRESHOLD))
        return if (x >= 0.0) knee else -knee
    }

    private fun fastTanh(x: Double): Double {
        val x2 = x * x
        return x * (27.0 + x2) / (27.0 + 9.0 * x2)
    }

    private fun lfsr(s: Long): Long {
        var x = s
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl  5)
        return x and 0xFFFFFFFFL
    }

    private fun triangularDither(): Double {
        lfsrA = lfsr(lfsrA)
        lfsrB = lfsr(lfsrB)
        return (lfsrA.toDouble() - lfsrB.toDouble()) *
            (DITHER_AMP / 0xFFFFFFFFL.toDouble())
    }

    // ── lifecycle ─────────────────────────────────────────────────────────
    override fun onFlush() {
        hsMid.reset(); hsSide.reset()
        hpCross.reset(); lpShadow.reset()
        delayBuf.fill(0f); delayIdx = 0
        fastEnvL = 0.0; fastEnvR = 0.0
        slowEnvL = 0.0; slowEnvR = 0.0
        nsEL1 = 0.0; nsEL2 = 0.0
        nsER1 = 0.0; nsER2 = 0.0
        setupFilters()
    }

    override fun onReset() {
        sampleRate   = 48_000
        channelCount = 2
        onFlush()
    }

    // ── Direct Form II Transposed biquad ──────────────────────────────────
    // 2 state vars (w1,w2) vs 4 in Direct Form I — half the memory
    // bandwidth in the hot path. Numerically stable at all sample rates.
    class BiquadFilter {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
        private var a1 = 0.0; private var a2 = 0.0
        private var w1 = 0.0; private var w2 = 0.0

        fun process(x: Double): Double {
            val y  = b0 * x + w1
            val r1 = b1 * x - a1 * y + w2
            val r2 = b2 * x - a2 * y
            w1 = if (abs(r1) < 1e-25) 0.0 else r1
            w2 = if (abs(r2) < 1e-25) 0.0 else r2
            return y
        }

        fun setHighPass(freq: Double, sr: Double, Q: Double) {
            val w0 = 2.0 * PI * freq / sr
            val a  = sin(w0) / (2.0 * Q)
            val c  = cos(w0)
            val n  = 1.0 / (1.0 + a)
            val h  = (1.0 + c) * 0.5
            b0 =  h * n;  b1 = -(1.0 + c) * n;  b2 = h * n
            a1 = -2.0 * c * n;  a2 = (1.0 - a) * n
            reset()
        }

        fun setLowPass(freq: Double, sr: Double, Q: Double) {
            val w0 = 2.0 * PI * freq / sr
            val a  = sin(w0) / (2.0 * Q)
            val c  = cos(w0)
            val n  = 1.0 / (1.0 + a)
            val l  = (1.0 - c) * 0.5
            b0 = l * n;  b1 = (1.0 - c) * n;  b2 = l * n
            a1 = -2.0 * c * n;  a2 = (1.0 - a) * n
            reset()
        }

        // RBJ Audio EQ Cookbook high-shelf (Bristow-Johnson)
        // Used for exciter band extraction — boosts content above freq
        fun setHighShelf(freq: Double, sr: Double, Q: Double) {
            val A  = sqrt(2.0)   // +6 dB shelf gain for extraction
            val w0 = 2.0 * PI * freq / sr
            val c  = cos(w0)
            val s  = sin(w0)
            val a  = s / (2.0 * Q)
            val sA = sqrt(A)
            val n  = 1.0 / ((A + 1.0) - (A - 1.0) * c + 2.0 * sA * a)
            b0 =  A * ((A + 1.0) + (A - 1.0) * c + 2.0 * sA * a) * n
            b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * c) * n
            b2 =  A * ((A + 1.0) + (A - 1.0) * c - 2.0 * sA * a) * n
            a1 =  2.0 * ((A - 1.0) - (A + 1.0) * c) * n
            a2 =  ((A + 1.0) - (A - 1.0) * c - 2.0 * sA * a) * n
            reset()
        }

        fun reset() { w1 = 0.0; w2 = 0.0 }
    }
}

