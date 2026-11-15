package com.antigravity.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val level: String, // INFO, WARN, ERROR, TOOL
    val tag: String,
    val message: String
)

class CircularLogBuffer(val capacity: Int = 2000) {
    private val buffer = ArrayDeque<LogEntry>(capacity)
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun append(entry: LogEntry) {
        if (buffer.size >= capacity) {
            buffer.removeFirst()
        }
        buffer.addLast(entry)
    }

    @Synchronized
    fun getAll(): List<LogEntry> = buffer.toList()

    @Synchronized
    fun size(): Int = buffer.size

    @Synchronized
    fun clear() {
        buffer.clear()
    }

    @Synchronized
    fun filter(level: String? = null, query: String? = null): List<LogEntry> {
        return buffer.filter { entry ->
            val matchesLevel = level == null || level == "ALL" || entry.level.equals(level, ignoreCase = true)
            val matchesQuery = query.isNullOrBlank() ||
                    entry.message.contains(query, ignoreCase = true) ||
                    entry.tag.contains(query, ignoreCase = true)
            matchesLevel && matchesQuery
        }
    }

    fun formatMonospace(entry: LogEntry): String {
        val timeStr = dateFormat.format(Date(entry.timestamp))
        val paddedLevel = entry.level.padEnd(5)
        return "[$timeStr] [$paddedLevel] [${entry.tag}] ${entry.message}"
    }
}

class LogBufferTest {

    // ==========================================
    // Tier 1: Happy Path Log Buffer Tests (F22)
    // ==========================================

    @Test
    fun testLogBuffer_appendsAndRetrievesEntries() {
        val logBuffer = CircularLogBuffer(capacity = 10)
        logBuffer.append(LogEntry(level = "INFO", tag = "Orchestrator", message = "Session started"))
        logBuffer.append(LogEntry(level = "TOOL", tag = "CodeiumTool", message = "Invoked write_file"))

        assertEquals(2, logBuffer.size())
        assertEquals("Session started", logBuffer.getAll()[0].message)
        assertEquals("TOOL", logBuffer.getAll()[1].level)
    }

    @Test
    fun testLogBuffer_capsAtCapacityAndEvictsOldest() {
        val buffer = CircularLogBuffer(capacity = 5)
        for (i in 1..10) {
            buffer.append(LogEntry(level = "INFO", tag = "Test", message = "Log entry $i"))
        }

        assertEquals(5, buffer.size())
        assertEquals("Log entry 6", buffer.getAll().first().message)
        assertEquals("Log entry 10", buffer.getAll().last().message)
    }

    @Test
    fun testFilterByLevel() {
        val buffer = CircularLogBuffer()
        buffer.append(LogEntry(level = "INFO", tag = "T", message = "info msg"))
        buffer.append(LogEntry(level = "WARN", tag = "T", message = "warn msg"))
        buffer.append(LogEntry(level = "ERROR", tag = "T", message = "error msg"))
        buffer.append(LogEntry(level = "TOOL", tag = "T", message = "tool msg"))

        val errors = buffer.filter(level = "ERROR")
        assertEquals(1, errors.size)
        assertEquals("error msg", errors[0].message)

        val all = buffer.filter(level = "ALL")
        assertEquals(4, all.size)
    }

    @Test
    fun testFilterBySearchQuery() {
        val buffer = CircularLogBuffer()
        buffer.append(LogEntry(level = "INFO", tag = "ConnectRPC", message = "WebSocket connected to 127.0.0.1:49784"))
        buffer.append(LogEntry(level = "INFO", tag = "Updater", message = "Update check manifest 200 OK"))
        buffer.append(LogEntry(level = "ERROR", tag = "ConnectRPC", message = "Keepalive ping timeout"))

        val rpcLogs = buffer.filter(query = "connectrpc")
        assertEquals(2, rpcLogs.size)

        val manifestLogs = buffer.filter(query = "manifest")
        assertEquals(1, manifestLogs.size)
    }

    @Test
    fun testMonospaceLogFormatting() {
        val buffer = CircularLogBuffer()
        val entry = LogEntry(timestamp = 1700000000000L, level = "INFO", tag = "Swarm", message = "Ready")
        val formatted = buffer.formatMonospace(entry)

        assertTrue("Must contain timestamp brackets", formatted.startsWith("["))
        assertTrue("Must contain padded level", formatted.contains("[INFO ]"))
        assertTrue("Must contain tag", formatted.contains("[Swarm]"))
        assertTrue("Must contain message", formatted.endsWith("Ready"))
    }

    // ==========================================
    // Tier 2: Boundary, Corner & Error Cases
    // ==========================================

    @Test
    fun test50000LogsStream_stressTestWithoutOOM() {
        val buffer = CircularLogBuffer(capacity = 2000)
        val startTime = System.currentTimeMillis()

        for (i in 1..50000) {
            buffer.append(LogEntry(level = "INFO", tag = "Stress", message = "Stream entry $i"))
        }

        val elapsed = System.currentTimeMillis() - startTime
        assertEquals(2000, buffer.size())
        assertEquals("Stream entry 48001", buffer.getAll().first().message)
        assertEquals("Stream entry 50000", buffer.getAll().last().message)
        assertTrue("Appending 50,000 logs should take under 3000ms (took ${elapsed}ms)", elapsed < 3000)
    }

    @Test
    fun testLargeMessage_10000CharsRetained() {
        val buffer = CircularLogBuffer()
        val bigMessage = "A".repeat(10000)
        buffer.append(LogEntry(level = "TOOL", tag = "Diff", message = bigMessage))

        assertEquals(1, buffer.size())
        assertEquals(10000, buffer.getAll()[0].message.length)
    }

    @Test
    fun testSearchQueryWithRegexSpecialChars_doesNotThrow() {
        val buffer = CircularLogBuffer()
        buffer.append(LogEntry(level = "INFO", tag = "Test", message = "Special regex chars .*+?^$\\{}()|[]/"))

        // Should perform literal substring match without throwing PatternSyntaxException
        val results = buffer.filter(query = ".*+?^$\\{}()|[]/")
        assertEquals(1, results.size)
    }

    @Test
    fun testEmptySearchQuery_returnsAll() {
        val buffer = CircularLogBuffer()
        buffer.append(LogEntry(level = "INFO", tag = "T", message = "msg1"))
        buffer.append(LogEntry(level = "WARN", tag = "T", message = "msg2"))

        assertEquals(2, buffer.filter(query = "").size)
        assertEquals(2, buffer.filter(query = "   ").size)
    }
}
