package com.github.serezhka.airplay.protocol.internal;

/**
 * Minimal logging shim so the protocol module stays free of SLF4J/Lombok and
 * Android-agnostic. The Android layer installs a sink that forwards to Logcat.
 */
public final class ProtoLog {

    public interface Sink {
        void log(int level, String tag, String message, Throwable error);
    }

    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;

    private static volatile Sink sink = null;
    private static volatile boolean debugEnabled = false;

    private ProtoLog() {
    }

    public static void install(Sink newSink, boolean debug) {
        sink = newSink;
        debugEnabled = debug;
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void d(String tag, String message) {
        if (debugEnabled) emit(DEBUG, tag, message, null);
    }

    public static void i(String tag, String message) {
        emit(INFO, tag, message, null);
    }

    public static void w(String tag, String message) {
        emit(WARN, tag, message, null);
    }

    public static void e(String tag, String message) {
        emit(ERROR, tag, message, null);
    }

    public static void e(String tag, String message, Throwable error) {
        emit(ERROR, tag, message, error);
    }

    private static void emit(int level, String tag, String message, Throwable error) {
        Sink s = sink;
        if (s != null) {
            s.log(level, tag, message, error);
        }
    }

    public static String hex(byte[] bytes) {
        if (bytes == null) return "null";
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(digits[(b >> 4) & 0xF]).append(digits[b & 0xF]);
        }
        return sb.toString();
    }
}
