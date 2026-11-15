package com.antigravity.android.viewmodel

import com.antigravity.android.data.model.CascadeTrajectory
import com.antigravity.android.data.model.SessionStatus
import com.antigravity.android.data.model.Step
import com.antigravity.android.model.AgentNode
import com.antigravity.android.model.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

enum class SessionTab {
    CHAT, SWARM, LOGS, ARTIFACTS
}

data class CodeEditApproval(
    val editId: String,
    val filePath: String,
    val diffText: String,
    val isPending: Boolean = true
)

data class SessionDetailUiState(
    val sessionId: String,
    val title: String = "Cascade Session",
    val activeTab: SessionTab = SessionTab.CHAT,
    val trajectory: CascadeTrajectory = CascadeTrajectory(cascadeId = sessionId),
    val swarm: List<AgentNode> = emptyList(),
    val logs: List<LogEntry> = emptyList(),
    val pendingApproval: CodeEditApproval? = null,
    val isCancelled: Boolean = false
)

class SessionStateHolder(sessionId: String) {
    private var _state = SessionDetailUiState(sessionId = sessionId)
    val state: SessionDetailUiState get() = _state

    fun selectTab(tab: SessionTab) {
        _state = _state.copy(activeTab = tab)
    }

    fun addStep(step: Step) {
        val updated = _state.trajectory.copy(steps = _state.trajectory.steps + step)
        _state = _state.copy(trajectory = updated)
    }

    fun setPendingApproval(approval: CodeEditApproval?) {
        _state = _state.copy(pendingApproval = approval)
    }

    fun resolveApproval(editId: String, approved: Boolean): Boolean {
        if (_state.pendingApproval?.editId == editId) {
            _state = _state.copy(pendingApproval = null)
            return approved
        }
        return false
    }

    fun cancelSession() {
        val cancelledTrajectory = _state.trajectory.copy(status = SessionStatus.CANCELLED)
        val cancelledSwarm = _state.swarm.map { it.cancelAll() }
        _state = _state.copy(
            trajectory = cancelledTrajectory,
            swarm = cancelledSwarm,
            isCancelled = true
        )
    }

    fun updateSwarm(swarmList: List<AgentNode>) {
        _state = _state.copy(swarm = swarmList)
    }
}

class SessionViewModelTest {

    // ==========================================
    // Tier 1: Happy-Path 4-Tab State & Lifecycle (F7, F10, F21)
    // ==========================================

    @Test
    fun testInitialTabIsChat() {
        val holder = SessionStateHolder("session-1")
        assertEquals(SessionTab.CHAT, holder.state.activeTab)
        assertFalse(holder.state.isCancelled)
    }

    @Test
    fun testTabSwitchingAcross4Tabs() {
        val holder = SessionStateHolder("session-1")

        holder.selectTab(SessionTab.SWARM)
        assertEquals(SessionTab.SWARM, holder.state.activeTab)

        holder.selectTab(SessionTab.LOGS)
        assertEquals(SessionTab.LOGS, holder.state.activeTab)

        holder.selectTab(SessionTab.ARTIFACTS)
        assertEquals(SessionTab.ARTIFACTS, holder.state.activeTab)

        holder.selectTab(SessionTab.CHAT)
        assertEquals(SessionTab.CHAT, holder.state.activeTab)
    }

    @Test
    fun testTrajectoryMessageTimeline() {
        val holder = SessionStateHolder("session-1")
        holder.addStep(Step(stepIndex = 0, role = "user", content = "Start task"))
        holder.addStep(Step(stepIndex = 1, role = "assistant", content = "Working on it"))

        assertEquals(2, holder.state.trajectory.steps.size)
        assertEquals("Start task", holder.state.trajectory.steps[0].content)
        assertEquals("Working on it", holder.state.trajectory.steps[1].content)
    }

    @Test
    fun testCodeEditApproval_accepted() {
        val holder = SessionStateHolder("session-1")
        val approval = CodeEditApproval(editId = "edit-1", filePath = "src/Main.kt", diffText = "+ println()")
        holder.setPendingApproval(approval)

        assertNotNull(holder.state.pendingApproval)
        val decision = holder.resolveApproval("edit-1", approved = true)

        assertTrue("Approval should be granted", decision)
        assertNull("Pending approval must be cleared", holder.state.pendingApproval)
    }

    @Test
    fun testCodeEditApproval_rejected() {
        val holder = SessionStateHolder("session-1")
        val approval = CodeEditApproval(editId = "edit-2", filePath = "src/Main.kt", diffText = "- remove()")
        holder.setPendingApproval(approval)

        val decision = holder.resolveApproval("edit-2", approved = false)

        assertFalse("Approval should be rejected", decision)
        assertNull(holder.state.pendingApproval)
    }

    @Test
    fun testSessionCancellation_haltsSessionAndSwarm() {
        val holder = SessionStateHolder("session-1")
        val rootAgent = AgentNode(agentId = "root", role = "Lead", typeName = "lead", rootConversationId = "session-1", status = "RUNNING")
        val workerAgent = AgentNode(agentId = "w1", role = "Worker", typeName = "worker", parentConversationId = "root", rootConversationId = "session-1", status = "RUNNING")
        rootAgent.subagents.add(workerAgent)

        holder.updateSwarm(listOf(rootAgent))
        holder.cancelSession()

        assertTrue(holder.state.isCancelled)
        assertEquals(SessionStatus.CANCELLED, holder.state.trajectory.status)
        assertEquals("CANCELLED", holder.state.swarm[0].status)
        assertEquals("CANCELLED", holder.state.swarm[0].subagents[0].status)
    }

    // ==========================================
    // Tier 2: Boundary, Corner & Error Cases
    // ==========================================

    @Test
    fun testResolveExpiredOrUnknownApprovalId_returnsFalse() {
        val holder = SessionStateHolder("session-1")
        holder.setPendingApproval(CodeEditApproval(editId = "edit-active", filePath = "A.kt", diffText = ""))

        val result = holder.resolveApproval("unknown-edit-id", approved = true)
        assertFalse("Resolving non-existent edit must return false", result)
        assertNotNull(holder.state.pendingApproval)
    }
}
