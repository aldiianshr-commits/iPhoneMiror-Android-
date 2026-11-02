package com.github.serezhka.airplay.protocol.rtsp;

import com.github.serezhka.airplay.protocol.internal.ProtoLog;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.NSDictionary;
import com.github.serezhka.airplay.protocol.media.AirPlayStreamType;
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.MediaStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Optional;

public class RtspMediaSetup {

    private static final String TAG = "RtspSetup";

    private byte[] ekey;
    private byte[] eiv;

    private String streamConnectionID;

    public Optional<MediaStreamInfo> setup(InputStream rtspSetupPayload) throws Exception {
        var setup = (NSDictionary) BinaryPropertyListParser.parse(rtspSetupPayload);
        if (setup.containsKey("ekey") || setup.containsKey("eiv")) {
            ekey = (byte[]) setup.get("ekey").toJavaObject();
            eiv = (byte[]) setup.get("eiv").toJavaObject();
            if (ProtoLog.isDebugEnabled()) {
                ProtoLog.d(TAG, "Encrypted AES key: " + String.valueOf(ProtoLog.hex(ekey)) + ", iv: " + String.valueOf(ProtoLog.hex(eiv)) + "");
            }
            return Optional.empty();
        } else if (setup.containsKey("streams")) {
            if (ProtoLog.isDebugEnabled()) {
                ProtoLog.d(TAG, "RTSP SETUP streams:\n" + String.valueOf(setup.toXMLPropertyList()) + "");
            }
            return Optional.ofNullable(getMediaStreamInfo(setup));
        } else {
            if (ProtoLog.isDebugEnabled()) {
                ProtoLog.e(TAG, "Unknown RTSP setup content\n" + String.valueOf(setup.toXMLPropertyList()) + "");
            } else {
                ProtoLog.e(TAG, "Unknown RTSP setup content");
            }
            return Optional.empty();
        }
    }

    public Optional<MediaStreamInfo> teardown(InputStream rtspTeardownPayload) throws Exception {
        var teardown = (NSDictionary) BinaryPropertyListParser.parse(rtspTeardownPayload);
        if (ProtoLog.isDebugEnabled()) {
            ProtoLog.d(TAG, "RTSP TEARDOWN streams:\n" + String.valueOf(teardown.toXMLPropertyList()) + "");
        }
        if (teardown.containsKey("streams")) {
            return Optional.ofNullable(getMediaStreamInfo(teardown));
        }
        return Optional.empty();
    }

    private MediaStreamInfo getMediaStreamInfo(NSDictionary request) {
        var streams = ((Object[]) request.get("streams").toJavaObject());
        if (streams.length > 1) {
            ProtoLog.w(TAG, "Request contains more than one stream info");
        }

        //noinspection rawtypes
        HashMap stream = (HashMap) streams[0];
        int type = (int) stream.get("type");
        switch (type) {

            // video stream (screen mirroring)
            case AirPlayStreamType.SCREEN -> {
                if (stream.containsKey("streamConnectionID")) {
                    Object rawId = stream.get("streamConnectionID");
                    long id = rawId instanceof Number n ? n.longValue() : Long.parseLong(rawId.toString());
                    streamConnectionID = Long.toUnsignedString(id);
                }
                return new VideoStreamInfo(streamConnectionID);
            }

            // realtime audio
            case AirPlayStreamType.AUDIO_REALTIME -> {
                AudioStreamInfo.AudioStreamInfoBuilder builder = new AudioStreamInfo.AudioStreamInfoBuilder();
                if (stream.containsKey("ct")) {
                    int compressionType = (int) stream.get("ct");
                    builder.compressionType(AudioStreamInfo.CompressionType.fromCode(compressionType));
                }
                if (stream.containsKey("audioFormat")) {
                    long audioFormatCode = (int) stream.get("audioFormat"); // int or long ?!
                    builder.audioFormat(AudioStreamInfo.AudioFormat.fromCode(audioFormatCode));
                }
                if (stream.containsKey("spf")) {
                    int samplesPerFrame = (int) stream.get("spf");
                    builder.samplesPerFrame(samplesPerFrame);
                }
                return builder.build();
            }

            default -> {
                ProtoLog.e(TAG, "Unknown stream type: " + String.valueOf(type) + "");
                return null;
            }
        }
    }

    public String getStreamConnectionID() {
        return streamConnectionID;
    }

    public byte[] getEkey() {
        return ekey;
    }

    public byte[] getEiv() {
        return eiv;
    }
}
