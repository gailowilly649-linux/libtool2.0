# MLBB Ultra 165 FPS & Fake Device Spoof Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unlock real 165 FPS engine rendering and the Ultra/Super high frame rate settings option in Mobile Legends: Bang Bang (`com.mobile.legends`) on Android 16 via fake device spoofing, game config binary patching (`GameSettings_Rank.bytes`), Unity `boot.config` uncapping, and Shizuku display refresh rate enforcement.

**Architecture:** A multi-layered high-FPS unlocker combining:
1. **Engine Layer (`boot.config`):** Injects Unity `target-frame-rate=165` and `v-sync-count=0` into game assets to uncap the Unity render loop.
2. **Game Config Layer (`GameSettings_Rank.bytes`):** Patches the internal Mulong table to elevate `m_custom_maxfps` to Tier 4 (144/165 FPS) across all device ranks.
3. **Device Profile & Settings Spoof:** Spoofs flagship gaming device profile (`ASUS ROG Phone 8 Pro / ASUS_AI2401`) and writes `high_fps_mode=4`, `fps_level=4`, and `__MaxFrameRateLevel__=4` into game cache/PlayerPrefs.
4. **Display SurfaceFlinger Layer:** Leverages Shizuku to lock screen refresh rate to maximum physical panel hardware capability (144.0 Hz) and sets Transsion/Tecno `game_fps_override=165`.
5. **UI Layer (`Android-LibTool-New`):** Adds a dedicated 165 FPS / Fake Spoof toggle in the floating overlay mod menu with instant status and one-click restore.

**Tech Stack:** Java, Android SDK 35 (Android 16), Shizuku Shell API, Unity Engine (2021/2022+), Mulong Binary Table Format.

---

## Hardware & Display Reality Check

> [!IMPORTANT]
> **Internal Screen Hardware vs Engine Frame Rate:**
> - Connected Device: **Tecno CM7 (Android 16)**
> - Physical Screen Supported Refresh Modes:
>   - Mode 1: `1080 x 2436 @ 120.00 Hz`
>   - Mode 2: `1080 x 2436 @ 144.00 Hz` (Physical Hardware Ceiling)
>   - Mode 3: `1080 x 2436 @ 60.00 Hz`
> - **Result:** When 165 FPS is unlocked, the GPU and Unity engine will render at up to **165 FPS** internally with ultra-low input latency. The physical panel will scan out at its maximum supported rate of **144 Hz**. If played on an external 165Hz+ monitor or recorded via high-speed video capture, it outputs full 165 FPS.

---

## Global Constraints

- **Non-Root Execution:** All file manipulations and settings must work via Shizuku shell (`user 2000`) without requiring root/Magisk.
- **Anti-Ban Safety:** No modification of executable code binaries (`libil2cpp.so` or `libunity.so`). All modifications are purely config-level (`boot.config`, `GameSettings_Rank.bytes`, `prefs_int`, `settings put`).
- **Idempotence & Reversibility:** Every unlock action must have an exact 1-click restore function that reverts to stock 60Hz and default MLBB settings.

---

## Review Focus

1. **MLBB Patch Overwrite:** MLBB mini-patches downloading in background could overwrite patched document files; must stage to both `dragon2017/assets/Document/android/` and active `mini_patch/*/Document/android/`.
2. **Display Frequency Throttling:** Transsion thermal throttling may force display back to 60Hz or 120Hz; must apply Transsion-specific settings (`tran_refresh_mode=144`, `tran_refresh_rate=144`, `tran_high_temperature_limit_refresh_rate=0`).
3. **Game Crash Prevention:** `GameSettings_Rank.bytes` Mulong signature (`$mulonggame_table_1`) and exact row size must be preserved byte-for-byte; only the 4-byte `m_custom_maxfps` fields are modified.
4. **Shizuku Disconnect:** If Shizuku is not running or permission is denied, UI must warn user gracefully instead of freezing.
5. **In-Game Settings UI Synchronization:** MLBB settings UI must reflect the new frame rate options without requiring game reinstallation.

---

## Implementation Tasks

### Task 1: Generate Patched `GameSettings_Rank.bytes` & `boot.config` Assets

**Files:**
- Create: `app/src/main/assets/mlbb_fps/boot.config`
- Create: `app/src/main/assets/mlbb_fps/GameSettings_Rank_165.bytes`
- Test: Script verifying table header integrity, row counts, and `m_custom_maxfps = 4` across all 50 device ranks.

