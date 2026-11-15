package com.antigravity.android.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}

@Serializable
data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel = LogLevel.INFO,
    val tag: String = "Orchestrator",
    val message: String,
    val tokenStream: Boolean = false,
    val stepIndex: Int? = null,
    val agentId: String? = null
)
