package com.antigravity.android.network

import com.antigravity.android.data.model.AgentInstance
import com.antigravity.android.data.model.AgentRole
import com.antigravity.android.data.model.AgentStatus
import com.antigravity.android.data.model.CascadeTrajectory
import com.antigravity.android.data.model.FieldDiff
import com.antigravity.android.data.model.LogEntry
import com.antigravity.android.data.model.LogLevel
import com.antigravity.android.data.model.MessageDiff
import com.antigravity.android.data.model.RepeatedDiff
import com.antigravity.android.data.model.SessionStatus
import com.antigravity.android.data.model.SessionSummary
import com.antigravity.android.data.model.SingularValue
import com.antigravity.android.data.model.Step
import com.antigravity.android.data.model.StepType
import com.antigravity.android.data.network.ConnectWebSocketClient
import com.antigravity.android.data.network.ConnectionStatus
import com.antigravity.android.data.network.OrchestratorService
import com.antigravity.android.domain.OrchestratorRepositoryImpl
import com.antigravity.android.domain.StateDiffReducer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorRepositoryTest {

    private lateinit var fakeService: FakeOrchestratorService
    private lateinit var fakeClient: FakeConnectWebSocketClient

    @Before
    fun setup() {
        fakeService = FakeOrchestratorService()
        fakeClient = FakeConnectWebSocketClient()
    }

    private fun createRepository(scope: CoroutineScope): OrchestratorRepositoryImpl {
        return OrchestratorRepositoryImpl(
            service = fakeService,
            client = fakeClient,
            scope = scope
        )
    }

    @Test
    fun testStartSession_addsNewSessionToFlow() = runTest {
        val repository = createRepository(backgroundScope)
        val result = repository.startSession("Refactor network layer")
        assertTrue(result.isSuccess)
        val cascadeId = result.getOrThrow()

        val sessions = repository.observeSessions().first()
        assertEquals(1, sessions.size)
        assertEquals(cascadeId, sessions[0].id)
        assertEquals("Refactor network layer", sessions[0].title)
        assertEquals(SessionStatus.RUNNING, sessions[0].status)
        repository.disconnect()
    }

    @Test
    fun testObserveActiveSession_receivesTrajectoryAndReducesDiffs() = runTest {
        val repository = createRepository(backgroundScope)
        val cascadeId = "cascade-test-123"
        fakeService.initialTrajectory = CascadeTrajectory(
            cascadeId = cascadeId,
            status = SessionStatus.RUNNING,
            title = "Initial Task",
            steps = listOf(
                Step(stepIndex = 0, type = StepType.USER_MESSAGE, role = "user", content = "Start task")
            )
        )

        val flow = repository.observeActiveSession(cascadeId)
        testScheduler.advanceUntilIdle()

        val currentDetail = flow.filter { !it.isLoading }.first()
        assertNotNull(currentDetail.trajectory)
        assertEquals(1, currentDetail.trajectory?.steps?.size)
        assertEquals("Initial Task", currentDetail.trajectory?.title)

        // Emit diff
        val diff = MessageDiff(
            fieldDiffs = listOf(
                FieldDiff(
                    fieldNumber = StateDiffReducer.FIELD_TITLE,
                    updateSingular = SingularValue(stringVal = "Title Updated via Diff")
                ),
                FieldDiff(
                    fieldNumber = StateDiffReducer.FIELD_STEPS,
                    updateRepeated = RepeatedDiff(
                        updateSteps = listOf(
                            Step(stepIndex = 1, type = StepType.MODEL_TURN, role = "assistant", content = "Step 1 generated")
                        )
                    )
                )
            )
        )
        fakeService.reactiveFlow.emit(diff)
        testScheduler.advanceUntilIdle()

        val updatedDetail = flow.filter { it.trajectory?.steps?.size == 2 }.first()
        assertEquals("Title Updated via Diff", updatedDetail.trajectory?.title)
        assertEquals(2, updatedDetail.trajectory?.steps?.size)
        assertEquals("Step 1 generated", updatedDetail.trajectory?.steps?.get(1)?.content)
        repository.disconnect()
    }

    @Test
    fun testObserveAgentSwarm_buildsHierarchy() = runTest {
        val repository = createRepository(backgroundScope)
        val cascadeId = "swarm-test-1"
        val swarmFlow = repository.observeAgentSwarm(cascadeId)
        testScheduler.advanceUntilIdle()

        val initialSwarm = swarmFlow.first()
        assertEquals(1, initialSwarm.size)
        assertEquals(AgentRole.ORCHESTRATOR, initialSwarm[0].role)

        // Emit subagents
        val rootInstance = AgentInstance(
            agentId = "agent-root",
            role = AgentRole.ORCHESTRATOR,
            typeName = "MainOrchestrator",
            status = AgentStatus.RUNNING,
            nestingDepth = 0
        )
        val workerInstance = AgentInstance(
            agentId = "agent-worker-1",
            role = AgentRole.WORKER,
            typeName = "CodeWorker",
            status = AgentStatus.RUNNING,
            parentConversationId = "agent-root",
            nestingDepth = 1,
            assignedTaskId = "Implement backend"
        )

        fakeService.agentStateFlow.emit(rootInstance)
        fakeService.agentStateFlow.emit(workerInstance)
        testScheduler.advanceUntilIdle()

        val updatedSwarm = swarmFlow.filter { list -> list.any { it.agentId == "agent-root" } }.first()
        assertEquals(1, updatedSwarm.size)
        val root = updatedSwarm[0]
        assertEquals("agent-root", root.agentId)
        assertEquals(1, root.children.size)
        assertEquals("agent-worker-1", root.children[0].agentId)
        assertEquals("Implement backend", root.children[0].currentTask)
        repository.disconnect()
    }

    @Test
    fun testCancelSession_marksStatusCancelled() = runTest {
        val repository = createRepository(backgroundScope)
        val startResult = repository.startSession("Task to be cancelled")
        val sessionId = startResult.getOrThrow()
        testScheduler.advanceUntilIdle()

        val cancelResult = repository.cancelSession(sessionId)
        assertTrue(cancelResult.isSuccess)
        testScheduler.advanceUntilIdle()

        val sessions = repository.observeSessions().first()
        val session = sessions.find { it.id == sessionId }
        assertNotNull(session)
        assertEquals(SessionStatus.CANCELLED, session?.status)
        repository.disconnect()
    }

    @Test
    fun testAcknowledgeActionsAndEdits() = runTest {
        val repository = createRepository(backgroundScope)
        val editResult = repository.acknowledgeEdit("cascade-1", "file://src/Main.kt", approved = true)
        assertTrue(editResult.isSuccess)
        assertEquals(1, fakeService.acknowledgedEdits.size)
        assertEquals("file://src/Main.kt", fakeService.acknowledgedEdits[0].first)
        assertTrue(fakeService.acknowledgedEdits[0].second)

        val actionResult = repository.acknowledgeAction("cascade-1", approved = false, feedback = "Rejecting step")
        assertTrue(actionResult.isSuccess)
        assertEquals(1, fakeService.acknowledgedActions.size)
        assertFalse(fakeService.acknowledgedActions[0].first)
        assertEquals("Rejecting step", fakeService.acknowledgedActions[0].second)
        repository.disconnect()
    }

    @Test
    fun testExecutionLogsEmission() = runTest {
        val repository = createRepository(backgroundScope)
        val sessionId = "session-logs-test"
        fakeService.initialTrajectory = CascadeTrajectory(
            cascadeId = sessionId,
            steps = listOf(
                Step(stepIndex = 0, type = StepType.USER_MESSAGE, role = "user", content = "Hello Logs"),
                Step(stepIndex = 1, type = StepType.STATUS_UPDATE, role = "system", status = "PLANNING")
            )
        )

        val logFlow = repository.observeExecutionLogs(sessionId)
        repository.observeActiveSession(sessionId)
        testScheduler.advanceUntilIdle()

        val firstLog = logFlow.first()
        assertNotNull(firstLog)
        assertTrue(firstLog.message.contains("User: Hello Logs") || firstLog.message.contains("Status: PLANNING"))
        repository.disconnect()
    }
}

