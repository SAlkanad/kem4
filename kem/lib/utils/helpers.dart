// lib/utils/helpers.dart
import 'dart:io';
import 'package:flutter/foundation.dart';

class AppHelpers {
  static String formatFileSize(int bytes) {
    if (bytes <= 0) return "0 B";
    const suffixes = ["B", "KB", "MB", "GB", "TB"];
    int i = (log(bytes) / log(1024)).floor();
    return ((bytes / pow(1024, i)).toStringAsFixed(1)) + ' ' + suffixes[i];
  }

  static bool isDebugMode() {
    return kDebugMode;
  }

  static String getCurrentPlatform() {
    if (Platform.isAndroid) return 'android';
    if (Platform.isIOS) return 'ios';
    if (Platform.isWindows) return 'windows';
    if (Platform.isMacOS) return 'macos';
    if (Platform.isLinux) return 'linux';
    return 'unknown';
  }

  static String sanitizeFileName(String fileName) {
    return fileName.replaceAll(RegExp(r'[<>:"/\\|?*]'), '_');
  }

  static bool isValidUrl(String url) {
    try {
      final uri = Uri.parse(url);
      return uri.hasScheme && (uri.scheme == 'http' || uri.scheme == 'https');
    } catch (e) {
      return false;
    }
  }
}

import 'dart:math';

double log(num x) => math.log(x);
double pow(num x, num exponent) => math.pow(x, exponent).toDouble();

class math {
  static double log(num x) => dartMath.log(x);
  static num pow(num x, num exponent) => dartMath.pow(x, exponent);
}

import 'dart:math' as dartMath;