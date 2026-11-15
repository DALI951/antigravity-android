package com.antigravity.android.network

import com.antigravity.android.data.network.ConnectBinaryFrame
import com.antigravity.android.data.network.ConnectFrameBuilder
import com.antigravity.android.data.network.ConnectFrameEnvelope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class ConnectProtocolTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val sampleCsrf = "62a9c73c-e20f-4d31-bbfc-03ebb744f9b9"

    // ==========================================
    // Tier 1: JSON Envelope Framing Tests
    // ==========================================

    @Test
    fun testStartFrame_hasStreamIdUuidAndHeaders() {
        val startFrame = ConnectFrameBuilder.createStartFrame(
            procedure = "/exa.language_server_pb.LanguageServerService/StartCascade",
            csrfToken = sampleCsrf
        )

        assertEquals("start", startFrame.type)
        assertNotNull(UUID.fromString(startFrame.streamId))
        assertEquals("/exa.language_server_pb.LanguageServerService/StartCascade", startFrame.procedure)
        assertNotNull(startFrame.headers)
        assertEquals(listOf("application/json"), startFrame.headers?.get(ConnectFrameBuilder.HEADER_CONTENT_TYPE))
        assertEquals(listOf(sampleCsrf), startFrame.headers?.get(ConnectFrameBuilder.HEADER_CSRF))
        assertEquals(listOf("antigravity-android"), startFrame.headers?.get(ConnectFrameBuilder.HEADER_HOST_CLIENT))
    }

    @Test
    fun testDataFrame_serializesAndDeserializesCorrectly() {
        val streamId = UUID.randomUUID().toString()
        val payload = buildJsonObject {
            put("message", "hello antigravity")
            put("step", 1)
        }
        val dataFrame = ConnectFrameBuilder.createDataFrame(streamId, payload)

        val jsonString = json.encodeToString(dataFrame)
        assertTrue(jsonString.contains("\"type\":\"data\""))
        assertTrue(jsonString.contains("\"streamId\":\"$streamId\""))

        val parsed = json.decodeFromString<ConnectFrameEnvelope>(jsonString)
        assertEquals("data", parsed.type)
        assertEquals(streamId, parsed.streamId)
        assertEquals(payload, parsed.payload)
    }

    @Test
    fun testEndFrame_hasStatusCodeZero() {
        val streamId = UUID.randomUUID().toString()
        val endFrame = ConnectFrameBuilder.createEndFrame(streamId, statusCode = 0)

        assertEquals("end", endFrame.type)
        assertEquals(0, endFrame.statusCode)
        assertNull(endFrame.error)
    }

    @Test
    fun testPingFrame_createsLivenessProbe() {
        val pingFrame = ConnectFrameBuilder.createPingFrame()
        assertEquals("ping", pingFrame.type)
        assertNotNull(pingFrame.streamId)
    }

    @Test
    fun testCancelFrame_createsCancellationEnvelope() {
        val streamId = UUID.randomUUID().toString()
        val cancelFrame = ConnectFrameBuilder.createCancelFrame(streamId)

        assertEquals("cancel", cancelFrame.type)
        assertEquals(streamId, cancelFrame.streamId)
    }

    // ==========================================
    // Tier 2: 5-Byte Binary Framing & Chunking Tests
    // ==========================================

    @Test
    fun testPackAndUnpackBinaryFrame_dataPayload() {
        val rawMessage = """{"status":"RUNNING","steps":[]}""".toByteArray(Charsets.UTF_8)
        val packed = ConnectFrameBuilder.packBinaryFrame(rawMessage, isEndStream = false)

        assertEquals(5 + rawMessage.size, packed.size)
        // Flag should be 0x00 for data
        assertEquals(0x00.toByte(), packed[0])

        // Verify big-endian 4-byte length
        val lengthFromHeader = ByteBuffer.wrap(packed, 1, 4).order(ByteOrder.BIG_ENDIAN).int
        assertEquals(rawMessage.size, lengthFromHeader)

        // Unpack
        val unpacked = ConnectFrameBuilder.unpackBinaryFrame(packed)
        assertEquals(0x00.toByte(), unpacked.flag)
        assertEquals(rawMessage.size, unpacked.length)
        assertFalse(unpacked.isEndStream)
        assertEquals(String(rawMessage), String(unpacked.payload))
    }

    @Test
    fun testPackAndUnpackBinaryFrame_endStreamTrailer() {
        val errorJson = ConnectFrameBuilder.serializeEndStreamError("resource_exhausted", "Quota exceeded")
        val packed = ConnectFrameBuilder.packBinaryFrame(errorJson, isEndStream = true)

        assertEquals(5 + errorJson.size, packed.size)
        // Flag should be 0x02 for end-of-stream
        assertEquals(0x02.toByte(), packed[0])

        val unpacked = ConnectFrameBuilder.unpackBinaryFrame(packed)
        assertEquals(0x02.toByte(), unpacked.flag)
        assertTrue(unpacked.isEndStream)
        assertTrue(String(unpacked.payload).contains("resource_exhausted"))
    }

    @Test
    fun testUnpackBinaryFrames_multipleConcatenatedFrames() {
        val msg1 = "frame-1".toByteArray(Charsets.UTF_8)
        val msg2 = "frame-2-longer-payload".toByteArray(Charsets.UTF_8)
        val endTrailer = """{"error":null}""".toByteArray(Charsets.UTF_8)

        val frame1 = ConnectFrameBuilder.packBinaryFrame(msg1, isEndStream = false)
        val frame2 = ConnectFrameBuilder.packBinaryFrame(msg2, isEndStream = false)
        val frame3 = ConnectFrameBuilder.packBinaryFrame(endTrailer, isEndStream = true)

        val combined = frame1 + frame2 + frame3
        val frames = ConnectFrameBuilder.unpackBinaryFrames(combined)

        assertEquals(3, frames.size)
        assertEquals("frame-1", String(frames[0].payload))
        assertFalse(frames[0].isEndStream)

        assertEquals("frame-2-longer-payload", String(frames[1].payload))
        assertFalse(frames[1].isEndStream)

        assertEquals("""{"error":null}""", String(frames[2].payload))
        assertTrue(frames[2].isEndStream)
    }

    @Test
    fun testUnpackBinaryFrames_partialStreamBuffer() {
        val msg1 = "complete-frame".toByteArray(Charsets.UTF_8)
        val frame1 = ConnectFrameBuilder.packBinaryFrame(msg1, isEndStream = false)
        val incompleteHeader = byteArrayOf(0x00, 0x00, 0x00) // only 3 bytes of header

        val buffer = frame1 + incompleteHeader
        val frames = ConnectFrameBuilder.unpackBinaryFrames(buffer)

        assertEquals(1, frames.size)
        assertEquals("complete-frame", String(frames[0].payload))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testUnpackBinaryFrame_tooShort_throwsException() {
        ConnectFrameBuilder.unpackBinaryFrame(byteArrayOf(0x00, 0x01, 0x02))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testUnpackBinaryFrame_truncatedPayload_throwsException() {
        val buffer = ByteBuffer.allocate(7).order(ByteOrder.BIG_ENDIAN)
        buffer.put(0x00.toByte())
        buffer.putInt(10) // Claims 10 bytes payload
        buffer.put(byteArrayOf(0x01, 0x02)) // Only provides 2 bytes
        ConnectFrameBuilder.unpackBinaryFrame(buffer.array())
    }

    // ==========================================
    // Tier 3: Validation, Error Handling & Thresholds
    // ==========================================

    @Test(expected = IllegalArgumentException::class)
    fun testStartFrame_withBlankCsrfToken_throwsException() {
        ConnectFrameBuilder.createStartFrame(
            procedure = "/service/method",
            csrfToken = "   "
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testStartFrame_withInvalidProcedure_throwsException() {
        ConnectFrameBuilder.createStartFrame(
            procedure = "serviceWithoutLeadingSlash",
            csrfToken = sampleCsrf
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testDataFrame_withEmptyStreamId_throwsException() {
        ConnectFrameBuilder.createDataFrame("", buildJsonObject {})
    }

    @Test
    fun testEndFrame_withNonZeroStatusCode_recordsError() {
        val streamId = UUID.randomUUID().toString()
        val endFrame = ConnectFrameBuilder.createEndFrame(streamId, statusCode = 14, error = "UNAVAILABLE: Connection dropped")

        assertEquals(14, endFrame.statusCode)
        assertEquals("UNAVAILABLE: Connection dropped", endFrame.error)
    }

    @Test
    fun testKeepaliveTimer_constantThresholds() {
        assertEquals("Ping idle trigger should be 2000ms", 2000L, ConnectFrameBuilder.PING_IDLE_TIMEOUT_MS)
        assertEquals("Liveness timeout should be 5000ms", 5000L, ConnectFrameBuilder.LIVENESS_ABORT_TIMEOUT_MS)
        assertEquals("Total silence threshold should be 7000ms", 7000L, ConnectFrameBuilder.TOTAL_SILENCE_TIMEOUT_MS)
    }

    @Test
    fun testMalformedJsonFrame_decodingGracefulFailure() {
        val malformedJson = "{\"streamId\": \"123\", \"type\": \"data\", \"unclosed\": }"
        var failed = false
        try {
            json.decodeFromString<ConnectFrameEnvelope>(malformedJson)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("Malformed JSON should throw deserialization exception", failed)
    }

    @Test
    fun testMultiplexedStreamTracking() {
        val streamMap = mutableMapOf<String, String>()
        val stream1 = UUID.randomUUID().toString()
        val stream2 = UUID.randomUUID().toString()

        streamMap[stream1] = "/service/procedure1"
        streamMap[stream2] = "/service/procedure2"

        assertEquals(2, streamMap.size)
        assertEquals("/service/procedure1", streamMap[stream1])
        assertEquals("/service/procedure2", streamMap[stream2])

        // End stream 1
        streamMap.remove(stream1)
        assertEquals(1, streamMap.size)
        assertFalse(streamMap.containsKey(stream1))
        assertTrue(streamMap.containsKey(stream2))
    }
}
