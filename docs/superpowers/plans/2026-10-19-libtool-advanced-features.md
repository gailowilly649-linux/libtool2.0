# Android-LibTool-New Advanced Capabilities Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform `Android-LibTool-New` into a complete runtime modding, stealth anti-detect, memory patching, and analysis suite for Mobile Legends: Bang Bang (v2.2.16.12322 SOSplit / Android 16).

**Architecture:** Modular C++ subsystems integrated into the ImGui overlay: `LivePatcher` for direct memory opcode modification (`KittyMemory`), `Stealth` for `/proc/self/maps` and symbol cloaking via Dobby hooks, `DumpExporter` for SOSplit-aware `dump.cs` and IDA/Ghidra/GG scripts, `ModFeatures` for drone view camera height scaling, `InvokerTab` for live method execution, and UI enhancements including floating bubble minimization and JSON config persistence.

**Tech Stack:** C++17, Android NDK r27, ImGui, KittyMemory, Dobby, IL2CPP API, ARM64 assembly.

---

## Global Constraints
- Target ABI: `arm64-v8a` (with 16KB page alignment `0x4000` for Android 16 CM7).
- Multi-library SOSplit target: `libcsharp.so`, `liblogic.so`, `libfirst.so`, `libil2cpp.so`.
- Zero crash tolerance: All memory writes must verify page boundaries and permissions via `KittyMemory::ProtectAddr`.
- Preserve existing comments and docstrings.

---

### Task 1: Live In-Memory Patcher (`LivePatcher`)
**Files:**
- Create: `app/src/main/jni/Tool/LivePatcher.h`
- Create: `app/src/main/jni/Tool/LivePatcher.cpp`
- Modify: `app/src/main/jni/Tool/ClassesTab.cpp`
- Modify: `app/src/main/jni/Tool/Tool.cpp`
- Modify: `app/src/main/jni/Android.mk`

**Interfaces:**
- Produces: `LivePatcher::PatchNop(uintptr_t addr, const std::string &name)`
- Produces: `LivePatcher::PatchRet(uintptr_t addr, int returnValue, const std::string &name)`
- Produces: `LivePatcher::PatchHex(uintptr_t addr, const std::string &hex, const std::string &name)`
- Produces: `LivePatcher::Restore(uintptr_t addr)`
- Produces: `LivePatcher::DrawTab()`
- Consumes: `KittyMemory::MemoryPatch`, `MethodInfo::getAbsAddress()`

- [x] **Step 1: Create `Tool/LivePatcher.h`**
  - Define `struct PatchItem { uintptr_t address; std::string name; std::string moduleName; std::string hexBytes; std::string origHex; bool active; };`
  - Declare functions: `Init()`, `PatchNop()`, `PatchRet()`, `PatchHex()`, `Restore()`, `RestoreAll()`, `DrawTab()`, `GetActivePatches()`.

