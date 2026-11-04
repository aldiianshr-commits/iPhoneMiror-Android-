package com.aldiianshr.iphonemiror.input.touch

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal client for WebDriverAgent — the XCTest-based automation server used by Appium.
 * XCTest is Apple's official UI-testing framework: once WDA is signed with the user's own
 * (free) Apple developer account and started on the iPhone, it accepts real, system-level
 * touch input (including multi-touch) over HTTP. No jailbreak is involved.
 */
class WebDriverAgentClient(baseUrl: String, private val timeoutMs: Int = 5_000) {

    class WdaException(message: String) : IOException(message)

    data class Status(val ready: Boolean, val osVersion: String?, val deviceIp: String?, val raw: JSONObject)

    /** Host + port parsed from the base URL; supports IPv4, hostnames and IPv6 (with or
     *  without brackets, including link-local zone ids such as `fe80::1%wlan0`). */
    data class Endpoint(val host: String, val port: Int) {
        val isIpv6: Boolean get() = host.contains(':')
        /** Canonical URL for display/storage, e.g. `http://[fe80::1%wlan0]:8100`. */
        val url: String get() = "http://${if (isIpv6) "[$host]" else host}:$port"
        val hostHeader: String get() = (if (isIpv6) "[${host.substringBefore('%')}]" else host) + ":$port"
    }

    val endpoint: Endpoint = parse(baseUrl)
    val baseUrl: String = endpoint.url

    fun status(): Status {
        val v = request("GET", "/status").optJSONObject("value") ?: JSONObject()
        val os = v.optJSONObject("os")
        val ios = v.optJSONObject("ios")
        return Status(
            ready = v.optBoolean("ready", true),
            osVersion = os?.optString("version")?.takeIf { it.isNotBlank() },
            deviceIp = ios?.optString("ip")?.takeIf { it.isNotBlank() },
            raw = v,
        )
    }

    fun createSession(): String {
        val body = JSONObject().put(
            "capabilities",
            JSONObject().put("alwaysMatch", JSONObject()).put("firstMatch", JSONArray().put(JSONObject())),
        )
        val r = request("POST", "/session", body)
        val id = r.optString("sessionId").takeIf { it.isNotBlank() }
            ?: r.optJSONObject("value")?.optString("sessionId")?.takeIf { it.isNotBlank() }
        return id ?: throw WdaException("WDA tidak mengembalikan sessionId")
    }

    fun windowSize(session: String): Pair<Int, Int> {
        val v = request("GET", "/session/$session/window/size").optJSONObject("value")
            ?: throw WdaException("window/size tanpa nilai")
        return v.optDouble("width").toInt() to v.optDouble("height").toInt()
    }

    fun performActions(session: String, actions: JSONObject) {
        request("POST", "/session/$session/actions", actions)
    }

    fun typeText(session: String, text: String) {
        val chars = JSONArray()
        text.forEach { chars.put(it.toString()) }
        request("POST", "/session/$session/wda/keys", JSONObject().put("value", chars))
    }

    fun home() {
        request("POST", "/wda/homescreen", JSONObject())
    }

