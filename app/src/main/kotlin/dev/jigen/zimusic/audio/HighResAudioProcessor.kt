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

@UnstableApi
class HighResAudioProcessor : BaseAudioProcessor() {

    private val INPUT_GAIN = 0.50
    private val BASS_FREQ = 85.0
    private val BASS_GAIN = 1.65
    private val MID_FREQ = 500.0
    private val MID_Q = 0.8
    private val MID_GAIN = 0.85
    private val TREBLE_FREQ = 9000.0
    private val TREBLE_GAIN = 1.45
    private val SPATIAL_WIDTH = 1.20
    private val OUTPUT_GAIN = 1.70

    private var sampleRate = 48000.0
    private var channelCount = 2

    private val bassL = BiquadFilter()
    private val midL = BiquadFilter()
    private val trebL = BiquadFilter()

    private val bassR = BiquadFilter()
    private val midR = BiquadFilter()
    private val trebR = BiquadFilter()

    private var delayBuffer = DoubleArray(0)
    private var writeIdx = 0
    private var readIdx = 0

    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }

        sampleRate = input.sampleRate.toDouble()
        channelCount = input.channelCount

        bassL.setLowShelf(BASS_FREQ, sampleRate, BASS_GAIN)
        bassR.setLowShelf(BASS_FREQ, sampleRate, BASS_GAIN)

        midL.setPeaking(MID_FREQ, sampleRate, MID_GAIN, MID_Q)
        midR.setPeaking(MID_FREQ, sampleRate, MID_GAIN, MID_Q)

        trebL.setHighShelf(TREBLE_FREQ, sampleRate, TREBLE_GAIN)
        trebR.setHighShelf(TREBLE_FREQ, sampleRate, TREBLE_GAIN)

        val bufferSize = (sampleRate * 0.02).toInt()
        delayBuffer = DoubleArray(bufferSize)

        val delaySamples = (sampleRate * 0.004).toInt()
        readIdx = (bufferSize - delaySamples) % bufferSize

        return AudioProcessor.AudioFormat(
            input.sampleRate,
            input.channelCount,
            C.ENCODING_PCM_FLOAT
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val buffer = replaceOutputBuffer(remaining * 2)

        while (inputBuffer.hasRemaining()) {
            if (channelCount == 2) {
                val (l, r) = readSample(inputBuffer)
                processStereo(l, r, buffer)
            } else {
                val (s, _) = readSample(inputBuffer)
                processMono(s, buffer)
            }
        }

        inputBuffer.position(inputBuffer.limit())
        buffer.flip()
    }

    private fun readSample(buffer: ByteBuffer): Pair<Double, Double> {
        if (buffer.remaining() < 4) return Pair(0.0, 0.0)
        return Pair(buffer.short / 32768.0, buffer.short / 32768.0)
    }

    private fun processStereo(inL: Double, inR: Double, output: ByteBuffer) {
        var sL = inL * INPUT_GAIN
        var sR = inR * INPUT_GAIN

        sL = trebL.process(midL.process(bassL.process(sL)))
        sR = trebR.process(midR.process(bassR.process(sR)))

        val mid = (sL + sR) * 0.5
        var side = (sL - sR) * 0.5

        if (delayBuffer.isNotEmpty()) {
            delayBuffer[writeIdx] = side
            writeIdx = (writeIdx + 1) % delayBuffer.size

            val delayedSide = delayBuffer[readIdx]
            readIdx = (readIdx + 1) % delayBuffer.size

            side = (side + delayedSide * 0.5) * SPATIAL_WIDTH
        }

        sL = mid + side
        sR = mid - side

        sL *= OUTPUT_GAIN
        sR *= OUTPUT_GAIN

        output.putFloat(tanh(sL).toFloat())
        output.putFloat(tanh(sR).toFloat())
    }

    private fun processMono(input: Double, output: ByteBuffer) {
        var s = input * INPUT_GAIN
        s = trebL.process(midL.process(bassL.process(s)))
        s *= OUTPUT_GAIN
        output.putFloat(tanh(s).toFloat())
    }

    class BiquadFilter {
        private var a0 = 1.0; private var a1 = 0.0; private var a2 = 0.0
        private var b1 = 0.0; private var b2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0
        private var y1 = 0.0; private var y2 = 0.0

        fun process(x: Double): Double {
            val y = a0 * x + a1 * x1 + a2 * x2 - b1 * y1 - b2 * y2
            val cleanY = if (abs(y) < 1e-25) 0.0 else y

            x2 = x1
            x1 = x
            y2 = y1
            y1 = cleanY
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

            a0 = b0 / a0r
            a1 = b1r / a0r
            a2 = b2r / a0r
            b1 = a1r / a0r
            b2 = a2r / a0r
        }

        fun setHighShelf(freq: Double, sr: Double, gain: Double) {
            val A = sqrt(gain)
            val w0 = 2.0 * PI * freq / sr
            val alpha = sin(w0) / 2.0 * sqrt(2.0)
            val cosW = cos(w0)
            val beta = 2.0 * sqrt(A) * alpha

            val b0 = A * ((A + 1) + (A - 1) * cosW + beta)
            val b1r = -2 * A * ((A - 1) + (A + 1) * cosW)
            val b2r = A * ((A + 1) + (A - 1) * cosW - beta)
            val a0r = (A + 1) - (A - 1) * cosW + beta
            val a1r = 2 * ((A - 1) - (A + 1) * cosW)
            val a2r = (A + 1) - (A - 1) * cosW - beta

            a0 = b0 / a0r
            a1 = b1r / a0r
            a2 = b2r / a0r
            b1 = a1r / a0r
            b2 = a2r / a0r
        }

        fun setPeaking(freq: Double, sr: Double, gain: Double, Q: Double) {
            val A = sqrt(gain)
            val w0 = 2.0 * PI * freq / sr
            val alpha = sin(w0) / (2.0 * Q)
            val cosW = cos(w0)

            val b0 = 1.0 + alpha * A
            val b1r = -2.0 * cosW
            val b2r = 1.0 - alpha * A
            val a0r = 1.0 + alpha / A
            val a1r = -2.0 * cosW
            val a2r = 1.0 - alpha / A

            a0 = b0 / a0r
            a1 = b1r / a0r
            a2 = b2r / a0r
            b1 = a1r / a0r
            b2 = a2r / a0r
        }
    }

    override fun onFlush() {
        bassL.setLowShelf(BASS_FREQ, 48000.0, BASS_GAIN)
        bassR.setLowShelf(BASS_FREQ, 48000.0, BASS_GAIN)
        writeIdx = 0
        readIdx = 0
        if (delayBuffer.isNotEmpty()) delayBuffer.fill(0.0)
    }

    override fun onReset() {
        sampleRate = 48000.0
        channelCount = 2
        onFlush()
    }
}
