package com.example.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

data class LogEntry(
    val id: Long,
    val timestamp: String,
    val level: String,
    val tag: String,
    val message: String
) {
    override fun toString(): String {
        return "[$timestamp] [$level/$tag] $message"
    }
}

object AppLogger {
    private const val MAX_LOGS = 200
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var idCounter = 0L

    private val logBuffer = LinkedList<LogEntry>()
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    @Synchronized
    fun log(level: String, tag: String, message: String) {
        val entry = LogEntry(
            id = ++idCounter,
            timestamp = timeFormat.format(Date()),
            level = level,
            tag = tag,
            message = message
        )
        if (logBuffer.size >= MAX_LOGS) {
            logBuffer.removeFirst()
        }
        logBuffer.addLast(entry)
        _logsFlow.value = logBuffer.toList()

        try {
            when (level) {
                "DEBUG" -> Log.d(tag, message)
                "INFO" -> Log.i(tag, message)
                "WARN" -> Log.w(tag, message)
                "ERROR" -> Log.e(tag, message)
                else -> Log.i(tag, message)
            }
        } catch (_: Throwable) {
            // Ignored when running standard JVM unit tests without android.util.Log mock
        }
    }

    fun d(tag: String, message: String) = log("DEBUG", tag, message)
    fun i(tag: String, message: String) = log("INFO", tag, message)
    fun w(tag: String, message: String) = log("WARN", tag, message)
    fun e(tag: String, message: String) = log("ERROR", tag, message)

    @Synchronized
    fun clear() {
        logBuffer.clear()
        _logsFlow.value = emptyList()
    }

    @Synchronized
    fun getAllLogsAsText(): String {
        return logBuffer.joinToString("\n") { it.toString() }
    }
}
