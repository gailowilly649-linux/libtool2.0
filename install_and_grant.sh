#!/usr/bin/env bash
set -e

APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.android.support"

if [ ! -f "$APK" ]; then
    echo "APK not found at $APK. Build it first."
    exit 1
fi

echo "Installing $APK..."
adb install -r "$APK"

echo "Granting SYSTEM_ALERT_WINDOW (floating overlay) permission..."
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow

echo "Granting storage permissions..."
adb shell pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
adb shell pm grant "$PKG" android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true

echo "Done! Tool is installed and ready."
