# Android-LibTool-New 🚀

Advanced Native Android IL2CPP Reverse Engineering, ImGui Overlay & Runtime Memory Tool.

![Android NDK](https://img.shields.io/badge/NDK-27.0.12077973-green.svg)
![Target SDK](https://img.shields.io/badge/TargetSDK-34-blue.svg)
![16KB Aligned](https://img.shields.io/badge/16KB_Page_Alignment-Verified-success.svg)
![Language](https://img.shields.io/badge/Language-C++17_|_Java-orange.svg)

---

## 🌟 Highlights & Key Features

- **🎮 Dear ImGui Native Overlay**: High-performance touch-responsive floating menu rendered directly via EGL / OpenGL ES 3.
- **🔍 IL2CPP Reflection & Invoker**: Dynamic metadata parsing, class browsing, method scanning, and live runtime method invocation.
- **🛡️ MemoryGuard & Anti-Tamper**: Memory integrity validation and protection mechanisms against debugging and hook tampering.
- **⚡ AOB (Array of Bytes) Scanner**: Fast sigscanning engine with wildcard (`??`) support for locating dynamic code patterns in memory.
- **🎯 ESP & Visual Canvas**: Native drawing primitives for boxes, skeletons, lines, and distance indicators.
- **🚀 120 FPS Dynamic Unlocker**: Frame pacing and display refresh rate hooker for buttery smooth rendering.
- **🔎 In-Game Memory Searcher**: Search and filter memory addresses across Dword, Float, Qword, and Hex data types.
- **🪝 Native Hooking Engines**: Powered by Dobby Hook, Frida Gum, and AsmJit JIT compilation.
- **📱 Android 15 Ready**: Fully aligned with `0x4000` (16 KB) page size support.

---

## 🛠️ Project Structure

```
Android-LibTool-New/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── jni/                    # Core C++ Native Code
│   │   │   ├── Main.cpp            # Entrypoint & JNI lifecycle
│   │   │   ├── asmjit/             # JIT code generation
│   │   │   ├── Dobby/              # Native inline hooking
│   │   │   ├── Frida/              # Frida Gum runtime
│   │   │   ├── Il2cpp/             # IL2CPP runtime & API bindings
│   │   │   ├── imgui/              # Dear ImGui library
│   │   │   ├── Menu/               # GUI tabs and themes
│   │   │   └── Tool/               # Advanced Power Modules
│   │   │       ├── AOBScanner.h/cpp
│   │   │       ├── MemoryGuard.h/cpp
│   │   │       ├── MemorySearcherTab.h/cpp
│   │   │       ├── ESP.h/cpp
│   │   │       ├── InvokerTab.h/cpp
│   │   │       └── Stealth.h/cpp
│   │   └── res/                    # Adaptive icons and layouts
│   └── build.gradle
├── gradle/
├── build.gradle
└── README.md
```

---

## 🚀 Building the Project

### Prerequisites
- **Android SDK** (API 34)
- **Android NDK** (r27 or compatible)
- **Java JDK** (17 or 21)

### Build Command
To compile both native C++ libraries (`libTool.so`) and the Android application package:

```bash
./gradlew assembleDebug
```

The output APK will be generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 📜 License
This project is distributed under the terms of the project LICENSE. For educational and security research purposes only.