    /**
     * Plain HTTP/1.1 over a socket. HttpURLConnection/OkHttp cannot address IPv6 link-local
     * hosts with a zone id (`%wlan0`), which is exactly what an iPhone often uses on the LAN.
     */
    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val payload = body?.toString()?.toByteArray() ?: ByteArray(0)
        val address = try {
            InetAddress.getByName(endpoint.host)
        } catch (e: Exception) {
            throw WdaException("Host tidak valid: ${endpoint.host}")
        }
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, endpoint.port), timeoutMs)
            socket.soTimeout = timeoutMs * 3
            socket.tcpNoDelay = true
            val head = StringBuilder()
                .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(endpoint.hostHeader).append("\r\n")
                .append("Accept: application/json\r\n")
                .append("Connection: close\r\n")
            if (body != null) head.append("Content-Type: application/json; charset=utf-8\r\n")
            if (body != null || method == "POST") head.append("Content-Length: ").append(payload.size).append("\r\n")
            head.append("\r\n")
            val out = socket.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            out.write(payload)
            out.flush()

            val input = socket.getInputStream().buffered()
            val statusLine = readLine(input) ?: throw WdaException("Tidak ada respons dari WDA")
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw WdaException("Respons tidak valid: $statusLine")
            var length = -1
            var chunked = false
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val name = line.substringBefore(':').trim().lowercase()
                val value = line.substringAfter(':', "").trim()
                if (name == "content-length") length = value.toIntOrNull() ?: -1
                if (name == "transfer-encoding" && value.contains("chunked", true)) chunked = true
            }
            val bytes = when {
                chunked -> readChunked(input)
                length >= 0 -> ByteArray(length).also { buf ->
                    var r = 0
                    while (r < length) { val n = input.read(buf, r, length - r); if (n < 0) break; r += n }
                }
                else -> input.readBytes()
            }
            val text = String(bytes, Charsets.UTF_8)
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            val error = json.optJSONObject("value")?.takeIf { it.has("error") }
            if (code !in 200..299 || error != null) {
                val msg = error?.optString("message")?.takeIf { it.isNotBlank() } ?: error?.optString("error") ?: "HTTP $code"
                throw WdaException(msg.take(200))
            }
            return json
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) return buf.toString("ISO-8859-1").trimEnd('\r')
            buf.write(b)
        }
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: break
            if (size == 0) { readLine(input); break }
            val chunk = ByteArray(size)
            var r = 0
            while (r < size) { val n = input.read(chunk, r, size - r); if (n < 0) break; r += n }
            out.write(chunk, 0, r)
            readLine(input)
        }
        return out.toByteArray()
    }

    /** Result of checking one candidate address for a running WebDriverAgent. */
    data class ProbeResult(
        val url: String,
        val outcome: Outcome,
        val detail: String,
        val rttMs: Long? = null,
        val osVersion: String? = null,
    ) {
        enum class Outcome { OK, REFUSED, TIMEOUT, UNREACHABLE, NOT_WDA, INVALID }
    }

    companion object {
        const val DEFAULT_PORT = 8100

        /**
         * Checks `GET /status` on [url] with a short timeout and explains the result in plain
         * terms: REFUSED = the iPhone answered but nothing listens on the port (WDA not
         * running); TIMEOUT = no answer at all (WDA not running on an iPhone that silently
         * drops packets, wrong address, or a different network).
         */
        fun probe(url: String, timeoutMs: Int = 2_500): ProbeResult {
            val client = try {
                WebDriverAgentClient(url, timeoutMs)
            } catch (e: Exception) {
                return ProbeResult(url, ProbeResult.Outcome.INVALID, "URL tidak valid: ${e.message}")
            }
            val t0 = System.nanoTime()
            return try {
                val st = client.status()
                ProbeResult(client.baseUrl, ProbeResult.Outcome.OK, "WebDriverAgent menjawab",
                    (System.nanoTime() - t0) / 1_000_000, st.osVersion)
            } catch (e: Exception) {
                val (outcome, text) = classify(e)
                ProbeResult(client.baseUrl, outcome, text)
            }
        }

        fun classify(e: Throwable): Pair<ProbeResult.Outcome, String> {
            val msg = (e.message ?: "").lowercase()
            return when {
                e is java.net.ConnectException && ("refused" in msg || "econnrefused" in msg) ->
                    ProbeResult.Outcome.REFUSED to "iPhone terjangkau, tetapi port tertutup — WebDriverAgent tidak berjalan"
                e is java.net.SocketTimeoutException || "timed out" in msg || "etimedout" in msg || "after" in msg && "ms" in msg ->
                    ProbeResult.Outcome.TIMEOUT to "Tidak ada jawaban — WebDriverAgent tidak berjalan, atau alamat/jaringan berbeda"
                e is java.net.NoRouteToHostException || "ehostunreach" in msg || "enetunreach" in msg || "unreachable" in msg ->
                    ProbeResult.Outcome.UNREACHABLE to "Alamat tidak dapat dijangkau dari jaringan tablet"
                e is WdaException ->
                    ProbeResult.Outcome.NOT_WDA to "Ada server di port ini, tetapi bukan WebDriverAgent (${e.message})"
                e is java.net.ConnectException ->
                    ProbeResult.Outcome.UNREACHABLE to "Koneksi gagal: ${e.message}"
                else -> ProbeResult.Outcome.NOT_WDA to (e.message ?: e.javaClass.simpleName)
            }
        }

        /** Builds the default WDA URL for an address seen on the AirPlay connection. */
        fun urlFor(address: String, port: Int = DEFAULT_PORT): String = Endpoint(address.trim().removePrefix("/"), port).url

        /**
         * Accepts `http://host:port`, `host`, `[v6]:port`, `[v6%zone]`, and also the unbracketed
         * form `fe80::1%wlan0:8100` that earlier versions produced.
         */
        fun parse(raw: String): Endpoint {
            var s = raw.trim()
            s = s.removePrefix("http://").removePrefix("HTTP://")
            if (s.startsWith("https://", true)) throw WdaException("WDA memakai http, bukan https")
            s = s.substringBefore('/').replace("%25", "%")
            if (s.isEmpty()) throw WdaException("URL WDA kosong")
            if (s.startsWith("[")) {
                val close = s.indexOf(']')
                if (close < 0) throw WdaException("Alamat IPv6 tanpa ']'")
                val host = s.substring(1, close)
                val rest = s.substring(close + 1)
                val port = if (rest.startsWith(":")) rest.drop(1).toIntOrNull() ?: throw WdaException("Port tidak valid: $rest") else DEFAULT_PORT
                return Endpoint(host, checkPort(port))
            }
            val colons = s.count { it == ':' }
            if (colons <= 1) {
                val host = s.substringBefore(':')
                val port = if (colons == 1) s.substringAfter(':').toIntOrNull() ?: throw WdaException("Port tidak valid: ${s.substringAfter(':')}") else DEFAULT_PORT
                return Endpoint(host, checkPort(port))
            }
            // Unbracketed IPv6. A trailing ":<port>" is only taken as a port when what is left
            // is still a complete address (has a zone id or a '::').
            val last = s.substringAfterLast(':')
            val head = s.substringBeforeLast(':')
            val port = last.toIntOrNull()
            if (port != null && last.length <= 5 && (head.contains('%') || head.contains("::")) && !head.endsWith(":")) {
                return Endpoint(head, checkPort(port))
            }
            return Endpoint(s, DEFAULT_PORT)
        }

        private fun checkPort(p: Int): Int = if (p in 1..65535) p else throw WdaException("Port di luar rentang: $p")
    }
}
