package com.ld.microsoftttsbridge

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * 微软 Translator 语音合成桥接实现。
 * 参考自 FMB (TranslatorTtsEngine / MicrosoftTranslatorProtocol) 的已验证黄金实现。
 */
class TranslatorTtsBridge(
    private val log: (String) -> Unit = {},
) {
    // 针对本地桥接优化超时，单次请求严格在 Operit 10s 硬限制内完成或快速返回错误
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .build()

    @Volatile private var cachedEndpoint: Endpoint? = null
    private val endpointLock = Any()
    private val backgroundExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "tts-token-refresher").apply { isDaemon = true }
    }

    init {
        // 定期检查并刷新 Token，确保内存中始终持有有效 Endpoint
        backgroundExecutor.scheduleWithFixedDelay(
            { runCatching { ensureEndpointFresh() } },
            5,
            5,
            TimeUnit.MINUTES
        )
    }

    /**
     * 异步预热 Endpoint，供服务启动时调用，避免首个 TTS 请求遭遇冷启动双重 HTTPS 握手
     */
    fun warmUp() {
        thread(name = "tts-warmup", isDaemon = true) {
            runCatching {
                val ep = endpoint()
                log("warmUp success: region=${ep.region} expiresAt=${ep.expiresAt}")
            }.onFailure {
                log("warmUp failed: ${it.message}")
            }
        }
    }

    fun shutdown() {
        backgroundExecutor.shutdownNow()
        runCatching { client.dispatcher.cancelAll() }
        runCatching { client.connectionPool.evictAll() }
    }

    /**
     * 合成语音音频
     */
    fun synthesize(
        text: String,
        voice: String,
        localeTag: String = DEFAULT_LOCALE_TAG,
        speechRate: Int = 100,
        pitch: Int = 100,
        outputFormat: OutputFormat = OutputFormat.MP3,
        cancellation: SynthesisCancellation = SynthesisCancellation(),
    ): ByteArray {
        val output = ByteArrayOutputStream()
        synthesizeTo(
            text = text,
            voice = voice,
            localeTag = localeTag,
            speechRate = speechRate,
            pitch = pitch,
            outputFormat = outputFormat,
            cancellation = cancellation,
        ) { bytes, count -> output.write(bytes, 0, count) }
        return output.toByteArray()
    }

    fun synthesizeTo(
        text: String,
        voice: String,
        localeTag: String = DEFAULT_LOCALE_TAG,
        speechRate: Int = 100,
        pitch: Int = 100,
        outputFormat: OutputFormat = OutputFormat.MP3,
        cancellation: SynthesisCancellation = SynthesisCancellation(),
        onAudio: (ByteArray, Int) -> Unit,
    ) {
        val parts = splitText(text)
        if (parts.isEmpty()) return

        parts.forEach { part ->
            cancellation.throwIfCancelled()
            var endpoint = endpoint()
            var result = speechPart(
                endpoint = endpoint,
                text = part,
                voice = voice,
                localeTag = localeTag,
                speechRate = speechRate,
                pitch = pitch,
                outputFormat = outputFormat,
                cancellation = cancellation,
                onAudio = onAudio,
            )
            // 遇到鉴权失效时使缓存失效并自动重试一次 (400, 401, 403)
            if (result.status in AUTH_RETRY_CODES) {
                log("TTS auth retry for code ${result.status}")
                cachedEndpoint = null
                endpoint = endpoint(forceRefresh = true)
                result = speechPart(
                    endpoint = endpoint,
                    text = part,
                    voice = voice,
                    localeTag = localeTag,
                    speechRate = speechRate,
                    pitch = pitch,
                    outputFormat = outputFormat,
                    cancellation = cancellation,
                    onAudio = onAudio,
                )
            }
            if (result.status !in 200..299 || result.audioBytes == 0L) {
                error("Translator TTS HTTP ${result.status}: ${result.errorBody.take(240)}")
            }
        }
    }

    private fun speechPart(
        endpoint: Endpoint,
        text: String,
        voice: String,
        localeTag: String,
        speechRate: Int,
        pitch: Int,
        outputFormat: OutputFormat,
        cancellation: SynthesisCancellation,
        onAudio: (ByteArray, Int) -> Unit,
    ): SpeechPartResult {
        val request = Request.Builder()
            .url("https://${endpoint.region}.tts.speech.microsoft.com/cognitiveservices/v1")
            .header("Authorization", endpoint.authorization)
            .header("X-Microsoft-OutputFormat", outputFormat.microsoftHeader)
            .header("User-Agent", "okhttp/4.5.0")
            .post(
                ssml(text, voice, localeTag, speechRate, pitch)
                    .toRequestBody("application/ssml+xml; charset=utf-8".toMediaType())
            )
            .build()
        val call = client.newCall(request)
        cancellation.track(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    return SpeechPartResult(
                        status = response.code,
                        errorBody = response.body?.string().orEmpty(),
                        audioBytes = 0,
                    )
                }
                val input = response.body?.byteStream()
                    ?: return SpeechPartResult(response.code, "empty response body", 0)
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    cancellation.throwIfCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    onAudio(buffer, count)
                    total += count
                }
                return SpeechPartResult(response.code, "", total)
            }
        } finally {
            cancellation.untrack(call)
        }
    }

    /**
     * 获取或刷新 Endpoint。
     * 双重校验 + 独立锁，避免持有全局锁阻塞网络请求。
     */
    private fun endpoint(forceRefresh: Boolean = false): Endpoint {
        val nowSeconds = System.currentTimeMillis() / 1_000L
        if (!forceRefresh) {
            cachedEndpoint?.takeIf { it.expiresAt > nowSeconds + TOKEN_REFRESH_MARGIN_SECONDS }?.let { return it }
        }

        synchronized(endpointLock) {
            val refreshedNowSeconds = System.currentTimeMillis() / 1_000L
            if (!forceRefresh) {
                cachedEndpoint?.takeIf { it.expiresAt > refreshedNowSeconds + TOKEN_REFRESH_MARGIN_SECONDS }?.let {
                    return it
                }
            }
            val fetched = fetchEndpoint(refreshedNowSeconds)
            cachedEndpoint = fetched
            return fetched
        }
    }

    private fun ensureEndpointFresh() {
        val nowSeconds = System.currentTimeMillis() / 1_000L
        val current = cachedEndpoint
        if (current == null || current.expiresAt <= nowSeconds + TOKEN_REFRESH_MARGIN_SECONDS) {
            endpoint(forceRefresh = true)
        }
    }

    private fun fetchEndpoint(nowSeconds: Long): Endpoint {
        val nowMillis = System.currentTimeMillis()
        val signatureUuid = UUID.randomUUID().toString().replace("-", "")
        val request = Request.Builder()
            .url("https://dev.microsofttranslator.com/apps/endpoint?api-version=1.0")
            .header("Accept-Language", "zh-Hans")
            .header("X-ClientVersion", "4.0.530a 5fe1dc6c")
            .header("X-UserId", randomUserId())
            .header("X-HomeGeographicRegion", "zh-Hans-CN")
            .header("X-ClientTraceId", UUID.randomUUID().toString())
            .header("X-MT-Signature", signature(nowMillis, signatureUuid))
            .header("User-Agent", "okhttp/4.5.0")
            .header("Content-Type", "application/json; charset=utf-8")
            .post(ByteArray(0).toRequestBody(null))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Translator endpoint HTTP ${response.code}: ${raw.take(240)}")
            }
            val json = JSONObject(raw)
            val authorization = json.optString("t").trim()
            val region = json.optString("r").trim().lowercase(Locale.US)
            if (authorization.isBlank() || !region.matches(Regex("^[a-z0-9-]+$"))) {
                throw IOException("Translator endpoint response invalid")
            }
            val expiresAt = parseJwtExpiry(authorization) ?: (nowSeconds + DEFAULT_TOKEN_LIFETIME_SECONDS)
            log("fetched new endpoint region=$region expiresAt=$expiresAt (in ${expiresAt - nowSeconds}s)")
            return Endpoint(authorization, region, expiresAt)
        }
    }

    /**
     * 解析 JWT Token 声明的有效时间，完全对齐 FMB 实现
     */
    private fun parseJwtExpiry(authorization: String): Long? = runCatching {
        val token = authorization.substringAfter(' ').trim()
        val payload = token.split('.')[1]
        val jsonString = String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8)
        val exp = JSONObject(jsonString).optLong("exp")
        if (exp > 0L) exp else null
    }.getOrNull()

    private fun signature(nowMillis: Long, uuid: String): String {
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(nowMillis)).lowercase(Locale.US)
        val encodedUrl = URLEncoder.encode("dev.microsofttranslator.com/apps/endpoint?api-version=1.0", "UTF-8")
        val input = "MSTranslatorAndroidApp$encodedUrl$date${uuid.lowercase(Locale.US)}"
            .lowercase(Locale.US)
        val key = Base64.getDecoder().decode(VOICE_DECODE_KEY)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val digest = Base64.getEncoder().encodeToString(mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)))
        return "MSTranslatorAndroidApp::$digest::$date::$uuid"
    }

    private fun randomUserId(): String {
        val chars = "abcdef0123456789"
        val random = SecureRandom()
        return buildString(16) { repeat(16) { append(chars[random.nextInt(chars.length)]) } }
    }

    private fun ssml(text: String, voice: String, localeTag: String, speechRate: Int, pitch: Int): String =
        "<speak xmlns=\"http://www.w3.org/2001/10/synthesis\" version=\"1.0\" xml:lang=\"${escapeXml(localeTag)}\">" +
            "<voice name=\"${escapeXml(voice)}\"><prosody rate=\"${percentFromAndroid(speechRate)}\" " +
            "pitch=\"${percentFromAndroid(pitch)}\" volume=\"50\">" +
            escapeXml(cleanText(text)) + "</prosody></voice></speak>"

    private fun percentFromAndroid(value: Int): String =
        "${(value.coerceIn(20, 200) - 100)}%"

    private fun splitText(text: String, maxBytes: Int = 4_000): List<String> {
        val cleaned = cleanText(text).trim()
        if (cleaned.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        while (start < cleaned.length) {
            var index = start
            var bytes = 0
            var preferredEnd = -1
            while (index < cleaned.length) {
                val codePoint = cleaned.codePointAt(index)
                val charCount = Character.charCount(codePoint)
                val charBytes = String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8).size
                if (bytes + charBytes > maxBytes) break
                bytes += charBytes
                index += charCount
                if (codePoint == '\n'.code || codePoint == '。'.code || codePoint == '！'.code || codePoint == '？'.code || codePoint == '；'.code) {
                    preferredEnd = index
                }
            }
            val end = when {
                index >= cleaned.length -> cleaned.length
                preferredEnd > start -> preferredEnd
                index > start -> index
                else -> start + Character.charCount(cleaned.codePointAt(start))
            }
            cleaned.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(result::add)
            start = end
        }
        return result
    }

    private fun cleanText(text: String): String = buildString(text.length) {
        text.forEach { char -> append(if (char.code in 0..8 || char.code in 11..12 || char.code in 14..31) ' ' else char) }
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private data class Endpoint(val authorization: String, val region: String, val expiresAt: Long)

    private data class SpeechPartResult(
        val status: Int,
        val errorBody: String,
        val audioBytes: Long,
    )

    enum class OutputFormat(val microsoftHeader: String) {
        MP3("audio-24khz-48kbitrate-mono-mp3"),
        PCM_24KHZ_16BIT_MONO("raw-24khz-16bit-mono-pcm"),
    }

    companion object {
        private const val TOKEN_REFRESH_MARGIN_SECONDS = 300L
        private const val DEFAULT_TOKEN_LIFETIME_SECONDS = 1_200L
        private const val DEFAULT_LOCALE_TAG = "zh-CN"
        private const val STREAM_BUFFER_BYTES = 8_192
        private val AUTH_RETRY_CODES = setOf(400, 401, 403)
        private const val VOICE_DECODE_KEY =
            "oik6PdDdMnOXemTbwvMn9de/h9lFnfBaCWbGMMZqqoSaQaqUOqjVGm5NqsmjcBI1x+sS9ugjB55HEJWRiFXYFw=="
    }
}

class SynthesisCancellation {
    private val cancelled = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) calls.forEach(Call::cancel)
    }

    val isCancelled: Boolean
        get() = cancelled.get()

    fun throwIfCancelled() {
        if (cancelled.get()) throw CancellationException("speech synthesis stopped")
    }

    internal fun track(call: Call) {
        calls += call
        if (cancelled.get()) call.cancel()
    }

    internal fun untrack(call: Call) {
        calls -= call
    }
}
