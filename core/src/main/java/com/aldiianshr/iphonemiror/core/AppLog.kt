package com.aldiianshr.iphonemiror.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * App-wide logger. Every line goes to Logcat and to an in-memory ring buffer that the
 * Settings → Diagnostics screen shows, so problems on a real Xiaomi Pad 6 can be read
 * (and shared) without a computer attached.
 */
object AppLog {

    enum class Level(val short: String) { DEBUG("D"), INFO("I"), WARN("W"), ERROR("E") }

    data class Entry(val timeMillis: Long, val level: Level, val tag: String, val message: String) {
        fun format(): String = "${TIME.get()!!.format(Date(timeMillis))} ${level.short}/$tag: $message"
    }

    private const val MAX_ENTRIES = 600
    private val TIME = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    private val buffer = ArrayDeque<Entry>(MAX_ENTRIES)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    @Volatile var debugEnabled: Boolean = false

    /** Set to false in plain JVM unit tests where android.util.Log is not available. */
    @Volatile var logcatEnabled: Boolean = true

    fun d(tag: String, message: String) {
        if (debugEnabled) add(Level.DEBUG, tag, message, null)
    }

    fun i(tag: String, message: String) = add(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = add(Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = add(Level.ERROR, tag, message, error)

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        _entries.value = emptyList()
    }

    fun dump(): String = entries.value.joinToString("\n") { it.format() }

    private fun add(level: Level, tag: String, message: String, error: Throwable?) {
        val text = if (error != null) "$message — ${error.javaClass.simpleName}: ${error.message}" else message
        if (logcatEnabled) {
            runCatching {
                when (level) {
                    Level.DEBUG -> Log.d(tag, text, error)
                    Level.INFO -> Log.i(tag, text, error)
                    Level.WARN -> Log.w(tag, text, error)
                    Level.ERROR -> Log.e(tag, text, error)
                }
            }
        }
        val snapshot = synchronized(buffer) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(Entry(System.currentTimeMillis(), level, tag, text))
            buffer.toList()
        }
        _entries.value = snapshot
    }
}
