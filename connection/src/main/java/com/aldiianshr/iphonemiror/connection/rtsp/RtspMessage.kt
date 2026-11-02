package com.aldiianshr.iphonemiror.connection.rtsp

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.TreeMap

/** One RTSP/HTTP request as sent by an AirPlay sender on the control connection. */
class RtspRequest(
    val method: String,
    val uri: String,
    val protocol: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name]
    val cseq: String? get() = header("CSeq")
    val isRtsp: Boolean get() = protocol.startsWith("RTSP/")
    val path: String get() = uri.substringBefore('?')

    override fun toString(): String = "$method $uri $protocol (${body.size} bytes)"
}

class RtspResponse(
    var status: Int = 200,
    var reason: String = "OK",
    val headers: MutableMap<String, String> = linkedMapOf(),
    var body: ByteArray = ByteArray(0),
) {
    fun header(name: String, value: String): RtspResponse = apply { headers[name] = value }
}

class RtspProtocolException(message: String) : IOException(message)

object RtspCodec {
    private const val MAX_LINE = 8 * 1024
    private const val MAX_HEADERS = 100
    const val MAX_BODY = 4 * 1024 * 1024

    /** Returns null on clean EOF before a new request starts. */
    fun readRequest(input: InputStream): RtspRequest? {
        var requestLine = readLine(input) ?: return null
        // Tolerate stray blank lines between pipelined requests.
        while (requestLine.isEmpty()) requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 3) throw RtspProtocolException("Malformed request line: $requestLine")
        val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        while (true) {
            val line = readLine(input) ?: throw RtspProtocolException("EOF in headers")
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
            if (headers.size > MAX_HEADERS) throw RtspProtocolException("Too many headers")
        }
        val length = headers["Content-Length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY) throw RtspProtocolException("Bad Content-Length $length")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw RtspProtocolException("EOF in body")
            read += n
        }
        return RtspRequest(parts[0], parts[1], parts[2], headers, body)
    }

    fun writeResponse(output: OutputStream, request: RtspRequest, response: RtspResponse) {
        val protocol = if (request.isRtsp) "RTSP/1.0" else request.protocol
        val sb = StringBuilder()
        sb.append(protocol).append(' ').append(response.status).append(' ').append(response.reason).append("\r\n")
        request.cseq?.let { sb.append("CSeq: ").append(it).append("\r\n") }
        for ((k, v) in response.headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Content-Length: ").append(response.body.size).append("\r\n\r\n")
        output.write(sb.toString().toByteArray(Charsets.US_ASCII))
        if (response.body.isNotEmpty()) output.write(response.body)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.US_ASCII.name())
            if (b == '\n'.code) {
                val bytes = buf.toByteArray()
                val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.US_ASCII)
            }
            buf.write(b)
            if (buf.size() > MAX_LINE) throw RtspProtocolException("Line too long")
        }
    }
}
