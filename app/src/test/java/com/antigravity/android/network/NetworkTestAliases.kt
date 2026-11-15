package com.antigravity.android.network

// Typealiases bridging to production data/network/domain packages
sealed class ConnectionStatus {
    data class Connected(val endpoint: String) : ConnectionStatus()
    data class Reconnecting(val attempt: Int, val delayMs: Long) : ConnectionStatus()
    data class Disconnected(val reason: String) : ConnectionStatus()
}

typealias BackoffCalculator = com.antigravity.android.data.network.BackoffCalculator
typealias OfflineBannerState = com.antigravity.android.data.network.OfflineBannerState
typealias ConnectFrameBuilder = com.antigravity.android.data.network.ConnectFrameBuilder
typealias ConnectFrameEnvelope = com.antigravity.android.data.network.ConnectFrameEnvelope
typealias ConnectError = com.antigravity.android.data.network.ConnectError
typealias ConnectBinaryFrame = com.antigravity.android.data.network.ConnectBinaryFrame

typealias SingularValue = com.antigravity.android.data.model.SingularValue
typealias RepeatedDiff = com.antigravity.android.data.model.RepeatedDiff
typealias MapDiff = com.antigravity.android.data.model.MapDiff
typealias FieldDiff = com.antigravity.android.data.model.FieldDiff
typealias MessageDiff = com.antigravity.android.data.model.MessageDiff

data class TrajectoryStep(
    val stepIndex: Int,
    val role: String,
    val content: String,
    val status: String = "PENDING"
)

data class CascadeTrajectoryState(
    val cascadeId: String,
    val status: String = "RUNNING",
    val title: String = "",
    val steps: List<TrajectoryStep> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

class StateDiffReducer {
    companion object {
        const val FIELD_STATUS = com.antigravity.android.domain.StateDiffReducer.FIELD_STATUS
        const val FIELD_TITLE = com.antigravity.android.domain.StateDiffReducer.FIELD_TITLE
        const val FIELD_STEPS = com.antigravity.android.domain.StateDiffReducer.FIELD_STEPS
        const val FIELD_METADATA = com.antigravity.android.domain.StateDiffReducer.FIELD_METADATA
        const val FIELD_GENERATOR_METADATA = com.antigravity.android.domain.StateDiffReducer.FIELD_GENERATOR_METADATA

        fun reduce(current: com.antigravity.android.data.model.CascadeTrajectory, diff: MessageDiff): com.antigravity.android.data.model.CascadeTrajectory {
            return com.antigravity.android.domain.StateDiffReducer.reduce(current, diff)
        }

        fun reduce(current: CascadeTrajectoryState, diff: MessageDiff): CascadeTrajectoryState {
            var updatedStatus = current.status
            var updatedTitle = current.title
            val updatedSteps = current.steps.toMutableList()
            val updatedMetadata = current.metadata.toMutableMap()

            for (fieldDiff in diff.fieldDiffs) {
                if (fieldDiff.clear) {
                    when (fieldDiff.fieldNumber) {
                        FIELD_TITLE -> updatedTitle = ""
                        FIELD_STEPS -> updatedSteps.clear()
                        FIELD_METADATA -> updatedMetadata.clear()
                    }
                    continue
                }

                when (fieldDiff.fieldNumber) {
                    FIELD_STATUS -> {
                        fieldDiff.updateSingular?.stringVal?.let { updatedStatus = it }
                    }
                    FIELD_TITLE -> {
                        fieldDiff.updateSingular?.stringVal?.let { updatedTitle = it }
                    }
                    FIELD_STEPS -> {
                        fieldDiff.updateRepeated?.let { repDiff ->
                            for (newStep in repDiff.updateSteps) {
                                updatedSteps.add(TrajectoryStep(
                                    stepIndex = newStep.stepIndex,
                                    role = newStep.role,
                                    content = newStep.content,
                                    status = newStep.status
                                ))
                            }
                            for ((idx, appendText) in repDiff.tokenAppends) {
                                if (idx in updatedSteps.indices) {
                                    val existing = updatedSteps[idx]
                                    updatedSteps[idx] = existing.copy(content = existing.content + appendText)
                                }
                            }
                            repDiff.newLength?.let { len ->
                                if (len in 0..updatedSteps.size) {
                                    while (updatedSteps.size > len) {
                                        updatedSteps.removeAt(updatedSteps.lastIndex)
                                    }
                                }
                            }
                        }
                    }
                    FIELD_METADATA -> {
                        fieldDiff.updateMap?.let { mapDiff ->
                            updatedMetadata.putAll(mapDiff.updates)
                            for (rem in mapDiff.removals) {
                                updatedMetadata.remove(rem)
                            }
                        }
                    }
                }
            }

            return current.copy(
                status = updatedStatus,
                title = updatedTitle,
                steps = updatedSteps,
                metadata = updatedMetadata
            )
        }
    }
}
