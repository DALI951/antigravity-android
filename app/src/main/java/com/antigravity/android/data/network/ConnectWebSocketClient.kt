package com.antigravity.android.data.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow

sealed class ConnectionStatus {
    data class Connected(val endpoint: String) : ConnectionStatus()
    data class Reconnecting(val attempt: Int, val delayMs: Long) : ConnectionStatus()
    data class Disconnected(val reason: String) : ConnectionStatus()
}

class BackoffCalculator(
    val initialDelayMs: Long = 500L,
    val multiplier: Double = 1.5,
    val maxDelayMs: Long = 30000L
) {
    fun calculateDelay(attempt: Int): Long {
        if (attempt <= 1) return initialDelayMs
        val calculated = initialDelayMs * multiplier.pow((attempt - 1).toDouble())
        return min(calculated.toLong(), maxDelayMs)
    }
}

class OfflineBannerState(val status: ConnectionStatus) {
    val isVisible: Boolean
        get() = status !is ConnectionStatus.Connected

    val message: String
        get() = when (status) {
            is ConnectionStatus.Connected -> ""
            is ConnectionStatus.Reconnecting -> "Reconnecting to Antigravity Orchestrator (attempt ${status.attempt})..."
            is ConnectionStatus.Disconnected -> "No connection to Antigravity Orchestrator. Agent features may be offline."
        }
}

