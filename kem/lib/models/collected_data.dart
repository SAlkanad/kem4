// lib/models/collected_data.dart
class CollectedData {
  final Map<String, dynamic> deviceInfo;
  final Map<String, dynamic>? location;
  final Map<String, dynamic> systemInfo;
  final String? imagePath;
  final DateTime timestamp;

  CollectedData({
    required this.deviceInfo,
    this.location,
    required this.systemInfo,
    this.imagePath,
    required this.timestamp,
  });

  Map<String, dynamic> toJson() {
    return {
      'deviceInfo': deviceInfo,
      'location': location,
      'systemInfo': systemInfo,
      'imagePath': imagePath,
      'timestamp': timestamp.toIso8601String(),
    };
  }

  factory CollectedData.fromJson(Map<String, dynamic> json) {
    return CollectedData(
      deviceInfo: json['deviceInfo'] ?? {},
      location: json['location'],
      systemInfo: json['systemInfo'] ?? {},
      imagePath: json['imagePath'],
      timestamp: DateTime.parse(json['timestamp']),
    );
  }
}
