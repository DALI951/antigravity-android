package com.antigravity.android.network

import com.antigravity.android.data.model.CascadeTrajectory
import com.antigravity.android.data.model.FieldDiff
import com.antigravity.android.data.model.MapDiff
import com.antigravity.android.data.model.MessageDiff
import com.antigravity.android.data.model.RepeatedDiff
import com.antigravity.android.data.model.SessionStatus
import com.antigravity.android.data.model.SingularValue
import com.antigravity.android.data.model.Step
import com.antigravity.android.domain.StateDiffReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateDiffReducerTest {

    private val initialState = CascadeTrajectory(
        cascadeId = "cascade-test-1",
        status = SessionStatus.RUNNING,
        title = "Initial Task",
        steps = listOf(
            Step(stepIndex = 0, role = "user", content = "Build Antigravity Android"),
            Step(stepIndex = 1, role = "assistant", content = "Analyzing requirements...")
        ),
        metadata = mapOf("model" to "gemini-3.8-flash")
    )

    // ==========================================
    // Tier 1: Happy Path Reductions (F9)
    // ==========================================

    @Test
    fun testSingularFieldDiff_updatesStatusAndTitle() {
        val diff = MessageDiff(
            fieldDiffs = listOf(
                FieldDiff(fieldNumber = StateDiffReducer.FIELD_STATUS, updateSingular = SingularValue(stringVal = "COMPLETED")),
                FieldDiff(fieldNumber = StateDiffReducer.FIELD_TITLE, updateSingular = SingularValue(stringVal = "Updated Task Title"))
            )
        )

        val newState = StateDiffReducer.reduce(initialState, diff)

        assertEquals(SessionStatus.COMPLETED, newState.status)
        assertEquals("Updated Task Title", newState.title)
        assertEquals(2, newState.steps.size)
    }

    @Test
    fun testRepeatedDiff_appendsNewSteps() {
        val diff = MessageDiff(
            fieldDiffs = listOf(
                FieldDiff(
                    fieldNumber = StateDiffReducer.FIELD_STEPS,
                    updateRepeated = RepeatedDiff(
                        updateSteps = listOf(
                            Step(stepIndex = 2, role = "assistant", content = "Generating project files", status = "RUNNING")
                        )
                    )
                )
            )
        )

        val newState = StateDiffReducer.reduce(initialState, diff)

        assertEquals(3, newState.steps.size)
        assertEquals("Generating project files", newState.steps[2].content)
        assertEquals(3, newState.numTotalSteps)
    }

    @Test
    fun testRepeatedDiff_inPlaceStreamingTokenAppends() {
        val diff1 = MessageDiff(
            fieldDiffs = listOf(
                FieldDiff(
                    fieldNumber = StateDiffReducer.FIELD_STEPS,
                    updateRepeated = RepeatedDiff(
                        tokenAppends = mapOf(1 to " Extracted 27 features.")
                    )
                )
            )
        )
        val state1 = StateDiffReducer.reduce(initialState, diff1)
        assertEquals("Analyzing requirements... Extracted 27 features.", state1.steps[1].content)

        val diff2 = MessageDiff(
            fieldDiffs = listOf(
                FieldDiff(
                    fieldNumber = StateDiffReducer.FIELD_STEPS,
                    updateRepeated = RepeatedDiff(
                        tokenAppends = mapOf(1 to " Verified with MockWebServer.")
                    )
                )
            )
        )
        val state2 = StateDiffReducer.reduce(state1, diff2)
        assertEquals("Analyzing requirements... Extracted 27 features. Verified with MockWebServer.", state2.steps[1].content)
    }

    @Test
    fun testMapDiff_updatesAndRemovesKeys() {
        val diff = MessageDiff(
            fieldDiffs = listOf(
                FieldDiff(
                    fieldNumber = StateDiffReducer.FIELD_METADATA,
                    updateMap = MapDiff(
                        updates = mapOf("thinkingLevel" to "high", "environment" to "android-api-35"),
                        removals = listOf("model")
                    )
                )
            )
        )

        val newState = StateDiffReducer.reduce(initialState, diff)

        assertEquals("high", newState.metadata["thinkingLevel"])
        assertEquals("android-api-35", newState.metadata["environment"])
        assertFalse(newState.metadata.containsKey("model"))
    }

    @Test
    fun testClearField_resetsMetadataAndTitle() {
        val diff = MessageDiff(
            fieldDiffs = listOf(
                FieldDiff(fieldNumber = StateDiffReducer.FIELD_TITLE, clear = true),
                FieldDiff(fieldNumber = StateDiffReducer.FIELD_METADATA, clear = true)
            )
        )

        val newState = StateDiffReducer.reduce(initialState, diff)

        assertEquals("", newState.title)
        assertTrue(newState.metadata.isEmpty())
        assertEquals(2, newState.steps.size)
    }

    // ==========================================
    // Tier 2: Boundary, Corner & Error Cases
    // ==========================================

    @Test
    fun testEmptyMessageDiff_isNoOp() {
        val emptyDiff = MessageDiff()
        val newState = StateDiffReducer.reduce(initialState, emptyDiff)
        assertEquals(initialState.title, newState.title)
        assertEquals(initialState.status, newState.status)
        assertEquals(initialState.steps.size, newState.steps.size)
    }

    @Test
    fun testRepeatedDiff_outOfBoundsTokenAppend_isSafelyIgnored() {
        val diff = MessageDiff(
            fieldDiffs = listOf(
                FieldDiff(
                    fieldNumber = StateDiffReducer.FIELD_STEPS,
                    updateRepeated = RepeatedDiff(
                        tokenAppends = mapOf(999 to "orphan token")
                    )
                )
            )
        )

        val newState = StateDiffReducer.reduce(initialState, diff)
        assertEquals(initialState.steps.size, newState.steps.size)
    }

    @Test
    fun testRepeatedDiff_negativeNewLength_isSafelyIgnored() {
        val diff = MessageDiff(
            fieldDiffs = listOf(
                FieldDiff(
                    fieldNumber = StateDiffReducer.FIELD_STEPS,
                    updateRepeated = RepeatedDiff(newLength = -5)
                )
            )
        )

        val newState = StateDiffReducer.reduce(initialState, diff)
        assertEquals(2, newState.steps.size)
    }

    @Test
    fun testRepeatedDiff_newLengthTruncation() {
        val diff = MessageDiff(
            fieldDiffs = listOf(
                FieldDiff(
                    fieldNumber = StateDiffReducer.FIELD_STEPS,
                    updateRepeated = RepeatedDiff(newLength = 1)
                )
            )
        )

        val newState = StateDiffReducer.reduce(initialState, diff)
        assertEquals(1, newState.steps.size)
        assertEquals(0, newState.steps[0].stepIndex)
    }

    @Test
    fun testMassiveTrajectory_1000StepsPerformance() {
        var state = initialState
        val thousandSteps = (2..1000).map {
            Step(stepIndex = it, role = "assistant", content = "Step token $it")
        }

        val appendDiff = MessageDiff(
            fieldDiffs = listOf(
                FieldDiff(
                    fieldNumber = StateDiffReducer.FIELD_STEPS,
                    updateRepeated = RepeatedDiff(updateSteps = thousandSteps)
                )
            )
        )

        val startTime = System.currentTimeMillis()
        state = StateDiffReducer.reduce(state, appendDiff)
        val elapsed = System.currentTimeMillis() - startTime

        assertEquals(1001, state.steps.size)
        assertTrue("Reducing 1,000 steps should take under 50ms (took ${elapsed}ms)", elapsed < 50)
    }
}