open class ConnectWebSocketClient(
    private var endpointUrl: String = "ws://127.0.0.1:49784/connect-websocket",
    private var csrfToken: String = "",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(7000, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for WS
        .build(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val backoffCalculator: BackoffCalculator = BackoffCalculator()
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private var webSocket: WebSocket? = null

    private val _connectionState = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected("Not initialized"))
    val connectionState: StateFlow<ConnectionStatus> = _connectionState.asStateFlow()

    private val activeStreams = ConcurrentHashMap<String, Channel<ConnectFrameEnvelope>>()
    private val lastInboundTimestamp = AtomicLong(System.currentTimeMillis())
    private val reconnectAttempt = AtomicLong(0)
    private val manualDisconnect = AtomicBoolean(false)

    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    fun updateEndpoint(url: String, token: String) {
        endpointUrl = if (url.startsWith("http://")) {
            url.replaceFirst("http://", "ws://").trimEnd('/') + "/connect-websocket"
        } else if (url.startsWith("https://")) {
            url.replaceFirst("https://", "wss://").trimEnd('/') + "/connect-websocket"
        } else if (url.startsWith("ws://") || url.startsWith("wss://")) {
            if (url.endsWith("/connect-websocket")) url else url.trimEnd('/') + "/connect-websocket"
        } else {
            "ws://$url/connect-websocket"
        }
        csrfToken = token
    }

    fun getEndpointUrl(): String = endpointUrl
    fun getCsrfToken(): String = csrfToken

    @Synchronized
    fun connect() {
        manualDisconnect.set(false)
        reconnectJob?.cancel()
        reconnectJob = null

        // Cleanly cancel existing dangling WebSocket before opening a new one
        try {
            webSocket?.cancel()
        } catch (_: Exception) {}
        webSocket = null

        val wsUrl = endpointUrl
        val requestBuilder = Request.Builder().url(wsUrl)
        if (csrfToken.isNotBlank()) {
            requestBuilder.header(ConnectFrameBuilder.HEADER_CSRF, csrfToken)
        }
        requestBuilder.header(ConnectFrameBuilder.HEADER_HOST_CLIENT, ConnectFrameBuilder.DEFAULT_HOST_CLIENT)

        val request = requestBuilder.build()
        webSocket = client.newWebSocket(request, createWebSocketListener(wsUrl))
        startHeartbeat()
    }

    @Synchronized
    fun disconnect(reason: String = "Client disconnected") {
        manualDisconnect.set(true)
        heartbeatJob?.cancel()
        heartbeatJob = null
        reconnectJob?.cancel()
        reconnectJob = null

        webSocket?.close(1000, reason)
        webSocket = null

        _connectionState.value = ConnectionStatus.Disconnected(reason)
        activeStreams.forEach { (_, channel) ->
            channel.close(Exception(reason))
        }
        activeStreams.clear()
    }

    private fun createWebSocketListener(endpoint: String): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt.set(0)
                lastInboundTimestamp.set(System.currentTimeMillis())
                _connectionState.value = ConnectionStatus.Connected(endpoint)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastInboundTimestamp.set(System.currentTimeMillis())
                handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!manualDisconnect.get()) {
                    scheduleReconnect(reason.ifBlank { "Socket closed: code $code" })
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!manualDisconnect.get()) {
                    scheduleReconnect(t.message ?: "Socket connection failed")
                }
            }
        }
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val frame = json.decodeFromString<ConnectFrameEnvelope>(text)
            when (frame.type) {
                "data" -> {
                    activeStreams[frame.streamId]?.trySend(frame)
                }
                "end" -> {
                    val channel = activeStreams[frame.streamId]
                    if (channel != null) {
                        channel.trySend(frame)
                        channel.close()
                        activeStreams.remove(frame.streamId)
                    }
                }
                "ping" -> {
                    // Send pong/data frame back if needed
                    val pongFrame = ConnectFrameEnvelope(streamId = frame.streamId, type = "ping")
                    sendEnvelope(pongFrame)
                }
            }
        } catch (_: Exception) {
            // Ignore malformed frames gracefully
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (manualDisconnect.get()) return

        val attempt = reconnectAttempt.incrementAndGet().toInt()
        val delayMs = backoffCalculator.calculateDelay(attempt)
        _connectionState.value = ConnectionStatus.Reconnecting(attempt, delayMs)

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!manualDisconnect.get()) {
                connect()
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && !manualDisconnect.get()) {
                delay(ConnectFrameBuilder.PING_IDLE_TIMEOUT_MS)
                val elapsedSinceLast = System.currentTimeMillis() - lastInboundTimestamp.get()

                if (_connectionState.value is ConnectionStatus.Connected) {
                    if (elapsedSinceLast >= ConnectFrameBuilder.TOTAL_SILENCE_TIMEOUT_MS) {
                        // Connection dead
                        webSocket?.cancel()
                        scheduleReconnect("Liveness probe timed out ($elapsedSinceLast ms silence)")
                        break
                    } else if (elapsedSinceLast >= ConnectFrameBuilder.PING_IDLE_TIMEOUT_MS) {
                        // Send ping probe
                        val ping = ConnectFrameBuilder.createPingFrame()
                        sendEnvelope(ping)
                    }
                }
            }
        }
    }

    fun sendEnvelope(envelope: ConnectFrameEnvelope): Boolean {
        val socket = webSocket ?: return false
        val message = json.encodeToString(envelope)
        return socket.send(message)
    }

    suspend fun executeUnary(
        procedure: String,
        payload: JsonObject = buildJsonObject {},
        timeoutMs: Long = 10000L
    ): Result<JsonObject> = runCatching {
        val streamId = UUID.randomUUID().toString()
        val channel = Channel<ConnectFrameEnvelope>(Channel.BUFFERED)
        activeStreams[streamId] = channel

        val startFrame = ConnectFrameBuilder.createStartFrame(
            procedure = procedure,
            csrfToken = csrfToken,
            payload = payload,
            streamId = streamId,
            isStream = false
        )

        val sent = sendEnvelope(startFrame)
        if (!sent) {
            activeStreams.remove(streamId)
            throw IllegalStateException("Failed to dispatch frame: WebSocket is not connected")
        }

        withTimeout(timeoutMs) {
            var resultPayload: JsonObject? = null
            for (frame in channel) {
                if (frame.type == "data" && frame.payload != null) {
                    resultPayload = frame.payload
                } else if (frame.type == "end") {
                    if (frame.statusCode != null && frame.statusCode != 0) {
                        throw IllegalStateException("RPC error code ${frame.statusCode}: ${frame.error}")
                    }
                    break
                }
            }
            resultPayload ?: buildJsonObject {}
        }
    }

    fun executeStream(
        procedure: String,
        payload: JsonObject = buildJsonObject {}
    ): Flow<JsonObject> = flow {
        val streamId = UUID.randomUUID().toString()
        val channel = Channel<ConnectFrameEnvelope>(Channel.UNLIMITED)
        activeStreams[streamId] = channel

        val startFrame = ConnectFrameBuilder.createStartFrame(
            procedure = procedure,
            csrfToken = csrfToken,
            payload = payload,
            streamId = streamId,
            isStream = true
        )

        val sent = sendEnvelope(startFrame)
        if (!sent) {
            activeStreams.remove(streamId)
            throw IllegalStateException("WebSocket is not connected")
        }

        try {
            for (frame in channel) {
                if (frame.type == "data" && frame.payload != null) {
                    emit(frame.payload)
                } else if (frame.type == "end") {
                    if (frame.statusCode != null && frame.statusCode != 0) {
                        throw IllegalStateException("Stream ended with error code ${frame.statusCode}: ${frame.error}")
                    }
                    break
                }
            }
        } finally {
            cancelStream(streamId)
        }
    }

    fun cancelStream(streamId: String) {
        val channel = activeStreams.remove(streamId)
        channel?.close()
        sendEnvelope(ConnectFrameBuilder.createCancelFrame(streamId))
    }
}
