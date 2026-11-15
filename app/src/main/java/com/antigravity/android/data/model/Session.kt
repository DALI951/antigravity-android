package com.antigravity.android.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class SessionStatus {
    IDLE,
    RUNNING,
    PAUSED,
    WAITING_APPROVAL,
    COMPLETED,
    CANCELLED,
    ERROR
}

@Serializable
enum class StepType {
    USER_MESSAGE,
    MODEL_TURN,
    TOOL_CALL,
    TOOL_RESULT,
    STATUS_UPDATE,
    SYSTEM_NOTICE
}

@Serializable
data class SessionSummary(
    val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val status: SessionStatus = SessionStatus.RUNNING,
    val tags: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val unreadCount: Int = 0
)

@Serializable
data class ToolCall(
    val id: String,
    val toolName: String,
    val argumentsJson: String = "{}"
)

@Serializable
data class ToolResult(
    val toolCallId: String,
    val output: String = "",
    val isError: Boolean = false
)

@Serializable
data class ModelTurn(
    val textTokens: String = "",
    val thinkingBlock: String = "",
    val toolCalls: List<ToolCall> = emptyList()
)

@Serializable
data class Step(
    val stepIndex: Int,
    val type: StepType = StepType.MODEL_TURN,
    val role: String = "assistant",
    val content: String = "",
    val modelTurn: ModelTurn? = null,
    val toolCall: ToolCall? = null,
    val toolResult: ToolResult? = null,
    val status: String = "COMPLETED",
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class GeneratorMetadata(
    val model: String = "",
    val thinkingLevel: Int = 0,
    val totalInputTokens: Long = 0L,
    val totalOutputTokens: Long = 0L
)

@Serializable
data class CascadeTrajectory(
    val cascadeId: String,
    val status: SessionStatus = SessionStatus.RUNNING,
    val title: String = "",
    val steps: List<Step> = emptyList(),
    val generatorMetadata: GeneratorMetadata? = null,
    val numTotalSteps: Int = 0,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class SessionDetailState(
    val summary: SessionSummary? = null,
    val trajectory: CascadeTrajectory? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)
