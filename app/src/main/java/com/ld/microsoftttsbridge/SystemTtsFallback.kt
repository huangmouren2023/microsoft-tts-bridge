package com.ld.microsoftttsbridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class FallbackPcmAudio(
    val enginePackage: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    val pcmBytes: ByteArray,
)

/**
 * 通过明确指定另一个系统引擎完成单次兜底；不会修改系统默认 TTS。
 * 临时 WAV 只用于保留外层 SynthesisCallback 语义，并在读取后立即删除。
 */
class SystemTtsFallback(
    context: Context,
    private val log: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeTts = AtomicReference<TextToSpeech?>()

    fun synthesize(
        text: String,
        locale: Locale,
        speechRate: Int,
        pitch: Int,
        cancellation: SynthesisCancellation,
    ): FallbackPcmAudio {
        val enginePackage = resolveSystemFallbackEngine()
        val outputFile = File.createTempFile("tts-system-fallback-", ".wav", appContext.cacheDir)
        var tts: TextToSpeech? = null
        try {
            val initLatch = CountDownLatch(1)
            val initResult = AtomicInteger(RESULT_PENDING)
            val holder = AtomicReference<TextToSpeech?>()
            mainHandler.post {
                val created = TextToSpeech(
                    appContext,
                    { status ->
                        initResult.set(status)
                        if (holder.get() != null) initLatch.countDown()
                        else mainHandler.post { initLatch.countDown() }
                    },
                    enginePackage,
                )
                holder.set(created)
                activeTts.set(created)
                if (initResult.get() != RESULT_PENDING) initLatch.countDown()
            }
            await(initLatch, INIT_TIMEOUT_MS, cancellation, "fallback engine initialization")
            if (initResult.get() != TextToSpeech.SUCCESS) {
                throw IOException("fallback engine initialization failed: ${initResult.get()}")
            }
            tts = holder.get() ?: throw IOException("fallback engine instance missing")

            val utteranceId = "system-fallback-${UUID.randomUUID()}"
            val doneLatch = CountDownLatch(1)
            val synthesisResult = AtomicInteger(RESULT_PENDING)
            mainHandler.post {
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit

                    override fun onDone(id: String?) {
                        if (id == utteranceId) {
                            synthesisResult.set(TextToSpeech.SUCCESS)
                            doneLatch.countDown()
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        if (id == utteranceId) {
                            synthesisResult.set(TextToSpeech.ERROR_SYNTHESIS)
                            doneLatch.countDown()
                        }
                    }

                    override fun onError(id: String?, errorCode: Int) {
                        if (id == utteranceId) {
                            synthesisResult.set(errorCode)
                            doneLatch.countDown()
                        }
                    }

                    override fun onStop(id: String?, interrupted: Boolean) {
                        if (id == utteranceId) {
                            synthesisResult.set(TextToSpeech.STOPPED)
                            doneLatch.countDown()
                        }
                    }
                })
                val languageResult = tts.setLanguage(locale)
                if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                    languageResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    synthesisResult.set(languageResult)
                    doneLatch.countDown()
                    return@post
                }
                tts.setSpeechRate(speechRate.coerceIn(20, 200) / 100f)
                tts.setPitch(pitch.coerceIn(20, 200) / 100f)
                val queued = tts.synthesizeToFile(text, Bundle(), outputFile, utteranceId)
                if (queued != TextToSpeech.SUCCESS) {
                    synthesisResult.set(queued)
                    doneLatch.countDown()
                }
            }
            await(doneLatch, SYNTHESIS_TIMEOUT_MS, cancellation, "fallback synthesis")
            if (synthesisResult.get() != TextToSpeech.SUCCESS) {
                throw IOException("fallback synthesis failed: ${synthesisResult.get()}")
            }
            val parsed = parseWav(outputFile.readBytes())
            log(
                "temporary system fallback rendered engine=$enginePackage " +
                    "sampleRate=${parsed.sampleRateHz} channels=${parsed.channelCount} " +
                    "pcmBytes=${parsed.pcmBytes.size}",
            )
            return parsed.copy(enginePackage = enginePackage)
        } finally {
            val current = tts ?: activeTts.get()
            activeTts.compareAndSet(current, null)
            current?.let { engine ->
                mainHandler.post {
                    engine.stop()
                    engine.shutdown()
                }
            }
            if (!outputFile.delete()) outputFile.deleteOnExit()
        }
    }

    fun stop() {
        activeTts.get()?.let { tts -> mainHandler.post { tts.stop() } }
    }

    private fun resolveSystemFallbackEngine(): String {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val services = appContext.packageManager.queryIntentServices(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        return services
            .asSequence()
            .filter { it.serviceInfo.packageName != appContext.packageName }
            .sortedByDescending { it.priority }
            .map { it.serviceInfo.packageName }
            .firstOrNull()
            ?: throw IOException("no other enabled system TTS engine found")
    }

    private fun await(
        latch: CountDownLatch,
        timeoutMs: Long,
        cancellation: SynthesisCancellation,
        operation: String,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            cancellation.throwIfCancelled()
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0L) throw IOException("$operation timed out")
            val waitMs = minOf(
                WAIT_SLICE_MS,
                TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L),
            )
            if (latch.await(waitMs, TimeUnit.MILLISECONDS)) return
        }
    }

    private fun parseWav(bytes: ByteArray): FallbackPcmAudio {
        if (bytes.size < 44 || fourCc(bytes, 0) != "RIFF" || fourCc(bytes, 8) != "WAVE") {
            throw IOException("fallback engine returned an invalid WAV file")
        }
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var audioFormat = 0
        var dataOffset = -1
        var dataSize = 0
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkId = fourCc(bytes, offset)
            val rawSize = littleEndianInt(bytes, offset + 4).toLong() and 0xffff_ffffL
            val chunkStart = offset + 8
            val available = bytes.size - chunkStart
            val chunkSize = minOf(rawSize, available.toLong()).toInt()
            when (chunkId) {
                "fmt " -> if (chunkSize >= 16) {
                    audioFormat = littleEndianShort(bytes, chunkStart)
                    channels = littleEndianShort(bytes, chunkStart + 2)
                    sampleRate = littleEndianInt(bytes, chunkStart + 4)
                    bitsPerSample = littleEndianShort(bytes, chunkStart + 14)
                }
                "data" -> {
                    dataOffset = chunkStart
                    dataSize = chunkSize
                    break
                }
            }
            val paddedSize = rawSize + (rawSize and 1L)
            if (paddedSize > Int.MAX_VALUE || chunkStart + paddedSize > bytes.size) break
            offset = chunkStart + paddedSize.toInt()
        }
        if (audioFormat != PCM_WAVE_FORMAT || bitsPerSample != 16 ||
            sampleRate <= 0 || channels !in 1..2 || dataOffset < 0 || dataSize <= 0
        ) {
            throw IOException(
                "unsupported fallback WAV format=$audioFormat bits=$bitsPerSample " +
                    "rate=$sampleRate channels=$channels dataSize=$dataSize",
            )
        }
        val frameBytes = channels * 2
        dataSize -= dataSize % frameBytes
        return FallbackPcmAudio(
            enginePackage = "",
            sampleRateHz = sampleRate,
            channelCount = channels,
            pcmBytes = bytes.copyOfRange(dataOffset, dataOffset + dataSize),
        )
    }

    private fun fourCc(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, StandardCharsets.US_ASCII)

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    companion object {
        private const val RESULT_PENDING = Int.MIN_VALUE
        private const val PCM_WAVE_FORMAT = 1
        private const val INIT_TIMEOUT_MS = 8_000L
        private const val SYNTHESIS_TIMEOUT_MS = 20_000L
        private const val WAIT_SLICE_MS = 100L
    }
}
