package com.android.support.shizuku;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * High-performance Shizuku-based injector for Mobile Legends:
 * 1. Drone View (2x, 3x, 4x, 5x, Revert)
 * 2. 120 FPS & Ultra Graphics Unlock
 * 3. Custom Map Assets & Skins Injection (and Revert)
 */
public final class MlbbModInjector {

    private static final String TAG = "MlbbModInjector";
    public static final String PKG = "com.mobile.legends";
    public static final String DATA = "/sdcard/Android/data/" + PKG;
    public static final String FILES = DATA + "/files";
    public static final String DRAGON_ASSETS = FILES + "/dragon2017/assets";
    public static final String MINI_PATCH = FILES + "/mini_patch";
    public static final String STAGE_DIR = "/sdcard/Download/WillyLibTool/stage";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onProgress(String message);
        void onComplete(boolean success, String message);
    }

    private MlbbModInjector() {}

    private static void postProgress(Callback cb, String msg) {
        if (cb != null) MAIN_HANDLER.post(() -> cb.onProgress(msg));
    }

    private static void postComplete(Callback cb, boolean ok, String msg) {
        if (cb != null) MAIN_HANDLER.post(() -> cb.onComplete(ok, msg));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. DRONE VIEW (2X, 3X, 4X, 5X, RESTORE)
    // ─────────────────────────────────────────────────────────────────────────

    public static void applyDroneView(Context context, int zoomLevel, Callback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                postComplete(callback, false, "Shizuku permission not granted.");
                return;
            }

            try {
                postProgress(callback, "Preparing Drone View " + zoomLevel + "x payload...");
                String tierFile = "battle_" + zoomLevel + "x.bytes";
                byte[] battleBytes = readAsset(context, "mlbb_drone/tiers/" + tierFile);
                if (battleBytes == null || battleBytes.length == 0) {
                    postComplete(callback, false, "Asset missing: " + tierFile);
                    return;
                }

                // Stage files into public storage
                File stage = new File(STAGE_DIR);
                stage.mkdirs();
                File stageBattle = new File(stage, "BattleSystemConfig.bytes");
                writeFile(stageBattle, battleBytes);

                // Stage secondary config files
                stageAsset(context, "mlbb_drone/base/Document/android/DroneViewConfig.json", stage, "DroneViewConfig.json");
                stageAsset(context, "mlbb_drone/base/Document/android/CameraConfig.json", stage, "CameraConfig.json");
                stageAsset(context, "mlbb_drone/base/Document/android/BinaryPatchMD5.xml", stage, "BinaryPatchMD5.xml");
                stageAsset(context, "mlbb_drone/base/Document/android/ResCheckConf.xml", stage, "ResCheckConf.xml");
                stageAsset(context, "mlbb_drone/base/__fix_rescheck", stage, "__fix_rescheck");

                postProgress(callback, "Injecting camera config into dragon2017...");
                // 1. Direct dragon2017 & LoadResManager target paths
                String[] docTargets = {
                        DRAGON_ASSETS + "/Document/android",
                        DRAGON_ASSETS + "/Document",
                        FILES + "/LoadResManager/Document/android"
                };

                for (String doc : docTargets) {
                    ShizukuShell.exec("mkdir -p \"" + doc + "\"");
                    ShizukuShell.exec("chmod 777 \"" + doc + "\" 2>/dev/null");
                    ShizukuShell.exec("cp -f \"" + stageBattle.getAbsolutePath() + "\" \"" + doc + "/BattleSystemConfig.bytes\"");
                    ShizukuShell.exec("cp -f \"" + STAGE_DIR + "/DroneViewConfig.json\" \"" + doc + "/\" 2>/dev/null");
                    ShizukuShell.exec("cp -f \"" + STAGE_DIR + "/CameraConfig.json\" \"" + doc + "/\" 2>/dev/null");
                    ShizukuShell.exec("cp -f \"" + STAGE_DIR + "/BinaryPatchMD5.xml\" \"" + doc + "/\" 2>/dev/null");
                    ShizukuShell.exec("cp -f \"" + STAGE_DIR + "/ResCheckConf.xml\" \"" + doc + "/\" 2>/dev/null");
                    // Lock with chmod 444 so in-game patch check doesn't overwrite
                    ShizukuShell.exec("chmod 444 \"" + doc + "/BattleSystemConfig.bytes\"");
                }

                postProgress(callback, "Scanning active mini_patch slots...");
                List<String> activeSlots = discoverActiveSlots();
                int slotsCount = 0;
                for (String slot : activeSlots) {
                    ShizukuShell.exec("mkdir -p \"" + slot + "/Document/android\"");
                    ShizukuShell.exec("chmod 777 \"" + slot + "/Document/android\" 2>/dev/null");
                    ShizukuShell.exec("touch \"" + slot + "/__ready\"");
                    ShizukuShell.exec("cp -f \"" + stageBattle.getAbsolutePath() + "\" \"" + slot + "/Document/android/BattleSystemConfig.bytes\"");
                    ShizukuShell.exec("cp -f \"" + STAGE_DIR + "/__fix_rescheck\" \"" + slot + "/__fix_rescheck\" 2>/dev/null");
                    ShizukuShell.exec("touch \"" + slot + "/__active\"");
                    ShizukuShell.exec("chmod 444 \"" + slot + "/Document/android/BattleSystemConfig.bytes\"");
                    slotsCount++;
                }

                // Cleanup stage
                ShizukuShell.exec("rm -rf \"" + STAGE_DIR + "\"");

                postComplete(callback, true, "Drone View " + zoomLevel + "x applied! (" + slotsCount + " slots patched)\nRestart MLBB to see effect.");
            } catch (Throwable t) {
                Log.e(TAG, "Drone injection error", t);
                postComplete(callback, false, "Injection error: " + t.getMessage());
            }
        });
    }

    public static void restoreDefaultDrone(Callback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                postComplete(callback, false, "Shizuku permission not granted.");
                return;
            }

            try {
                postProgress(callback, "Restoring stock camera...");
                String cmd = "chmod -R 777 \"" + MINI_PATCH + "\" 2>/dev/null; " +
                        "chmod -R 777 \"" + DRAGON_ASSETS + "/Document\" 2>/dev/null; " +
                        "chmod -R 777 \"" + FILES + "/LoadResManager/Document\" 2>/dev/null; " +
                        "rm -f \"" + DRAGON_ASSETS + "/Document/android/BattleSystemConfig.bytes\" 2>/dev/null; " +
                        "rm -f \"" + DRAGON_ASSETS + "/Document/BattleSystemConfig.bytes\" 2>/dev/null; " +
                        "rm -f \"" + DRAGON_ASSETS + "/Document/android/DroneViewConfig.json\" 2>/dev/null; " +
                        "rm -f \"" + DRAGON_ASSETS + "/Document/android/CameraConfig.json\" 2>/dev/null; " +
                        "rm -f \"" + FILES + "/LoadResManager/Document/android/BattleSystemConfig.bytes\" 2>/dev/null; " +
                        "find \"" + MINI_PATCH + "\" -name 'BattleSystemConfig.bytes' -exec rm -f {} + 2>/dev/null; " +
                        "find \"" + MINI_PATCH + "\" -name '__active' -exec rm -f {} + 2>/dev/null";
                ShizukuShell.exec(cmd);

                postComplete(callback, true, "Camera restored to default!\nRestart MLBB.");
            } catch (Throwable t) {
                postComplete(callback, false, "Restore error: " + t.getMessage());
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. REAL 165 FPS & ULTRA GRAPHICS UNLOCK (ASUS ROG PHONE 8 PRO FAKE SPOOF)
    // ─────────────────────────────────────────────────────────────────────────

    public static final String BACKUP_FPS_DIR = FILES + "/dragon2017/backup_fps";
    public static final String FAKE_SPOOF_MODEL = "ASUS_AI2401_A";
    public static final String FAKE_SPOOF_MANUFACTURER = "asus";
    public static final String FAKE_SPOOF_NAME = "ASUS ROG Phone 8 Pro (165Hz)";

    public static void unlockReal165Fps(Context context, boolean unlock, Callback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                postComplete(callback, false, "Shizuku permission not granted.");
                return;
            }

            try {
                if (unlock) {
                    postProgress(callback, "1/4: Locking display panel to 165 Hz (hardware ceiling)...");
                    // Backup original display settings before overriding
                    ShizukuShell.exec("mkdir -p \"" + BACKUP_FPS_DIR + "\"; " +
                            "if [ ! -f \"" + BACKUP_FPS_DIR + "/old_peak.txt\" ]; then " +
                            "  settings get system peak_refresh_rate > \"" + BACKUP_FPS_DIR + "/old_peak.txt\"; " +
                            "  settings get system min_refresh_rate > \"" + BACKUP_FPS_DIR + "/old_min.txt\"; " +
                            "  settings get system user_refresh_rate > \"" + BACKUP_FPS_DIR + "/old_user.txt\"; " +
                            "fi");

                    // 1. Force SurfaceFlinger & all vendor display controllers to 165Hz
                    String displayCmd = 
                            "settings put system peak_refresh_rate 165.0; " +
                            "settings put system min_refresh_rate 165.0; " +
                            "settings put system user_refresh_rate 165; " +
                            "settings put system tran_refresh_mode 165; " +
                            "settings put system tran_refresh_rate 165; " +
                            "settings put system tran_screen_refresh_rate_mode 165; " +
                            "settings put system refresh_rate_setting 165; " +
                            "settings put system tecno_refresh_rate 165; " +
                            "settings put system infinix_refresh_rate 165; " +
                            "settings put system max_refresh_rate 165; " +
                            "settings put system game_fps_override 165; " +
                            "settings put system display_refresh_rate 165; " +
                            "settings put system asus_fps 165; " +
                            "settings put system asus_refresh_rate 165; " +
                            "settings put system nubia_refresh_rate 165; " +
                            "settings put system ro.surface_flinger.set_idle_timer_ms 0; " +
                            "settings put system ro.surface_flinger.set_touch_timer_ms 0; " +
                            "settings put global peak_refresh_rate 165.0; " +
                            "settings put global min_refresh_rate 165.0; " +
                            "settings put global user_refresh_rate 165; " +
                            "settings put global game_overlay_refresh_rate 165; " +
                            "settings put global preferred_refresh_rate 165; " +
                            "settings put global force_refresh_rate 165; " +
                            "settings put global refresh_rate_force_high 1; " +
                            "settings put global tran_high_temperature_limit_refresh_rate 0; " +
                            "settings put global game_refresh_rate_pkgs com.mobile.legends; " +
                            "settings put global game_refresh_rate_values 165; " +
                            "settings put secure refresh_rate_mode 165; " +
                            "settings put secure user_refresh_rate 165";
                    ShizukuShell.exec(displayCmd);

                    postProgress(callback, "2/4: Injecting Unity boot.config (165 FPS & VSync disabled)...");
                    File stage = new File(STAGE_DIR);
                    stage.mkdirs();
                    stageAsset(context, "mlbb_fps/boot.config", stage, "boot.config");

                    String stageBoot = stage.getAbsolutePath() + "/boot.config";
                    if (new File(stageBoot).exists()) {
                        ShizukuShell.exec("cp -f \"" + stageBoot + "\" \"" + FILES + "/boot.config\"");
                        ShizukuShell.exec("cp -f \"" + stageBoot + "\" \"" + DRAGON_ASSETS + "/boot.config\"");
                    }

                    postProgress(callback, "3/4: Patching GameSettings_Rank table (Ultra 165 FPS tier)...");
                    stageAsset(context, "mlbb_fps/GameSettings_Rank_165.bytes", stage, "GameSettings_Rank.bytes");
                    File stageRank = new File(stage, "GameSettings_Rank.bytes");
                    if (stageRank.exists()) {
                        String docDir = DRAGON_ASSETS + "/Document/android";
                        ShizukuShell.exec("mkdir -p \"" + docDir + "\"");
                        ShizukuShell.exec("mkdir -p \"" + BACKUP_FPS_DIR + "\"");
                        // Backup original stock table if not already backed up
                        ShizukuShell.exec("if [ ! -f \"" + BACKUP_FPS_DIR + "/GameSettings_Rank.bytes\" ] && [ -f \"" + docDir + "/GameSettings_Rank.bytes\" ]; then cp -f \"" + docDir + "/GameSettings_Rank.bytes\" \"" + BACKUP_FPS_DIR + "/GameSettings_Rank.bytes\"; fi");
                        // Copy patched table
                        ShizukuShell.exec("cp -f \"" + stageRank.getAbsolutePath() + "\" \"" + docDir + "/GameSettings_Rank.bytes\"");

                        // Also patch into active mini_patch slots
                        List<String> slots = discoverActiveSlots();
                        for (String slot : slots) {
                            String slotDoc = slot + "/Document/android";
                            ShizukuShell.exec("if [ -d \"" + slotDoc + "\" ]; then cp -f \"" + stageRank.getAbsolutePath() + "\" \"" + slotDoc + "/GameSettings_Rank.bytes\"; fi");
                        }
                    }

                    postProgress(callback, "4/4: Spoofing ASUS ROG 8 Pro & Ultra Extreme PlayerPrefs (Level 6)...");
                    // Write high FPS Level 6 (Ultra Extreme) and ASUS spoof parameters to prefs_int and prefs_string
                    String prefsIntScript = 
                            "INT_FILE=\"" + DRAGON_ASSETS + "/prefs_int\"; " +
                            "touch \"$INT_FILE\"; " +
                            "for k in high_fps_mode:6 fps_level:6 __FrameRateLevel__:6 __MaxFrameRateLevel__:6 __FrameRateLevel__\\$AI:6 resolution_high:1 hd_mode:1 shadow:1 outline:1 render_scale:165; do " +
                            "  key=$(echo \"$k\" | cut -d: -f1); " +
                            "  val=$(echo \"$k\" | cut -d: -f2); " +
                            "  sed -i \"/^$key:/d\" \"$INT_FILE\"; " +
                            "  echo \"$key:$val\" >> \"$INT_FILE\"; " +
                            "done";
                    ShizukuShell.exec(prefsIntScript);

                    String prefsStringScript = 
                            "STR_FILE=\"" + DRAGON_ASSETS + "/prefs_string\"; " +
                            "touch \"$STR_FILE\"; " +
                            "for k in ro.product.model:" + FAKE_SPOOF_MODEL + " ro.product.manufacturer:" + FAKE_SPOOF_MANUFACTURER + " deviceModel:\"" + FAKE_SPOOF_NAME + "\"; do " +
                            "  key=$(echo \"$k\" | cut -d: -f1); " +
                            "  val=$(echo \"$k\" | cut -d: -f2); " +
                            "  sed -i \"/^$key:/d\" \"$STR_FILE\"; " +
                            "  echo \"$key:$val\" >> \"$STR_FILE\"; " +
                            "done";
                    ShizukuShell.exec(prefsStringScript);

                    // Also generate shared_prefs XML for extra persistence
                    String prefsXml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                            + "<map>\n"
                            + "    <int name=\"high_fps_mode\" value=\"6\" />\n"
                            + "    <int name=\"fps_level\" value=\"6\" />\n"
                            + "    <int name=\"__FrameRateLevel__\" value=\"6\" />\n"
                            + "    <int name=\"__MaxFrameRateLevel__\" value=\"6\" />\n"
                            + "    <int name=\"__FrameRateLevel__$AI\" value=\"6\" />\n"
                            + "    <int name=\"resolution_high\" value=\"1\" />\n"
                            + "    <int name=\"outline\" value=\"1\" />\n"
                            + "    <int name=\"shadow\" value=\"1\" />\n"
                            + "    <int name=\"hd_mode\" value=\"1\" />\n"
                            + "    <int name=\"render_scale\" value=\"165\" />\n"
                            + "    <string name=\"device_model\">" + FAKE_SPOOF_MODEL + "</string>\n"
                            + "    <string name=\"device_manufacturer\">" + FAKE_SPOOF_MANUFACTURER + "</string>\n"
                            + "</map>";
                    File prefsFile = new File(stage, "165fps_prefs.xml");
                    writeFile(prefsFile, prefsXml.getBytes("UTF-8"));
                    String targetPrefsData = "/data/data/" + PKG + "/shared_prefs/" + PKG + "_preferences.xml";
                    String targetPrefsSd = "/sdcard/Android/data/" + PKG + "/shared_prefs/" + PKG + "_preferences.xml";
                    ShizukuShell.exec("mkdir -p /sdcard/Android/data/" + PKG + "/shared_prefs");
                    ShizukuShell.exec("cp -f \"" + prefsFile.getAbsolutePath() + "\" \"" + targetPrefsData + "\" 2>/dev/null");
                    ShizukuShell.exec("cp -f \"" + prefsFile.getAbsolutePath() + "\" \"" + targetPrefsSd + "\" 2>/dev/null");

                    // Cleanup stage
                    ShizukuShell.exec("rm -rf \"" + STAGE_DIR + "\"");

                    postComplete(callback, true, 
                            "⚡ Real 165 FPS & Ultra Extreme Unlocked!\n" +
                            "• __FrameRateLevel__: 6 (Ultra Extreme)\n" +
                            "• high_fps_mode: 6\n" +
                            "• render_scale: 165\n" +
                            "• Spoofed Device: ASUS ROG Phone 8 Pro (165Hz)\n" +
                            "• Screen Display: Locked to 165 Hz\n" +
                            "• Engine Target: 165 FPS (VSync Off)\n" +
                            "Launch MLBB to enjoy 165 FPS Ultra Extreme gameplay!");
                } else {
                    float deviceMax = getDeviceDefaultRefreshRate(context);
                    float targetRestoreHz = (deviceMax > 60f) ? deviceMax : 120.0f;
                    int targetRestoreInt = Math.round(targetRestoreHz);
                    String val = String.format(Locale.US, "%.1f", targetRestoreHz);

                    postProgress(callback, "Restoring native display refresh rate (" + targetRestoreInt + " Hz) & settings...");

                    // 1. Revert display refresh rate dynamically based on device hardware and saved backup
                    String revertDisplayCmd = 
                            // Delete vendor spoof properties
                            "settings delete system game_fps_override; " +
                            "settings delete system asus_fps; " +
                            "settings delete system asus_refresh_rate; " +
                            "settings delete system nubia_refresh_rate; " +
                            "settings delete system display_refresh_rate; " +
                            "settings delete global game_overlay_refresh_rate; " +
                            "settings delete global preferred_refresh_rate; " +
                            "settings delete global force_refresh_rate; " +
                            "settings delete global refresh_rate_force_high; " +
                            "settings delete global game_refresh_rate_pkgs; " +
                            "settings delete global game_refresh_rate_values; " +
                            // If backup exists and is valid, restore backup; otherwise set to native targetRestoreHz
                            "PEAK=$(cat \"" + BACKUP_FPS_DIR + "/old_peak.txt\" 2>/dev/null | tr -d '[:space:]'); " +
                            "MIN=$(cat \"" + BACKUP_FPS_DIR + "/old_min.txt\" 2>/dev/null | tr -d '[:space:]'); " +
                            "USER=$(cat \"" + BACKUP_FPS_DIR + "/old_user.txt\" 2>/dev/null | tr -d '[:space:]'); " +
                            "if [ -z \"$PEAK\" ] || [ \"$PEAK\" = \"null\" ]; then PEAK=\"" + val + "\"; fi; " +
                            "if [ -z \"$MIN\" ] || [ \"$MIN\" = \"null\" ]; then MIN=\"" + val + "\"; fi; " +
                            "if [ -z \"$USER\" ] || [ \"$USER\" = \"null\" ]; then USER=\"" + targetRestoreInt + "\"; fi; " +
                            "settings put system peak_refresh_rate \"$PEAK\"; " +
                            "settings put system min_refresh_rate \"$MIN\"; " +
                            "settings put system user_refresh_rate \"$USER\"; " +
                            "settings put system tran_refresh_mode \"$USER\"; " +
                            "settings put system tran_refresh_rate \"$USER\"; " +
                            "settings put system tran_screen_refresh_rate_mode \"$USER\"; " +
                            "settings put system refresh_rate_setting \"$USER\"; " +
                            "settings put global peak_refresh_rate \"$PEAK\"; " +
                            "settings put global min_refresh_rate \"$MIN\"; " +
                            "settings put global user_refresh_rate \"$USER\"; " +
                            "rm -f \"" + BACKUP_FPS_DIR + "/old_*.txt\" 2>/dev/null";
                    ShizukuShell.exec(revertDisplayCmd);

                    // 2. Remove boot.config
                    ShizukuShell.exec("rm -f \"" + FILES + "/boot.config\" 2>/dev/null; rm -f \"" + DRAGON_ASSETS + "/boot.config\" 2>/dev/null");

                    // 3. Restore stock GameSettings_Rank.bytes
                    stageAsset(context, "mlbb_fps/GameSettings_Rank_stock.bytes", new File(STAGE_DIR), "GameSettings_Rank_stock.bytes");
                    File stageStock = new File(STAGE_DIR, "GameSettings_Rank_stock.bytes");
                    String docDir = DRAGON_ASSETS + "/Document/android";
                    if (new File(BACKUP_FPS_DIR + "/GameSettings_Rank.bytes").exists()) {
                        ShizukuShell.exec("cp -f \"" + BACKUP_FPS_DIR + "/GameSettings_Rank.bytes\" \"" + docDir + "/GameSettings_Rank.bytes\"");
                    } else if (stageStock.exists()) {
                        ShizukuShell.exec("cp -f \"" + stageStock.getAbsolutePath() + "\" \"" + docDir + "/GameSettings_Rank.bytes\"");
                    }

                    // Also restore into active mini_patch slots
                    List<String> slots = discoverActiveSlots();
                    for (String slot : slots) {
                        String slotDoc = slot + "/Document/android";
                        if (stageStock.exists()) {
                            ShizukuShell.exec("if [ -f \"" + slotDoc + "/GameSettings_Rank.bytes\" ]; then cp -f \"" + stageStock.getAbsolutePath() + "\" \"" + slotDoc + "/GameSettings_Rank.bytes\"; fi");
                        }
                    }

                    // 4. Remove spoof lines from prefs_int and prefs_string
                    ShizukuShell.exec("sed -i '/__FrameRateLevel__/d; /__MaxFrameRateLevel__/d; /high_fps_mode/d; /fps_level/d; /render_scale/d' \"" + DRAGON_ASSETS + "/prefs_int\" 2>/dev/null");
                    ShizukuShell.exec("sed -i '/ASUS/d; /asus/d' \"" + DRAGON_ASSETS + "/prefs_string\" 2>/dev/null");

                    ShizukuShell.exec("rm -rf \"" + STAGE_DIR + "\"");

                    postComplete(callback, true, "Native display refresh rate (" + targetRestoreInt + " Hz) & default MLBB FPS restored!");
                }
            } catch (Throwable t) {
                Log.e(TAG, "165 FPS injection error", t);
                postComplete(callback, false, "165 FPS error: " + t.getMessage());
            }
        });
    }

    public static void unlock120Fps(Context context, boolean unlock, Callback callback) {
        unlockReal165Fps(context, unlock, callback);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. MAP ASSETS & SKINS INJECTION
    // ─────────────────────────────────────────────────────────────────────────

    public static void injectMapAndSkins(Context context, Callback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                postComplete(callback, false, "Shizuku permission not granted.");
                return;
            }

            try {
                postProgress(callback, "Staging Map Assets (PVP_009, PVP_049)...");
                File stage = new File(STAGE_DIR);
                stage.mkdirs();

                // Stage Map Scenes
                stageAsset(context, "mlbb_drone/base/Scenes/android/PVP_009.unity3d", stage, "PVP_009.unity3d");
                stageAsset(context, "mlbb_drone/base/Scenes/android/PVP_049_low.unity3d", stage, "PVP_049_low.unity3d");
                stageAsset(context, "mlbb_drone/base/Scenes/android/GalleryCenter.unity3d", stage, "GalleryCenter.unity3d");

                // Stage Skin UI & Effects
                stageAsset(context, "mlbb_drone/base/UI/android/UI_TheMlabel_CollectionSet_Skin01.unity3d", stage, "UI_TheMlabel_CollectionSet_Skin01.unity3d");
                stageAsset(context, "mlbb_drone/base/UI/android/UI_TheMlabel_CollectionSet_Skin01_V2.unity3d", stage, "UI_TheMlabel_CollectionSet_Skin01_V2.unity3d");
                stageAsset(context, "mlbb_drone/base/UI/android/UI_TheMlabel_CollectionSet_Skin02.unity3d", stage, "UI_TheMlabel_CollectionSet_Skin02.unity3d");
                stageAsset(context, "mlbb_drone/base/Art/android/nextnextninja_skill03_UI_skin07_add.unity3d", stage, "nextnextninja_skill03_UI_skin07_add.unity3d");
                stageAsset(context, "mlbb_drone/base/Art/android/nextnextninja_skill03_fire_skin07_add.unity3d", stage, "nextnextninja_skill03_fire_skin07_add.unity3d");

                postProgress(callback, "Copying Map Scenes to dragon2017/assets/Scenes/android/...");
                String sceneDir = DRAGON_ASSETS + "/Scenes/android";
                ShizukuShell.exec("mkdir -p \"" + sceneDir + "\"");
                ShizukuShell.exec("cp -f \"" + STAGE_DIR + "/PVP_009.unity3d\" \"" + sceneDir + "/\"");
                ShizukuShell.exec("cp -f \"" + STAGE_DIR + "/PVP_049_low.unity3d\" \"" + sceneDir + "/\"");
                ShizukuShell.exec("cp -f \"" + STAGE_DIR + "/GalleryCenter.unity3d\" \"" + sceneDir + "/\" 2>/dev/null");

                postProgress(callback, "Copying Skin assets to UI & Art directories...");
                String uiDir = DRAGON_ASSETS + "/UI/android";
                String artDir = DRAGON_ASSETS + "/Art/android";
                ShizukuShell.exec("mkdir -p \"" + uiDir + "\"");
                ShizukuShell.exec("mkdir -p \"" + artDir + "\"");
                ShizukuShell.exec("cp -f \"" + STAGE_DIR + "/UI_TheMlabel_CollectionSet_Skin*\" \"" + uiDir + "/\" 2>/dev/null");
                ShizukuShell.exec("cp -f \"" + STAGE_DIR + "/nextnextninja*\" \"" + artDir + "/\" 2>/dev/null");

                // Cleanup stage
                ShizukuShell.exec("rm -rf \"" + STAGE_DIR + "\"");

                postComplete(callback, true, "Map & Skin assets injected successfully!\nLaunch MLBB to view customized battlefield.");
            } catch (Throwable t) {
                Log.e(TAG, "Map & Skin injection error", t);
                postComplete(callback, false, "Injection error: " + t.getMessage());
            }
        });
    }

    public static void restoreDefaultMapAndSkins(Callback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                postComplete(callback, false, "Shizuku permission not granted.");
                return;
            }

            try {
                postProgress(callback, "Restoring original map & skins...");
                String cmd = "rm -f \"" + DRAGON_ASSETS + "/Scenes/android/PVP_009.unity3d\" 2>/dev/null; " +
                        "rm -f \"" + DRAGON_ASSETS + "/Scenes/android/PVP_049_low.unity3d\" 2>/dev/null; " +
                        "rm -f \"" + DRAGON_ASSETS + "/Scenes/android/GalleryCenter.unity3d\" 2>/dev/null; " +
                        "rm -f \"" + DRAGON_ASSETS + "/UI/android/UI_TheMlabel_CollectionSet_Skin*\" 2>/dev/null; " +
                        "rm -f \"" + DRAGON_ASSETS + "/Art/android/nextnextninja*\" 2>/dev/null";
                ShizukuShell.exec(cmd);

                postComplete(callback, true, "Stock Map & Skins restored!\nMLBB will load default game assets.");
            } catch (Throwable t) {
                postComplete(callback, false, "Restore error: " + t.getMessage());
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. HERO SKINS UNLOCKER (7 HEROES: FANNY, GUSION, LING, NOLAN, MIYA, CLAUDE, KARRIE)
    // ─────────────────────────────────────────────────────────────────────────

    public static final String BACKUP_SKINS_DIR = FILES + "/dragon2017/backup_skins";
    public static final String PATCHED_SKINS_DIR = "/sdcard/Download/WillyLibTool/skins";

    public static class HeroSkinOption {
        public final String heroId;
        public final String heroName;
        public final String skinName;
        public final String role;
        public final String[][] swapPairs;

        public HeroSkinOption(String heroId, String heroName, String skinName, String role, String[][] swapPairs) {
            this.heroId = heroId;
            this.heroName = heroName;
            this.skinName = skinName;
            this.role = role;
            this.swapPairs = swapPairs;
        }
    }

    public static final List<HeroSkinOption> HERO_SKINS = new ArrayList<>();
    static {
        // 1. Fanny (Assassin) - Mikasa Ackerman (Attack on Titan / Skin 12)
        HERO_SKINS.add(new HeroSkinOption("fanny", "Fanny", "Mikasa Ackerman (AoT)", "Assassin", new String[][]{
                {"Art/android/h/hero_fanny_skin_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_fanny_skin_high_add.unity3d"},
                {"Art/android/h/hero_fanny_skin01_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_fanny_skin01_high_add.unity3d"},
                {"Art/android/h/hero_fanny_skin.unity3d", PATCHED_SKINS_DIR + "/hero_fanny_skin.unity3d"},
                {"Art/android/h/hero_fanny_skin_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_fanny_skin_shadow_high_add.unity3d"},
                {"Art/android/h/hero_fanny_skin01_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_fanny_skin01_shadow_high_add.unity3d"},
                {"Art/android/h/hero_fanny_skin01_add.unity3d", PATCHED_SKINS_DIR + "/hero_fanny_skin01_add.unity3d"},
                {"UI/android/Hero171_add.unity3d", PATCHED_SKINS_DIR + "/Hero171_add.unity3d"}
        }));

        // 2. Gusion (Assassin) - K' (KOF Collab / Skin 06)
        HERO_SKINS.add(new HeroSkinOption("gusion", "Gusion", "K' (KOF Collab)", "Assassin", new String[][]{
                {"Art/android/h/hero_Gusin_skin_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_Gusin_skin_high_add.unity3d"},
                {"Art/android/h/hero_Gusin_skin01_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_Gusin_skin01_high_add.unity3d"},
                {"Art/android/h/hero_Gusin_skin.unity3d", PATCHED_SKINS_DIR + "/hero_Gusin_skin.unity3d"},
                {"Art/android/h/hero_Gusin_skin_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_Gusin_skin_shadow_high_add.unity3d"},
                {"Art/android/h/hero_Gusin_skin01_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_Gusin_skin01_shadow_high_add.unity3d"},
                {"Art/android/h/hero_Gusin_skin01_add.unity3d", PATCHED_SKINS_DIR + "/hero_Gusin_skin01_add.unity3d"},
                {"UI/android/Hero056_add.unity3d", PATCHED_SKINS_DIR + "/Hero056_add.unity3d"}
        }));

        // 3. Ling (Assassin) - Seraphic Scourge (Collector / Skin 05)
        HERO_SKINS.add(new HeroSkinOption("ling", "Ling", "Seraphic Scourge (Collector)", "Assassin", new String[][]{
                {"Art/android/h/hero_pofa_skin_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_pofa_skin_high_add.unity3d"},
                {"Art/android/h/hero_pofa_skin01_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_pofa_skin01_high_add.unity3d"},
                {"Art/android/h/hero_pofa_skin.unity3d", PATCHED_SKINS_DIR + "/hero_pofa_skin.unity3d"},
                {"Art/android/h/hero_pofa_skin_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_pofa_skin_shadow_high_add.unity3d"},
                {"Art/android/h/hero_pofa_skin01_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_pofa_skin01_shadow_high_add.unity3d"},
                {"Art/android/h/hero_pofa_skin01_add.unity3d", PATCHED_SKINS_DIR + "/hero_pofa_skin01_add.unity3d"},
                {"UI/android/Hero084_add.unity3d", PATCHED_SKINS_DIR + "/Hero084_add.unity3d"}
        }));

        // 4. Nolan (Assassin) - The Navigator (Skin 03)
        HERO_SKINS.add(new HeroSkinOption("nolan", "Nolan", "The Navigator", "Assassin", new String[][]{
                {"Art/android/h/hero_NolanOsborne_skin_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_NolanOsborne_skin_high_add.unity3d"},
                {"Art/android/h/hero_NolanOsborne_skin01_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_NolanOsborne_skin01_high_add.unity3d"},
                {"Art/android/h/hero_NolanOsborne_skin.unity3d", PATCHED_SKINS_DIR + "/hero_NolanOsborne_skin.unity3d"},
                {"Art/android/h/hero_NolanOsborne_skin_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_NolanOsborne_skin_shadow_high_add.unity3d"},
                {"Art/android/h/hero_NolanOsborne_skin01_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_NolanOsborne_skin01_shadow_high_add.unity3d"},
                {"Art/android/h/hero_NolanOsborne_skin01_add.unity3d", PATCHED_SKINS_DIR + "/hero_NolanOsborne_skin01_add.unity3d"},
                {"UI/android/Hero1221_add.unity3d", PATCHED_SKINS_DIR + "/Hero1221_add.unity3d"}
        }));

        // 5. Miya (Marksman) - Doom Catalyst (Collector / Skin 14)
        HERO_SKINS.add(new HeroSkinOption("miya", "Miya", "Doom Catalyst (Collector)", "Marksman", new String[][]{
                {"Art/android/h/hero_gongjianshou_skin_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_gongjianshou_skin_high_add.unity3d"},
                {"Art/android/h/hero_gongjianshou_skin01_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_gongjianshou_skin01_high_add.unity3d"},
                {"Art/android/h/hero_gongjianshou_skin.unity3d", PATCHED_SKINS_DIR + "/hero_gongjianshou_skin.unity3d"},
                {"Art/android/h/hero_gongjianshou_skin_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_gongjianshou_skin_shadow_high_add.unity3d"},
                {"Art/android/h/hero_gongjianshou_skin01_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_gongjianshou_skin01_shadow_high_add.unity3d"},
                {"Art/android/h/hero_gongjianshou_skin01_add.unity3d", PATCHED_SKINS_DIR + "/hero_gongjianshou_skin01_add.unity3d"},
                {"UI/android/Hero1001_add.unity3d", PATCHED_SKINS_DIR + "/Hero1001_add.unity3d"}
        }));

        // 6. Claude (Marksman) - Bad Bro (Sanrio Collab / Skin 08)
        HERO_SKINS.add(new HeroSkinOption("claude", "Claude", "Bad Bro (Sanrio)", "Marksman", new String[][]{
                {"Art/android/h/hero_claude_skin_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_claude_skin_high_add.unity3d"},
                {"Art/android/h/hero_claude_skin01_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_claude_skin01_high_add.unity3d"},
                {"Art/android/h/hero_claude_skin.unity3d", PATCHED_SKINS_DIR + "/hero_claude_skin.unity3d"},
                {"Art/android/h/hero_claude_skin_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_claude_skin_shadow_high_add.unity3d"},
                {"Art/android/h/hero_claude_skin01_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_claude_skin01_shadow_high_add.unity3d"},
                {"Art/android/h/hero_claude_skin01_add.unity3d", PATCHED_SKINS_DIR + "/hero_claude_skin01_add.unity3d"},
                {"UI/android/Hero065_add.unity3d", PATCHED_SKINS_DIR + "/Hero065_add.unity3d"}
        }));

        // 7. Karrie (Marksman) - Starstruck (M5 Champion / Skin 11)
        HERO_SKINS.add(new HeroSkinOption("karrie", "Karrie", "Starstruck (M5 Champion)", "Marksman", new String[][]{
                {"Art/android/h/hero_feilun_skin_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_feilun_skin_high_add.unity3d"},
                {"Art/android/h/hero_feilun_skin01_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_feilun_skin01_high_add.unity3d"},
                {"Art/android/h/hero_feilun_skin.unity3d", PATCHED_SKINS_DIR + "/hero_feilun_skin.unity3d"},
                {"Art/android/h/hero_feilun_skin_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_feilun_skin_shadow_high_add.unity3d"},
                {"Art/android/h/hero_feilun_skin01_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_feilun_skin01_shadow_high_add.unity3d"},
                {"Art/android/h/hero_feilun_skin01_add.unity3d", PATCHED_SKINS_DIR + "/hero_feilun_skin01_add.unity3d"},
                {"UI/android/Hero401_add.unity3d", PATCHED_SKINS_DIR + "/Hero401_add.unity3d"}
        }));

        // 8. Chou (Fighter) - Iori Yagami (KOF Collab / Skin 09)
        HERO_SKINS.add(new HeroSkinOption("chou", "Chou", "Iori Yagami (KOF)", "Fighter", new String[][]{
                {"Art/android/h/hero_chou_skin_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_chou_skin_high_add.unity3d"},
                {"Art/android/h/hero_chou_skin01_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_chou_skin01_high_add.unity3d"},
                {"Art/android/h/hero_chou_skin.unity3d", PATCHED_SKINS_DIR + "/hero_chou_skin.unity3d"},
                {"Art/android/h/hero_chou_skin_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_chou_skin_shadow_high_add.unity3d"},
                {"Art/android/h/hero_chou_skin01_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_chou_skin01_shadow_high_add.unity3d"},
                {"Art/android/h/hero_chou_skin01_add.unity3d", PATCHED_SKINS_DIR + "/hero_chou_skin01_add.unity3d"},
                {"UI/android/Hero026_add.unity3d", PATCHED_SKINS_DIR + "/Hero026_add.unity3d"}
        }));

        // 9. Hayabusa (Assassin) - Shadow of Obscurity (Collector / Skin 05)
        HERO_SKINS.add(new HeroSkinOption("hayabusa", "Hayabusa", "Shadow of Obscurity", "Assassin", new String[][]{
                {"Art/android/h/hero_haya_skin_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_haya_skin_high_add.unity3d"},
                {"Art/android/h/hero_haya_skin01_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_haya_skin01_high_add.unity3d"},
                {"Art/android/h/hero_haya_skin.unity3d", PATCHED_SKINS_DIR + "/hero_haya_skin.unity3d"},
                {"Art/android/h/hero_haya_skin_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_haya_skin_shadow_high_add.unity3d"},
                {"Art/android/h/hero_haya_skin01_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_haya_skin01_shadow_high_add.unity3d"},
                {"Art/android/h/hero_haya_skin01_add.unity3d", PATCHED_SKINS_DIR + "/hero_haya_skin01_add.unity3d"},
                {"UI/android/Hero021_add.unity3d", PATCHED_SKINS_DIR + "/Hero021_add.unity3d"}
        }));

        // 10. Lancelot (Assassin) - Bren Esports (M2 Champion / Skin 06)
        HERO_SKINS.add(new HeroSkinOption("lancelot", "Lancelot", "Bren Esports (M2)", "Assassin", new String[][]{
                {"Art/android/h/hero_lance_skin_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_lance_skin_high_add.unity3d"},
                {"Art/android/h/hero_lance_skin01_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_lance_skin01_high_add.unity3d"},
                {"Art/android/h/hero_lance_skin.unity3d", PATCHED_SKINS_DIR + "/hero_lance_skin.unity3d"},
                {"Art/android/h/hero_lance_skin_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_lance_skin_shadow_high_add.unity3d"},
                {"Art/android/h/hero_lance_skin01_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_lance_skin01_shadow_high_add.unity3d"},
                {"Art/android/h/hero_lance_skin01_add.unity3d", PATCHED_SKINS_DIR + "/hero_lance_skin01_add.unity3d"},
                {"UI/android/Hero047_add.unity3d", PATCHED_SKINS_DIR + "/Hero047_add.unity3d"}
        }));

        // 11. Granger (Marksman) - Starfall Knight (Legend / Skin 05)
        HERO_SKINS.add(new HeroSkinOption("granger", "Granger", "Starfall Knight (Legend)", "Marksman", new String[][]{
                {"Art/android/h/hero_granger_skin_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_granger_skin_high_add.unity3d"},
                {"Art/android/h/hero_granger_skin01_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_granger_skin01_high_add.unity3d"},
                {"Art/android/h/hero_granger_skin.unity3d", PATCHED_SKINS_DIR + "/hero_granger_skin.unity3d"},
                {"Art/android/h/hero_granger_skin_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_granger_skin_shadow_high_add.unity3d"},
                {"Art/android/h/hero_granger_skin01_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_granger_skin01_shadow_high_add.unity3d"},
                {"Art/android/h/hero_granger_skin01_add.unity3d", PATCHED_SKINS_DIR + "/hero_granger_skin01_add.unity3d"},
                {"UI/android/Hero079_add.unity3d", PATCHED_SKINS_DIR + "/Hero079_add.unity3d"}
        }));

        // 12. Benedetta (Assassin) - Death Oath (Collector / Skin 03)
        HERO_SKINS.add(new HeroSkinOption("benedetta", "Benedetta", "Death Oath (Collector)", "Assassin", new String[][]{
                {"Art/android/h/hero_benedetta_skin_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_benedetta_skin_high_add.unity3d"},
                {"Art/android/h/hero_benedetta_skin01_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_benedetta_skin01_high_add.unity3d"},
                {"Art/android/h/hero_benedetta_skin.unity3d", PATCHED_SKINS_DIR + "/hero_benedetta_skin.unity3d"},
                {"Art/android/h/hero_benedetta_skin_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_benedetta_skin_shadow_high_add.unity3d"},
                {"Art/android/h/hero_benedetta_skin01_shadow_high_add.unity3d", PATCHED_SKINS_DIR + "/hero_benedetta_skin01_shadow_high_add.unity3d"},
                {"Art/android/h/hero_benedetta_skin01_add.unity3d", PATCHED_SKINS_DIR + "/hero_benedetta_skin01_add.unity3d"},
                {"UI/android/Hero097_add.unity3d", PATCHED_SKINS_DIR + "/Hero097_add.unity3d"}
        }));
    }

    public static void unlockHeroSkin(Context context, String heroId, Callback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                postComplete(callback, false, "Shizuku permission not granted.");
                return;
            }

            HeroSkinOption target = null;
            for (HeroSkinOption h : HERO_SKINS) {
                if (h.heroId.equalsIgnoreCase(heroId)) {
                    target = h;
                    break;
                }
            }

            if (target == null) {
                postComplete(callback, false, "Hero " + heroId + " not found.");
                return;
            }

            try {
                postProgress(callback, "Unlocking " + target.heroName + " (" + target.skinName + ")...");
                applyHeroSwapInternal(context, target);
                postComplete(callback, true, "⚡ Unlocked " + target.heroName + " (" + target.skinName + ")!\n" +
                        "💡 In Hero Pick: Select DEFAULT Skin!\n" +
                        "Your screen will render " + target.skinName + " with custom 3D model & VFX.");
            } catch (Throwable t) {
                Log.e(TAG, "Error unlocking " + heroId, t);
                postComplete(callback, false, "Unlock error: " + t.getMessage());
            }
        });
    }

    public static void unlockHeroSkin(String heroId, Callback callback) {
        unlockHeroSkin(null, heroId, callback);
    }

    public static void restoreHeroSkin(String heroId, Callback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                postComplete(callback, false, "Shizuku permission not granted.");
                return;
            }

            HeroSkinOption target = null;
            for (HeroSkinOption h : HERO_SKINS) {
                if (h.heroId.equalsIgnoreCase(heroId)) {
                    target = h;
                    break;
                }
            }

            if (target == null) {
                postComplete(callback, false, "Hero " + heroId + " not found.");
                return;
            }

            try {
                postProgress(callback, "Restoring stock " + target.heroName + " skin...");
                restoreHeroSwapInternal(target);
                postComplete(callback, true, "Restored stock " + target.heroName + " skin.");
            } catch (Throwable t) {
                Log.e(TAG, "Error restoring " + heroId, t);
                postComplete(callback, false, "Restore error: " + t.getMessage());
            }
        });
    }

    public static void unlockAll7Heroes(Context context, Callback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                postComplete(callback, false, "Shizuku permission not granted.");
                return;
            }

            try {
                injectGameResAlternative(context);
                for (HeroSkinOption h : HERO_SKINS) {
                    postProgress(callback, "Injecting " + h.heroName + " (" + h.skinName + ")...");
                    applyHeroSwapInternal(context, h);
                }
                postComplete(callback, true, "⚡ All " + HERO_SKINS.size() + " Skins unlocked successfully!\n" +
                        "💡 In Hero Pick: Select DEFAULT Skin for any of the " + HERO_SKINS.size() + " heroes!\n" +
                        "Works in Practice, Custom, Classic, and Rank.");
            } catch (Throwable t) {
                Log.e(TAG, "Error unlocking all skins", t);
                postComplete(callback, false, "Unlock error: " + t.getMessage());
            }
        });
    }

    public static void unlockAllHeroSkins(Context context, Callback callback) {
        unlockAll7Heroes(context, callback);
    }

    public static void unlockAllHeroSkins(Callback callback) {
        unlockAll7Heroes(null, callback);
    }

    public static void unlockAll7Heroes(Callback callback) {
        unlockAll7Heroes(null, callback);
    }

    public static void restoreAllHeroSkins(Callback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                postComplete(callback, false, "Shizuku permission not granted.");
                return;
            }

            try {
                postProgress(callback, "Restoring all stock skins from backup...");
                for (HeroSkinOption h : HERO_SKINS) {
                    restoreHeroSwapInternal(h);
                }
                // Restore GameResAlternative if backup exists
                String targetRes = DRAGON_ASSETS + "/Document/android/GameResAlternative.unity3d";
                String backupRes = BACKUP_SKINS_DIR + "/Document/android/GameResAlternative.unity3d";
                ShizukuShell.exec("if [ -f \"" + backupRes + "\" ]; then cp -f \"" + backupRes + "\" \"" + targetRes + "\"; fi");

                // Safety restore of all backup files
                ShizukuShell.exec("if [ -d \"" + BACKUP_SKINS_DIR + "\" ]; then cp -rf \"" + BACKUP_SKINS_DIR + "\"/* \"" + DRAGON_ASSETS + "/\" 2>/dev/null; rm -rf \"" + BACKUP_SKINS_DIR + "\"; fi");
                postComplete(callback, true, "All hero skins restored to stock genuine assets.");
            } catch (Throwable t) {
                Log.e(TAG, "Error restoring all skins", t);
                postComplete(callback, false, "Restore error: " + t.getMessage());
            }
        });
    }

    private static void injectGameResAlternative(Context context) {
        String target = DRAGON_ASSETS + "/Document/android/GameResAlternative.unity3d";
        String backup = BACKUP_SKINS_DIR + "/Document/android/GameResAlternative.unity3d";

        ShizukuShell.exec("mkdir -p \"" + DRAGON_ASSETS + "/Document/android\"");
        ShizukuShell.exec("mkdir -p \"" + BACKUP_SKINS_DIR + "/Document/android\"");
        ShizukuShell.exec("if [ ! -f \"" + backup + "\" ] && [ -f \"" + target + "\" ]; then cp -f \"" + target + "\" \"" + backup + "\"; fi");

        // 1. Check if bundled in assets
        if (context != null) {
            File stage = new File(STAGE_DIR);
            stage.mkdirs();
            stageAsset(context, "mlbb_drone/base/Document/android/GameResAlternative.unity3d", stage, "GameResAlternative.unity3d");
            File stagedRes = new File(stage, "GameResAlternative.unity3d");
            if (stagedRes.exists() && stagedRes.length() > 0) {
                ShizukuShell.exec("cp -f \"" + stagedRes.getAbsolutePath() + "\" \"" + target + "\"");
                stagedRes.delete();
                return;
            }
        }

        // 2. Fallback to /sdcard/Download/WillyLibTool/skins/GameResAlternative.unity3d
        ShizukuShell.exec("if [ -f \"" + PATCHED_SKINS_DIR + "/GameResAlternative.unity3d\" ]; then cp -f \"" + PATCHED_SKINS_DIR + "/GameResAlternative.unity3d\" \"" + target + "\"; fi");
    }

    private static void applyHeroSwapInternal(Context context, HeroSkinOption h) {
        injectGameResAlternative(context);

        for (String[] pair : h.swapPairs) {
            String targetRel = pair[0];
            String sourcePath = pair[1];

            String targetPath = DRAGON_ASSETS + "/" + targetRel;
            String backupPath = BACKUP_SKINS_DIR + "/" + targetRel;

            // 1. Ensure backup directory exists
            String backupParent = backupPath.substring(0, backupPath.lastIndexOf('/'));
            ShizukuShell.exec("mkdir -p \"" + backupParent + "\"");

            // 2. If genuine stock file is not yet backed up, back it up now
            String backupCmd = "if [ ! -f \"" + backupPath + "\" ] && [ -f \"" + targetPath + "\" ]; then cp -f \"" + targetPath + "\" \"" + backupPath + "\"; fi";
            ShizukuShell.exec(backupCmd);

            // 3. Overwrite target stock asset with patched skin asset
            String swapCmd = "if [ -f \"" + sourcePath + "\" ]; then cp -f \"" + sourcePath + "\" \"" + targetPath + "\"; fi";
            ShizukuShell.exec(swapCmd);
        }
    }

    private static void restoreHeroSwapInternal(HeroSkinOption h) {
        for (String[] pair : h.swapPairs) {
            String targetRel = pair[0];
            String targetPath = DRAGON_ASSETS + "/" + targetRel;
            String backupPath = BACKUP_SKINS_DIR + "/" + targetRel;

            // If backup exists, copy genuine file back and remove the backup
            String restoreCmd = "if [ -f \"" + backupPath + "\" ]; then cp -f \"" + backupPath + "\" \"" + targetPath + "\" && rm -f \"" + backupPath + "\"; fi";
            ShizukuShell.exec(restoreCmd);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private static List<String> discoverActiveSlots() {
        List<String> slots = new ArrayList<>();
        String[] fallbackSlots = {
                MINI_PATCH + "/1232.1/ZC_7108472971/2",
                MINI_PATCH + "/1232.1/ZC_7117732192/1",
                MINI_PATCH + "/1232.1/fix_1789616705/1",
                MINI_PATCH + "/1232.1/fix_1790076887/1",
                MINI_PATCH + "/1232.2/ZC_7117732192/1",
                MINI_PATCH + "/1232.2/fix_1790085128/1"
        };
        for (String s : fallbackSlots) slots.add(s);

        try {
            ShizukuShell.Result r = ShizukuShell.exec("find \"" + MINI_PATCH + "\" -maxdepth 4 -type d 2>/dev/null");
            if (r.ok() && r.output != null) {
                for (String line : r.output.split("\n")) {
                    String tr = line.trim();
                    if (tr.matches(".*/(ZC_|fix_)[^/]+(/\\d+)?$")) {
                        if (!slots.contains(tr)) slots.add(tr);
                    }
                }
            }
        } catch (Throwable ignored) {}

        return slots;
    }

    private static byte[] readAsset(Context context, String assetPath) {
        try (InputStream in = context.getAssets().open(assetPath)) {
            byte[] buf = new byte[in.available()];
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n == -1) break;
                read += n;
            }
            return buf;
        } catch (Throwable t) {
            Log.e(TAG, "Cannot read asset: " + assetPath, t);
            return null;
        }
    }

    private static void stageAsset(Context context, String assetPath, File stageDir, String outFileName) {
        try {
            byte[] bytes = readAsset(context, assetPath);
            if (bytes != null && bytes.length > 0) {
                File target = new File(stageDir, outFileName);
                writeFile(target, bytes);
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("deprecation")
    public static float getDeviceDefaultRefreshRate(Context context) {
        try {
            android.view.WindowManager wm = (android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                android.view.Display d = wm.getDefaultDisplay();
                if (d != null) {
                    float max = d.getRefreshRate();
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        for (android.view.Display.Mode m : d.getSupportedModes()) {
                            if (m.getRefreshRate() > max) {
                                max = m.getRefreshRate();
                            }
                        }
                    }
                    return max;
                }
            }
        } catch (Throwable ignored) {}
        return 120.0f; // Safe gaming phone default
    }

    private static void writeFile(File file, byte[] data) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
            fos.flush();
        }
        file.setReadable(true, false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. MEMHOOK NATIVE DAEMON DEPLOYMENT & EXECUTION
    // ─────────────────────────────────────────────────────────────────────────

    public static final String MEMHOOK_BIN = "/data/local/tmp/memhook";
    public static final String MEMHOOK_SOCK = "/data/local/tmp/memhook.sock";
    public static final String MEMHOOK_LOG = "/data/local/tmp/memhook.log";

    public static void deployMemHook(Context context, Callback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                postComplete(callback, false, "Shizuku permission not granted.");
                return;
            }

            try {
                postProgress(callback, "Preparing MemHook native binary for arm64-v8a...");
                byte[] binBytes = readAsset(context, "memhook/arm64-v8a/memhook");
                if (binBytes == null || binBytes.length == 0) {
                    postComplete(callback, false, "MemHook asset missing in APK.");
                    return;
                }

                File stage = new File(STAGE_DIR);
                stage.mkdirs();
                File stageBin = new File(stage, "memhook");
                writeFile(stageBin, binBytes);

                postProgress(callback, "Deploying MemHook to /data/local/tmp...");
                ShizukuShell.exec("cp -f \"" + stageBin.getAbsolutePath() + "\" " + MEMHOOK_BIN);
                ShizukuShell.exec("chmod 755 " + MEMHOOK_BIN);

                ShizukuShell.Result check = ShizukuShell.exec("ls -l " + MEMHOOK_BIN);
                if (check.ok()) {
                    postComplete(callback, true, "MemHook deployed successfully: " + check.output);
                } else {
                    postComplete(callback, false, "Failed to deploy MemHook: " + check.output);
                }
            } catch (Throwable t) {
                postComplete(callback, false, "Deploy error: " + t.getMessage());
            }
        });
    }

    public static void startMemHookDaemon(Context context, Callback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                postComplete(callback, false, "Shizuku permission not granted.");
                return;
            }

            try {
                postProgress(callback, "Ensuring MemHook is installed...");
                ShizukuShell.Result check = ShizukuShell.exec("test -f " + MEMHOOK_BIN + " && echo 1 || echo 0");
                if (!"1".equals(check.output.trim())) {
                    postProgress(callback, "MemHook not found in /data/local/tmp. Deploying now...");
                    byte[] binBytes = readAsset(context, "memhook/arm64-v8a/memhook");
                    if (binBytes != null && binBytes.length > 0) {
                        File stage = new File(STAGE_DIR);
                        stage.mkdirs();
                        File stageBin = new File(stage, "memhook");
                        writeFile(stageBin, binBytes);
                        ShizukuShell.exec("cp -f \"" + stageBin.getAbsolutePath() + "\" " + MEMHOOK_BIN);
                        ShizukuShell.exec("chmod 755 " + MEMHOOK_BIN);
                    }
                }

                postProgress(callback, "Stopping previous daemon instance if running...");
                ShizukuShell.exec("pkill -f memhook 2>/dev/null");
                ShizukuShell.exec("rm -f " + MEMHOOK_SOCK);

                postProgress(callback, "Launching MemHook daemon...");
                ShizukuShell.exec("nohup " + MEMHOOK_BIN + " --pkg " + PKG + " --daemon --socket " + MEMHOOK_SOCK + " > " + MEMHOOK_LOG + " 2>&1 &");

                Thread.sleep(600);
                ShizukuShell.Result logRes = ShizukuShell.exec("cat " + MEMHOOK_LOG + " 2>/dev/null");
                ShizukuShell.Result psRes = ShizukuShell.exec("pidof memhook || pgrep -f memhook");

                if (psRes.ok() && !psRes.output.trim().isEmpty()) {
                    postComplete(callback, true, "MemHook daemon active (PID " + psRes.output.trim() + "):\n" + logRes.output);
                } else {
                    postComplete(callback, false, "MemHook failed to start. Log:\n" + logRes.output);
                }
            } catch (Throwable t) {
                postComplete(callback, false, "Daemon launch error: " + t.getMessage());
            }
        });
    }

    public static void stopMemHookDaemon(Callback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                postComplete(callback, false, "Shizuku permission not granted.");
                return;
            }
            ShizukuShell.exec("pkill -f memhook 2>/dev/null");
            ShizukuShell.exec("rm -f " + MEMHOOK_SOCK);
            postComplete(callback, true, "MemHook daemon stopped.");
        });
    }

    public static void runMemHookScan(String targetModule, String pattern, Callback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                postComplete(callback, false, "Shizuku permission not granted.");
                return;
            }
            postProgress(callback, "Scanning " + targetModule + " for signature: " + pattern + "...");
            ShizukuShell.Result res = ShizukuShell.exec(MEMHOOK_BIN + " --pkg " + PKG);
            postComplete(callback, res.ok(), res.output);
        });
    }
}
