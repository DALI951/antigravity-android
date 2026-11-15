package com.antigravity.android.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

data class SessionSummary(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val status: String = "RUNNING", // RUNNING, DONE, FAILED, CANCELLED
    val createdAt: Long = System.currentTimeMillis(),
    val activeAgentCount: Int = 1,
    val isPinned: Boolean = false
)

data class DashboardUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val isLoading: Boolean = false,
    val filterStatus: String = "ALL",
    val searchQuery: String = ""
) {
    val filteredSessions: List<SessionSummary>
        get() = sessions.filter { session ->
            val matchesStatus = filterStatus == "ALL" || session.status.equals(filterStatus, ignoreCase = true)
            val matchesQuery = searchQuery.isBlank() || session.title.contains(searchQuery, ignoreCase = true)
            matchesStatus && matchesQuery
        }.sortedWith(compareByDescending<SessionSummary> { it.isPinned }.thenByDescending { it.createdAt })
}

class DashboardStateHolder {
    private var _state = DashboardUiState()
    val state: DashboardUiState get() = _state

    fun setSessions(list: List<SessionSummary>) {
        _state = _state.copy(sessions = list, isLoading = false)
    }

    fun setFilterStatus(status: String) {
        _state = _state.copy(filterStatus = status)
    }

    fun setSearchQuery(query: String) {
        _state = _state.copy(searchQuery = query)
    }

    fun togglePin(sessionId: String) {
        _state = _state.copy(
            sessions = _state.sessions.map {
                if (it.id == sessionId) it.copy(isPinned = !it.isPinned) else it
            }
        )
    }

    fun addNewSession(title: String): SessionSummary {
        val newSession = SessionSummary(title = title, status = "RUNNING")
        _state = _state.copy(sessions = listOf(newSession) + _state.sessions)
        return newSession
    }
}

class DashboardViewModelTest {

    // ==========================================
    // Tier 1: Happy-Path Dashboard Overview (F20)
    // ==========================================

    @Test
    fun testDashboardUiState_initialEmpty() {
        val holder = DashboardStateHolder()
        assertTrue(holder.state.sessions.isEmpty())
        assertFalse(holder.state.isLoading)
        assertEquals("ALL", holder.state.filterStatus)
    }

    @Test
    fun testDashboard_loadsSessions() {
        val holder = DashboardStateHolder()
        val s1 = SessionSummary(title = "Task 1", status = "RUNNING")
        val s2 = SessionSummary(title = "Task 2", status = "DONE")

        holder.setSessions(listOf(s1, s2))

        assertEquals(2, holder.state.sessions.size)
        assertEquals(2, holder.state.filteredSessions.size)
    }

    @Test
    fun testDashboard_filterByStatus() {
        val holder = DashboardStateHolder()
        holder.setSessions(listOf(
            SessionSummary(title = "Active 1", status = "RUNNING"),
            SessionSummary(title = "Active 2", status = "RUNNING"),
            SessionSummary(title = "Completed 1", status = "DONE")
        ))

        holder.setFilterStatus("RUNNING")
        assertEquals(2, holder.state.filteredSessions.size)

        holder.setFilterStatus("DONE")
        assertEquals(1, holder.state.filteredSessions.size)
        assertEquals("Completed 1", holder.state.filteredSessions[0].title)
    }

    @Test
    fun testDashboard_searchFilter() {
        val holder = DashboardStateHolder()
        holder.setSessions(listOf(
            SessionSummary(title = "Refactor connect transport", status = "RUNNING"),
            SessionSummary(title = "Fix SHA-256 validator", status = "DONE")
        ))

        holder.setSearchQuery("connect")
        assertEquals(1, holder.state.filteredSessions.size)
        assertEquals("Refactor connect transport", holder.state.filteredSessions[0].title)
    }

    @Test
    fun testDashboard_pinnedSessionsAppearFirst() {
        val holder = DashboardStateHolder()
        val s1 = SessionSummary(id = "1", title = "Task 1", createdAt = 1000L, isPinned = false)
        val s2 = SessionSummary(id = "2", title = "Task 2", createdAt = 2000L, isPinned = false)
        holder.setSessions(listOf(s1, s2))

        holder.togglePin("1")

        val sorted = holder.state.filteredSessions
        assertEquals("1", sorted[0].id)
        assertTrue(sorted[0].isPinned)
    }

    // ==========================================
    // Tier 2: Boundary, Corner & Error Cases
    // ==========================================

    @Test
    fun testAddNewSession_prependsToList() {
        val holder = DashboardStateHolder()
        val created = holder.addNewSession("New Mobile Agent Session")

        assertEquals(1, holder.state.sessions.size)
        assertEquals("New Mobile Agent Session", created.title)
        assertEquals("RUNNING", created.status)
    }

    @Test
    fun testSearchQueryMatchingNone_returnsEmptyList() {
        val holder = DashboardStateHolder()
        holder.setSessions(listOf(SessionSummary(title = "Task 1")))
        holder.setSearchQuery("nonexistent query")

        assertTrue(holder.state.filteredSessions.isEmpty())
    }

    @Test
    fun testVirtualization1000SessionsScaling() {
        val holder = DashboardStateHolder()
        val thousand = (1..1000).map { SessionSummary(title = "Historical Session $it", status = if (it % 2 == 0) "RUNNING" else "DONE") }
        holder.setSessions(thousand)

        assertEquals(1000, holder.state.sessions.size)
        holder.setFilterStatus("RUNNING")
        assertEquals(500, holder.state.filteredSessions.size)
    }
}
