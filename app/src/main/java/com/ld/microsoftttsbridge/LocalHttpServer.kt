package com.ld.microsoftttsbridge

import android.net.Uri
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 具有弹性工作线程池的本地 HTTP/1.1 服务端。
 * 针对 Operit 等短超时 HTTP 客户端优化，支持 TCP_NODELAY 与显式 TCP FIN 优雅结束。
 */
class LocalHttpServer(
    private val bindAddress: String,
    private val port: Int,
    private val defaultBackend: String,
    private val translator: TranslatorTtsBridge,
    private val log: (String) -> Unit,
) {
    private var socket: ServerSocket? = null
    private var workers: ExecutorService? = null
    @Volatile private var running = false

    fun start() {
        check(!running) { "server already started" }
        val serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(bindAddress), port), 50)
        }
        socket = serverSocket

        // 使用 SynchronousQueue 的弹性线程池，彻底避免请求在无界队列中长期积压造成级联超时
        val workerPool = ThreadPoolExecutor(
            2,
            16,
            60L,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            { task -> Thread(task, "tts-http-worker").apply { isDaemon = false } },
            ThreadPoolExecutor.CallerRunsPolicy()
        )
        workers = workerPool
        running = true

        thread(name = "tts-http-accept", isDaemon = false) {
            while (running) {
                try {
                    val client = serverSocket.accept()
                    log("accepted ${client.inetAddress.hostAddress}:${client.port}")
                    try {
                        workerPool.execute { handle(client) }
                    } catch (e: Exception) {
                        runCatching { client.close() }
                        if (running) log("connection rejected: ${e.message}")
                    }
                } catch (_: Exception) {
                    if (running) log("accept loop stopped unexpectedly")
                }
            }
        }
        log("native server listening on $bindAddress:$port")
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        workers?.shutdownNow()
        workers = null
    }

    private fun handle(client: Socket) {
        val startedAtNanos = System.nanoTime()
        var responseStatus: Int? = null
        var responseBytes = 0
        client.use { socket ->
            try {
                // 开启 TCP_NODELAY 降低小包等待延迟
                socket.tcpNoDelay = true
                socket.soTimeout = 12_000

                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())

                val requestLine = readLine(input) ?: return
                val requestParts = requestLine.split(' ', limit = 3)
                if (requestParts.size < 2) {
                    respond(output, socket, 400, "application/json; charset=utf-8", json("error" to "bad_request"))
                    return
                }
                val method = requestParts[0].uppercase()
                // 清理 target 中可能的换行或异常空白
                val target = requestParts[1].trim().lines().firstOrNull().orEmpty()

                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        headers[line.substring(0, separator).trim().lowercase()] =
                            line.substring(separator + 1).trim()
                    }
                }
                val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val body = ByteArray(contentLength)
                readFully(input, body)
                log("request $method $target bytes=$contentLength")

                val uri = Uri.parse(target)
                val response = route(method, uri, body)
                responseStatus = response.status
                responseBytes = response.bytes.size
                respond(output, socket, response.status, response.contentType, response.bytes)
            } catch (error: Exception) {
                log("request failed: ${error.message ?: error.javaClass.simpleName}")
                responseStatus = 500
                runCatching {
                    val output = BufferedOutputStream(socket.getOutputStream())
                    val errorBody = json("error" to (error.message ?: "internal_error"))
                    responseBytes = errorBody.size
                    respond(output, socket, 500, "application/json; charset=utf-8", errorBody)
                }
            } finally {
                val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
                log("request completed status=${responseStatus ?: 0} bytes=$responseBytes elapsedMs=$elapsedMs")
            }
        }
    }

    private fun route(method: String, uri: Uri, body: ByteArray): HttpResponse {
        return when (uri.path) {
            "/health" -> HttpResponse(200, "application/json; charset=utf-8", json("ok" to true, "backend" to defaultBackend))
            "/voices" -> HttpResponse(
                200,
                "application/json; charset=utf-8",
                json("voices" to listOf("zh-CN-XiaochenNeural", "zh-CN-XiaoyiNeural", "zh-CN-YunxiNeural")),
            )
            "/tts" -> tts(method, uri, body)
            else -> HttpResponse(404, "application/json; charset=utf-8", json("error" to "not_found"))
        }
    }

    private fun tts(method: String, uri: Uri, body: ByteArray): HttpResponse {
        if (method != "GET" && method != "POST") {
            return HttpResponse(405, "application/json; charset=utf-8", json("error" to "POST required"))
        }
        val input = if (method == "POST") {
            val raw = body.toString(StandardCharsets.UTF_8)
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } else {
            JSONObject().apply {
                uri.getQueryParameter("text")?.let { put("text", it) }
                uri.getQueryParameter("voice")?.let { put("voice", it) }
            }
        }
        val text = input.optString("text").trim()
        if (text.isBlank()) return HttpResponse(400, "application/json; charset=utf-8", json("error" to "text required"))
        val voice = input.optString("voice", "zh-CN-XiaochenNeural")
        val backend = input.optString("backend", defaultBackend)
        if (backend != "translator") {
            return HttpResponse(501, "application/json; charset=utf-8", json("error" to "backend_not_implemented"))
        }
        val audio = translator.synthesize(text, voice)
        return HttpResponse(200, "audio/mpeg", audio)
    }

    private fun respond(output: BufferedOutputStream, socket: Socket, status: Int, contentType: String, bytes: ByteArray) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            501 -> "Not Implemented"
            else -> "Error"
        }
        val header = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Connection: close\r\n" +
            "Content-Length: ${bytes.size}\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
        // 发送 TCP FIN，告知客户端数据已全部输出，明确关闭输出流
        runCatching { socket.shutdownOutput() }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString(StandardCharsets.ISO_8859_1.name())
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.write(value)
            if (bytes.size() > 16_384) error("header too large")
        }
        return bytes.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun readFully(input: BufferedInputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) error("unexpected end of request body")
            offset += count
        }
    }

    private fun json(vararg entries: Pair<String, Any?>): ByteArray =
        JSONObject().apply { entries.forEach { (key, value) -> put(key, value) } }
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

    private data class HttpResponse(val status: Int, val contentType: String, val bytes: ByteArray)
}
