package com.antigravity.android.data.network

import com.antigravity.android.data.model.AgentInstance
import com.antigravity.android.data.model.AgentRole
import com.antigravity.android.data.model.AgentStatus
import com.antigravity.android.data.model.CascadeTrajectory
import com.antigravity.android.data.model.FieldDiff
import com.antigravity.android.data.model.GeneratorMetadata
import com.antigravity.android.data.model.MapDiff
import com.antigravity.android.data.model.MessageDiff
import com.antigravity.android.data.model.ModelTurn
import com.antigravity.android.data.model.RepeatedDiff
import com.antigravity.android.data.model.SessionStatus
import com.antigravity.android.data.model.SessionSummary
import com.antigravity.android.data.model.SingularValue
import com.antigravity.android.data.model.Step
import com.antigravity.android.data.model.StepType
import com.antigravity.android.data.model.SubagentSpec
import com.antigravity.android.data.model.ToolCall
import com.antigravity.android.data.model.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

interface OrchestratorService {
    suspend fun startCascade(
        workspaceUris: List<String> = emptyList(),
        requestedModel: String? = null,
        initialPrompt: String? = null
    ): Result<String>

    suspend fun sendUserCascadeMessage(
        cascadeId: String,
        message: String
    ): Result<Unit>

    suspend fun cancelCascadeInvocation(
        cascadeId: String
    ): Result<Unit>

    suspend fun forceStopCascadeTree(
        cascadeId: String
    ): Result<Unit>

    suspend fun getCascadeTrajectory(
        cascadeId: String
    ): Result<CascadeTrajectory>

    suspend fun getAllCascadeSummaries(): Result<List<SessionSummary>>

    suspend fun acknowledgeCascadeCodeEdit(
        cascadeId: String,
        fileUri: String,
        contents: String,
        accept: Boolean
    ): Result<Unit>

    suspend fun acknowledgeCodeActionStep(
        cascadeId: String,
        accept: Boolean,
        writtenFeedback: String = ""
    ): Result<Unit>

    fun streamReactiveUpdates(
        cascadeId: String
    ): Flow<MessageDiff>

    fun streamCascadeSummaries(): Flow<MessageDiff>

    fun streamAgentStateUpdates(
        conversationId: String
    ): Flow<AgentInstance>
}

