package com.antigravity.android.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class AgentRole {
    ORCHESTRATOR,
    SUB_ORCHESTRATOR,
    WORKER,
    SPEC_MINER,
    EXPLORER,
    QA,
    AUDITOR,
    CHALLENGER,
    REVIEWER,
    OTHER
}

@Serializable
enum class AgentStatus {
    SPAWNING,
    IDLE,
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Serializable
data class SubagentSpec(
    val typeName: String = "",
    val role: String = "",
    val initialPrompt: String = "",
    val inherit: Boolean = true,
    val model: String = "",
    val modelOption: String = ""
)

@Serializable
data class AgentInstance(
    val agentId: String,
    val role: AgentRole = AgentRole.WORKER,
    val typeName: String = "",
    val status: AgentStatus = AgentStatus.RUNNING,
    val parentConversationId: String? = null,
    val rootConversationId: String? = null,
    val nestingDepth: Int = 0,
    val subagentSpec: SubagentSpec? = null,
    val assignedTaskId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class AgentNode(
    val agentId: String,
    val name: String = "",
    val role: AgentRole = AgentRole.WORKER,
    val status: AgentStatus = AgentStatus.RUNNING,
    val nestingDepth: Int = 0,
    val parentAgentId: String? = null,
    val children: List<AgentNode> = emptyList(),
    val currentTask: String = ""
)

val AgentNode.isRoot: Boolean
    get() = parentAgentId == null || nestingDepth == 0

fun AgentNode.findNode(id: String): AgentNode? {
    if (agentId == id) return this
    for (child in children) {
        val found = child.findNode(id)
        if (found != null) return found
    }
    return null
}

fun AgentNode.countActiveAgents(): Int {
    var count = if (status == AgentStatus.RUNNING) 1 else 0
    for (child in children) {
        count += child.countActiveAgents()
    }
    return count
}

fun AgentNode.cancelAll(): AgentNode {
    return this.copy(
        status = AgentStatus.CANCELLED,
        children = children.map { it.cancelAll() }
    )
}
