# Advanced Power Modules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement all 5 senior-level power modules into `Android-LibTool-New`: Safe Memory Access / Crash Guard, Dynamic AOB Pattern Scanner, 120 FPS / Ultra Refresh Rate Unlocker, In-Game Memory Value Searcher & Freezer, and WorldToScreen ESP Visuals Engine.

**Architecture:**
1. `MemoryGuard`: Safe memory read using `process_vm_readv` and signal recovery to prevent crashes on invalid pointers.
2. `AOBScanner`: High-speed IDA-style hex signature scanner across loaded ELF segments.
3. `ModFeatures` Extension: 120 FPS Unlocker via `UnityEngine.Application.set_targetFrameRate` and `QualitySettings.set_vSyncCount`.
4. `MemorySearcherTab`: Full in-game memory scanner (Float/Int32/Int64) with first search, refine search, write, and background value freezing.
5. `ESP`: WorldToScreen projection engine and ImGui background overlay drawing boxes, snaplines, and health meters.

**Tech Stack:** C++17, Android NDK 27 (arm64-v8a), Dear ImGui, Unity/IL2CPP Reflection, Linux Syscalls (`process_vm_readv`).

---

### Task 1: Crash-Proof Memory Guard (`MemoryGuard`) [COMPLETED]
- Files:
  - Create: `app/src/main/jni/Tool/MemoryGuard.h`
  - Create: `app/src/main/jni/Tool/MemoryGuard.cpp`
- Features:
  - `SafeRead(src, dst, size)` using `process_vm_readv` (zero-signal kernel validation)
  - `SafeReadVal<T>(src, outVal)`
  - `IsValidPtr(ptr)`

### Task 2: Dynamic AOB Pattern Scanner (`AOBScanner`) [COMPLETED]
- Files:
  - Create: `app/src/main/jni/Tool/AOBScanner.h`
  - Create: `app/src/main/jni/Tool/AOBScanner.cpp`
- Features:
  - Parses IDA signatures: `"E0 03 1F 2A ?? ?? ?? 94"`
  - Scans specified module memory (`libil2cpp.so`, etc.)
  - Returns vector of matching absolute addresses and module RVAs
  - Helper `FindPattern(module, signature)`

### Task 3: 120 FPS Unlocker & Target Frame Rate Modifier [COMPLETED]
- Files:
  - Modify: `app/src/main/jni/Tool/ModFeatures.h`
  - Modify: `app/src/main/jni/Tool/ModFeatures.cpp`
- Features:
  - `SetTargetFPS(int fps)`: invokes `Application.set_targetFrameRate` and `QualitySettings.set_vSyncCount(0)`
  - UI options in Mod Features tab: 60 FPS, 90 FPS, 120 FPS, 144 FPS, Max (240 FPS)
  - Real-time FPS monitor display

### Task 4: In-Game Memory Value Searcher & Freezer (`MemorySearcherTab`) [COMPLETED]
- Files:
  - Create: `app/src/main/jni/Tool/MemorySearcherTab.h`
  - Create: `app/src/main/jni/Tool/MemorySearcherTab.cpp`
- Features:
  - Data types: `Float`, `Int32`, `Int64`
  - Search modes: `Exact`, `Increased`, `Decreased`, `Changed`
  - Refine search on existing address pool
  - Result list with live value display, edit modal, and background freeze thread
  - Tab integrated into `Main.cpp`

### Task 5: WorldToScreen ESP Visuals Engine (`ESP`) [COMPLETED]
- Files:
  - Create: `app/src/main/jni/Tool/ESP.h`
  - Create: `app/src/main/jni/Tool/ESP.cpp`
- Features:
  - Vector3 / Vector2 structs and Matrix4x4 math
  - WorldToScreen transformation from Unity Camera
  - ImGui Background DrawList rendering:
    - 2D Box (cornered or full)
    - Snaplines from top / bottom of screen
    - Distance indicator & health meter
  - UI controls in `ModFeatures`

### Task 6: Build Integration & Verification [COMPLETED]
- Files:
  - Modify: `app/src/main/jni/Android.mk`
  - Modify: `app/src/main/jni/Main.cpp`
- Verification:
  - Full `./gradlew assembleDebug` build (Passed in 53s / Incremental 1s)
  - 16KB alignment check (`0x4000` passed)
  - Final APK verification (`app-debug.apk` 3.0MB ready)
