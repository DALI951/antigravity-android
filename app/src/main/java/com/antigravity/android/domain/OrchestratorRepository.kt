package com.antigravity.android.domain

import com.antigravity.android.data.model.AgentInstance
import com.antigravity.android.data.model.AgentNode
import com.antigravity.android.data.model.AgentRole
import com.antigravity.android.data.model.AgentStatus
import com.antigravity.android.data.model.CascadeTrajectory
import com.antigravity.android.data.model.LogEntry
import com.antigravity.android.data.model.LogLevel
import com.antigravity.android.data.model.SessionDetailState
import com.antigravity.android.data.model.SessionStatus
import com.antigravity.android.data.model.SessionSummary
import com.antigravity.android.data.model.Step
import com.antigravity.android.data.model.StepType
import com.antigravity.android.data.network.ConnectWebSocketClient
import com.antigravity.android.data.network.ConnectionStatus
import com.antigravity.android.data.network.OrchestratorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface OrchestratorRepository {
    fun observeSessions(): Flow<List<SessionSummary>>
    fun observeActiveSession(sessionId: String): Flow<SessionDetailState>
    fun observeAgentSwarm(sessionId: String): Flow<List<AgentNode>>
    fun observeExecutionLogs(sessionId: String): Flow<LogEntry>

    suspend fun startSession(prompt: String): Result<String>
    suspend fun sendUserMessage(sessionId: String, message: String): Result<Unit>
    suspend fun cancelSession(sessionId: String): Result<Unit>
    suspend fun acknowledgeEdit(sessionId: String, editId: String, approved: Boolean): Result<Unit>
    suspend fun acknowledgeAction(sessionId: String, approved: Boolean, feedback: String = ""): Result<Unit>

    val connectionState: StateFlow<ConnectionStatus>
    fun connect()
    fun disconnect()
    fun updateEndpoint(url: String, csrfToken: String)
    fun setAuthHeaderProvider(provider: () -> Map<String, String>) {}
    fun stopObservingSession(sessionId: String) {}
}

