/*
 * HighResAudioProcessor.kt
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
import kotlin.random.Random

@UnstableApi
class HighResAudioProcessor : BaseAudioProcessor() {

    private val TARGET_SAMPLE_RATE = 192000
    private val INPUT_GAIN = 0.65

    private val TRANSIENT_SENSITIVITY = 0.15
    private val TRANSIENT_BOOST = 1.40

    private val CRYSTAL_FREQ = 8000.0
    private val CRYSTAL_AMOUNT = 0.25

    private val SPATIAL_WIDTH = 1.12

    private val SUB_BASS_FREQ = 45.0
    private val SUB_BASS_GAIN = 1.35

    private val OUTPUT_GAIN = 1.45

    private var inputSampleRate = 48000
    private var channelCount = 2
    private var resampleStep = 0.0
    private var resamplePhase = 0.0

    private val histL = DoubleArray(4)
    private val histR = DoubleArray(4)

    private var envFollowerL = 0.0
    private var envFollowerR = 0.0

    private val hpL = BiquadFilter()
    private val hpR = BiquadFilter()
    private val bassL = BiquadFilter()
    private val bassR = BiquadFilter()

    private val random = Random(System.currentTimeMillis())

    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }

        inputSampleRate = input.sampleRate
        channelCount = input.channelCount
        resampleStep = inputSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()

        hpL.setHighPass(CRYSTAL_FREQ, TARGET_SAMPLE_RATE.toDouble(), 0.707)
        hpR.setHighPass(CRYSTAL_FREQ, TARGET_SAMPLE_RATE.toDouble(), 0.707)

        bassL.setLowShelf(SUB_BASS_FREQ, TARGET_SAMPLE_RATE.toDouble(), SUB_BASS_GAIN)
        bassR.setLowShelf(SUB_BASS_FREQ, TARGET_SAMPLE_RATE.toDouble(), SUB_BASS_GAIN)

        return AudioProcessor.AudioFormat(
            TARGET_SAMPLE_RATE,
            input.channelCount,
            C.ENCODING_PCM_FLOAT
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remainingBytes = inputBuffer.remaining()
        if (remainingBytes == 0) return

        val inputFrames = remainingBytes / (2 * channelCount)
        val estimatedOutputFrames = ceil((inputFrames + 1) / resampleStep).toInt() + 10
        val buffer = replaceOutputBuffer(estimatedOutputFrames * channelCount * 4)

        while (inputBuffer.hasRemaining()) {
            val (inL, inR) = readSample(inputBuffer)

            System.arraycopy(histL, 1, histL, 0, 3)
            histL[3] = inL
            System.arraycopy(histR, 1, histR, 0, 3)
            histR[3] = inR

            while (resamplePhase < 1.0) {
                if (channelCount == 2) {
                    val outL = cubicInterpolate(histL, resamplePhase)
                    val outR = cubicInterpolate(histR, resamplePhase)
                    processStereo(outL, outR, buffer)
                } else {
                    val outS = cubicInterpolate(histL, resamplePhase)
                    processMono(outS, buffer)
                }
                resamplePhase += resampleStep
            }
            resamplePhase -= 1.0
        }

        inputBuffer.position(inputBuffer.limit())
        buffer.flip()
    }

    private fun readSample(buffer: ByteBuffer): Pair<Double, Double> {
        if (buffer.remaining() < 2 * channelCount) return Pair(0.0, 0.0)
        val l = buffer.short / 32768.0
        val r = if (channelCount == 2) buffer.short / 32768.0 else l
        return Pair(l, r)
    }

    private fun cubicInterpolate(y: DoubleArray, mu: Double): Double {
        val y0 = y[0]; val y1 = y[1]; val y2 = y[2]; val y3 = y[3]
        val mu2 = mu * mu
        val a0 = -0.5 * y0 + 1.5 * y1 - 1.5 * y2 + 0.5 * y3
        val a1 = y0 - 2.5 * y1 + 2.0 * y2 - 0.5 * y3
        val a2 = -0.5 * y0 + 0.5 * y2
        val a3 = y1
        return a0 * mu * mu2 * mu + a1 * mu2 + a2 * mu + a3
    }

    private fun processStereo(inL: Double, inR: Double, output: ByteBuffer) {
        var sL = inL * INPUT_GAIN
        var sR = inR * INPUT_GAIN

        val envL = abs(sL)
        val envR = abs(sR)

        envFollowerL = envL * TRANSIENT_SENSITIVITY + envFollowerL * (1.0 - TRANSIENT_SENSITIVITY)
        envFollowerR = envR * TRANSIENT_SENSITIVITY + envFollowerR * (1.0 - TRANSIENT_SENSITIVITY)

        val punchL = (envL - envFollowerL).coerceAtLeast(0.0) * TRANSIENT_BOOST
        val punchR = (envR - envFollowerR).coerceAtLeast(0.0) * TRANSIENT_BOOST

        sL += sL * punchL
        sR += sR * punchR

        val highL = hpL.process(sL)
        val highR = hpR.process(sR)

        sL += highL * CRYSTAL_AMOUNT
        sR += highR * CRYSTAL_AMOUNT

        sL = bassL.process(sL)
        sR = bassR.process(sR)

        val mid = (sL + sR) * 0.5
        val side = (sL - sR) * 0.5 * SPATIAL_WIDTH
        sL = mid + side
        sR = mid - side

        sL *= OUTPUT_GAIN
        sR *= OUTPUT_GAIN

        val dither = (random.nextDouble() - random.nextDouble()) * 0.00001

        output.putFloat((sL + dither).coerceIn(-1.0, 1.0).toFloat())
        output.putFloat((sR + dither).coerceIn(-1.0, 1.0).toFloat())
    }

    private fun processMono(input: Double, output: ByteBuffer) {
        var s = input * INPUT_GAIN
        val env = abs(s)
        envFollowerL = env * TRANSIENT_SENSITIVITY + envFollowerL * (1.0 - TRANSIENT_SENSITIVITY)
        val punch = (env - envFollowerL).coerceAtLeast(0.0) * TRANSIENT_BOOST
        s += s * punch

        val high = hpL.process(s)
        s += high * CRYSTAL_AMOUNT
        s = bassL.process(s)
        s *= OUTPUT_GAIN

        val dither = (random.nextDouble() - random.nextDouble()) * 0.00001
        output.putFloat((s + dither).coerceIn(-1.0, 1.0).toFloat())
    }

    class BiquadFilter {
        private var a0 = 1.0; private var a1 = 0.0; private var a2 = 0.0
        private var b1 = 0.0; private var b2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0
        private var y1 = 0.0; private var y2 = 0.0

        fun process(x: Double): Double {
            val y = a0 * x + a1 * x1 + a2 * x2 - b1 * y1 - b2 * y2
            val cleanY = if (abs(y) < 1e-25) 0.0 else y
            x2 = x1; x1 = x; y2 = y1; y1 = cleanY
            return cleanY
        }

        fun setLowShelf(freq: Double, sr: Double, gain: Double) {
            val A = sqrt(gain)
            val w0 = 2.0 * PI * freq / sr
            val alpha = sin(w0) / 2.0 * sqrt(2.0)
            val cosW = cos(w0)
            val beta = 2.0 * sqrt(A) * alpha
            val b0 = A * ((A + 1) - (A - 1) * cosW + beta)
            val b1r = 2 * A * ((A - 1) - (A + 1) * cosW)
            val b2r = A * ((A + 1) - (A - 1) * cosW - beta)
            val a0r = (A + 1) + (A - 1) * cosW + beta
            val a1r = -2 * ((A - 1) + (A + 1) * cosW)
            val a2r = (A + 1) + (A - 1) * cosW - beta
            a0 = b0 / a0r; a1 = b1r / a0r; a2 = b2r / a0r; b1 = a1r / a0r; b2 = a2r / a0r
        }

        fun setHighPass(freq: Double, sr: Double, Q: Double) {
            val w0 = 2.0 * PI * freq / sr
            val alpha = sin(w0) / (2.0 * Q)
            val cosW = cos(w0)
            val b0 = (1.0 + cosW) / 2.0
            val b1r = -(1.0 + cosW)
            val b2r = (1.0 + cosW) / 2.0
            val a0r = 1.0 + alpha
            val a1r = -2.0 * cosW
            val a2r = 1.0 - alpha
            a0 = b0 / a0r; a1 = b1r / a0r; a2 = b2r / a0r; b1 = a1r / a0r; b2 = a2r / a0r
        }
    }

    override fun onFlush() {
        hpL.setHighPass(CRYSTAL_FREQ, TARGET_SAMPLE_RATE.toDouble(), 0.707)
        hpR.setHighPass(CRYSTAL_FREQ, TARGET_SAMPLE_RATE.toDouble(), 0.707)
        bassL.setLowShelf(SUB_BASS_FREQ, TARGET_SAMPLE_RATE.toDouble(), SUB_BASS_GAIN)
        bassR.setLowShelf(SUB_BASS_FREQ, TARGET_SAMPLE_RATE.toDouble(), SUB_BASS_GAIN)
        resamplePhase = 0.0
        envFollowerL = 0.0
        envFollowerR = 0.0
        histL.fill(0.0)
        histR.fill(0.0)
    }

    override fun onReset() {
        inputSampleRate = 48000
        channelCount = 2
        onFlush()
    }
}
