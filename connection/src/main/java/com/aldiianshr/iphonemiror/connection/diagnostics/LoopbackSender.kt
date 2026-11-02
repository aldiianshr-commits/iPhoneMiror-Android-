package com.aldiianshr.iphonemiror.connection.diagnostics

import com.aldiianshr.iphonemiror.connection.airplay.AirPlayProfile
import com.aldiianshr.iphonemiror.connection.airplay.MirrorChannel
import com.aldiianshr.iphonemiror.connection.rtsp.RtspCodec
import com.aldiianshr.iphonemiror.connection.rtsp.RtspProtocolException
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.PropertyListParser
import com.github.serezhka.airplay.protocol.crypto.AirPlayKeyLabels
import com.github.serezhka.airplay.protocol.crypto.X25519
import com.github.serezhka.airplay.protocol.fairplay.FairPlayHandshake
import com.github.serezhka.airplay.protocol.fairplay.FairPlayVideoCipher
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.KeyPairGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Diagnostic AirPlay *sender* used only for the receiver self-test and automated tests.
 * It performs the same legacy pair-verify + FairPlay SAP v3 handshake an iPhone does,
 * using the public test vectors from serezhka/java-airplay, then streams H.264 that the
 * caller supplies. Anything it produces is labelled as a self-test in the UI — it is
 * never presented as an iPhone.
 */
class LoopbackSender(private val host: String, private val port: Int) : AutoCloseable {

    private val control = Socket().apply { connect(InetSocketAddress(host, port), 3_000); tcpNoDelay = true }
    private val input: InputStream = control.getInputStream().buffered()
    private val output: OutputStream = control.getOutputStream()
    private var cseq = 0
    private val activeRemote = (SecureRandom().nextLong() and Long.MAX_VALUE).toString()
    private var sharedSecret = ByteArray(0)
    private var aesKey = ByteArray(16)
    private val eiv = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private var mirrorSocket: Socket? = null
    private var videoCipher: FairPlayVideoCipher? = null
    private var audioSocket: DatagramSocket? = null
    private var audioPort = 0
    private var audioSeq = 0
    val streamConnectionId: Long = SecureRandom().nextLong()

    data class Reply(val status: Int, val body: ByteArray)

