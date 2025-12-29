package dev.jigen.zimusic.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.tanh
import kotlin.math.max

@UnstableApi
class HighResAudioProcessor : AudioProcessor {

    companion object {
        var ENABLED = false
    }

    private var inputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    // ---------------- SOUND TUNING ----------------

    // Start with lower volume so boosts later don’t cause clipping
    private val inputGain = 0.50

    // Makes vocals stand out a bit more in the mix ( boosting mid channel and vocals usually live in mid )
    private val vocalPresence = 0.25

    // Adds punch to bass without spreading it across stereo
    private val bassMix = 0.30
    private val alphaBass = 0.05 // Roughly targets low frequencies (~150Hz)

    // Boosts stereo highs (cymbals, reverb, ambience)
    private val airAmount = 0.50
    private val alphaAir = 0.35 // Roughly targets upper highs (~6kHz)

    // Automatically controls volume so things don’t distort
    private var currentNormalizationGain = 1.0
    private val targetLoudness = 0.95
    private val attackSpeed = 0.005   // Reacts quickly when audio gets too loud
    private val releaseSpeed = 0.0005 // Slowly raises volume again

    // ---------------- INTERNAL STATE ----------------

    private var lowPassL = 0.0
    private var highPassSide = 0.0

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        // If disabled, we don't change the format
        if (!ENABLED) return AudioFormat.NOT_SET

        return if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            AudioFormat(
                inputAudioFormat.sampleRate,
                inputAudioFormat.channelCount,
                C.ENCODING_PCM_16BIT
            )
        } else {
            inputAudioFormat
        }.also {
            this.inputAudioFormat = inputAudioFormat
            this.outputAudioFormat = it
        }
    }

    override fun isActive(): Boolean {
        // If disabled, ExoPlayer will bypass this processor entirely
        return ENABLED && inputAudioFormat.encoding != C.ENCODING_INVALID
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        inputBuffer.order(ByteOrder.nativeOrder())

        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val frameCount =
            (limit - position) / (2 * inputAudioFormat.channelCount)
        val outputSize = frameCount * 2 * inputAudioFormat.channelCount

        if (outputBuffer.capacity() < outputSize) {
            outputBuffer = ByteBuffer
                .allocateDirect(outputSize)
                .order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }

        while (inputBuffer.remaining() >= 2 * inputAudioFormat.channelCount) {

            // Read left & right samples and convert to -1.0 to +1.0 range
            val rawL = (inputBuffer.short / 32768.0) * inputGain
            val rawR = (inputBuffer.short / 32768.0) * inputGain

            // Convert stereo into mid/side
            // Mid  = center (vocals + bass)
            // Side = stereo width (ambience, reverb)
            var mid = (rawL + rawR) * 0.5
            var side = (rawL - rawR) * 0.5

            // ---------- MID PROCESSING ----------

            // Pull out low frequencies from mid for bass boost
            lowPassL += alphaBass * (mid - lowPassL)
            val bass = lowPassL

            // Add bass weight + gentle vocal warmth
            mid += (bass * bassMix) + (tanh(mid) * vocalPresence)

            // ---------- SIDE PROCESSING ----------

            // Extract high-frequency content from side channel
            highPassSide += alphaAir * (side - highPassSide)
            val sideHighs = side - highPassSide

            // Boost stereo highs for clarity and air
            side += sideHighs * airAmount

            // Slight stereo widening
            side *= 1.35

            // Convert back to left/right
            var outL = mid + side
            var outR = mid - side

            // ---------- AUTO GAIN CONTROL ----------

            // Find how loud the signal currently is
            val peak = max(abs(outL), abs(outR))

            if (peak > targetLoudness) {
                // Too loud → pull volume down fast
                currentNormalizationGain -=
                    currentNormalizationGain * attackSpeed
            } else {
                // Safe → slowly bring volume back up
                if (currentNormalizationGain < 1.4) {
                    currentNormalizationGain += releaseSpeed
                }
            }

            // Apply the smart volume
            outL *= currentNormalizationGain
            outR *= currentNormalizationGain

            // Soft clip just in case of sudden spikes
            outL = tanh(outL)
            outR = tanh(outR)

            // Convert back to 16-bit PCM
            val finalL =
                (outL * 32767.0)
                    .coerceIn(-32767.0, 32767.0)
                    .toInt()
                    .toShort()

            val finalR =
                (outR * 32767.0)
                    .coerceIn(-32767.0, 32767.0)
                    .toInt()
                    .toShort()

            outputBuffer.putShort(finalL)
            outputBuffer.putShort(finalR)
        }

        inputBuffer.position(limit)
        outputBuffer.flip()
        buffer = outputBuffer
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = buffer
        buffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean =
        inputEnded && buffer === AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        buffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false

        // Reset filters and volume between tracks
        lowPassL = 0.0
        highPassSide = 0.0
        currentNormalizationGain = 1.0
    }

    override fun reset() {
        flush()
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
    }
}
