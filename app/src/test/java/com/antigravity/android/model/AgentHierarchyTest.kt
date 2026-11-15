package com.antigravity.android.model

import com.antigravity.android.data.model.AgentNode
import com.antigravity.android.data.model.AgentRole
import com.antigravity.android.data.model.AgentStatus
import com.antigravity.android.data.model.cancelAll
import com.antigravity.android.data.model.countActiveAgents
import com.antigravity.android.data.model.findNode
import com.antigravity.android.data.model.isRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHierarchyBuilder {
    companion object {
        fun buildTree(nodes: List<AgentNode>): AgentNode? {
            if (nodes.isEmpty()) return null
            val root = nodes.firstOrNull { it.isRoot } ?: nodes.first()

            fun attachChildren(current: AgentNode, pool: List<AgentNode>, visited: Set<String>): AgentNode {
                val nextVisited = visited + current.agentId
                val directChildren = pool.filter { it.parentAgentId == current.agentId && it.agentId !in visited }
                val attachedChildren = directChildren.map { child ->
                    attachChildren(child, pool, nextVisited)
                }
                return current.copy(children = attachedChildren)
            }

            return attachChildren(root, nodes, emptySet())
        }
    }
}

class AgentHierarchyTest {

    // ==========================================
    // Tier 1: Happy-Path Swarm Tree Tests (F8, F23)
    // ==========================================

    @Test
    fun testRootAgentNode_creationAndDepth() {
        val root = AgentNode(
            agentId = "root-100",
            name = "Root Orchestrator",
            role = AgentRole.ORCHESTRATOR,
            status = AgentStatus.RUNNING,
            parentAgentId = null,
            nestingDepth = 0
        )

        assertTrue(root.isRoot)
        assertEquals(0, root.nestingDepth)
        assertEquals(AgentRole.ORCHESTRATOR, root.role)
        assertEquals("Root Orchestrator", root.name)
    }

    @Test
    fun testSubagentHierarchy_nestingDepthCalculations() {
        val worker = AgentNode(
            agentId = "worker-300",
            name = "Connect-RPC Worker",
            role = AgentRole.WORKER,
            status = AgentStatus.RUNNING,
            parentAgentId = "sub-200",
            nestingDepth = 2
        )

        val subOrch = AgentNode(
            agentId = "sub-200",
            name = "Backend Sub-Orchestrator",
            role = AgentRole.SUB_ORCHESTRATOR,
            status = AgentStatus.RUNNING,
            parentAgentId = "root-100",
            nestingDepth = 1,
            children = listOf(worker)
        )

        val root = AgentNode(
            agentId = "root-100",
            name = "Project Orchestrator",
            role = AgentRole.ORCHESTRATOR,
            status = AgentStatus.RUNNING,
            nestingDepth = 0,
            children = listOf(subOrch)
        )

        assertEquals(0, root.nestingDepth)
        assertEquals(1, root.children[0].nestingDepth)
        assertEquals(2, root.children[0].children[0].nestingDepth)
        assertEquals("worker-300", root.findNode("worker-300")?.agentId)
    }

    @Test
    fun testCountActiveAgents_aggregatesAcrossHierarchy() {
        val worker1 = AgentNode(agentId = "w1", name = "M1", role = AgentRole.WORKER, status = AgentStatus.RUNNING, parentAgentId = "root")
        val worker2 = AgentNode(agentId = "w2", name = "M2", role = AgentRole.WORKER, status = AgentStatus.COMPLETED, parentAgentId = "root")
        val worker3 = AgentNode(agentId = "w3", name = "M3", role = AgentRole.WORKER, status = AgentStatus.RUNNING, parentAgentId = "root")

        val root = AgentNode(
            agentId = "root",
            name = "Lead",
            role = AgentRole.ORCHESTRATOR,
            status = AgentStatus.RUNNING,
            children = listOf(worker1, worker2, worker3)
        )

        // 1 root + 2 running workers = 3
        assertEquals(3, root.countActiveAgents())
    }

    @Test
    fun testCancelAll_recursivelyCancelsTree() {
        val worker = AgentNode(
            agentId = "worker",
            name = "Worker",
            role = AgentRole.WORKER,
            status = AgentStatus.RUNNING,
            parentAgentId = "root"
        )
        val root = AgentNode(
            agentId = "root",
            name = "Lead",
            role = AgentRole.ORCHESTRATOR,
            status = AgentStatus.RUNNING,
            children = listOf(worker)
        )

        val cancelledTree = root.cancelAll()

        assertEquals(AgentStatus.CANCELLED, cancelledTree.status)
        assertEquals(AgentStatus.CANCELLED, cancelledTree.children[0].status)
    }

    @Test
    fun testBuildTreeFromFlatList() {
        val list = listOf(
            AgentNode(agentId = "r", name = "Lead", role = AgentRole.ORCHESTRATOR, status = AgentStatus.RUNNING, nestingDepth = 0),
            AgentNode(agentId = "sub1", name = "Sub1", role = AgentRole.SUB_ORCHESTRATOR, status = AgentStatus.RUNNING, parentAgentId = "r", nestingDepth = 1),
            AgentNode(agentId = "sub2", name = "Sub2", role = AgentRole.SUB_ORCHESTRATOR, status = AgentStatus.RUNNING, parentAgentId = "r", nestingDepth = 1)
        )

        val tree = AgentHierarchyBuilder.buildTree(list)
        assertNotNull(tree)
        assertEquals("r", tree?.agentId)
        assertEquals(2, tree?.children?.size)
    }

    // ==========================================
    // Tier 2: Boundary, Corner & Error Cases
    // ==========================================

    @Test
    fun testEmptyNodeList_returnsNull() {
        assertNull(AgentHierarchyBuilder.buildTree(emptyList()))
    }

    @Test
    fun testCircularParentDetection_preventsInfiniteRecursion() {
        val nodeA = AgentNode(agentId = "A", name = "A", role = AgentRole.ORCHESTRATOR, status = AgentStatus.RUNNING, parentAgentId = "B")
        val nodeB = AgentNode(agentId = "B", name = "B", role = AgentRole.WORKER, status = AgentStatus.RUNNING, parentAgentId = "A")

        // Should not crash with StackOverflowError
        val tree = AgentHierarchyBuilder.buildTree(listOf(nodeA, nodeB))
        assertNotNull(tree)
    }

    @Test
    fun testDeeplyNestedSwarm_depth15() {
        val nodes = mutableListOf<AgentNode>()
        nodes.add(AgentNode(agentId = "level-0", name = "Root", role = AgentRole.ORCHESTRATOR, status = AgentStatus.RUNNING, nestingDepth = 0))
        for (i in 1..14) {
            nodes.add(
                AgentNode(
                    agentId = "level-$i",
                    name = "Worker $i",
                    role = AgentRole.WORKER,
                    status = AgentStatus.RUNNING,
                    parentAgentId = "level-${i - 1}",
                    nestingDepth = i
                )
            )
        }
        val tree = AgentHierarchyBuilder.buildTree(nodes)
        assertNotNull(tree)
        val leaf = tree?.findNode("level-14")
        assertNotNull(leaf)
        assertEquals(14, leaf?.nestingDepth)
    }

    @Test
    fun testFindNodeNonExistent_returnsNull() {
        val root = AgentNode(agentId = "root", name = "Root", role = AgentRole.ORCHESTRATOR, status = AgentStatus.RUNNING)
        assertNull(root.findNode("ghost-agent"))
    }
}
