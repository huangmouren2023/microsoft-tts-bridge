package com.ld.microsoftttsbridge

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import java.util.concurrent.CancellationException

/** 保证每次 SynthesisCallback 都只收到完整 PCM frame。 */
class PcmSynthesisWriter(
    private val callback: SynthesisCallback,
    private val cancellation: SynthesisCancellation,
) {
    private var sampleRateHz = 0
    private var channelCount = 0
    private var frameBytes = 0
    private var pending = ByteArray(0)
    private var pendingCount = 0

    var hasStarted: Boolean = false
        private set

    var bytesWritten: Long = 0
        private set

    fun write(bytes: ByteArray, count: Int, sampleRateHz: Int, channelCount: Int) {
        require(count in 0..bytes.size) { "invalid PCM byte count: $count" }
        if (count == 0) return
        configure(sampleRateHz, channelCount)

        var offset = 0
        if (pendingCount > 0) {
            val copied = minOf(frameBytes - pendingCount, count)
            bytes.copyInto(pending, pendingCount, 0, copied)
            pendingCount += copied
            offset += copied
            if (pendingCount == frameBytes) {
                sendFrames(pending, 0, frameBytes)
                pendingCount = 0
            }
        }

        val completeBytes = ((count - offset) / frameBytes) * frameBytes
        if (completeBytes > 0) {
            sendFrames(bytes, offset, completeBytes)
            offset += completeBytes
        }

        if (offset < count) {
            val remaining = count - offset
            bytes.copyInto(pending, 0, offset, count)
            pendingCount = remaining
        }
    }

    fun finishFrames() {
        check(pendingCount == 0) { "PCM stream ended with $pendingCount incomplete frame bytes" }
        check(hasStarted && bytesWritten > 0) { "PCM stream was empty" }
    }

    fun resetBeforeStart() {
        check(!hasStarted) { "cannot reset PCM after callback start" }
        sampleRateHz = 0
        channelCount = 0
        frameBytes = 0
        pending = ByteArray(0)
        pendingCount = 0
    }

    private fun configure(sampleRateHz: Int, channelCount: Int) {
        require(sampleRateHz > 0) { "invalid PCM sample rate" }
        require(channelCount in 1..2) { "invalid PCM channel count" }
        if (frameBytes == 0) {
            this.sampleRateHz = sampleRateHz
            this.channelCount = channelCount
            frameBytes = channelCount * PCM_16BIT_BYTES
            pending = ByteArray(frameBytes)
        } else {
            check(this.sampleRateHz == sampleRateHz && this.channelCount == channelCount) {
                "PCM format changed during synthesis"
            }
        }
    }

    private fun sendFrames(bytes: ByteArray, initialOffset: Int, totalCount: Int) {
        if (!hasStarted) {
            val startResult = callback.start(
                sampleRateHz,
                AudioFormat.ENCODING_PCM_16BIT,
                channelCount,
            )
            if (startResult != TextToSpeech.SUCCESS) {
                cancellation.cancel()
                throw CancellationException("system TTS callback start stopped: $startResult")
            }
            hasStarted = true
        }

        val maxChunk = callback.maxBufferSize - (callback.maxBufferSize % frameBytes)
        check(maxChunk >= frameBytes) { "system TTS callback buffer is smaller than one PCM frame" }
        var offset = initialOffset
        val end = initialOffset + totalCount
        while (offset < end) {
            cancellation.throwIfCancelled()
            val chunkSize = minOf(maxChunk, end - offset)
            val result = callback.audioAvailable(bytes, offset, chunkSize)
            if (result != TextToSpeech.SUCCESS) {
                cancellation.cancel()
                throw CancellationException("system TTS callback stopped: $result")
            }
            bytesWritten += chunkSize
            offset += chunkSize
        }
    }

    companion object {
        private const val PCM_16BIT_BYTES = 2
    }
}
