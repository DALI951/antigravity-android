package com.antigravity.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

data class AgentNode(
    val agentId: String,
    val role: String,
    val typeName: String,
    val parentConversationId: String? = null,
    val rootConversationId: String,
    val nestingDepth: Int = 1,
    val status: String = "RUNNING",
    val activeTool: String? = null,
    val subagents: MutableList<AgentNode> = mutableListOf()
) {
    val isRoot: Boolean
        get() = parentConversationId == null || parentConversationId == rootConversationId

    fun findNode(id: String): AgentNode? {
        if (agentId == id) return this
        for (sub in subagents) {
            val found = sub.findNode(id)
            if (found != null) return found
        }
        return null
    }

    fun countActiveAgents(): Int {
        var count = if (status == "RUNNING") 1 else 0
        for (sub in subagents) {
            count += sub.countActiveAgents()
        }
        return count
    }

    fun cancelAll(): AgentNode {
        return this.copy(
            status = "CANCELLED",
            subagents = subagents.map { it.cancelAll() }.toMutableList()
        )
    }
}

class AgentHierarchyBuilder {
    companion object {
        fun buildTree(nodes: List<AgentNode>): AgentNode? {
            if (nodes.isEmpty()) return null
            val nodeMap = nodes.associateBy { it.agentId }

            // Find root: either parent is null or parent == rootConversationId or depth 1
            val root = nodes.firstOrNull { it.isRoot } ?: nodes.first()

            // Link children
            for (node in nodes) {
                if (node.agentId != root.agentId && node.parentConversationId != null) {
                    val parent = nodeMap[node.parentConversationId]
                    if (parent != null && !hasCycle(node, parent, nodeMap)) {
                        parent.subagents.add(node)
                    }
                }
            }
            return root
        }

        private fun hasCycle(child: AgentNode, parent: AgentNode, map: Map<String, AgentNode>): Boolean {
            var curr: AgentNode? = parent
            val visited = mutableSetOf<String>()
            while (curr != null) {
                if (curr.agentId == child.agentId) return true
                if (!visited.add(curr.agentId)) return true
                curr = curr.parentConversationId?.let { map[it] }
            }
            return false
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
            role = "Root Orchestrator",
            typeName = "teamwork_lead",
            parentConversationId = null,
            rootConversationId = "root-100",
            nestingDepth = 1,
            status = "RUNNING"
        )

        assertTrue(root.isRoot)
        assertEquals(1, root.nestingDepth)
        assertEquals("Root Orchestrator", root.role)
    }

    @Test
    fun testSubagentHierarchy_nestingDepthCalculations() {
        val root = AgentNode(
            agentId = "root-100",
            role = "Project Orchestrator",
            typeName = "orchestrator",
            rootConversationId = "root-100",
            nestingDepth = 1
        )

        val subOrch = AgentNode(
            agentId = "sub-200",
            role = "Backend Sub-Orchestrator",
            typeName = "sub_orch",
            parentConversationId = "root-100",
            rootConversationId = "root-100",
            nestingDepth = 2
        )

        val worker = AgentNode(
            agentId = "worker-300",
            role = "Connect-RPC Worker",
            typeName = "worker",
            parentConversationId = "sub-200",
            rootConversationId = "root-100",
            nestingDepth = 3
        )

        subOrch.subagents.add(worker)
        root.subagents.add(subOrch)

        assertEquals(1, root.nestingDepth)
        assertEquals(2, root.subagents[0].nestingDepth)
        assertEquals(3, root.subagents[0].subagents[0].nestingDepth)
        assertEquals("worker-300", root.findNode("worker-300")?.agentId)
    }

    @Test
    fun testCountActiveAgents_aggregatesAcrossHierarchy() {
        val root = AgentNode(
            agentId = "root",
            role = "Lead",
            typeName = "lead",
            rootConversationId = "root",
            status = "RUNNING"
        )
        val worker1 = AgentNode(agentId = "w1", role = "M1", typeName = "worker", rootConversationId = "root", status = "RUNNING")
        val worker2 = AgentNode(agentId = "w2", role = "M2", typeName = "worker", rootConversationId = "root", status = "DONE")
        val worker3 = AgentNode(agentId = "w3", role = "M3", typeName = "worker", rootConversationId = "root", status = "RUNNING")

        root.subagents.add(worker1)
        root.subagents.add(worker2)
        root.subagents.add(worker3)

        // 1 root + 2 running workers = 3
        assertEquals(3, root.countActiveAgents())
    }

    @Test
    fun testCancelAll_recursivelyCancelsTree() {
        val root = AgentNode(
            agentId = "root",
            role = "Lead",
            typeName = "lead",
            rootConversationId = "root",
            status = "RUNNING"
        )
        val worker = AgentNode(
            agentId = "worker",
            role = "Worker",
            typeName = "worker",
            parentConversationId = "root",
            rootConversationId = "root",
            status = "RUNNING"
        )
        root.subagents.add(worker)

        val cancelledTree = root.cancelAll()

        assertEquals("CANCELLED", cancelledTree.status)
        assertEquals("CANCELLED", cancelledTree.subagents[0].status)
    }

    @Test
    fun testBuildTreeFromFlatList() {
        val list = listOf(
            AgentNode(agentId = "r", role = "Lead", typeName = "lead", rootConversationId = "r", nestingDepth = 1),
            AgentNode(agentId = "sub1", role = "Sub1", typeName = "sub", parentConversationId = "r", rootConversationId = "r", nestingDepth = 2),
            AgentNode(agentId = "sub2", role = "Sub2", typeName = "sub", parentConversationId = "r", rootConversationId = "r", nestingDepth = 2)
        )

        val tree = AgentHierarchyBuilder.buildTree(list)
        assertNotNull(tree)
        assertEquals("r", tree?.agentId)
        assertEquals(2, tree?.subagents?.size)
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
        val nodeA = AgentNode(agentId = "A", role = "A", typeName = "lead", parentConversationId = "B", rootConversationId = "A")
        val nodeB = AgentNode(agentId = "B", role = "B", typeName = "worker", parentConversationId = "A", rootConversationId = "A")

        // Should not crash with StackOverflowError
        val tree = AgentHierarchyBuilder.buildTree(listOf(nodeA, nodeB))
        assertNotNull(tree)
    }

    @Test
    fun testDeeplyNestedSwarm_depth15() {
        var current = AgentNode(agentId = "level-1", role = "Root", typeName = "lead", rootConversationId = "level-1", nestingDepth = 1)
        val root = current
        for (i in 2..15) {
            val child = AgentNode(
                agentId = "level-$i",
                role = "Worker $i",
                typeName = "worker",
                parentConversationId = current.agentId,
                rootConversationId = "level-1",
                nestingDepth = i
            )
            current.subagents.add(child)
            current = child
        }

        val leaf = root.findNode("level-15")
        assertNotNull(leaf)
        assertEquals(15, leaf?.nestingDepth)
    }

    @Test
    fun testFindNodeNonExistent_returnsNull() {
        val root = AgentNode(agentId = "root", role = "Root", typeName = "lead", rootConversationId = "root")
        assertNull(root.findNode("ghost-agent"))
    }
}
