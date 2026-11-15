package com.antigravity.android.domain

import com.antigravity.android.data.model.CascadeTrajectory
import com.antigravity.android.data.model.FieldDiff
import com.antigravity.android.data.model.MessageDiff
import com.antigravity.android.data.model.SessionStatus
import com.antigravity.android.data.model.Step

class StateDiffReducer {
    companion object {
        const val FIELD_STATUS = 1
        const val FIELD_TITLE = 2
        const val FIELD_STEPS = 3
        const val FIELD_METADATA = 4
        const val FIELD_GENERATOR_METADATA = 5

        fun reduce(current: CascadeTrajectory, diff: MessageDiff): CascadeTrajectory {
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
                        fieldDiff.updateSingular?.stringVal?.let { statusStr ->
                            updatedStatus = runCatching {
                                SessionStatus.valueOf(statusStr.uppercase())
                            }.getOrDefault(updatedStatus)
                        }
                    }
                    FIELD_TITLE -> {
                        fieldDiff.updateSingular?.stringVal?.let { updatedTitle = it }
                    }
                    FIELD_STEPS -> {
                        fieldDiff.updateRepeated?.let { repDiff ->
                            // If updateIndices is provided, replace steps in-place; otherwise append to end
                            if (repDiff.updateIndices.isNotEmpty()) {
                                for ((i, idx) in repDiff.updateIndices.withIndex()) {
                                    if (i in repDiff.updateSteps.indices) {
                                        val newStep = repDiff.updateSteps[i]
                                        if (idx in updatedSteps.indices) {
                                            updatedSteps[idx] = newStep
                                        } else if (idx == updatedSteps.size) {
                                            updatedSteps.add(newStep)
                                        }
                                    }
                                }
                                for (i in repDiff.updateIndices.size until repDiff.updateSteps.size) {
                                    updatedSteps.add(repDiff.updateSteps[i])
                                }
                            } else {
                                for (newStep in repDiff.updateSteps) {
                                    updatedSteps.add(newStep)
                                }
                            }
                            // In-place token streaming appends
                            for ((idx, appendText) in repDiff.tokenAppends) {
                                if (idx in updatedSteps.indices) {
                                    val existing = updatedSteps[idx]
                                    val newContent = existing.content + appendText
                                    val newModelTurn = existing.modelTurn?.let { mt ->
                                        mt.copy(textTokens = mt.textTokens + appendText)
                                    }
                                    updatedSteps[idx] = existing.copy(
                                        content = newContent,
                                        modelTurn = newModelTurn
                                    )
                                }
                            }
                            // Truncate if newLength specified
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
                metadata = updatedMetadata,
                numTotalSteps = updatedSteps.size
            )
        }
    }
}