class OrchestratorServiceImpl(
    private val client: ConnectWebSocketClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : OrchestratorService {

    companion object {
        const val SERVICE_NAME = "exa.language_server_pb.LanguageServerService"
        const val METHOD_START_CASCADE = "/$SERVICE_NAME/StartCascade"
        const val METHOD_SEND_MESSAGE = "/$SERVICE_NAME/SendUserCascadeMessage"
        const val METHOD_CANCEL_INVOCATION = "/$SERVICE_NAME/CancelCascadeInvocation"
        const val METHOD_FORCE_STOP = "/$SERVICE_NAME/ForceStopCascadeTree"
        const val METHOD_GET_TRAJECTORY = "/$SERVICE_NAME/GetCascadeTrajectory"
        const val METHOD_GET_ALL_SUMMARIES = "/$SERVICE_NAME/GetAllCascadeSummaries"
        const val METHOD_ACK_CODE_EDIT = "/$SERVICE_NAME/AcknowledgeCascadeCodeEdit"
        const val METHOD_ACK_CODE_ACTION = "/$SERVICE_NAME/AcknowledgeCodeActionStep"
        const val METHOD_STREAM_REACTIVE = "/$SERVICE_NAME/StreamCascadeReactiveUpdates"
        const val METHOD_STREAM_SUMMARIES = "/$SERVICE_NAME/StreamCascadeSummariesReactiveUpdates"
        const val METHOD_STREAM_AGENT_STATES = "/$SERVICE_NAME/StreamAgentStateUpdates"
    }

    override suspend fun startCascade(
        workspaceUris: List<String>,
        requestedModel: String?,
        initialPrompt: String?
    ): Result<String> = runCatching {
        val payload = buildJsonObject {
            put("workspace_uris", buildJsonArray {
                workspaceUris.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            })
            if (!requestedModel.isNullOrBlank()) {
                put("requested_model", requestedModel)
            }
            if (!initialPrompt.isNullOrBlank()) {
                put("initial_prompt", initialPrompt)
            }
        }
        val response = client.executeUnary(METHOD_START_CASCADE, payload).getOrThrow()
        val cascadeId = response["cascade_id"]?.jsonPrimitive?.content
            ?: response["cascadeId"]?.jsonPrimitive?.content
            ?: UUID.randomUUID().toString()
        cascadeId
    }

    override suspend fun sendUserCascadeMessage(
        cascadeId: String,
        message: String
    ): Result<Unit> = runCatching {
        val payload = buildJsonObject {
            put("cascade_id", cascadeId)
            put("items", buildJsonArray {
                add(buildJsonObject {
                    put("text", message)
                })
            })
        }
        client.executeUnary(METHOD_SEND_MESSAGE, payload).getOrThrow()
    }

    override suspend fun cancelCascadeInvocation(
        cascadeId: String
    ): Result<Unit> = runCatching {
        val payload = buildJsonObject {
            put("cascade_id", cascadeId)
        }
        client.executeUnary(METHOD_CANCEL_INVOCATION, payload).getOrThrow()
    }

    override suspend fun forceStopCascadeTree(
        cascadeId: String
    ): Result<Unit> = runCatching {
        val payload = buildJsonObject {
            put("cascade_id", cascadeId)
        }
        client.executeUnary(METHOD_FORCE_STOP, payload).getOrThrow()
    }

    override suspend fun getCascadeTrajectory(
        cascadeId: String
    ): Result<CascadeTrajectory> = runCatching {
        val payload = buildJsonObject {
            put("cascade_id", cascadeId)
            put("trajectory_verbosity", "VERBOSE")
        }
        val response = client.executeUnary(METHOD_GET_TRAJECTORY, payload).getOrThrow()
        parseTrajectory(cascadeId, response)
    }

    override suspend fun getAllCascadeSummaries(): Result<List<SessionSummary>> = runCatching {
        val payload = buildJsonObject {}
        val response = client.executeUnary(METHOD_GET_ALL_SUMMARIES, payload).getOrThrow()
        val summariesArray = response["cascade_summaries"]?.jsonArray
            ?: response["summaries"]?.jsonArray
            ?: emptyList()
        summariesArray.mapNotNull { element ->
            if (element is JsonObject) parseSessionSummary(element) else null
        }
    }

    override suspend fun acknowledgeCascadeCodeEdit(
        cascadeId: String,
        fileUri: String,
        contents: String,
        accept: Boolean
    ): Result<Unit> = runCatching {
        val payload = buildJsonObject {
            put("cascade_id", cascadeId)
            put("absolute_uri", fileUri)
            put("contents", contents)
            put("accept", accept)
        }
        client.executeUnary(METHOD_ACK_CODE_EDIT, payload).getOrThrow()
    }

    override suspend fun acknowledgeCodeActionStep(
        cascadeId: String,
        accept: Boolean,
        writtenFeedback: String
    ): Result<Unit> = runCatching {
        val payload = buildJsonObject {
            put("cascade_id", cascadeId)
            put("accept", accept)
            put("written_feedback", writtenFeedback)
        }
        client.executeUnary(METHOD_ACK_CODE_ACTION, payload).getOrThrow()
    }

    override fun streamReactiveUpdates(
        cascadeId: String
    ): Flow<MessageDiff> {
        val payload = buildJsonObject {
            put("protocol_version", 1)
            put("id", cascadeId)
            put("subscriber_id", UUID.randomUUID().toString())
        }
        return client.executeStream(METHOD_STREAM_REACTIVE, payload).map { jsonObject ->
            parseMessageDiff(jsonObject["diff"]?.jsonObject ?: jsonObject)
        }
    }

    override fun streamCascadeSummaries(): Flow<MessageDiff> {
        val payload = buildJsonObject {
            put("protocol_version", 1)
            put("subscriber_id", UUID.randomUUID().toString())
        }
        return client.executeStream(METHOD_STREAM_SUMMARIES, payload).map { jsonObject ->
            parseMessageDiff(jsonObject["diff"]?.jsonObject ?: jsonObject)
        }
    }

    override fun streamAgentStateUpdates(
        conversationId: String
    ): Flow<AgentInstance> {
        val payload = buildJsonObject {
            put("conversation_id", conversationId)
        }
        return client.executeStream(METHOD_STREAM_AGENT_STATES, payload).map { jsonObject ->
            parseAgentInstance(jsonObject)
        }
    }

    private fun parseTrajectory(cascadeId: String, obj: JsonObject): CascadeTrajectory {
        val trajObj = obj["trajectory"]?.jsonObject ?: obj
        val statusStr = trajObj["status"]?.jsonPrimitive?.content ?: "RUNNING"
        val status = parseSessionStatus(statusStr)
        val title = trajObj["title"]?.jsonPrimitive?.content ?: ""
        val stepsList = trajObj["steps"]?.jsonArray?.mapIndexed { index, item ->
            parseStep(index, item.jsonObject)
        } ?: emptyList()

        val numTotal = trajObj["num_total_steps"]?.jsonPrimitive?.intOrNull ?: stepsList.size
        return CascadeTrajectory(
            cascadeId = cascadeId,
            status = status,
            title = title,
            steps = stepsList,
            numTotalSteps = numTotal
        )
    }

    private fun parseStep(defaultIndex: Int, stepObj: JsonObject): Step {
        val index = stepObj["step_index"]?.jsonPrimitive?.intOrNull ?: defaultIndex
        val role = stepObj["role"]?.jsonPrimitive?.content ?: "assistant"
        val content = stepObj["content"]?.jsonPrimitive?.content ?: ""
        val status = stepObj["status"]?.jsonPrimitive?.content ?: "COMPLETED"

        val typeStr = stepObj["type"]?.jsonPrimitive?.content ?: ""
        val type = when (typeStr.uppercase()) {
            "USER_MESSAGE" -> StepType.USER_MESSAGE
            "TOOL_CALL" -> StepType.TOOL_CALL
            "TOOL_RESULT" -> StepType.TOOL_RESULT
            "STATUS_UPDATE" -> StepType.STATUS_UPDATE
            "SYSTEM_NOTICE" -> StepType.SYSTEM_NOTICE
            else -> if (role == "user") StepType.USER_MESSAGE else StepType.MODEL_TURN
        }

        var modelTurn: ModelTurn? = null
        stepObj["model_turn"]?.jsonObject?.let { mt ->
            val tokens = mt["text_tokens"]?.jsonPrimitive?.content ?: ""
            val thinking = mt["thinking_block"]?.jsonPrimitive?.content ?: ""
            val toolCalls = mt["tool_calls"]?.jsonArray?.map { tc ->
                parseToolCall(tc.jsonObject)
            } ?: emptyList()
            modelTurn = ModelTurn(textTokens = tokens, thinkingBlock = thinking, toolCalls = toolCalls)
        }

        var toolCall: ToolCall? = null
        stepObj["tool_call"]?.jsonObject?.let { tc ->
            toolCall = parseToolCall(tc)
        }

        var toolResult: ToolResult? = null
        stepObj["tool_result"]?.jsonObject?.let { tr ->
            toolResult = ToolResult(
                toolCallId = tr["tool_call_id"]?.jsonPrimitive?.content ?: "",
                output = tr["output"]?.jsonPrimitive?.content ?: "",
                isError = tr["is_error"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        }

        return Step(
            stepIndex = index,
            type = type,
            role = role,
            content = content,
            modelTurn = modelTurn,
            toolCall = toolCall,
            toolResult = toolResult,
            status = status
        )
    }

    private fun parseToolCall(obj: JsonObject): ToolCall {
        return ToolCall(
            id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString(),
            toolName = obj["tool_name"]?.jsonPrimitive?.content ?: obj["name"]?.jsonPrimitive?.content ?: "tool",
            argumentsJson = obj["arguments_json"]?.jsonPrimitive?.content ?: obj["arguments"]?.toString() ?: "{}"
        )
    }

    private fun parseSessionSummary(obj: JsonObject): SessionSummary {
        val id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
        val title = obj["title"]?.jsonPrimitive?.content ?: "Untitled Session"
        val statusStr = obj["status"]?.jsonPrimitive?.content ?: "RUNNING"
        val status = parseSessionStatus(statusStr)
        val createdAt = obj["created_at"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
        val updatedAt = obj["updated_at"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
        val isPinned = obj["is_pinned"]?.jsonPrimitive?.booleanOrNull ?: false
        val isArchived = obj["is_archived"]?.jsonPrimitive?.booleanOrNull ?: false
        val unread = obj["unread_count"]?.jsonPrimitive?.intOrNull ?: 0

        val tags = obj["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()

        return SessionSummary(
            id = id,
            title = title,
            createdAt = createdAt,
            updatedAt = updatedAt,
            status = status,
            tags = tags,
            isPinned = isPinned,
            isArchived = isArchived,
            unreadCount = unread
        )
    }

    private fun parseAgentInstance(obj: JsonObject): AgentInstance {
        val agentId = obj["agent_id"]?.jsonPrimitive?.content ?: obj["conversation_id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
        val roleStr = obj["role"]?.jsonPrimitive?.content ?: "WORKER"
        val statusStr = obj["status"]?.jsonPrimitive?.content ?: "RUNNING"
        val parentId = obj["parent_conversation_id"]?.jsonPrimitive?.content
        val rootId = obj["root_conversation_id"]?.jsonPrimitive?.content
        val nestingDepth = obj["nesting_depth"]?.jsonPrimitive?.intOrNull ?: 0

        val role = runCatching { AgentRole.valueOf(roleStr.uppercase()) }.getOrDefault(AgentRole.WORKER)
        val status = runCatching { AgentStatus.valueOf(statusStr.uppercase()) }.getOrDefault(AgentStatus.RUNNING)

        return AgentInstance(
            agentId = agentId,
            role = role,
            typeName = obj["type_name"]?.jsonPrimitive?.content ?: "",
            status = status,
            parentConversationId = parentId,
            rootConversationId = rootId,
            nestingDepth = nestingDepth,
            assignedTaskId = obj["assigned_task_id"]?.jsonPrimitive?.content
        )
    }

    private fun parseSessionStatus(status: String): SessionStatus {
        return when (status.uppercase()) {
            "RUNNING" -> SessionStatus.RUNNING
            "IDLE" -> SessionStatus.IDLE
            "PAUSED" -> SessionStatus.PAUSED
            "WAITING_APPROVAL" -> SessionStatus.WAITING_APPROVAL
            "COMPLETED" -> SessionStatus.COMPLETED
            "CANCELLED" -> SessionStatus.CANCELLED
            "ERROR", "FAILED" -> SessionStatus.ERROR
            else -> SessionStatus.RUNNING
        }
    }

    fun parseMessageDiff(diffObj: JsonObject): MessageDiff {
        val fieldDiffsArray = diffObj["field_diffs"]?.jsonArray
            ?: diffObj["fieldDiffs"]?.jsonArray
            ?: emptyList()

        val fieldDiffs = fieldDiffsArray.mapNotNull { element ->
            if (element is JsonObject) parseFieldDiff(element) else null
        }
        return MessageDiff(fieldDiffs = fieldDiffs)
    }

    private fun parseFieldDiff(obj: JsonObject): FieldDiff {
        val fieldNumber = obj["field_number"]?.jsonPrimitive?.intOrNull
            ?: obj["fieldNumber"]?.jsonPrimitive?.intOrNull ?: 0
        val clear = obj["clear"]?.jsonPrimitive?.booleanOrNull ?: false

        var singular: SingularValue? = null
        val singObj = obj["update_singular"]?.jsonObject ?: obj["updateSingular"]?.jsonObject
        if (singObj != null) {
            singular = SingularValue(
                stringVal = singObj["string_val"]?.jsonPrimitive?.content ?: singObj["stringVal"]?.jsonPrimitive?.content,
                intVal = singObj["int_val"]?.jsonPrimitive?.intOrNull ?: singObj["intVal"]?.jsonPrimitive?.intOrNull,
                longVal = singObj["long_val"]?.jsonPrimitive?.longOrNull ?: singObj["longVal"]?.jsonPrimitive?.longOrNull,
                boolVal = singObj["bool_val"]?.jsonPrimitive?.booleanOrNull ?: singObj["boolVal"]?.jsonPrimitive?.booleanOrNull
            )
        }

        var repeated: RepeatedDiff? = null
        val repObj = obj["update_repeated"]?.jsonObject ?: obj["updateRepeated"]?.jsonObject
        if (repObj != null) {
            val newLength = repObj["new_length"]?.jsonPrimitive?.intOrNull ?: repObj["newLength"]?.jsonPrimitive?.intOrNull
            val tokenAppends = mutableMapOf<Int, String>()
            (repObj["token_appends"]?.jsonObject ?: repObj["tokenAppends"]?.jsonObject)?.forEach { (k, v) ->
                k.toIntOrNull()?.let { idx ->
                    tokenAppends[idx] = v.jsonPrimitive.content
                }
            }

            repeated = RepeatedDiff(
                newLength = newLength,
                tokenAppends = tokenAppends
            )
        }

        var mapDiff: MapDiff? = null
        val mapObj = obj["update_map"]?.jsonObject ?: obj["updateMap"]?.jsonObject
        if (mapObj != null) {
            val updates = mutableMapOf<String, String>()
            mapObj["updates"]?.jsonObject?.forEach { (k, v) ->
                updates[k] = v.jsonPrimitive.content
            }
            val removals = mapObj["removals"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
            mapDiff = MapDiff(updates = updates, removals = removals)
        }

        return FieldDiff(
            fieldNumber = fieldNumber,
            updateSingular = singular,
            updateRepeated = repeated,
            updateMap = mapDiff,
            clear = clear
        )
    }
}
