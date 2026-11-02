package com.aldiianshr.iphonemiror.connection

import com.aldiianshr.iphonemiror.connection.rtsp.RtspCodec
import com.aldiianshr.iphonemiror.connection.rtsp.RtspProtocolException
import com.aldiianshr.iphonemiror.connection.rtsp.RtspResponse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RtspCodecTest {
    @Test fun parsesPipelinedRequestsWithBodies() {
        val raw = "POST /pair-setup RTSP/1.0\r\nCSeq: 3\r\ncontent-length: 4\r\nActive-Remote: 99\r\n\r\nABCD" +
            "GET /info RTSP/1.0\r\nCSeq: 4\r\n\r\n"
        val input = ByteArrayInputStream(raw.toByteArray())
        val first = RtspCodec.readRequest(input)!!
        assertEquals("POST", first.method)
        assertEquals("/pair-setup", first.path)
        assertEquals("99", first.header("active-remote"))
        assertArrayEquals("ABCD".toByteArray(), first.body)
        val second = RtspCodec.readRequest(input)!!
        assertEquals("GET", second.method)
        assertEquals("4", second.cseq)
        assertNull(RtspCodec.readRequest(input))
    }

    @Test(expected = RtspProtocolException::class)
    fun rejectsHugeBodies() {
        RtspCodec.readRequest(ByteArrayInputStream("POST / RTSP/1.0\r\nContent-Length: 999999999\r\n\r\n".toByteArray()))
    }

    @Test fun writesRtspResponseWithCseqAndLength() {
        val req = RtspCodec.readRequest(ByteArrayInputStream("OPTIONS * RTSP/1.0\r\nCSeq: 7\r\n\r\n".toByteArray()))!!
        val out = ByteArrayOutputStream()
        RtspCodec.writeResponse(out, req, RtspResponse(body = byteArrayOf(1, 2)).header("Public", "SETUP"))
        val text = out.toString(Charsets.ISO_8859_1.name())
        assertTrue(text.startsWith("RTSP/1.0 200 OK\r\nCSeq: 7\r\n"))
        assertTrue(text.contains("Public: SETUP\r\n"))
        assertTrue(text.contains("Content-Length: 2\r\n\r\n"))
    }

    @Test fun httpRequestsGetHttpStatusLine() {
        val req = RtspCodec.readRequest(ByteArrayInputStream("GET /server-info HTTP/1.1\r\n\r\n".toByteArray()))!!
        val out = ByteArrayOutputStream()
        RtspCodec.writeResponse(out, req, RtspResponse(404, "Not Found"))
        assertTrue(out.toString().startsWith("HTTP/1.1 404 Not Found"))
    }
}