class OrchestratorRepositoryImpl(
    private val service: OrchestratorService,
    private val client: ConnectWebSocketClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : OrchestratorRepository {

    override val connectionState: StateFlow<ConnectionStatus> = client.connectionState

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    private val sessionDetailStates = ConcurrentHashMap<String, MutableStateFlow<SessionDetailState>>()
    private val swarmStates = ConcurrentHashMap<String, MutableStateFlow<List<AgentNode>>>()
    private val rawAgentInstances = ConcurrentHashMap<String, MutableMap<String, AgentInstance>>()
    private val logFlows = ConcurrentHashMap<String, MutableSharedFlow<LogEntry>>()

    private val activeStreamJobs = ConcurrentHashMap<String, Job>()
    private val activeSwarmJobs = ConcurrentHashMap<String, Job>()
    private var summariesStreamJob: Job? = null

    init {
        // Observe connection state and refresh summaries when connected
        scope.launch {
            connectionState.collect { status ->
                if (status is ConnectionStatus.Connected) {
                    refreshSummaries()
                    startSummariesStream()
                }
            }
        }
    }

    override fun connect() {
        client.connect()
    }

    override fun disconnect() {
        summariesStreamJob?.cancel()
        summariesStreamJob = null
        activeStreamJobs.values.forEach { it.cancel() }
        activeStreamJobs.clear()
        activeSwarmJobs.values.forEach { it.cancel() }
        activeSwarmJobs.clear()
        client.disconnect()
    }

    override fun updateEndpoint(url: String, csrfToken: String) {
        client.updateEndpoint(url, csrfToken)
    }

    override fun setAuthHeaderProvider(provider: () -> Map<String, String>) {
        client.setAuthHeaderProvider(provider)
    }

    override fun observeSessions(): Flow<List<SessionSummary>> = _sessions.asStateFlow()

    private suspend fun refreshSummaries() {
        val result = service.getAllCascadeSummaries()
        result.onSuccess { list ->
            if (list.isNotEmpty()) {
                _sessions.value = list
            }
        }
    }

    private fun startSummariesStream() {
        summariesStreamJob?.cancel()
        summariesStreamJob = scope.launch {
            service.streamCascadeSummaries()
                .catch { /* ignore network blips */ }
                .collect {
                    refreshSummaries()
                }
        }
    }

    override fun stopObservingSession(sessionId: String) {
        activeStreamJobs.remove(sessionId)?.cancel()
        activeSwarmJobs.remove(sessionId)?.cancel()
    }

    override fun observeActiveSession(sessionId: String): Flow<SessionDetailState> {
        // Cancel stream jobs for other inactive sessions to prevent coroutine leaks
        activeStreamJobs.keys.filter { it != sessionId }.forEach { oldId ->
            activeStreamJobs.remove(oldId)?.cancel()
        }

        val stateFlow = sessionDetailStates.getOrPut(sessionId) {
            val summary = _sessions.value.find { it.id == sessionId }
            MutableStateFlow(
                SessionDetailState(
                    summary = summary,
                    trajectory = CascadeTrajectory(cascadeId = sessionId, status = summary?.status ?: SessionStatus.RUNNING),
                    isLoading = true
                )
            )
        }

        val existingJob = activeStreamJobs[sessionId]
        if (existingJob == null || !existingJob.isActive) {
            val job = scope.launch {
                // Fetch initial trajectory
                val initialTrajectoryResult = service.getCascadeTrajectory(sessionId)
                initialTrajectoryResult.onSuccess { traj ->
                    stateFlow.value = stateFlow.value.copy(
                        trajectory = traj,
                        isLoading = false
                    )
                    // Convert initial steps to logs
                    traj.steps.forEach { step ->
                        emitStepLog(sessionId, step)
                    }
                }.onFailure {
                    stateFlow.value = stateFlow.value.copy(isLoading = false, error = it.message)
                }

                // Stream differential updates
                service.streamReactiveUpdates(sessionId)
                    .catch { e ->
                        stateFlow.value = stateFlow.value.copy(error = e.message)
                    }
                    .collect { diff ->
                        val currentTraj = stateFlow.value.trajectory
                            ?: CascadeTrajectory(cascadeId = sessionId, status = SessionStatus.RUNNING)
                        val prevStepCount = currentTraj.steps.size
                        val updated = StateDiffReducer.reduce(currentTraj, diff)

                        stateFlow.value = stateFlow.value.copy(
                            trajectory = updated,
                            error = null
                        )

                        // Check for new steps or token appends
                        if (updated.steps.size > prevStepCount) {
                            for (i in prevStepCount until updated.steps.size) {
                                emitStepLog(sessionId, updated.steps[i])
                            }
                        } else {
                            diff.fieldDiffs.forEach { fd ->
                                fd.updateRepeated?.tokenAppends?.forEach { (idx, token) ->
                                    if (idx in updated.steps.indices) {
                                        emitTokenStreamLog(sessionId, idx, token)
                                    }
                                }
                            }
                        }
                    }
            }
            activeStreamJobs[sessionId] = job
        }

        return stateFlow.asStateFlow()
    }

    override fun observeAgentSwarm(sessionId: String): Flow<List<AgentNode>> {
        // Cancel swarm stream jobs for other inactive sessions to prevent coroutine leaks
        activeSwarmJobs.keys.filter { it != sessionId }.forEach { oldId ->
            activeSwarmJobs.remove(oldId)?.cancel()
        }

        val swarmFlow = swarmStates.getOrPut(sessionId) {
            MutableStateFlow(createDefaultSwarmTree(sessionId))
        }

        val instanceMap = rawAgentInstances.getOrPut(sessionId) {
            ConcurrentHashMap()
        }

        val existingSwarmJob = activeSwarmJobs[sessionId]
        if (existingSwarmJob == null || !existingSwarmJob.isActive) {
            val job = scope.launch {
                service.streamAgentStateUpdates(sessionId)
                    .catch { /* ignore */ }
                    .collect { instance ->
                        instanceMap[instance.agentId] = instance
                        val tree = buildAgentTree(sessionId, instanceMap.values.toList())
                        swarmFlow.value = tree
                    }
            }
            activeSwarmJobs[sessionId] = job
        }

        return swarmFlow.asStateFlow()
    }

    private fun createDefaultSwarmTree(sessionId: String): List<AgentNode> {
        return listOf(
            AgentNode(
                agentId = "root-$sessionId",
                name = "Orchestrator",
                role = AgentRole.ORCHESTRATOR,
                status = AgentStatus.RUNNING,
                nestingDepth = 0,
                currentTask = "Coordinating task execution"
            )
        )
    }

    private fun buildAgentTree(sessionId: String, instances: List<AgentInstance>): List<AgentNode> {
        if (instances.isEmpty()) return createDefaultSwarmTree(sessionId)

        val rootInstances = instances.filter { it.parentConversationId == null || it.nestingDepth == 0 }
        val nonRootInstances = instances.filter { it !in rootInstances }

        return rootInstances.map { root ->
            toAgentNode(root, nonRootInstances)
        }
    }

    private fun toAgentNode(instance: AgentInstance, pool: List<AgentInstance>): AgentNode {
        val children = pool.filter { it.parentConversationId == instance.agentId }.map { child ->
            toAgentNode(child, pool)
        }
        val name = if (instance.typeName.isNotBlank()) instance.typeName else instance.role.name
        return AgentNode(
            agentId = instance.agentId,
            name = name,
            role = instance.role,
            status = instance.status,
            nestingDepth = instance.nestingDepth,
            parentAgentId = instance.parentConversationId,
            children = children,
            currentTask = instance.assignedTaskId ?: ""
        )
    }

    override fun observeExecutionLogs(sessionId: String): Flow<LogEntry> {
        val logFlow = logFlows.getOrPut(sessionId) {
            MutableSharedFlow(replay = 200, extraBufferCapacity = 1000)
        }
        return logFlow.asSharedFlow()
    }

    private fun emitStepLog(sessionId: String, step: Step) {
        val flow = logFlows.getOrPut(sessionId) {
            MutableSharedFlow(replay = 200, extraBufferCapacity = 1000)
        }
        val (level, message) = when (step.type) {
            StepType.USER_MESSAGE -> LogLevel.INFO to "User: ${step.content}"
            StepType.TOOL_CALL -> LogLevel.DEBUG to "Tool Invocation [${step.toolCall?.toolName}]: ${step.toolCall?.argumentsJson}"
            StepType.TOOL_RESULT -> (if (step.toolResult?.isError == true) LogLevel.ERROR else LogLevel.INFO) to "Tool Output: ${step.toolResult?.output}"
            StepType.STATUS_UPDATE -> LogLevel.INFO to "Status: ${step.status}"
            StepType.SYSTEM_NOTICE -> LogLevel.WARN to "Notice: ${step.content}"
            StepType.MODEL_TURN -> {
                val thinking = if (step.modelTurn?.thinkingBlock?.isNotBlank() == true) " [Thinking: ${step.modelTurn.thinkingBlock}]" else ""
                LogLevel.INFO to "Assistant: ${step.content}$thinking"
            }
        }
        flow.tryEmit(
            LogEntry(
                level = level,
                tag = step.role.replaceFirstChar { it.uppercase() },
                message = message,
                stepIndex = step.stepIndex
            )
        )
    }

    private fun emitTokenStreamLog(sessionId: String, stepIndex: Int, token: String) {
        val flow = logFlows.getOrPut(sessionId) {
            MutableSharedFlow(replay = 200, extraBufferCapacity = 1000)
        }
        flow.tryEmit(
            LogEntry(
                level = LogLevel.VERBOSE,
                tag = "TokenStream",
                message = token,
                tokenStream = true,
                stepIndex = stepIndex
            )
        )
    }

    override suspend fun startSession(prompt: String): Result<String> {
        val result = service.startCascade(initialPrompt = prompt)
        result.onSuccess { cascadeId ->
            val newSummary = SessionSummary(
                id = cascadeId,
                title = if (prompt.length > 50) prompt.take(47) + "..." else prompt,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                status = SessionStatus.RUNNING
            )
            _sessions.value = listOf(newSummary) + _sessions.value
        }
        return result
    }

    override suspend fun sendUserMessage(sessionId: String, message: String): Result<Unit> {
        return service.sendUserCascadeMessage(sessionId, message)
    }

    override suspend fun cancelSession(sessionId: String): Result<Unit> {
        val result = service.forceStopCascadeTree(sessionId)
        result.onSuccess {
            val current = _sessions.value.toMutableList()
            val idx = current.indexOfFirst { it.id == sessionId }
            if (idx != -1) {
                current[idx] = current[idx].copy(status = SessionStatus.CANCELLED)
                _sessions.value = current
            }
            sessionDetailStates[sessionId]?.let { stateFlow ->
                val prevTraj = stateFlow.value.trajectory
                stateFlow.value = stateFlow.value.copy(
                    trajectory = prevTraj?.copy(status = SessionStatus.CANCELLED)
                )
            }
        }
        return result
    }

    override suspend fun acknowledgeEdit(sessionId: String, editId: String, approved: Boolean): Result<Unit> {
        return service.acknowledgeCascadeCodeEdit(sessionId, fileUri = editId, contents = "", accept = approved)
    }

    override suspend fun acknowledgeAction(sessionId: String, approved: Boolean, feedback: String): Result<Unit> {
        return service.acknowledgeCodeActionStep(sessionId, accept = approved, writtenFeedback = feedback)
    }
}