- [ ] **Step 1:** Extract original `GameSettings_Rank.bytes` from `dump_mlbb/Document/android/Document.unity3d`.
- [ ] **Step 2:** Write Python patching script to parse Mulong table header and set `m_custom_maxfps` (column index 12) to `4` for all 50 rank tiers.
- [ ] **Step 3:** Generate `app/src/main/assets/mlbb_fps/boot.config` with:
  ```properties
  wait-for-native-debugger=0
  vr-enabled=0
  hdr-display-enabled=0
  gc-max-time-slice=3
  androidStartInFullscreen=1
  androidRenderOutsideSafeArea=1
  target-frame-rate=165
  v-sync-count=0
  gfx-enable-native-gfx-jobs=1
  ```
- [ ] **Step 4:** Place both patched assets into `app/src/main/assets/mlbb_fps/`.

---

### Task 2: Implement 165 FPS & Fake Spoof Logic in `MlbbModInjector.java`

**Files:**
- Modify: `app/src/main/java/com/android/support/shizuku/MlbbModInjector.java`

- [ ] **Step 1:** Add device profile constants for ASUS ROG Phone 8 Pro / RedMagic 9 Pro spoofing (`ASUS_AI2401_A`, manufacturer `asus`, brand `asus`).
- [ ] **Step 2:** Implement `unlockReal165Fps(Context context, boolean unlock, Callback callback)`:
  - When `unlock == true`:
    1. Force SurfaceFlinger to 144Hz:
       - `settings put system peak_refresh_rate 144.0`
       - `settings put system min_refresh_rate 144.0`
       - `settings put system user_refresh_rate 144`
       - `settings put system tran_refresh_mode 144`
       - `settings put system tran_refresh_rate 144`
       - `settings put system tran_screen_refresh_rate_mode 144`
       - `settings put system refresh_rate_setting 144`
       - `settings put system game_fps_override 165`
       - `settings put global peak_refresh_rate 144.0`
       - `settings put global min_refresh_rate 144.0`
       - `settings put global game_overlay_refresh_rate 165`
       - `settings put global force_refresh_rate 165`
    2. Deploy `boot.config` to:
       - `/sdcard/Android/data/com.mobile.legends/files/boot.config`
       - `/sdcard/Android/data/com.mobile.legends/files/dragon2017/assets/boot.config`
    3. Deploy patched `GameSettings_Rank_165.bytes` to:
       - `/sdcard/Android/data/com.mobile.legends/files/dragon2017/assets/Document/android/GameSettings_Rank.bytes`
       - `/sdcard/Android/data/com.mobile.legends/files/mini_patch/*/Document/android/GameSettings_Rank.bytes`
    4. Update `prefs_int` and `prefs_string`:
       - Append or update `__FrameRateLevel__:4`, `__MaxFrameRateLevel__:4`, `fps_level:4`, `high_fps_mode:4`.
  - When `unlock == false`:
    - Restore `peak_refresh_rate 60.0`, `min_refresh_rate 60.0`, remove custom `boot.config` and restored default table.
- [ ] **Step 3:** Implement helper method to verify active display mode via `dumpsys display`.

---

### Task 3: Update Floating Menu UI & Overlay Service

**Files:**
- Modify: `app/src/main/res/layout/floating_menu.xml`
- Modify: `app/src/main/java/com/android/support/OverlayService.java`

- [ ] **Step 1:** In `floating_menu.xml`, replace the old basic 120 FPS button with a modern high-performance card:
  - Title: **⚡ Unlock Real 165 FPS & Ultra Settings**
  - Subtitle: `ASUS ROG 8 Pro Spoof • 144Hz Panel Sync • Uncapped Engine`
  - Action Buttons: `[ Unlock 165 FPS ]` and `[ Restore Default (60Hz) ]`
  - Status badge showing active FPS mode and spoof state.
- [ ] **Step 2:** Wire button click events in `OverlayService.java` to invoke `MlbbModInjector.unlockReal165Fps`.
- [ ] **Step 3:** Show Toast feedback and update status indicators upon successful injection.

---

### Task 4: Compile, Deploy to Device & End-to-End Verification

**Files:**
- Build: `./gradlew assembleDebug`
- Deploy: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 1:** Run `./gradlew assembleDebug` and verify 0 compilation errors.
- [ ] **Step 2:** Install updated APK onto connected device `140892554P020737`.
- [ ] **Step 3:** Trigger 165 FPS unlock through Shizuku shell.
- [ ] **Step 4:** Query `dumpsys display` to verify 144Hz active refresh rate and inspect MLBB directory for staged `boot.config` and `GameSettings_Rank.bytes`.
- [ ] **Step 5:** Commit changes to Git `main` branch.
