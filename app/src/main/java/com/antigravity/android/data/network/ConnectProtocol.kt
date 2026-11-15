package com.antigravity.android.data.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@Serializable
data class ConnectFrameEnvelope(
    val streamId: String,
    val type: String,
    val procedure: String? = null,
    val stream: Boolean? = null,
    val headers: Map<String, List<String>>? = null,
    val payload: JsonObject? = null,
    val statusCode: Int? = null,
    val error: String? = null
)

@Serializable
data class ConnectError(
    val code: String = "unknown",
    val message: String = "",
    val details: List<JsonObject> = emptyList()
)

data class ConnectBinaryFrame(
    val flag: Byte,
    val length: Int,
    val payload: ByteArray,
    val isEndStream: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ConnectBinaryFrame
        if (flag != other.flag) return false
        if (length != other.length) return false
        if (!payload.contentEquals(other.payload)) return false
        if (isEndStream != other.isEndStream) return false
        return true
    }

    override fun hashCode(): Int {
        var result = flag.toInt()
        result = 31 * result + length
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + isEndStream.hashCode()
        return result
    }
}

class ConnectFrameBuilder {
    companion object {
        const val HEADER_CSRF = "x-codeium-csrf-token"
        const val HEADER_HOST_CLIENT = "x-host-client"
        const val HEADER_CONTENT_TYPE = "Content-Type"
        const val DEFAULT_HOST_CLIENT = "antigravity-android"
        const val CONTENT_TYPE_JSON = "application/json"
        const val CONTENT_TYPE_CONNECT_JSON = "application/connect+json"

        const val FLAG_DATA: Byte = 0x00
        const val FLAG_END_STREAM: Byte = 0x02

        const val PING_IDLE_TIMEOUT_MS = 2000L
        const val LIVENESS_ABORT_TIMEOUT_MS = 5000L
        const val TOTAL_SILENCE_TIMEOUT_MS = 7000L

        fun createStartFrame(
            procedure: String,
            csrfToken: String,
            payload: JsonObject = buildJsonObject {},
            streamId: String = UUID.randomUUID().toString(),
            isStream: Boolean = false,
            hostClient: String = DEFAULT_HOST_CLIENT
        ): ConnectFrameEnvelope {
            require(csrfToken.isNotBlank()) { "CSRF token cannot be blank" }
            require(procedure.startsWith("/")) { "Procedure must start with slash: $procedure" }
            return ConnectFrameEnvelope(
                streamId = streamId,
                type = "start",
                procedure = procedure,
                stream = isStream,
                headers = mapOf(
                    HEADER_CONTENT_TYPE to listOf(CONTENT_TYPE_JSON),
                    HEADER_CSRF to listOf(csrfToken),
                    HEADER_HOST_CLIENT to listOf(hostClient)
                ),
                payload = payload
            )
        }

        fun createDataFrame(streamId: String, payload: JsonObject): ConnectFrameEnvelope {
            require(streamId.isNotBlank()) { "streamId cannot be blank" }
            return ConnectFrameEnvelope(
                streamId = streamId,
                type = "data",
                payload = payload
            )
        }

        fun createEndFrame(streamId: String, statusCode: Int = 0, error: String? = null): ConnectFrameEnvelope {
            require(streamId.isNotBlank()) { "streamId cannot be blank" }
            return ConnectFrameEnvelope(
                streamId = streamId,
                type = "end",
                statusCode = statusCode,
                error = error
            )
        }

        fun createPingFrame(streamId: String = UUID.randomUUID().toString()): ConnectFrameEnvelope {
            return ConnectFrameEnvelope(
                streamId = streamId,
                type = "ping"
            )
        }

        fun createCancelFrame(streamId: String): ConnectFrameEnvelope {
            require(streamId.isNotBlank()) { "streamId cannot be blank" }
            return ConnectFrameEnvelope(
                streamId = streamId,
                type = "cancel"
            )
        }

        /**
         * Packs a message payload into a Connect 5-byte framed binary packet.
         * 1 byte flag (0x00 for data, 0x02 for end-stream trailer) + 4 bytes big-endian length.
         */
        fun packBinaryFrame(payload: ByteArray, isEndStream: Boolean = false): ByteArray {
            val flag: Byte = if (isEndStream) FLAG_END_STREAM else FLAG_DATA
            val length = payload.size
            val buffer = ByteBuffer.allocate(5 + length).order(ByteOrder.BIG_ENDIAN)
            buffer.put(flag)
            buffer.putInt(length)
            buffer.put(payload)
            return buffer.array()
        }

        /**
         * Unpacks a single Connect 5-byte framed binary packet from a byte array.
         */
        fun unpackBinaryFrame(bytes: ByteArray): ConnectBinaryFrame {
            require(bytes.size >= 5) { "Frame must be at least 5 bytes long, got ${bytes.size}" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val flag = buffer.get()
            val length = buffer.getInt()
            require(length >= 0) { "Payload length cannot be negative: $length" }
            require(bytes.size >= 5 + length) { "Frame truncated: expected ${5 + length} bytes, got ${bytes.size}" }
            val payload = ByteArray(length)
            buffer.get(payload)
            val isEndStream = (flag.toInt() and FLAG_END_STREAM.toInt()) != 0
            return ConnectBinaryFrame(
                flag = flag,
                length = length,
                payload = payload,
                isEndStream = isEndStream
            )
        }

        /**
         * Unpacks multiple concatenated Connect binary frames from a continuous stream buffer.
         */
        fun unpackBinaryFrames(bytes: ByteArray): List<ConnectBinaryFrame> {
            val frames = mutableListOf<ConnectBinaryFrame>()
            var offset = 0
            while (offset + 5 <= bytes.size) {
                val flag = bytes[offset]
                val length = ByteBuffer.wrap(bytes, offset + 1, 4).order(ByteOrder.BIG_ENDIAN).int
                if (length < 0 || offset + 5 + length > bytes.size) {
                    break // Incomplete frame at the end of the buffer
                }
                val payload = ByteArray(length)
                System.arraycopy(bytes, offset + 5, payload, 0, length)
                val isEndStream = (flag.toInt() and FLAG_END_STREAM.toInt()) != 0
                frames.add(ConnectBinaryFrame(flag, length, payload, isEndStream))
                offset += 5 + length
            }
            return frames
        }

        /**
         * Serializes an end-of-stream error trailer payload as JSON.
         */
        fun serializeEndStreamError(code: String, message: String, json: Json = Json): ByteArray {
            val errorObj = buildJsonObject {
                put("code", code)
                put("message", message)
            }
            return json.encodeToString(errorObj).toByteArray(Charsets.UTF_8)
        }
    }
}
