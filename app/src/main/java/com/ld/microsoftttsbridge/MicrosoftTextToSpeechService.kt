package com.ld.microsoftttsbridge

import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

class MicrosoftTextToSpeechService : TextToSpeechService() {
    private val activeSynthesis = AtomicReference<SynthesisCancellation?>()
    private lateinit var core: TranslatorTtsBridge
    private lateinit var fallback: SystemTtsFallback
    @Volatile private var fallbackUntilElapsedMs = 0L

    override fun onCreate() {
        super.onCreate()
        core = (application as MicrosoftTtsApplication).ttsCore
        fallback = SystemTtsFallback(applicationContext) { message -> Log.i(LOG_TAG, message) }
        core.warmUp()
        Log.i(LOG_TAG, "Android TextToSpeechService created")
    }

    override fun onGetLanguage(): Array<String> = arrayOf(LANGUAGE_ISO3, COUNTRY_ISO3, "")

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (!isChineseLanguage(lang)) return TextToSpeech.LANG_NOT_SUPPORTED
        if (country.isNullOrBlank()) return TextToSpeech.LANG_AVAILABLE
        return if (isChinaCountry(country)) {
            if (variant.isNullOrBlank()) TextToSpeech.LANG_COUNTRY_AVAILABLE
            else TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        } else {
            TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onGetFeaturesForLanguage(
        lang: String?,
        country: String?,
        variant: String?,
    ): Set<String> = if (isChineseLanguage(lang)) {
        setOf(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)
    } else {
        emptySet()
    }

    override fun onGetVoices(): List<Voice> = VOICE_NAMES.map { name ->
        Voice(
            name,
            Locale.SIMPLIFIED_CHINESE,
            Voice.QUALITY_HIGH,
            Voice.LATENCY_HIGH,
            true,
            setOf(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS),
        )
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String =
        DEFAULT_VOICE

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (voiceName in VOICE_NAMES) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)

    override fun onStop() {
        activeSynthesis.get()?.cancel()
        fallback.stop()
        Log.i(LOG_TAG, "system TTS stop requested")
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val cancellation = SynthesisCancellation()
        activeSynthesis.getAndSet(cancellation)?.cancel()
        var errorCode: Int? = null
        try {
            val text = request.charSequenceText?.toString().orEmpty()
            if (text.isBlank() || !isChineseLanguage(request.language)) {
                errorCode = TextToSpeech.ERROR_INVALID_REQUEST
                return
            }
            val voice = request.voiceName.takeIf { it in VOICE_NAMES } ?: DEFAULT_VOICE
            val writer = PcmSynthesisWriter(callback, cancellation)
            val forceFallback = request.params.getBoolean(FORCE_FALLBACK_PARAM, false)
            val circuitOpen = SystemClock.elapsedRealtime() < fallbackUntilElapsedMs

            if (forceFallback || circuitOpen) {
                Log.i(
                    LOG_TAG,
                    "temporary system fallback requested forced=$forceFallback circuitOpen=$circuitOpen",
                )
                synthesizeWithSystemFallback(request, text, writer, cancellation, forceFallback)
            } else {
                try {
                    core.synthesizeTo(
                        text = text,
                        voice = voice,
                        localeTag = LOCALE_TAG,
                        speechRate = request.speechRate,
                        pitch = request.pitch,
                        outputFormat = TranslatorTtsBridge.OutputFormat.PCM_24KHZ_16BIT_MONO,
                        cancellation = cancellation,
                    ) { bytes, count ->
                        writer.write(bytes, count, SAMPLE_RATE_HZ, 1)
                    }
                    writer.finishFrames()
                    fallbackUntilElapsedMs = 0L
                    Log.i(
                        LOG_TAG,
                        "system TTS completed chars=${text.length} voice=$voice " +
                            "rate=${request.speechRate} pitch=${request.pitch} pcmBytes=${writer.bytesWritten}",
                    )
                } catch (error: Exception) {
                    if (error is CancellationException || cancellation.isCancelled) throw error
                    if (writer.hasStarted) throw error
                    writer.resetBeforeStart()
                    fallbackUntilElapsedMs = SystemClock.elapsedRealtime() + FALLBACK_COOLDOWN_MS
                    Log.w(
                        LOG_TAG,
                        "Microsoft TTS failed before audio; using temporary system fallback: ${error.message}",
                    )
                    synthesizeWithSystemFallback(request, text, writer, cancellation, false)
                }
            }
        } catch (_: CancellationException) {
            Log.i(LOG_TAG, "system TTS synthesis cancelled")
        } catch (error: SocketTimeoutException) {
            if (cancellation.isCancelled) {
                Log.i(LOG_TAG, "system TTS synthesis cancelled during timeout")
            } else {
                errorCode = TextToSpeech.ERROR_NETWORK_TIMEOUT
                Log.e(LOG_TAG, "system TTS timed out", error)
            }
        } catch (error: IOException) {
            if (cancellation.isCancelled) {
                Log.i(LOG_TAG, "system TTS synthesis cancelled during network I/O")
            } else {
                errorCode = TextToSpeech.ERROR_NETWORK
                Log.e(LOG_TAG, "system TTS network failure", error)
            }
        } catch (error: Exception) {
            errorCode = TextToSpeech.ERROR_SYNTHESIS
            Log.e(LOG_TAG, "system TTS synthesis failure", error)
        } finally {
            activeSynthesis.compareAndSet(cancellation, null)
            if (!callback.hasFinished()) {
                if (errorCode != null) callback.error(errorCode) else callback.done()
            }
        }
    }

    private fun synthesizeWithSystemFallback(
        request: SynthesisRequest,
        text: String,
        writer: PcmSynthesisWriter,
        cancellation: SynthesisCancellation,
        forced: Boolean,
    ) {
        val audio = fallback.synthesize(
            text = text,
            locale = Locale.SIMPLIFIED_CHINESE,
            speechRate = request.speechRate,
            pitch = request.pitch,
            cancellation = cancellation,
        )
        writer.write(audio.pcmBytes, audio.pcmBytes.size, audio.sampleRateHz, audio.channelCount)
        writer.finishFrames()
        Log.i(
            LOG_TAG,
            "system fallback completed engine=${audio.enginePackage} chars=${text.length} " +
                "sampleRate=${audio.sampleRateHz} channels=${audio.channelCount} " +
                "pcmBytes=${writer.bytesWritten} forced=$forced",
        )
    }

    private fun isChineseLanguage(value: String?): Boolean =
        value.equals("zh", ignoreCase = true) || value.equals(LANGUAGE_ISO3, ignoreCase = true)

    private fun isChinaCountry(value: String): Boolean =
        value.equals("CN", ignoreCase = true) || value.equals(COUNTRY_ISO3, ignoreCase = true)

    companion object {
        private const val LOG_TAG = "MicrosoftTtsBridge"
        private const val SAMPLE_RATE_HZ = 24_000
        private const val FALLBACK_COOLDOWN_MS = 60_000L
        const val FORCE_FALLBACK_PARAM = "com.ld.microsoftttsbridge.force_system_fallback"
        private const val LANGUAGE_ISO3 = "zho"
        private const val COUNTRY_ISO3 = "CHN"
        private const val LOCALE_TAG = "zh-CN"
        private const val DEFAULT_VOICE = "zh-CN-XiaochenNeural"
        private val VOICE_NAMES = linkedSetOf(
            DEFAULT_VOICE,
            "zh-CN-XiaoyiNeural",
            "zh-CN-YunxiNeural",
        )
    }
}
