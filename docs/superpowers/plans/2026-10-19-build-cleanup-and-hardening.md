# Build Cleanup, Warning Elimination & Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate all build warnings (NDK ABI riscv64 warnings, Gradle jcenter deprecation warnings), harden Android permissions for file export, fix potential null-pointer crashes in `InvokerTab.cpp`, and optimize Gradle build speed to achieve clean, fast, and warning-free builds.

**Architecture:** 
1. Fix NDK 27 metadata by removing the unsupported `riscv64` ABI entry that triggers 40+ warnings in AGP.
2. Modernize repository definitions in `build.gradle` by eliminating deprecated `jcenter()`.
3. Add full external and legacy storage permissions in `AndroidManifest.xml` for dumps and config persistence.
4. Add null-safety guards in `InvokerTab.cpp` for method/class names and zero-argument `Il2cpp::RuntimeInvoke`.
5. Optimize `gradle.properties` with parallel task execution.

**Tech Stack:** Gradle 7.2, Android Gradle Plugin 7.1.3, Android NDK 27.0.12077973, C++17 (arm64-v8a).

## Global Constraints
- Target ABI is strictly `arm64-v8a`.
- NDK page size alignment must remain `0x4000` (16KB) for Android 15/16.
- Existing comments and docstrings must be preserved.

---

### Task 1: Silence NDK 27 `riscv64` ABI Metadata Warnings

**Files:**
- Modify: `/usr/lib/android-sdk/ndk/27.0.12077973/meta/abis.json`

**Interfaces:**
- Consumes: `/usr/lib/android-sdk/ndk/27.0.12077973/meta/abis.json`
- Produces: Clean JSON containing only supported Android ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`)

- [x] **Step 1: Backup `abis.json` to `abis.json.bak`**
- [x] **Step 2: Remove the `riscv64` block from `/usr/lib/android-sdk/ndk/27.0.12077973/meta/abis.json`**
- [x] **Step 3: Run `./gradlew :app:stripDebugDebugSymbols` to verify 0 `riscv64` warnings appear**

---

### Task 2: Remove Deprecated `jcenter()` Repositories from `build.gradle`

**Files:**
- Modify: `build.gradle:5-25`

**Interfaces:**
- Consumes: Gradle repository declarations
- Produces: Repositories limited to `google()` and `mavenCentral()`

- [x] **Step 1: Edit `build.gradle` to delete `jcenter()` from `buildscript.repositories` and `allprojects.repositories`**
- [x] **Step 2: Run `./gradlew help --warning-mode all` and verify no JCenter deprecation warnings are emitted**

---

### Task 3: Declare Storage Permissions in `AndroidManifest.xml`

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:6-15`

**Interfaces:**
- Consumes: Storage access for `/sdcard/Download/` used by `DumpExporter` and `ConfigManager`
- Produces: Proper permission declarations for Android 10-16

- [x] **Step 1: Add permissions and attributes to `app/src/main/AndroidManifest.xml`:**
  - `<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />`
  - `<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="29" tools:ignore="ScopedStorage" />`
  - `<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" tools:ignore="ScopedStorage" />`
  - `android:requestLegacyExternalStorage="true"` in `<application>`
- [x] **Step 2: Verify manifest merges cleanly without warnings during `./gradlew processDebugMainManifest`**

---

### Task 4: Harden `InvokerTab.cpp` Null Pointer Safety & Invoke Signature

**Files:**
- Modify: `app/src/main/jni/Tool/InvokerTab.cpp:113-165`

**Interfaces:**
- Consumes: `Il2CppMethod::getName()`, `Il2CppType::getName()`, `Il2cpp::RuntimeInvoke`
- Produces: Null-safe method viewer and correct zero-arg invocation

- [x] **Step 1: Add null check for `m->getName()`, `retType->getName()`, and `s_SelectedClass->getName()` in `InvokerTab.cpp`**
- [x] **Step 2: Update `Il2cpp::RuntimeInvoke` call to pass `nullptr` for `params` when `canInvoke` (0 params)**
- [x] **Step 3: Verify native compilation passes with 0 warnings or errors**

---

### Task 5: Enable Parallel Builds in `gradle.properties` & Verify End-to-End Clean Build

**Files:**
- Modify: `gradle.properties`

**Interfaces:**
- Consumes: Gradle daemon build configuration
- Produces: Multi-threaded parallel task execution

- [x] **Step 1: Add `org.gradle.parallel=true` to `gradle.properties`**
- [x] **Step 2: Execute `./gradlew assembleDebug` and verify the build finishes completely warning-free and error-free**
- [x] **Step 3: Verify `app-debug.apk` exists with 16KB aligned `libTool.so`**
