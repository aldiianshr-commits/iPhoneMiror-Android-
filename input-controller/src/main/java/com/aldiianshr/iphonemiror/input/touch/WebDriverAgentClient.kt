package com.aldiianshr.iphonemiror.input.touch

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for WebDriverAgent — the XCTest-based automation server used by Appium.
 * XCTest is Apple's official UI-testing framework: once WDA is signed with the user's own
 * (free) Apple developer account and started on the iPhone, it accepts real, system-level
 * touch input (including multi-touch) over HTTP. No jailbreak is involved.
 */
class WebDriverAgentClient(baseUrl: String, private val timeoutMs: Int = 5_000) {

    class WdaException(message: String) : IOException(message)

    data class Status(val ready: Boolean, val osVersion: String?, val deviceIp: String?, val raw: JSONObject)

    val baseUrl: String = baseUrl.trimEnd('/')

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

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs * 3
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            val error = json.optJSONObject("value")?.takeIf { it.has("error") }
            if (code !in 200..299 || error != null) {
                val msg = error?.optString("message")?.takeIf { it.isNotBlank() } ?: error?.optString("error") ?: "HTTP $code"
                throw WdaException(msg.take(200))
            }
            return json
        } finally {
            conn.disconnect()
        }
    }
}