    fun request(method: String, uri: String, body: ByteArray = ByteArray(0), contentType: String? = null): Reply {
        val sb = StringBuilder("$method $uri RTSP/1.0\r\nCSeq: ${++cseq}\r\nActive-Remote: $activeRemote\r\n")
        sb.append("User-Agent: iPhoneMiror-SelfTest/1.0\r\n")
        if (contentType != null) sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${body.size}\r\n\r\n")
        output.write(sb.toString().toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
        return readReply()
    }

    private fun readReply(): Reply {
        val status = readLine().split(' ').getOrNull(1)?.toIntOrNull() ?: throw RtspProtocolException("bad status")
        var length = 0
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
        }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw RtspProtocolException("EOF")
            read += n
        }
        return Reply(status, body)
    }

    private fun readLine(): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw RtspProtocolException("EOF")
            if (b == '\n'.code) return buf.toString(Charsets.US_ASCII.name()).trimEnd('\r')
            buf.write(b)
        }
    }

    /** GET /info → returns the parsed receiver name. */
    fun info(): String? {
        val reply = request("GET", "/info")
        val dict = PropertyListParser.parse(reply.body) as NSDictionary
        return dict["name"]?.toString()
    }

    /** Legacy pair-setup + pair-verify + FairPlay fp-setup (both phases). */
    fun handshake(): Boolean {
        val setup = request("POST", "/pair-setup", ByteArray(32), "application/octet-stream")
        require(setup.body.size == 32) { "pair-setup returned ${setup.body.size} bytes" }

        val ed = KeyPairGenerator().generateKeyPair()
        val ecdhPrivate = X25519.generatePrivateKey(SecureRandom())
        val ecdhPublic = X25519.publicKey(ecdhPrivate)
        val m1 = ByteArray(68)
        m1[0] = 1
        System.arraycopy(ecdhPublic, 0, m1, 4, 32)
        System.arraycopy((ed.public as EdDSAPublicKey).abyte, 0, m1, 36, 32)
        val r1 = request("POST", "/pair-verify", m1, "application/octet-stream").body
        require(r1.size >= 96) { "pair-verify M2 too short: ${r1.size}" }
        val theirs = r1.copyOfRange(0, 32)
        sharedSecret = X25519.sharedSecret(ecdhPrivate, theirs)

        val sha = MessageDigest.getInstance("SHA-512")
        sha.update(AirPlayKeyLabels.PAIR_VERIFY_AES_KEY.toByteArray()); sha.update(sharedSecret)
        val key = sha.digest().copyOf(16)
        sha.update(AirPlayKeyLabels.PAIR_VERIFY_AES_IV.toByteArray()); sha.update(sharedSecret)
        val iv = sha.digest().copyOf(16)
        val ctr = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        ctr.update(r1.copyOfRange(32, 96))
        val signer = EdDSAEngine().apply { initSign(ed.private) }
        val toSign = ecdhPublic + theirs
        val sig = ctr.update(signer.signOneShot(toSign))
        val m3 = ByteArray(68)
        System.arraycopy(sig, 0, m3, 4, 64)
        request("POST", "/pair-verify", m3, "application/octet-stream")

        request("POST", "/fp-setup", FP_SETUP_1, "application/octet-stream")
        request("POST", "/fp-setup", FP_SETUP_2, "application/octet-stream")
        aesKey = unwrapAesKey(FIXTURE_EKEY)
        return true
    }

    /** Session SETUP carrying the key material and a clearly-labelled self-test identity. */
    fun setupSession(): Int {
        val dict = NSDictionary().apply {
            put("ekey", FIXTURE_EKEY)
            put("eiv", eiv)
            put("deviceID", "00:00:00:00:00:01")
            put("name", SELF_TEST_NAME)
            put("model", "SelfTest")
            put("osName", "Android")
            put("osVersion", "loopback")
            put("isScreenMirroringSession", true)
        }
        return request("SETUP", "rtsp://$host/$activeRemote", BinaryPropertyListWriter.writeToArray(dict),
            "application/x-apple-binary-plist").status
    }

    /** SETUP for the mirror stream; connects to the returned data port. */
    fun setupMirror() {
        val stream = NSDictionary().apply {
            put("type", AirPlayProfile.STREAM_TYPE_SCREEN)
            put("streamConnectionID", streamConnectionId)
        }
        val reply = request("SETUP", "rtsp://$host/$activeRemote",
            BinaryPropertyListWriter.writeToArray(NSDictionary().apply { put("streams", NSArray(stream)) }),
            "application/x-apple-binary-plist")
        val root = PropertyListParser.parse(reply.body) as NSDictionary
        val dataPort = (((root["streams"] as NSArray).array[0] as NSDictionary)["dataPort"] as NSNumber).intValue()
        mirrorSocket = Socket().apply { connect(InetSocketAddress(host, dataPort), 3_000); tcpNoDelay = true }
        videoCipher = FairPlayVideoCipher(aesKey, sharedSecret, java.lang.Long.toUnsignedString(streamConnectionId))
    }

    fun sendCodecConfig(sps: ByteArray, pps: ByteArray, width: Int, height: Int) {
        val payload = ByteBuffer.allocate(6 + 2 + sps.size + 1 + 2 + pps.size).apply {
            put(1); put(sps.getOrElse(1) { 0 }); put(sps.getOrElse(2) { 0 }); put(sps.getOrElse(3) { 0 })
            put(0xFF.toByte()); put(0xE1.toByte())
            putShort(sps.size.toShort()); put(sps)
            put(1); putShort(pps.size.toShort()); put(pps)
        }.array()
        writeMirror(MirrorChannel.TYPE_CODEC, payload, 0, width, height)
    }

    /** Sends one access unit given as a list of raw NAL units (no start codes). */
    fun sendFrame(nals: List<ByteArray>, ntpTimestamp: Long) {
        val out = ByteArrayOutputStream()
        for (nal in nals) {
            out.write(ByteBuffer.allocate(4).putInt(nal.size).array())
            out.write(nal)
        }
        val payload = out.toByteArray()
        videoCipher!!.crypt(payload)
        writeMirror(MirrorChannel.TYPE_VIDEO, payload, ntpTimestamp, 0, 0)
    }

    private fun writeMirror(type: Int, payload: ByteArray, ts: Long, width: Int, height: Int) {
        val header = ByteBuffer.allocate(MirrorChannel.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0, payload.size)
        header.put(4, type.toByte())
        header.putLong(8, ts)
        if (type == MirrorChannel.TYPE_CODEC) {
            header.putFloat(40, width.toFloat()); header.putFloat(44, height.toFloat())
            header.putFloat(56, width.toFloat()); header.putFloat(60, height.toFloat())
        }
        val os = mirrorSocket!!.getOutputStream()
        os.write(header.array())
        os.write(payload)
        os.flush()
    }

    fun setupAudio(): Int {
        val stream = NSDictionary().apply {
            put("type", AirPlayProfile.STREAM_TYPE_AUDIO)
            put("ct", 8)
            put("audioFormat", 0x1000000)
            put("spf", 480)
        }
        val reply = request("SETUP", "rtsp://$host/$activeRemote",
            BinaryPropertyListWriter.writeToArray(NSDictionary().apply { put("streams", NSArray(stream)) }),
            "application/x-apple-binary-plist")
        val root = PropertyListParser.parse(reply.body) as NSDictionary
        audioPort = (((root["streams"] as NSArray).array[0] as NSDictionary)["dataPort"] as NSNumber).intValue()
        audioSocket = DatagramSocket()
        return audioPort
    }

    /** Sends one RTP packet with FairPlay-style AES-CBC encryption of the payload. */
    fun sendAudio(plain: ByteArray, rtpTimestamp: Long) {
        val sha = MessageDigest.getInstance("SHA-512")
        sha.update(aesKey); sha.update(sharedSecret)
        val key = sha.digest().copyOf(16)
        val enc = plain.copyOf()
        val blocks = enc.size / 16 * 16
        if (blocks > 0) {
            val cbc = Cipher.getInstance("AES/CBC/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(eiv))
            }
            cbc.doFinal(enc, 0, blocks, enc, 0)
        }
        val packet = ByteBuffer.allocate(12 + enc.size).apply {
            put(0x80.toByte()); put(0x60.toByte()); putShort((audioSeq++ and 0xFFFF).toShort())
            putInt(rtpTimestamp.toInt()); putInt(0x1234)
            put(enc)
        }.array()
        audioSocket!!.send(DatagramPacket(packet, packet.size, InetSocketAddress(host, audioPort)))
    }

    fun feedback() = request("POST", "/feedback").status

    fun teardown() = runCatching { request("TEARDOWN", "rtsp://$host/$activeRemote").status }.getOrDefault(-1)

    override fun close() {
        runCatching { mirrorSocket?.close() }
        runCatching { audioSocket?.close() }
        runCatching { control.close() }
    }

    companion object {
        const val SELF_TEST_NAME = "Self-test (bukan iPhone)"

        /** Public FairPlay test vectors (serezhka/java-airplay, MIT). */
        val FIXTURE_EKEY = byteArrayOf(
            70, 80, 76, 89, 1, 2, 1, 0, 0, 0, 0, 60, 0, 0, 0, 0, 63, 121, 70, -69, 3, -8, 117, -13, 83, 72,
            105, -51, -11, -43, -1, 17, 0, 0, 0, 16, 24, -109, 13, 105, -32, -125, -73, -128, 21, 29, -31, 72,
            -41, 112, -36, -75, 57, 110, 71, -72, -25, -59, 102, 22, 19, -43, 35, 74, -20, 86, 15, 16, 126, 5,
            15, -45,
        )
        val FP_SETUP_1 = byteArrayOf(70, 80, 76, 89, 3, 1, 1, 0, 0, 0, 0, 4, 2, 0, 0, -69)
        val FP_SETUP_2 = byteArrayOf(
            70, 80, 76, 89, 3, 1, 3, 0, 0, 0, 0, -104, 0, -113, 26, -100, -40, -92, -10, 52, 109, 20, 120, 6,
            -62, -67, -118, 75, -47, -71, -109, -45, -61, 106, -95, 1, 36, -104, -7, 78, -1, -13, 70, 123,
            -49, 27, 49, -104, 98, 92, -94, 69, -114, 62, -48, 30, -35, 53, -25, 41, 53, 125, -7, 75, -128,
            -51, 10, -50, 35, 84, -42, -116, -29, 127, 94, 24, -16, -49, -46, 109, 65, 103, 21, 63, -64, -76,
            54, 35, 22, 111, 8, -58, 111, -45, 1, 56, 14, -80, -98, -97, -115, -24, 59, -46, -82, -57, -92,
            1, -15, -5, -67, -13, 46, 10, -43, 81, -24, 121, 63, -25, -63, 25, 35, 51, -103, -91, 53, 76,
            -59, 67, 7, 30, -68, -50, -32, -84, -123, 34, -82, 27, -85, 51, -44, 65, -60, 120, -11, 99, -50,
            -3, 66, 117, -5, 85, 90, 58, -29, 58, -40, -71, -7, -108, -7, -75,
        )

        fun unwrapAesKey(ekey: ByteArray): ByteArray {
            val local = FairPlayHandshake()
            local.fairPlaySetup(ByteArrayInputStream(FP_SETUP_1), ByteArrayOutputStream())
            local.fairPlaySetup(ByteArrayInputStream(FP_SETUP_2), ByteArrayOutputStream())
            return local.decryptAesKey(ekey)
        }
    }
}