- [x] **Step 2: Implement `Tool/LivePatcher.cpp`**
  - Implement ARM64 opcodes:
    - NOP: `1F 20 03 D5`
    - RET void: `C0 03 5F D6`
    - RET true: `20 00 80 52 C0 03 5F D6` (mov w0, #1; ret)
    - RET false: `00 00 80 52 C0 03 5F D6` (mov w0, #0; ret)
  - Utilize `KittyMemory::MemoryPatch::createWithHex` to safely patch target addresses and backup original bytes.
  - Implement `DrawTab()` with search bar, active patch list, enable/disable checkboxes, manual address & hex input, and one-click quick restore buttons.

- [x] **Step 3: Integrate Quick Patch Buttons into `ClassesTab.cpp` and `CombatMetadata`**
  - In `ClassesTab.cpp` method context menu / action buttons, add:
    - `[Patch NOP]`, `[RET True]`, `[RET False]`, `[Restore]`.
  - In `Tool::CombatStrings()`, add a `Patch` button next to identified combat methods.

- [x] **Step 4: Register in `Android.mk` and verify compilation**
  - Add `Tool/LivePatcher.cpp` to `LOCAL_SRC_FILES` in `Android.mk`.
  - Verify build with `./gradlew assembleDebug`.

---

### Task 2: Stealth & Anti-Detection Hardening (`Stealth`)
**Files:**
- Create: `app/src/main/jni/Tool/Stealth.h`
- Create: `app/src/main/jni/Tool/Stealth.cpp`
- Modify: `app/src/main/jni/Main.cpp`
- Modify: `app/src/main/jni/Android.mk`

**Interfaces:**
- Produces: `void Stealth::Init()`
- Produces: `void Stealth::SetCloakEnabled(bool enabled)`
- Produces: `bool Stealth::IsCloakEnabled()`
- Consumes: `DobbyHook`, `fopen`, `fopen64`, `open`, `openat`

- [x] **Step 1: Create `Tool/Stealth.h`**
  - Declare stealth API: `Init()`, `SetCloakEnabled(bool)`, `IsCloakEnabled()`, `DrawSettings()`.

- [x] **Step 2: Implement `Tool/Stealth.cpp`**
  - Hook libc `fopen`, `fopen64`, `open`, `openat` using Dobby.
  - If pathname contains `/proc/self/maps` or `/proc/<pid>/maps`:
    - Intercept and sanitize the file stream to remove lines referencing `libTool.so`, `libfrida_gum.so`, `libdobby.so`, or anonymous RWX memory blocks mapped by the tool.
  - Hook `signal`/`sigaction` to ignore SIGTRAP used by anti-debugging loops.

- [x] **Step 3: Register in `Android.mk` and initialize in `Main.cpp`**
  - Add `Tool/Stealth.cpp` to `Android.mk`.
  - Call `Stealth::Init()` in `Main.cpp` initialization thread.
  - Add stealth toggles into the Settings tab.

---

### Task 3: Full Runtime `dump.cs` & IDA/Ghidra/GG Script Exporter (`DumpExporter`)
**Files:**
- Create: `app/src/main/jni/Tool/DumpExporter.h`
- Create: `app/src/main/jni/Tool/DumpExporter.cpp`
- Modify: `app/src/main/jni/Tool/Tool.cpp`
- Modify: `app/src/main/jni/Android.mk`

**Interfaces:**
- Produces: `bool DumpExporter::ExportDumpCs(const std::string &outPath)`
- Produces: `bool DumpExporter::ExportIdaScript(const std::string &outPath)`
- Produces: `bool DumpExporter::ExportGhidraScript(const std::string &outPath)`
- Produces: `bool DumpExporter::ExportGameGuardianScript(const std::string &outPath)`
- Consumes: `Il2cpp::GetAssemblies()`, `MethodInfo::getAbsAddress()`, `getModuleRelativeOffset()`

- [x] **Step 1: Create `Tool/DumpExporter.h`**
  - Declare export functions for `dump.cs`, IDA Python, Ghidra Python, and GameGuardian Lua.

- [x] **Step 2: Implement `Tool/DumpExporter.cpp`**
  - Format live `dump.cs` with SOSplit annotations (`// Module: libcsharp.so | RVA: 0x...`).
  - Output IDA Pro script generating `idc.set_name(base + RVA, "Class$$Method")`.
  - Output Ghidra script generating `createLabel(toAddr(base + RVA), "Class$$Method", True)`.
  - Output GameGuardian Lua script table with module search queries and offset tables.

- [x] **Step 3: Integrate into `Tool::Dumper()` UI**
  - Add action buttons:
    - `[Export SOSplit dump.cs]`
    - `[Export IDA Pro Python Script]`
    - `[Export Ghidra Script]`
    - `[Export GameGuardian Script]`
  - Save outputs directly to `/sdcard/Download/` with notifications.

---

### Task 4: Drone View & Camera Height Modifier (`ModFeatures`)
**Files:**
- Create: `app/src/main/jni/Tool/ModFeatures.h`
- Create: `app/src/main/jni/Tool/ModFeatures.cpp`
- Modify: `app/src/main/jni/Main.cpp`
- Modify: `app/src/main/jni/Android.mk`

**Interfaces:**
- Produces: `void ModFeatures::Init()`
- Produces: `void ModFeatures::Update()`
- Produces: `void ModFeatures::DrawTab()`
- Consumes: `UnityEngine.Camera`, `il2cpp_runtime_invoke`

- [x] **Step 1: Create `Tool/ModFeatures.h`**
  - Declare camera height / drone view controls and overlay HUD renderer.

- [x] **Step 2: Implement `Tool/ModFeatures.cpp`**
  - Hook or dynamically invoke `UnityEngine.Camera.set_fieldOfView` and `set_orthographicSize`.
  - Implement Drone View slider (multiplier 1.0x to 3.5x).
  - Add toggle for HUD overlay (FPS counter, patch count, memory stats).

- [x] **Step 3: Add Drone View / Mod Tab into `Main.cpp`**
  - Add "Mod Features" tab to `mainTabber`.

---

### Task 5: Runtime Method Invoker & Object Inspector (`InvokerTab`)
**Files:**
- Create: `app/src/main/jni/Tool/InvokerTab.h`
- Create: `app/src/main/jni/Tool/InvokerTab.cpp`
- Modify: `app/src/main/jni/Tool/Tool.cpp`
- Modify: `app/src/main/jni/Android.mk`

**Interfaces:**
- Produces: `void InvokerTab::Draw()`
- Consumes: `Il2cpp::GC::FindObjects`, `il2cpp_runtime_invoke`, `Il2CppClass::getMethods`

- [x] **Step 1: Create `Tool/InvokerTab.h`**
  - Declare `InvokerTab::Draw()`.

- [x] **Step 2: Implement `Tool/InvokerTab.cpp`**
  - Interactive class selector.
  - List static fields and their runtime values.
  - Method parameter input fields for `int`, `float`, `bool`, and `string`.
  - "Invoke Method" button executing `il2cpp_runtime_invoke()` with error handling and result display.

- [x] **Step 3: Add "Invoker" tab into `Main.cpp`**

---

### Task 6: UI/UX Floating Bubble Minimization & Config Persistence
**Files:**
- Create: `app/src/main/jni/Tool/ConfigManager.h`
- Create: `app/src/main/jni/Tool/ConfigManager.cpp`
- Modify: `app/src/main/jni/Main.cpp`
- Modify: `app/src/main/jni/Android.mk`

**Interfaces:**
- Produces: `void ConfigManager::SavePatches()`
- Produces: `void ConfigManager::LoadPatches()`
- Produces: Draggable Floating Bubble UI state when minimized.

- [x] **Step 1: Implement `ConfigManager`**
  - Save enabled patches and drone view settings to `/sdcard/Download/libtool_config.json`.
  - Auto-load on startup.

- [x] **Step 2: Add Minimize to Floating Bubble in `Main.cpp`**
  - Add `[-]` minimize button in window title bar.
  - When minimized, render a small, draggable round icon: `[⚙️ LibTool]`.
  - Clicking the bubble restores the full menu.

- [x] **Step 3: Compile and Test Build**
  - Run `./gradlew assembleDebug` and verify clean build and 16KB page alignment.