class FakeConnectWebSocketClient : ConnectWebSocketClient() {
    // Uses default state flow and methods
}

class FakeOrchestratorService : OrchestratorService {
    var initialTrajectory = CascadeTrajectory(cascadeId = "default")
    val reactiveFlow = MutableSharedFlow<MessageDiff>(replay = 10)
    val summariesFlow = MutableSharedFlow<MessageDiff>(replay = 10)
    val agentStateFlow = MutableSharedFlow<AgentInstance>(replay = 10)

    val acknowledgedEdits = mutableListOf<Pair<String, Boolean>>()
    val acknowledgedActions = mutableListOf<Pair<Boolean, String>>()
    val sentMessages = mutableListOf<Pair<String, String>>()

    override suspend fun startCascade(
        workspaceUris: List<String>,
        requestedModel: String?,
        initialPrompt: String?
    ): Result<String> {
        return Result.success(UUID.randomUUID().toString())
    }

    override suspend fun sendUserCascadeMessage(cascadeId: String, message: String): Result<Unit> {
        sentMessages.add(cascadeId to message)
        return Result.success(Unit)
    }

    override suspend fun cancelCascadeInvocation(cascadeId: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun forceStopCascadeTree(cascadeId: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun getCascadeTrajectory(cascadeId: String): Result<CascadeTrajectory> {
        return Result.success(initialTrajectory.copy(cascadeId = cascadeId))
    }

    override suspend fun getAllCascadeSummaries(): Result<List<SessionSummary>> {
        return Result.success(emptyList())
    }

    override suspend fun acknowledgeCascadeCodeEdit(
        cascadeId: String,
        fileUri: String,
        contents: String,
        accept: Boolean
    ): Result<Unit> {
        acknowledgedEdits.add(fileUri to accept)
        return Result.success(Unit)
    }

    override suspend fun acknowledgeCodeActionStep(
        cascadeId: String,
        accept: Boolean,
        writtenFeedback: String
    ): Result<Unit> {
        acknowledgedActions.add(accept to writtenFeedback)
        return Result.success(Unit)
    }

    override fun streamReactiveUpdates(cascadeId: String): Flow<MessageDiff> = reactiveFlow

    override fun streamCascadeSummaries(): Flow<MessageDiff> = summariesFlow

    override fun streamAgentStateUpdates(conversationId: String): Flow<AgentInstance> = agentStateFlow
}
