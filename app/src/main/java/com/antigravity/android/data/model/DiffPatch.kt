package com.antigravity.android.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SingularValue(
    val stringVal: String? = null,
    val intVal: Int? = null,
    val longVal: Long? = null,
    val boolVal: Boolean? = null,
    val floatVal: Float? = null,
    val nestedDiff: MessageDiff? = null
)

@Serializable
data class RepeatedDiff(
    val newLength: Int? = null,
    val updateIndices: List<Int> = emptyList(),
    val updateSteps: List<Step> = emptyList(),
    val tokenAppends: Map<Int, String> = emptyMap(),
    val appendValues: List<SingularValue> = emptyList()
)

@Serializable
data class MapDiff(
    val updates: Map<String, String> = emptyMap(),
    val removals: List<String> = emptyList()
)

@Serializable
data class FieldDiff(
    val fieldNumber: Int,
    val updateSingular: SingularValue? = null,
    val updateRepeated: RepeatedDiff? = null,
    val updateMap: MapDiff? = null,
    val clear: Boolean = false
)

@Serializable
data class MessageDiff(
    val fieldDiffs: List<FieldDiff> = emptyList()
)
