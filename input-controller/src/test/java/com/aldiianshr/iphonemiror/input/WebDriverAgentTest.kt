package com.aldiianshr.iphonemiror.input

import com.aldiianshr.iphonemiror.input.touch.GestureRecorder
import com.aldiianshr.iphonemiror.input.touch.NPoint
import com.aldiianshr.iphonemiror.input.touch.TouchEvent
import com.aldiianshr.iphonemiror.input.touch.W3cActions
import com.aldiianshr.iphonemiror.input.touch.WebDriverAgentClient
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class WebDriverAgentTest {

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()

    @Before fun setUp() {
        // Fake WebDriverAgent with the response shapes of the real server.
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            val path = ex.requestURI.path
            requests += "${ex.requestMethod} $path" to body
            val (code, resp) = when {
                path == "/status" -> 200 to """{"value":{"ready":true,"os":{"version":"26.1","name":"iOS"},"ios":{"ip":"192.168.1.20"}},"sessionId":null}"""
                path == "/session" -> 200 to """{"value":{"sessionId":"ABC123","capabilities":{}},"sessionId":"ABC123"}"""
                path.endsWith("/window/size") -> 200 to """{"value":{"width":393,"height":852},"sessionId":"ABC123"}"""
                path.endsWith("/actions") -> 200 to """{"value":null,"sessionId":"ABC123"}"""
                path.endsWith("/wda/keys") -> 200 to """{"value":null}"""
                path == "/wda/homescreen" -> 200 to """{"value":null}"""
                else -> 404 to """{"value":{"error":"unknown command","message":"Unhandled endpoint"}}"""
            }
            val bytes = resp.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After fun tearDown() = server.stop(0)

    private fun url() = "http://127.0.0.1:${server.address.port}"

    @Test fun sessionLifecycleAndActions() {
        val c = WebDriverAgentClient(url())
        val st = c.status()
        assertTrue(st.ready)
        assertEquals("26.1", st.osVersion)
        val s = c.createSession()
        assertEquals("ABC123", s)
        assertEquals(393 to 852, c.windowSize(s))

        val rec = GestureRecorder()
        listOf<TouchEvent>(
            TouchEvent.Down(0, NPoint(0.45f, 0.5f), 0), TouchEvent.Down(1, NPoint(0.55f, 0.5f), 10),
            TouchEvent.Move(mapOf(0 to NPoint(0.3f, 0.5f), 1 to NPoint(0.7f, 0.5f)), 200),
            TouchEvent.Up(0, NPoint(0.3f, 0.5f), 260), TouchEvent.Up(1, NPoint(0.7f, 0.5f), 260),
        ).forEach(rec::onEvent)
        val actions = W3cActions.build(rec.tracks()) { (it.u * 392).toInt() to (it.v * 851).toInt() }
        c.performActions(s, actions)
        c.typeText(s, "hi")
        c.home()

        val posted = requests.first { it.first.endsWith("/actions") }.second
        val sources = JSONObject(posted).getJSONArray("actions")
        assertEquals(2, sources.length())
        assertEquals("touch", sources.getJSONObject(0).getJSONObject("parameters").getString("pointerType"))
        val keys = JSONObject(requests.first { it.first.endsWith("/wda/keys") }.second).getJSONArray("value")
        assertEquals("h", keys.getString(0))
        assertTrue(requests.any { it.first == "POST /wda/homescreen" })
    }

    @Test(expected = WebDriverAgentClient.WdaException::class)
    fun errorsAreSurfaced() {
        WebDriverAgentClient(url()).typeText("ABC123", "x").also { } // ok
        WebDriverAgentClient(url()).windowSize("ABC123")
        WebDriverAgentClient(url()).run { request404() }
    }

    private fun WebDriverAgentClient.request404() {
        // /session/ABC123/unknown is not handled by the fake → W3C error payload.
        val m = WebDriverAgentClient::class.java.getDeclaredMethod("request", String::class.java, String::class.java, JSONObject::class.java)
        m.isAccessible = true
        try {
            m.invoke(this, "GET", "/session/ABC123/unknown", null)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test fun w3cTicksKeepFingersInSyncAndEndWithUp() {
        val rec = GestureRecorder()
        listOf<TouchEvent>(
            TouchEvent.Down(0, NPoint(0.1f, 0.1f), 0),
            TouchEvent.Move(mapOf(0 to NPoint(0.5f, 0.5f)), 100),
            TouchEvent.Up(0, NPoint(0.9f, 0.9f), 200),
        ).forEach(rec::onEvent)
        val json = W3cActions.build(rec.tracks()) { (it.u * 100).toInt() to (it.v * 100).toInt() }
        val acts = json.getJSONArray("actions").getJSONObject(0).getJSONArray("actions")
        assertEquals("pointerMove", acts.getJSONObject(0).getString("type"))
        assertEquals(10, acts.getJSONObject(0).getInt("x"))
        assertEquals("pointerDown", acts.getJSONObject(1).getString("type"))
        val types = (0 until acts.length()).map { acts.getJSONObject(it).getString("type") }
        assertEquals(1, types.count { it == "pointerUp" })
        val lastMove = (0 until acts.length()).map { acts.getJSONObject(it) }.last { it.getString("type") == "pointerMove" }
        assertEquals(90, lastMove.getInt("x"))
        // Total duration roughly matches the gesture (200 ms).
        val total = (0 until acts.length()).sumOf { acts.getJSONObject(it).optLong("duration", 0) }
        assertTrue("duration $total", total in 180..320)
    }
}
