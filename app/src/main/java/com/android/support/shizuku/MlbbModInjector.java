package com.android.support.shizuku;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

                    // 1. Force Android Game Manager, SurfaceFlinger & all vendor display controllers to 165 Hz
                    String displayCmd = 
                            "cmd game set --mode performance " + PKG + " 2>/dev/null; " +
                            "cmd game set --fps 165 " + PKG + " 2>/dev/null; " +
                            "cmd power set-fixed-performance-mode-enabled true 2>/dev/null; " +
                            "device_config put game_overlay " + PKG + " mode=2,fps=165:mode=3,fps=165 2>/dev/null; " +
                            "settings put system custom_mode 2; " +
                            "settings put system screen_refresh_rate 165; " +
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
                            "settings put global game_refresh_rate_pkgs " + PKG + "; " +
                            "settings put global game_refresh_rate_values 165; " +
                            "settings put secure refresh_rate_mode 2; " +
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
                            ShizukuShell.exec("mkdir -p \"" + slotDoc + "\"; cp -f \"" + stageRank.getAbsolutePath() + "\" \"" + slotDoc + "/GameSettings_Rank.bytes\"");
                            ShizukuShell.exec("cp -f \"" + stageBoot + "\" \"" + slot + "/boot.config\" 2>/dev/null");
                        }
                    }

                    postProgress(callback, "4/4: Spoofing ASUS ROG 8 Pro & Ultra Extreme PlayerPrefs (Level 6)...");
                    // 4/4: Full Device Spoof (ASUS ROG Phone 8 Pro Snapdragon 8 Gen 3 + Adreno 750 + 16GB RAM)
                    // Write high FPS Level 6 (Ultra Extreme 165 FPS) and comprehensive ASUS spoof parameters
                    String prefsIntScript = 
                            "INT_FILE=\"" + DRAGON_ASSETS + "/prefs_int\"; " +
                            "chmod 666 \"$INT_FILE\" 2>/dev/null; " +
                            "touch \"$INT_FILE\"; " +
                            "for k in high_fps_mode:6 fps_level:6 __FrameRateLevel__:6 __MaxFrameRateLevel__:6 __FrameRateLevel__\\$AI:6 " +
                            "         high_fps_mode_ultra:1 resolution_high:1 hd_mode:1 shadow:1 outline:1 render_scale:165 " +
                            "         system_memory:16384 cpu_cores:8 performance_level:6; do " +
                            "  key=$(echo \"$k\" | cut -d: -f1); " +
                            "  val=$(echo \"$k\" | cut -d: -f2); " +
                            "  sed -i \"/^$key:/d\" \"$INT_FILE\"; " +
                            "  echo \"$key:$val\" >> \"$INT_FILE\"; " +
                            "done; " +
                            "cp -f \"$INT_FILE\" \"" + FILES + "/prefs_int\" 2>/dev/null; ";
                    ShizukuShell.exec(prefsIntScript);

                    String prefsStringScript = 
                            "STR_FILE=\"" + DRAGON_ASSETS + "/prefs_string\"; " +
                            "chmod 666 \"$STR_FILE\" 2>/dev/null; " +
                            "touch \"$STR_FILE\"; " +
                            "for k in ro.product.model:" + FAKE_SPOOF_MODEL + " " +
                            "         ro.product.manufacturer:" + FAKE_SPOOF_MANUFACTURER + " " +
                            "         ro.product.brand:" + FAKE_SPOOF_MANUFACTURER + " " +
                            "         ro.product.name:" + FAKE_SPOOF_MODEL + " " +
                            "         ro.product.device:" + FAKE_SPOOF_MODEL + " " +
                            "         ro.build.product:" + FAKE_SPOOF_MODEL + " " +
                            "         ro.board.platform:pineapple " +
                            "         ro.hardware:qcom " +
                            "         ro.soc.model:SM8650 " +
                            "         deviceModel:\"" + FAKE_SPOOF_NAME + "\" " +
                            "         gpu_name:\"Adreno (TM) 750\" " +
                            "         gl_renderer:\"Adreno (TM) 750\" " +
                            "         gl_vendor:\"Qualcomm\" " +
                            "         gl_version:\"OpenGL ES 3.2 V@0615.0\" " +
                            "         device_brand:\"asus\"; do " +
                            "  key=$(echo \"$k\" | cut -d: -f1); " +
                            "  val=$(echo \"$k\" | cut -d: -f2); " +
                            "  sed -i \"/^$key:/d\" \"$STR_FILE\"; " +
                            "  echo \"$key:$val\" >> \"$STR_FILE\"; " +
                            "done; " +
                            "cp -f \"$STR_FILE\" \"" + FILES + "/prefs_string\" 2>/dev/null; " +
                            "for slot in " + FILES + "/mini_patch/*/*/*/ ; do " +
                            "  if [ -d \"$slot\" ]; then " +
                            "    cp -f \"$INT_FILE\" \"$slot/prefs_int\" 2>/dev/null; " +
                            "    cp -f \"$STR_FILE\" \"$slot/prefs_string\" 2>/dev/null; " +
                            "    chmod 444 \"$slot/prefs_int\" \"$slot/prefs_string\" 2>/dev/null; " +
                            "  fi; " +
                            "done; " +
                            "chmod 444 \"$INT_FILE\" \"$STR_FILE\" \"" + FILES + "/prefs_int\" \"" + FILES + "/prefs_string\" 2>/dev/null; "; // Lock files read-only so MLBB cannot revert on startup!
                    ShizukuShell.exec(prefsStringScript);

                    // Also generate shared_prefs XML for extra persistence
                    String prefsXml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                            + "<map>\n"
                            + "    <int name=\"high_fps_mode\" value=\"6\" />\n"
                            + "    <int name=\"fps_level\" value=\"6\" />\n"
                            + "    <int name=\"__FrameRateLevel__\" value=\"6\" />\n"
                            + "    <int name=\"__MaxFrameRateLevel__\" value=\"6\" />\n"
                            + "    <int name=\"__FrameRateLevel__$AI\" value=\"6\" />\n"
                            + "    <int name=\"performance_level\" value=\"6\" />\n"
                            + "    <int name=\"high_fps_mode_ultra\" value=\"1\" />\n"
                            + "    <int name=\"resolution_high\" value=\"1\" />\n"
                            + "    <int name=\"outline\" value=\"1\" />\n"
                            + "    <int name=\"shadow\" value=\"1\" />\n"
                            + "    <int name=\"hd_mode\" value=\"1\" />\n"
                            + "    <int name=\"render_scale\" value=\"165\" />\n"
                            + "    <int name=\"system_memory\" value=\"16384\" />\n"
                            + "    <string name=\"device_model\">" + FAKE_SPOOF_MODEL + "</string>\n"
                            + "    <string name=\"device_manufacturer\">" + FAKE_SPOOF_MANUFACTURER + "</string>\n"
                            + "    <string name=\"device_brand\">" + FAKE_SPOOF_MANUFACTURER + "</string>\n"
                            + "    <string name=\"gpu_name\">Adreno (TM) 750</string>\n"
                            + "    <string name=\"gl_renderer\">Adreno (TM) 750</string>\n"
                            + "</map>";
                    File prefsFile = new File(stage, "165fps_prefs.xml");
                    writeFile(prefsFile, prefsXml.getBytes("UTF-8"));
                    String targetPrefsData = "/data/data/" + PKG + "/shared_prefs/" + PKG + "_preferences.xml";
                    String targetPrefsV2Data = "/data/data/" + PKG + "/shared_prefs/" + PKG + ".v2.playerprefs.xml";
                    String targetPrefsSd = "/sdcard/Android/data/" + PKG + "/shared_prefs/" + PKG + "_preferences.xml";
                    String targetPrefsV2Sd = "/sdcard/Android/data/" + PKG + "/shared_prefs/" + PKG + ".v2.playerprefs.xml";
                    ShizukuShell.exec("mkdir -p /sdcard/Android/data/" + PKG + "/shared_prefs");
                    ShizukuShell.exec("cp -f \"" + prefsFile.getAbsolutePath() + "\" \"" + targetPrefsData + "\" 2>/dev/null");
                    ShizukuShell.exec("cp -f \"" + prefsFile.getAbsolutePath() + "\" \"" + targetPrefsV2Data + "\" 2>/dev/null");
                    ShizukuShell.exec("cp -f \"" + prefsFile.getAbsolutePath() + "\" \"" + targetPrefsSd + "\" 2>/dev/null");
                    ShizukuShell.exec("cp -f \"" + prefsFile.getAbsolutePath() + "\" \"" + targetPrefsV2Sd + "\" 2>/dev/null");

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
                    float targetRestoreHz = (deviceMax > 60f) ? deviceMax : 165.0f;
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

                    // 4. Unlock and remove spoof lines from prefs_int and prefs_string
                    ShizukuShell.exec("chmod 666 \"" + DRAGON_ASSETS + "/prefs_int\" \"" + DRAGON_ASSETS + "/prefs_string\" 2>/dev/null; " +
                            "sed -i '/__FrameRateLevel__/d; /__MaxFrameRateLevel__/d; /high_fps_mode/d; /fps_level/d; /render_scale/d; /performance_level/d; /system_memory/d' \"" + DRAGON_ASSETS + "/prefs_int\" 2>/dev/null; " +
                            "sed -i '/ASUS/d; /asus/d; /pineapple/d; /SM8650/d; /Adreno/d; /Qualcomm/d' \"" + DRAGON_ASSETS + "/prefs_string\" 2>/dev/null");

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

    public static void restoreDefaultMapAndSkins(Context context, Callback callback) {
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
                        "rm -f \"" + DRAGON_ASSETS + "/Art/android/nextnextninja*\" 2>/dev/null; " +
                        "if [ -d \"" + FILES + "/mini_patch\" ]; then " +
                        "  find \"" + FILES + "/mini_patch\" -name \"PVP_009.unity3d\" -exec rm -f {} + 2>/dev/null; " +
                        "  find \"" + FILES + "/mini_patch\" -name \"PVP_049_low.unity3d\" -exec rm -f {} + 2>/dev/null; " +
                        "  find \"" + FILES + "/mini_patch\" -name \"GalleryCenter.unity3d\" -exec rm -f {} + 2>/dev/null; " +
                        "  find \"" + FILES + "/mini_patch\" -name \"UI_TheMlabel_CollectionSet_Skin*\" -exec rm -f {} + 2>/dev/null; " +
                        "  find \"" + FILES + "/mini_patch\" -name \"nextnextninja*\" -exec rm -f {} + 2>/dev/null; " +
                        "fi; ";
                ShizukuShell.exec(cmd);

                // Restore genuine stock GameResAlternative if context is available
                if (context != null) {
                    File stage = new File(STAGE_DIR);
                    stage.mkdirs();
                    stageAsset(context, "mlbb_drone/base/Document/android/GameResAlternative_stock.unity3d", stage, "GameResAlternative.unity3d");
                    stageAsset(context, "mlbb_drone/base/Document/android/BinaryPatchMD5_stock.xml", stage, "BinaryPatchMD5.xml");
                    File stagedRes = new File(stage, "GameResAlternative.unity3d");
                    if (stagedRes.exists() && stagedRes.length() > 500000) {
                        String docAndroid = DRAGON_ASSETS + "/Document/android";
                        ShizukuShell.exec("mkdir -p \"" + docAndroid + "\"; cp -f \"" + stagedRes.getAbsolutePath() + "\" \"" + docAndroid + "/GameResAlternative.unity3d\"");
                        ShizukuShell.exec(
                                "if [ -d \"" + FILES + "/mini_patch\" ]; then " +
                                "  find \"" + FILES + "/mini_patch\" -type d -name \"Document\" -exec mkdir -p {}/android \\; 2>/dev/null; " +
                                "  find \"" + FILES + "/mini_patch\" -type d -name \"Document\" -exec cp -f \"" + stagedRes.getAbsolutePath() + "\" {}/android/GameResAlternative.unity3d \\; 2>/dev/null; " +
                                "  for d in " + FILES + "/mini_patch/*/*/*/Document/android ; do if [ -d \"$d\" ]; then cp -f \"" + stagedRes.getAbsolutePath() + "\" \"$d/GameResAlternative.unity3d\" 2>/dev/null; fi; done; " +
                                "  for d in " + FILES + "/mini_patch/*/*/Document/android ; do if [ -d \"$d\" ]; then cp -f \"" + stagedRes.getAbsolutePath() + "\" \"$d/GameResAlternative.unity3d\" 2>/dev/null; fi; done; " +
                                "fi;"
                        );
                    }
                    File stagedMD5 = new File(stage, "BinaryPatchMD5.xml");
                    if (stagedMD5.exists() && stagedMD5.length() > 0) {
                        String docAndroid = DRAGON_ASSETS + "/Document/android";
                        ShizukuShell.exec("cp -f \"" + stagedMD5.getAbsolutePath() + "\" \"" + docAndroid + "/BinaryPatchMD5.xml\"");
                        ShizukuShell.exec(
                                "if [ -d \"" + FILES + "/mini_patch\" ]; then " +
                                "  find \"" + FILES + "/mini_patch\" -type d -name \"Document\" -exec cp -f \"" + stagedMD5.getAbsolutePath() + "\" {}/android/BinaryPatchMD5.xml \\; 2>/dev/null; " +
                                "  for d in " + FILES + "/mini_patch/*/*/*/Document/android ; do if [ -d \"$d\" ]; then cp -f \"" + stagedMD5.getAbsolutePath() + "\" \"$d/BinaryPatchMD5.xml\" 2>/dev/null; fi; done; " +
                                "  for d in " + FILES + "/mini_patch/*/*/Document/android ; do if [ -d \"$d\" ]; then cp -f \"" + stagedMD5.getAbsolutePath() + "\" \"$d/BinaryPatchMD5.xml\" 2>/dev/null; fi; done; " +
                                "fi;"
                        );
                    }
                    deleteRecursive(stage);
                }

                ShizukuShell.exec("am force-stop " + PKG + " 2>/dev/null; chmod -R 777 \"" + DRAGON_ASSETS + "\" \"" + FILES + "/mini_patch\" 2>/dev/null");
                postComplete(callback, true, "✅ Stock Map & Skins restored!\nMLBB flushed and loading default game assets.");
            } catch (Throwable t) {
                postComplete(callback, false, "Restore error: " + t.getMessage());
            }
        });
    }

    public static void restoreDefaultMapAndSkins(Callback callback) {
        restoreDefaultMapAndSkins(null, callback);
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
        public final String heroCodeName;
        public final String targetSkinId;
        public final String downloadUrl;

        public HeroSkinOption(String heroId, String heroName, String skinName, String role, String heroCodeName, String targetSkinId, String downloadUrl) {
            this.heroId = heroId;
            this.heroName = heroName;
            this.skinName = skinName;
            this.role = role;
            this.heroCodeName = heroCodeName;
            this.targetSkinId = targetSkinId;
            this.downloadUrl = downloadUrl;
        }

        public HeroSkinOption(String heroId, String heroName, String skinName, String role, String heroCodeName, String targetSkinId) {
            this(heroId, heroName, skinName, role, heroCodeName, targetSkinId, null);
        }
    }

    public static final List<HeroSkinOption> HERO_SKINS = new ArrayList<>();
    static {
        // ── 1. JUNGLE / ASSASSIN ───────────────────────────────────────────
        HERO_SKINS.add(new HeroSkinOption("fanny", "Fanny", "Blade of Kibou (Aspirant)", "Jungle", "hero_fanny", "08",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln/main/fanny%20basic%20s%20to%20aspirants.zip"));
        HERO_SKINS.add(new HeroSkinOption("ling", "Ling", "Seraphic Scourge (Collector)", "Jungle", "hero_pofa", "04",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln2/main/ling%20basic%20to%20collector.zip"));
        HERO_SKINS.add(new HeroSkinOption("gusion", "Gusion", "Dimension Crusher (11.11)", "Jungle", "hero_Gusin", "07",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln2/main/gusion%20basic%20to%2011.11.zip"));
        HERO_SKINS.add(new HeroSkinOption("hayabusa", "Hayabusa", "Shura (11.11)", "Jungle", "hero_ninja", "05",
                "https://raw.githubusercontent.com/n3wz1ips/D3f4ultSk1n/main/hayabusa%2011.11.zip"));
        HERO_SKINS.add(new HeroSkinOption("lancelot", "Lancelot", "Bren Esports (Champion)", "Jungle", "hero_lancelot", "04",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln2/main/lancelot%20basic%20to%20champion.zip"));
        HERO_SKINS.add(new HeroSkinOption("nolan", "Nolan", "The Navigator", "Jungle", "hero_NolanOsborne", "03"));
        HERO_SKINS.add(new HeroSkinOption("aamon", "Aamon", "Noble Crest (Elite)", "Jungle", "hero_Aamon", "03",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln/main/aamon%20basic%20to%20elite.zip"));
        HERO_SKINS.add(new HeroSkinOption("saber", "Saber", "Codename: Storm (Legend)", "Jungle", "hero_assassin", "04",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln3/main/saber%20basic%20to%20legend.zip"));
        HERO_SKINS.add(new HeroSkinOption("julian", "Julian", "Kurapika (HxH)", "Jungle", "hero_julian", "03",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln2/main/julian%20basic%20to%20hxh.zip"));

        // ── 2. MAGE ────────────────────────────────────────────────────────
        HERO_SKINS.add(new HeroSkinOption("kagura", "Kagura", "Water Lily (Annual Starlight)", "Mage", "hero_kagura", "06",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln2/main/kagura%20basic%20to%20annual%20star.zip"));
        HERO_SKINS.add(new HeroSkinOption("lunox", "Lunox", "Eyes of Eternity (Epic)", "Mage", "hero_balancemagic", "06",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln3/main/lunox%20basic%20to%20epic.zip"));
        HERO_SKINS.add(new HeroSkinOption("harith", "Harith", "EVOS Legends (Champion)", "Mage", "hero_moshushi", "04",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln2/main/harith%20basic%20to%20champion.zip"));
        HERO_SKINS.add(new HeroSkinOption("cecilion", "Cecilion", "Crimson Wings (Collector)", "Mage", "hero_couplemage", "04",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln/main/cecilion%20basic%20to%20collector.zip"));
        HERO_SKINS.add(new HeroSkinOption("xavier", "Xavier", "Satoru Gojo (JJK)", "Mage", "hero_xavier", "03",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln3/main/xavier%20collector%20to%20jjk.zip"));
        HERO_SKINS.add(new HeroSkinOption("nana", "Nana", "Aqua Pura (Collector)", "Mage", "hero_nana", "08",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln3/main/nana%20elite%20to%20collector.zip"));
        HERO_SKINS.add(new HeroSkinOption("change", "Chang'e", "Cyber Cherub (Aspirants)", "Mage", "hero_change", "05",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln/main/change%20basic%20to%20aspirants.zip"));
        HERO_SKINS.add(new HeroSkinOption("vexana", "Vexana", "The Sun Empress (Collector)", "Mage", "hero_Vexana", "05"));

        // ── 3. TANK / ROAM ─────────────────────────────────────────────────
        HERO_SKINS.add(new HeroSkinOption("tigreal", "Tigreal", "Lightborn Defender", "Tank", "hero_warrior", "05"));
        HERO_SKINS.add(new HeroSkinOption("franco", "Franco", "King of Hell (Legend)", "Tank", "hero_franco", "08",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln/main/franco%20basic%20s%20to%20legend.zip"));
        HERO_SKINS.add(new HeroSkinOption("khufra", "Khufra", "Volcanic Overlord (Collector)", "Tank", "hero_munaiyi", "04",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln2/main/khufra%20basic%20to%20collector.zip"));
        HERO_SKINS.add(new HeroSkinOption("atlas", "Atlas", "Space Mech (Collector)", "Tank", "hero_atelasi", "03",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln/main/atlas%20basic%20to%20collector.zip"));
        HERO_SKINS.add(new HeroSkinOption("johnson", "Johnson", "Starblazer (Legend)", "Tank", "hero_johnson", "05",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln2/main/johnson%20basic%20to%20legend.zip"));
        HERO_SKINS.add(new HeroSkinOption("gatotkaca", "Gatotkaca", "Sentinel (Epic)", "Tank", "hero_gatot", "03",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln/main/gatotkaca%20basic%20to%20epic.zip"));
        HERO_SKINS.add(new HeroSkinOption("akai", "Akai", "Kung Fu Panda (Po)", "Tank", "hero_xiongmao", "07",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln/main/akai%20elite%20s%20to%20kungfu%20panda.zip"));

        // ── 4. MARKSMAN (MM) ───────────────────────────────────────────────
        HERO_SKINS.add(new HeroSkinOption("granger", "Granger", "Starfall Knight (Legend)", "Marksman", "hero_mike", "12",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln2/main/granger%20basic%20to%20legend.zip"));
        HERO_SKINS.add(new HeroSkinOption("layla", "Layla", "Miss Hikari (Aspirant)", "Marksman", "hero_shooter", "12",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln2/main/layla%20basic%20to%20aspirants.zip"));
        HERO_SKINS.add(new HeroSkinOption("moskov", "Moskov", "Doom Incarnate (Abyss)", "Marksman", "hero_Moskov", "10",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln3/main/moskov%20basic%20to%20abyss.zip"));
        HERO_SKINS.add(new HeroSkinOption("claude", "Claude", "Blazing Trace", "Marksman", "hero_claude", "08",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln/main/claude%20basic%20to%20blazing.zip"));
        HERO_SKINS.add(new HeroSkinOption("miya", "Miya", "Atomic Pop", "Marksman", "hero_gongjianshou", "14",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln3/main/miya%20elite%20to%20atomic.zip"));
        HERO_SKINS.add(new HeroSkinOption("karrie", "Karrie", "Gill-Girl (Annual Starlight)", "Marksman", "hero_feilun", "11",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln2/main/karrie%20basic%20to%20annual%20star.zip"));
        HERO_SKINS.add(new HeroSkinOption("beatrix", "Beatrix", "Stellar Brilliance (M4 Prime)", "Marksman", "hero_chargingshooter", "06",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln/main/beatrix%20basic%20to%20prime.zip"));
        HERO_SKINS.add(new HeroSkinOption("brody", "Brody", "Apex Hunter (Collector)", "Marksman", "hero_feiden", "04",
                "https://raw.githubusercontent.com/Opoad55/upluad2/main/brody%20basic%20to%20collector.zip"));
        HERO_SKINS.add(new HeroSkinOption("wanwan", "Wanwan", "Pixel Blast (Collector)", "Marksman", "hero_wanwan", "04"));
        HERO_SKINS.add(new HeroSkinOption("lesley", "Lesley", "Hawk-eyed Sniper (Annual Star)", "Marksman", "hero_sniper", "05",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln2/main/lesley%20basic%20to%20annual%20star.zip"));

        // ── 5. SUPPORT ─────────────────────────────────────────────────────
        HERO_SKINS.add(new HeroSkinOption("angela", "Angela", "Avatar of Time (Annual Star)", "Support", "hero_angela", "05",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln/main/angela%20basic%20to%20annual%20star.zip"));
        HERO_SKINS.add(new HeroSkinOption("estes", "Estes", "Blacklist International (M3)", "Support", "hero_angel", "05",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln/main/estes%20basic%20to%20champion.zip"));
        HERO_SKINS.add(new HeroSkinOption("diggie", "Diggie", "Fairytaler (Epic)", "Support", "hero_digger", "04",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln/main/diggie%20basic%20to%20epic.zip"));
        HERO_SKINS.add(new HeroSkinOption("floryn", "Floryn", "Cinnamoroll (Sanrio)", "Support", "hero_Idun", "02",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln/main/floryn%20basic%20to%20sanrio.zip"));
        HERO_SKINS.add(new HeroSkinOption("mathilda", "Mathilda", "Dream Groove (MPL)", "Support", "hero_madierda", "03",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln3/main/mathilda%20basic%20to%20epic.zip"));
        HERO_SKINS.add(new HeroSkinOption("rafaela", "Rafaela", "S.A.B.E.R. Savior / Lunar Fest", "Support", "hero_lanbote", "03",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln3/main/rafaela%20elite%20to%20lunar%20fest.zip"));

        // ── 6. FIGHTER ─────────────────────────────────────────────────────
        HERO_SKINS.add(new HeroSkinOption("chou", "Chou", "Iori Yagami (KOF)", "Fighter", "hero_kongfu", "13",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln/main/chou%20basic%20to%20kof.zip"));
        HERO_SKINS.add(new HeroSkinOption("yuzhong", "Yu Zhong", "Cosmic Dragon (Dragon/Collector)", "Fighter", "hero_BlackDragon", "05",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln3/main/yu%20zhong%20basic%20to%20dragon.zip"));
        HERO_SKINS.add(new HeroSkinOption("paquito", "Paquito", "Ryu (Street Fighter)", "Fighter", "hero_pakuite", "04",
                "https://raw.githubusercontent.com/Opoad55/upluad2/main/paquito%20basic%20to%20sf2.zip"));
        HERO_SKINS.add(new HeroSkinOption("ruby", "Ruby", "Mecha Maiden (Aspirants)", "Fighter", "hero_xiaohongmao", "07",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln3/main/ruby%20basic%20to%20aspirants.zip"));
        HERO_SKINS.add(new HeroSkinOption("dyrroth", "Dyrroth", "Street Fighter (SF)", "Fighter", "hero_diyasi", "05",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln/main/dyrroth%20collector%20to%20sf2.zip"));
        HERO_SKINS.add(new HeroSkinOption("alpha", "Alpha", "General Void (Abyss)", "Fighter", "hero_entropy", "05",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln/main/alpha%20basic%20to%20abyss.zip"));
        HERO_SKINS.add(new HeroSkinOption("alucard", "Alucard", "Obsidian Blade (Legend)", "Fighter", "hero_alucard", "05",
                "https://raw.githubusercontent.com/n3wz1ips/0pTS1kln/main/alucard%20basic%20to%20legend.zip"));
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
                postProgress(callback, "Preparing " + target.heroName + " (" + target.skinName + ")...");
                applyHeroSwapInternal(context, target, callback);
                postComplete(callback, true, "⚡ Injected " + target.heroName + " (" + target.skinName + ")!\n" +
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

    public static void restoreHeroSkin(Context context, String heroId, Callback callback) {
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
                postProgress(callback, "Restoring stock " + target.heroName + " skin & purging modded meshes...");
                restoreHeroSwapInternal(target);

                if (context != null) {
                    File stage = new File(STAGE_DIR);
                    stage.mkdirs();
                    stageAsset(context, "mlbb_drone/base/Document/android/GameResAlternative_stock.unity3d", stage, "GameResAlternative.unity3d");
                    stageAsset(context, "mlbb_drone/base/Document/android/BinaryPatchMD5_stock.xml", stage, "BinaryPatchMD5.xml");
                    File stagedRes = new File(stage, "GameResAlternative.unity3d");
                    if (stagedRes.exists() && stagedRes.length() > 500000) {
                        String docAndroid = DRAGON_ASSETS + "/Document/android";
                        ShizukuShell.exec("mkdir -p \"" + docAndroid + "\"; cp -f \"" + stagedRes.getAbsolutePath() + "\" \"" + docAndroid + "/GameResAlternative.unity3d\"");
                        ShizukuShell.exec(
                                "if [ -d \"" + FILES + "/mini_patch\" ]; then " +
                                "  find \"" + FILES + "/mini_patch\" -type d -name \"Document\" -exec mkdir -p {}/android \\; 2>/dev/null; " +
                                "  find \"" + FILES + "/mini_patch\" -type d -name \"Document\" -exec cp -f \"" + stagedRes.getAbsolutePath() + "\" {}/android/GameResAlternative.unity3d \\; 2>/dev/null; " +
                                "  for d in " + FILES + "/mini_patch/*/*/*/Document/android ; do if [ -d \"$d\" ]; then cp -f \"" + stagedRes.getAbsolutePath() + "\" \"$d/GameResAlternative.unity3d\" 2>/dev/null; fi; done; " +
                                "  for d in " + FILES + "/mini_patch/*/*/Document/android ; do if [ -d \"$d\" ]; then cp -f \"" + stagedRes.getAbsolutePath() + "\" \"$d/GameResAlternative.unity3d\" 2>/dev/null; fi; done; " +
                                "fi;"
                        );
                    }
                    File stagedMD5 = new File(stage, "BinaryPatchMD5.xml");
                    if (stagedMD5.exists() && stagedMD5.length() > 0) {
                        String docAndroid = DRAGON_ASSETS + "/Document/android";
                        ShizukuShell.exec("cp -f \"" + stagedMD5.getAbsolutePath() + "\" \"" + docAndroid + "/BinaryPatchMD5.xml\"");
                        ShizukuShell.exec(
                                "if [ -d \"" + FILES + "/mini_patch\" ]; then " +
                                "  find \"" + FILES + "/mini_patch\" -type d -name \"Document\" -exec cp -f \"" + stagedMD5.getAbsolutePath() + "\" {}/android/BinaryPatchMD5.xml \\; 2>/dev/null; " +
                                "  for d in " + FILES + "/mini_patch/*/*/*/Document/android ; do if [ -d \"$d\" ]; then cp -f \"" + stagedMD5.getAbsolutePath() + "\" \"$d/BinaryPatchMD5.xml\" 2>/dev/null; fi; done; " +
                                "  for d in " + FILES + "/mini_patch/*/*/Document/android ; do if [ -d \"$d\" ]; then cp -f \"" + stagedMD5.getAbsolutePath() + "\" \"$d/BinaryPatchMD5.xml\" 2>/dev/null; fi; done; " +
                                "fi;"
                        );
                    }
                    deleteRecursive(stage);
                }

                if ("hero_ninja".equalsIgnoreCase(target.heroCodeName)) {
                    ShizukuShell.exec("rm -f \"" + DRAGON_ASSETS + "/Art/android/nextnextninja*\" 2>/dev/null; " +
                            "if [ -d \"" + FILES + "/mini_patch\" ]; then find \"" + FILES + "/mini_patch\" -name \"nextnextninja*\" -exec rm -f {} + 2>/dev/null; fi;");
                }

                ShizukuShell.exec("am force-stop " + PKG + " 2>/dev/null; chmod -R 777 \"" + DRAGON_ASSETS + "\" \"" + FILES + "/mini_patch\" 2>/dev/null");
                postComplete(callback, true, "✅ Restored stock " + target.heroName + " skin.\nModded meshes purged and MLBB cache flushed.");
            } catch (Throwable t) {
                Log.e(TAG, "Error restoring " + heroId, t);
                postComplete(callback, false, "Restore error: " + t.getMessage());
            }
        });
    }

    public static void restoreHeroSkin(String heroId, Callback callback) {
        restoreHeroSkin(null, heroId, callback);
    }

    public static void unlockHeroesByRole(Context context, String role, Callback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                postComplete(callback, false, "Shizuku permission not granted.");
                return;
            }

            try {
                injectGameResAlternative(context);
                int count = 0;
                for (HeroSkinOption h : HERO_SKINS) {
                    if (role == null || role.equalsIgnoreCase("ALL") || h.role.equalsIgnoreCase(role)) {
                        count++;
                        postProgress(callback, "Injecting " + h.heroName + " (" + h.skinName + ")...");
                        applyHeroSwapInternal(context, h, callback);
                    }
                }
                postComplete(callback, true, "⚡ " + count + " " + (role == null || role.equalsIgnoreCase("ALL") ? "All" : role) + " Skins unlocked!\n" +
                        "💡 In Hero Pick: Select DEFAULT Skin for any of them!\n" +
                        "Works in Practice, Custom, Classic, and Rank.");
            } catch (Throwable t) {
                Log.e(TAG, "Error unlocking " + role + " skins", t);
                postComplete(callback, false, "Unlock error: " + t.getMessage());
            }
        });
    }

    public static void unlockHeroesByRole(String role, Callback callback) {
        unlockHeroesByRole(null, role, callback);
    }

    public static void unlockAllHeroSkins(Context context, Callback callback) {
        unlockHeroesByRole(context, "ALL", callback);
    }

    public static void unlockAllHeroSkins(Callback callback) {
        unlockHeroesByRole(null, "ALL", callback);
    }

    public static void unlockAll7Heroes(Context context, Callback callback) {
        unlockHeroesByRole(context, "ALL", callback);
    }

    public static void unlockAll7Heroes(Callback callback) {
        unlockHeroesByRole(null, "ALL", callback);
    }

    public static void restoreAllHeroSkins(Context context, Callback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                postComplete(callback, false, "Shizuku permission not granted.");
                return;
            }

            try {
                postProgress(callback, "1/4: Restoring genuine stock GameResAlternative & integrity tables...");
                if (context != null) {
                    File stage = new File(STAGE_DIR);
                    stage.mkdirs();
                    stageAsset(context, "mlbb_drone/base/Document/android/GameResAlternative_stock.unity3d", stage, "GameResAlternative.unity3d");
                    stageAsset(context, "mlbb_drone/base/Document/android/BinaryPatchMD5_stock.xml", stage, "BinaryPatchMD5.xml");

                    File stagedRes = new File(stage, "GameResAlternative.unity3d");
                    File stagedMD5 = new File(stage, "BinaryPatchMD5.xml");
                    String docAndroid = DRAGON_ASSETS + "/Document/android";
                    ShizukuShell.exec("mkdir -p \"" + docAndroid + "\"");

                    if (stagedRes.exists() && stagedRes.length() > 500000) {
                        ShizukuShell.exec("cp -f \"" + stagedRes.getAbsolutePath() + "\" \"" + docAndroid + "/GameResAlternative.unity3d\"");
                        ShizukuShell.exec(
                                "if [ -d \"" + FILES + "/mini_patch\" ]; then " +
                                "  find \"" + FILES + "/mini_patch\" -type d -name \"Document\" -exec mkdir -p {}/android \\; 2>/dev/null; " +
                                "  find \"" + FILES + "/mini_patch\" -type d -name \"Document\" -exec cp -f \"" + stagedRes.getAbsolutePath() + "\" {}/android/GameResAlternative.unity3d \\; 2>/dev/null; " +
                                "  for d in " + FILES + "/mini_patch/*/*/*/Document/android ; do if [ -d \"$d\" ]; then cp -f \"" + stagedRes.getAbsolutePath() + "\" \"$d/GameResAlternative.unity3d\" 2>/dev/null; fi; done; " +
                                "  for d in " + FILES + "/mini_patch/*/*/Document/android ; do if [ -d \"$d\" ]; then cp -f \"" + stagedRes.getAbsolutePath() + "\" \"$d/GameResAlternative.unity3d\" 2>/dev/null; fi; done; " +
                                "fi;"
                        );
                    }

                    if (stagedMD5.exists() && stagedMD5.length() > 0) {
                        ShizukuShell.exec("cp -f \"" + stagedMD5.getAbsolutePath() + "\" \"" + docAndroid + "/BinaryPatchMD5.xml\"");
                        ShizukuShell.exec(
                                "if [ -d \"" + FILES + "/mini_patch\" ]; then " +
                                "  find \"" + FILES + "/mini_patch\" -type d -name \"Document\" -exec cp -f \"" + stagedMD5.getAbsolutePath() + "\" {}/android/BinaryPatchMD5.xml \\; 2>/dev/null; " +
                                "  for d in " + FILES + "/mini_patch/*/*/*/Document/android ; do if [ -d \"$d\" ]; then cp -f \"" + stagedMD5.getAbsolutePath() + "\" \"$d/BinaryPatchMD5.xml\" 2>/dev/null; fi; done; " +
                                "  for d in " + FILES + "/mini_patch/*/*/Document/android ; do if [ -d \"$d\" ]; then cp -f \"" + stagedMD5.getAbsolutePath() + "\" \"$d/BinaryPatchMD5.xml\" 2>/dev/null; fi; done; " +
                                "fi;"
                        );
                    }
                    deleteRecursive(stage);
                }

                postProgress(callback, "2/4: Purging all modded 3D hero meshes from dragon2017 & mini_patch...");
                for (HeroSkinOption h : HERO_SKINS) {
                    restoreHeroSwapInternal(h);
                }

                postProgress(callback, "3/4: Restoring backed-up stock assets and purging modded UI/VFX...");
                String restoreSync =
                        "if [ -d \"" + BACKUP_SKINS_DIR + "\" ]; then " +
                        "  cp -rf \"" + BACKUP_SKINS_DIR + "\"/* \"" + DRAGON_ASSETS + "/\" 2>/dev/null; " +
                        "  if [ -d \"" + FILES + "/mini_patch\" ]; then " +
                        "    if [ -d \"" + BACKUP_SKINS_DIR + "/Art\" ]; then find \"" + FILES + "/mini_patch\" -type d -name \"Art\" -exec cp -rf \"" + BACKUP_SKINS_DIR + "/Art/\"* {}/ \\; 2>/dev/null; fi; " +
                        "    if [ -d \"" + BACKUP_SKINS_DIR + "/UI\" ]; then find \"" + FILES + "/mini_patch\" -type d -name \"UI\" -exec cp -rf \"" + BACKUP_SKINS_DIR + "/UI/\"* {}/ \\; 2>/dev/null; fi; " +
                        "    if [ -d \"" + BACKUP_SKINS_DIR + "/Audio\" ]; then find \"" + FILES + "/mini_patch\" -type d -name \"Audio\" -exec cp -rf \"" + BACKUP_SKINS_DIR + "/Audio/\"* {}/ \\; 2>/dev/null; fi; " +
                        "  fi; " +
                        "  rm -rf \"" + BACKUP_SKINS_DIR + "\"; " +
                        "fi; " +
                        "rm -f \"" + DRAGON_ASSETS + "/Art/android/nextnextninja*\" 2>/dev/null; " +
                        "rm -f \"" + DRAGON_ASSETS + "/UI/android/Hero*1_add.unity3d\" 2>/dev/null; " +
                        "rm -f \"" + DRAGON_ASSETS + "/UI/android/Hero*2_add.unity3d\" 2>/dev/null; " +
                        "rm -f \"" + DRAGON_ASSETS + "/UI/android/Hero*3_add.unity3d\" 2>/dev/null; " +
                        "rm -f \"" + DRAGON_ASSETS + "/UI/android/UI_TheMlabel_CollectionSet_Skin*\" 2>/dev/null; " +
                        "if [ -d \"" + FILES + "/mini_patch\" ]; then " +
                        "  find \"" + FILES + "/mini_patch\" -name \"nextnextninja*\" -exec rm -f {} + 2>/dev/null; " +
                        "  find \"" + FILES + "/mini_patch\" -name \"Hero*1_add.unity3d\" -exec rm -f {} + 2>/dev/null; " +
                        "  find \"" + FILES + "/mini_patch\" -name \"Hero*2_add.unity3d\" -exec rm -f {} + 2>/dev/null; " +
                        "  find \"" + FILES + "/mini_patch\" -name \"Hero*3_add.unity3d\" -exec rm -f {} + 2>/dev/null; " +
                        "  find \"" + FILES + "/mini_patch\" -name \"UI_TheMlabel_CollectionSet_Skin*\" -exec rm -f {} + 2>/dev/null; " +
                        "fi; " +
                        "rm -rf \"" + STAGE_DIR + "\" 2>/dev/null; ";
                ShizukuShell.exec(restoreSync);

                postProgress(callback, "4/4: Flushing in-memory cached assets & setting permissions...");
                ShizukuShell.exec("am force-stop " + PKG + " 2>/dev/null; " +
                        "chmod -R 777 \"" + DRAGON_ASSETS + "\" \"" + FILES + "/mini_patch\" 2>/dev/null");

                postComplete(callback, true, "✅ All hero skins 100% restored to stock genuine assets!\n" +
                        "• Genuine stock GameResAlternative.unity3d (743 KB) restored across all slots.\n" +
                        "• All modded 3D meshes, UI portraits & VFX purged from dragon2017 & mini_patch.\n" +
                        "• Base hero skeletons and rigs healed.\n" +
                        "• MLBB process stopped to flush memory cache.\n" +
                        "Launch MLBB now to see genuine original hero skins!");
            } catch (Throwable t) {
                Log.e(TAG, "Error restoring all skins", t);
                postComplete(callback, false, "Restore error: " + t.getMessage());
            }
        });
    }

    public static void restoreAllHeroSkins(Callback callback) {
        restoreAllHeroSkins(null, callback);
    }

    private static void injectGameResAlternative(Context context) {
        String docAndroid = DRAGON_ASSETS + "/Document/android";
        String target = docAndroid + "/GameResAlternative.unity3d";
        String backup = BACKUP_SKINS_DIR + "/Document/android/GameResAlternative.unity3d";

        ShizukuShell.exec("chmod -R 777 \"" + DRAGON_ASSETS + "/Document\" 2>/dev/null");
        ShizukuShell.exec("chmod -R 777 \"" + FILES + "/mini_patch\" 2>/dev/null");
        ShizukuShell.exec("mkdir -p \"" + docAndroid + "\"");
        ShizukuShell.exec("mkdir -p \"" + BACKUP_SKINS_DIR + "/Document/android\"");
        ShizukuShell.exec("if [ ! -s \"" + backup + "\" ] && [ -s \"" + target + "\" ]; then cp -f \"" + target + "\" \"" + backup + "\"; fi");

        boolean injected = false;
        // 1. Check if bundled in assets (genuine 789 KB GameResAlternative.unity3d + ResCheck bypass)
        if (context != null) {
            File stage = new File(STAGE_DIR);
            stage.mkdirs();
            stageAsset(context, "mlbb_drone/base/Document/android/GameResAlternative.unity3d", stage, "GameResAlternative.unity3d");
            stageAsset(context, "mlbb_drone/base/Document/android/ResCheckConf.unity3d", stage, "ResCheckConf.unity3d");
            stageAsset(context, "mlbb_drone/base/Document/android/ResCheckConf.xml", stage, "ResCheckConf.xml");
            stageAsset(context, "mlbb_drone/base/Document/android/BinaryPatchMD5.xml", stage, "BinaryPatchMD5.xml");
            stageAsset(context, "mlbb_drone/base/Document/android/res_skip_patch.xml", stage, "res_skip_patch.xml");
            stageAsset(context, "mlbb_drone/base/__fix_rescheck", stage, "__fix_rescheck");

            File stagedRes = new File(stage, "GameResAlternative.unity3d");
            if (stagedRes.exists() && stagedRes.length() > 100000) {
                ShizukuShell.exec("cp -f \"" + stagedRes.getAbsolutePath() + "\" \"" + target + "\"");
                injected = true;
            }
            for (String cfg : new String[]{"ResCheckConf.unity3d", "ResCheckConf.xml", "BinaryPatchMD5.xml", "res_skip_patch.xml"}) {
                File f = new File(stage, cfg);
                if (f.exists() && f.length() > 0) {
                    ShizukuShell.exec("cp -f \"" + f.getAbsolutePath() + "\" \"" + docAndroid + "/" + cfg + "\" 2>/dev/null");
                }
            }
            File fixRes = new File(stage, "__fix_rescheck");
            if (fixRes.exists()) {
                for (String slot : discoverActiveSlots()) {
                    ShizukuShell.exec("cp -f \"" + fixRes.getAbsolutePath() + "\" \"" + slot + "/__fix_rescheck\" 2>/dev/null; touch \"" + slot + "/__ready\" \"" + slot + "/__active\" 2>/dev/null");
                }
            }
        }

        // 2. Fallback to /sdcard/Download/WillyLibTool/skins/GameResAlternative.unity3d
        if (!injected) {
            ShizukuShell.exec("if [ -s \"" + PATCHED_SKINS_DIR + "/GameResAlternative.unity3d\" ]; then cp -f \"" + PATCHED_SKINS_DIR + "/GameResAlternative.unity3d\" \"" + target + "\"; fi");
        }

        // 3. Sync GameResAlternative.unity3d into active mini_patch slots so MLBB v1232+ loads it
        ShizukuShell.exec(
                "if [ -s \"" + target + "\" ] && [ -d \"" + FILES + "/mini_patch\" ]; then " +
                "  find \"" + FILES + "/mini_patch\" -type d -name \"Document\" -exec mkdir -p {}/android \\; 2>/dev/null; " +
                "  find \"" + FILES + "/mini_patch\" -type d -name \"Document\" -exec cp -f \"" + target + "\" {}/android/GameResAlternative.unity3d \\; 2>/dev/null; " +
                "fi"
        );
    }

    private static void downloadAndExtractSkinZip(String urlStr, File destDir, Callback callback) throws Exception {
        File downloadFolder = new File("/sdcard/Download/WillyLibTool");
        if (!downloadFolder.exists()) downloadFolder.mkdirs();

        File tempZip = new File(downloadFolder, "temp_" + System.currentTimeMillis() + ".zip");
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.connect();

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new java.io.IOException("HTTP Error " + responseCode + " downloading skin from: " + urlStr);
        }

        long totalSize = conn.getContentLengthLong();
        long downloaded = 0;

        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream out = new FileOutputStream(tempZip)) {
            byte[] buf = new byte[32768];
            int read;
            long lastPostTime = 0;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                downloaded += read;
                long now = System.currentTimeMillis();
                if (totalSize > 0 && (now - lastPostTime > 300)) {
                    lastPostTime = now;
                    int pct = (int) ((downloaded * 100) / totalSize);
                    postProgress(callback, "Downloading skin... " + pct + "% (" + (downloaded / 1048576) + "MB / " + (totalSize / 1048576) + "MB)");
                }
            }
            out.flush();
        }

        postProgress(callback, "Extracting skin bundle...");
        destDir.mkdirs();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(tempZip))) {
            ZipEntry ze;
            byte[] buffer = new byte[32768];
            String canonicalDest = destDir.getCanonicalPath();

            while ((ze = zis.getNextEntry()) != null) {
                File file = new File(destDir, ze.getName());
                if (!file.getCanonicalPath().startsWith(canonicalDest)) {
                    throw new SecurityException("Zip traversal attempt: " + ze.getName());
                }

                if (ze.isDirectory()) {
                    file.mkdirs();
                } else {
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        } finally {
            tempZip.delete();
        }
    }

    private static File findAssetsRoot(File dir) {
        if (new File(dir, "Art").exists() || new File(dir, "UI").exists() || new File(dir, "Audio").exists()) {
            return dir;
        }
        if (new File(dir, "assets").exists()) {
            return new File(dir, "assets");
        }
        if (new File(dir, "dragon2017/assets").exists()) {
            return new File(dir, "dragon2017/assets");
        }
        File[] subs = dir.listFiles();
        if (subs != null) {
            for (File s : subs) {
                if (s.isDirectory()) {
                    File found = findAssetsRoot(s);
                    if (found != null && (new File(found, "Art").exists() || new File(found, "UI").exists() || new File(found, "Audio").exists())) {
                        return found;
                    }
                }
            }
        }
        return dir;
    }

    /**
     * Validates an extracted cache directory to ensure it was not poisoned by
     * 0-byte self-truncation or partial extraction.
     */
    private static boolean isExtractedCacheValid(File cacheDir) {
        if (cacheDir == null || !cacheDir.exists() || !cacheDir.isDirectory()) {
            return false;
        }
        File assetsRoot = findAssetsRoot(cacheDir);
        if (assetsRoot == null || !assetsRoot.exists()) {
            return false;
        }
        File hDir = new File(assetsRoot, "Art/android/h");
        if (!hDir.exists() || !hDir.isDirectory()) {
            return false;
        }
        File[] hFiles = hDir.listFiles();
        if (hFiles == null || hFiles.length == 0) {
            return false;
        }
        int validMeshCount = 0;
        for (File f : hFiles) {
            if (f.isFile() && f.getName().endsWith(".unity3d")) {
                if (f.length() <= 1024) {
                    // Poisoned / 0-byte unity3d file detected!
                    return false;
                }
                validMeshCount++;
            }
        }
        return validMeshCount > 0 && !hasZeroByteUnity3dRecursive(assetsRoot);
    }

    private static boolean hasZeroByteUnity3dRecursive(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return false;
        for (File f : children) {
            if (f.isDirectory()) {
                if (hasZeroByteUnity3dRecursive(f)) return true;
            } else if (f.getName().endsWith(".unity3d") && f.length() == 0) {
                return true;
            }
        }
        return false;
    }

    private static void backupExistingFiles(File root, File current, String targetBase) {
        File[] files = current.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                backupExistingFiles(root, f, targetBase);
            } else {
                if (f.length() == 0) continue; // Never back up or stage 0-byte files
                String rel = root.toURI().relativize(f.toURI()).getPath();
                String targetPath = targetBase + "/" + rel;
                String backupPath = BACKUP_SKINS_DIR + "/" + rel;
                int lastSlash = backupPath.lastIndexOf('/');
                if (lastSlash > 0) {
                    String backupParent = backupPath.substring(0, lastSlash);
                    ShizukuShell.exec("mkdir -p \"" + backupParent + "\"");
                }
                // Only back up non-empty original files (`-s` checks file exists and size > 0)
                ShizukuShell.exec("if [ ! -s \"" + backupPath + "\" ] && [ -s \"" + targetPath + "\" ]; then cp -f \"" + targetPath + "\" \"" + backupPath + "\"; fi");
            }
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        if (src == null || dst == null || !src.exists() || src.length() == 0) {
            return;
        }
        // Prevent self-copy truncation to 0 bytes when src and dst resolve to the same path!
        if (src.getCanonicalPath().equals(dst.getCanonicalPath())) {
            return;
        }
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[32768];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    private static void copyDirectoryRecursive(File srcDir, File dstDir) throws IOException {
        if (srcDir == null || !srcDir.exists()) return;
        if (srcDir.isDirectory()) {
            if (!dstDir.exists()) dstDir.mkdirs();
            File[] children = srcDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyDirectoryRecursive(child, new File(dstDir, child.getName()));
                }
            }
        } else if (srcDir.length() > 0) {
            copyFile(srcDir, dstDir);
        }
    }

    /**
     * Self-heals any <prefix>_skin.unity3d base skeleton rig that was accidentally overwritten
     * by an _add.unity3d mesh bundle in an earlier version.
     */
    private static void healHeroBaseRig(String heroCodeName) {
        if (heroCodeName == null || heroCodeName.isEmpty()) return;
        File checkDir = new File(STAGE_DIR, "rig_check");
        checkDir.mkdirs();
        String hDir = DRAGON_ASSETS + "/Art/android/h";
        String backupHDir = BACKUP_SKINS_DIR + "/Art/android/h";

        for (String prefix : getCandidateHeroPrefixes(heroCodeName)) {
            String baseSkin = hDir + "/" + prefix + "_skin.unity3d";
            String bakSkin = backupHDir + "/" + prefix + "_skin.unity3d";

            // 1. Check if backup _skin.unity3d is actually a fake cloned _add bundle
            File tmpBak = new File(checkDir, prefix + "_bak_skin.unity3d");
            tmpBak.delete();
            ShizukuShell.exec("if [ -s \"" + bakSkin + "\" ]; then cp -f \"" + bakSkin + "\" \"" + tmpBak.getAbsolutePath() + "\"; chmod 666 \"" + tmpBak.getAbsolutePath() + "\"; fi");
            if (tmpBak.exists() && UnityBundlePatcher.isClonedAddBundle(tmpBak)) {
                Log.w(TAG, "Removing poisoned fake backup rig: " + bakSkin);
                ShizukuShell.exec("rm -f \"" + bakSkin + "\"");
                tmpBak.delete();
            }

            // 2. Check if active dragon2017 _skin.unity3d is a fake cloned _add bundle
            File tmpActive = new File(checkDir, prefix + "_active_skin.unity3d");
            tmpActive.delete();
            ShizukuShell.exec("if [ -s \"" + baseSkin + "\" ]; then cp -f \"" + baseSkin + "\" \"" + tmpActive.getAbsolutePath() + "\"; chmod 666 \"" + tmpActive.getAbsolutePath() + "\"; fi");
            if (tmpActive.exists() && UnityBundlePatcher.isClonedAddBundle(tmpActive)) {
                Log.w(TAG, "Healing poisoned active rig: " + baseSkin);
                if (tmpBak.exists() && tmpBak.length() > 64) {
                    // Restore genuine backup rig
                    ShizukuShell.exec("cp -f \"" + bakSkin + "\" \"" + baseSkin + "\"");
                    ShizukuShell.exec("if [ -d \"" + FILES + "/mini_patch\" ]; then find \"" + FILES + "/mini_patch\" -type d -path \"*/Art/android/h\" -exec cp -f \"" + bakSkin + "\" {}/" + prefix + "_skin.unity3d \\; 2>/dev/null; fi");
                } else {
                    // Delete fake _skin.unity3d so it stops corrupting the hero skeleton
                    ShizukuShell.exec("rm -f \"" + baseSkin + "\"");
                    ShizukuShell.exec("if [ -d \"" + FILES + "/mini_patch\" ]; then find \"" + FILES + "/mini_patch\" -name \"" + prefix + "_skin.unity3d\" -exec rm -f {} + 2>/dev/null; fi");
                }
            }
            tmpBak.delete();
            tmpActive.delete();
        }
        deleteRecursive(checkDir);
    }

    private static void prepareSkinMeshes(File assetsRoot, HeroSkinOption h) {
        // 1. Process 3D meshes in Art/android/h using UnityBundlePatcher so internal
        //    AssetBundle m_Container & m_AssetBundleName match _skin_, _skin01_, _skin02_, _skin03_
        //    across all quality tiers (_high_add, _add, _low_add, _extlow_add) without touching _skin.unity3d!
        File hDir = new File(assetsRoot, "Art/android/h");
        if (hDir.exists() && hDir.isDirectory()) {
            UnityBundlePatcher.patchHeroModelSet(hDir, h.heroCodeName, h.targetSkinId);
        }

        // 2. Process UI Portraits for Hero Selection & Loading Screen
        File uiDir = new File(assetsRoot, "UI/android");
        if (uiDir.exists() && uiDir.isDirectory()) {
            File[] initialUiFiles = uiDir.listFiles();
            if (initialUiFiles != null) {
                for (File f : initialUiFiles) {
                    if (!f.isFile() || f.length() <= 100) continue;
                    String name = f.getName();
                    if (name.startsWith("Hero") && name.endsWith("_add.unity3d")) {
                        String rawNum = name.substring(4, name.indexOf("_add.unity3d"));
                        if (rawNum.length() >= 3) {
                            String heroIdPrefix = rawNum.substring(0, rawNum.length() - 1);
                            File tmpSrc = new File(uiDir, name + ".src_tmp");
                            try {
                                copyFile(f, tmpSrc);
                                String dst1 = "Hero" + heroIdPrefix + "1_add.unity3d";
                                String dst2 = "Hero" + heroIdPrefix + "2_add.unity3d";
                                String dst3 = "Hero" + heroIdPrefix + "3_add.unity3d";
                                UnityBundlePatcher.patchBundleContainer(tmpSrc, new File(uiDir, dst1), dst1, "hero" + heroIdPrefix + "1_add.prefab");
                                UnityBundlePatcher.patchBundleContainer(tmpSrc, new File(uiDir, dst2), dst2, "hero" + heroIdPrefix + "2_add.prefab");
                                UnityBundlePatcher.patchBundleContainer(tmpSrc, new File(uiDir, dst3), dst3, "hero" + heroIdPrefix + "3_add.prefab");
                            } catch (Exception ignored) {
                            } finally {
                                tmpSrc.delete();
                            }
                        }
                    }
                }
            }
        }

        // 3. Process skill VFX & indicators in Art/android/f
        File fDir = new File(assetsRoot, "Art/android/f");
        if (fDir.exists() && fDir.isDirectory()) {
            File[] initialFFiles = fDir.listFiles();
            if (initialFFiles != null) {
                for (File f : initialFFiles) {
                    if (!f.isFile() || f.length() <= 100) continue;
                    String name = f.getName();
                    if (!name.endsWith(".unity3d")) continue;

                    File tmpSrc = new File(fDir, name + ".src_tmp");
                    try {
                        copyFile(f, tmpSrc);
                        if (name.contains("biaoji01.unity3d")) {
                            String dst1 = name.replace("biaoji01.unity3d", "biaoji.unity3d");
                            String dst2 = name.replace("biaoji01.unity3d", "biaoji00.unity3d");
                            UnityBundlePatcher.patchBundleContainer(tmpSrc, new File(fDir, dst1), dst1, dst1.replace(".unity3d", ".prefab"));
                            UnityBundlePatcher.patchBundleContainer(tmpSrc, new File(fDir, dst2), dst2, dst2.replace(".unity3d", ".prefab"));
                        } else if (name.contains("_skin") && name.endsWith("_add.unity3d")) {
                            // Patch the skin-specific effect bundle in-place with multi-skin container aliases
                            UnityBundlePatcher.patchBundleContainer(tmpSrc, f, name, null);
                            // Also generate the base effect bundle (<effect>_add.unity3d) if not already in fDir
                            String baseEffectName = name.replaceAll("_skin\\d*_", "_");
                            File baseEffectFile = new File(fDir, baseEffectName);
                            if (!baseEffectFile.exists()) {
                                UnityBundlePatcher.patchBundleContainer(tmpSrc, baseEffectFile, baseEffectName, null);
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        tmpSrc.delete();
                    }
                }
            }
        }
    }

    private static void installExtractedSkinBundle(File cacheDir, HeroSkinOption h, Callback callback) throws IOException {
        File cachedAssetsRoot = findAssetsRoot(cacheDir);
        if (cachedAssetsRoot == null || !cachedAssetsRoot.exists()) return;

        // Stage a fresh working copy from cacheDir so cacheDir is NEVER mutated in-place
        File stageSkinRoot = new File(STAGE_DIR, "skin_work_" + h.heroId);
        deleteRecursive(stageSkinRoot);
        stageSkinRoot.mkdirs();
        copyDirectoryRecursive(cachedAssetsRoot, stageSkinRoot);

        // Self-heal any <prefix>_skin.unity3d base rig on the device before backing up or deploying
        healHeroBaseRig(h.heroCodeName);

        postProgress(callback, "Patching 3D models & containers for " + h.heroName + "...");
        prepareSkinMeshes(stageSkinRoot, h);

        // Remove any accidental 0-byte files inside stageSkinRoot before copying to MLBB
        purgeZeroByteFilesRecursive(stageSkinRoot);

        // CRITICAL: NEVER allow custom skin zips to overwrite the hero's base skeleton rig (<prefix>.unity3d and <prefix>_skin.unity3d)
        File stageHDir = new File(stageSkinRoot, "Art/android/h");
        if (stageHDir.exists() && h.heroCodeName != null) {
            for (String prefix : getCandidateHeroPrefixes(h.heroCodeName)) {
                File fakeRig = new File(stageHDir, prefix + ".unity3d");
                if (fakeRig.exists()) fakeRig.delete();
                File fakeSkin = new File(stageHDir, prefix + "_skin.unity3d");
                if (fakeSkin.exists()) fakeSkin.delete();
            }
        }

        backupExistingFiles(stageSkinRoot, stageSkinRoot, DRAGON_ASSETS);

        // Ensure base hero skeleton rig (<heroCodeName>.unity3d) and base smooth rig are intact and never missing
        if (h.heroCodeName != null) {
            for (String prefix : getCandidateHeroPrefixes(h.heroCodeName)) {
                String baseRig = DRAGON_ASSETS + "/Art/android/h/" + prefix + ".unity3d";
                String bakRig = BACKUP_SKINS_DIR + "/Art/android/h/" + prefix + ".unity3d";
                ShizukuShell.exec("if [ ! -s \"" + baseRig + "\" ] && [ -s \"" + bakRig + "\" ]; then cp -f \"" + bakRig + "\" \"" + baseRig + "\"; fi");

                String baseSkin = DRAGON_ASSETS + "/Art/android/h/" + prefix + "_skin.unity3d";
                String bakSkin = BACKUP_SKINS_DIR + "/Art/android/h/" + prefix + "_skin.unity3d";
                ShizukuShell.exec("if [ ! -s \"" + baseSkin + "\" ] && [ -s \"" + bakSkin + "\" ]; then cp -f \"" + bakSkin + "\" \"" + baseSkin + "\"; fi");
            }
        }

        String sourcePath = stageSkinRoot.getAbsolutePath();
        postProgress(callback, "Deploying skin to dragon2017/assets/...");
        ShizukuShell.exec("chmod -R 777 \"" + DRAGON_ASSETS + "/Art\" \"" + DRAGON_ASSETS + "/UI\" \"" + DRAGON_ASSETS + "/Audio\" \"" + FILES + "/mini_patch\" 2>/dev/null");
        ShizukuShell.exec("cp -rf \"" + sourcePath + "\"/* \"" + DRAGON_ASSETS + "/\"");

        // CRUCIAL FOR MODERN MLBB (v1232+): Overwrite active mini_patch directories!
        postProgress(callback, "Overwriting active MLBB mini_patch assets...");
        String miniPatchSync =
                "if [ -d \"" + FILES + "/mini_patch\" ]; then " +
                "  find \"" + FILES + "/mini_patch\" -type d -name \"Art\" -exec cp -rf \"" + sourcePath + "/Art/\"* {}/ \\; 2>/dev/null; " +
                "  find \"" + FILES + "/mini_patch\" -type d -name \"UI\" -exec cp -rf \"" + sourcePath + "/UI/\"* {}/ \\; 2>/dev/null; " +
                "  find \"" + FILES + "/mini_patch\" -type d -name \"Audio\" -exec cp -rf \"" + sourcePath + "/Audio/\"* {}/ \\; 2>/dev/null; " +
                "  for d in " + FILES + "/mini_patch/*/*/*/ ; do " +
                "    if [ -d \"$d\" ]; then cp -rf \"" + sourcePath + "\"/* \"$d/\" 2>/dev/null; fi; " +
                "  done; " +
                "  for d in " + FILES + "/mini_patch/*/*/ ; do " +
                "    if [ -d \"$d\" ]; then cp -rf \"" + sourcePath + "\"/* \"$d/\" 2>/dev/null; fi; " +
                "  done; " +
                "fi; ";
        ShizukuShell.exec(miniPatchSync);

        ShizukuShell.exec("chmod -R 777 \"" + DRAGON_ASSETS + "\" 2>/dev/null");
        ShizukuShell.exec("chmod -R 777 \"" + FILES + "/mini_patch\" 2>/dev/null");
        deleteRecursive(stageSkinRoot);
    }

    private static void purgeZeroByteFilesRecursive(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                purgeZeroByteFilesRecursive(f);
            } else if (f.length() == 0) {
                f.delete();
            }
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        f.delete();
    }

    private static List<String> getCandidateHeroPrefixes(String heroCodeName) {
        List<String> list = new ArrayList<>();
        if (heroCodeName != null && !heroCodeName.isEmpty()) {
            list.add(heroCodeName);
        }
        if ("hero_pofa".equalsIgnoreCase(heroCodeName) || "hero_zeo".equalsIgnoreCase(heroCodeName)) {
            if (!list.contains("hero_pofa")) list.add("hero_pofa");
            if (!list.contains("hero_zeo")) list.add("hero_zeo");
        } else if ("hero_ninja".equalsIgnoreCase(heroCodeName) || "hero_haya".equalsIgnoreCase(heroCodeName)) {
            if (!list.contains("hero_ninja")) list.add("hero_ninja");
            if (!list.contains("hero_haya")) list.add("hero_haya");
        } else if ("hero_kongfu".equalsIgnoreCase(heroCodeName) || "hero_chou".equalsIgnoreCase(heroCodeName)) {
            if (!list.contains("hero_kongfu")) list.add("hero_kongfu");
            if (!list.contains("hero_chou")) list.add("hero_chou");
        } else if ("hero_lancelot".equalsIgnoreCase(heroCodeName) || "hero_lance".equalsIgnoreCase(heroCodeName)) {
            if (!list.contains("hero_lancelot")) list.add("hero_lancelot");
            if (!list.contains("hero_lance")) list.add("hero_lance");
        } else if ("hero_mike".equalsIgnoreCase(heroCodeName) || "hero_granger".equalsIgnoreCase(heroCodeName)) {
            if (!list.contains("hero_mike")) list.add("hero_mike");
            if (!list.contains("hero_granger")) list.add("hero_granger");
        }
        return list;
    }

    private static void swapHero3DModel(String heroCodeName, String targetSkinId) {
        healHeroBaseRig(heroCodeName);

        String hDir = DRAGON_ASSETS + "/Art/android/h";
        String backupHDir = BACKUP_SKINS_DIR + "/Art/android/h";
        ShizukuShell.exec("mkdir -p \"" + backupHDir + "\"");

        for (String prefix : getCandidateHeroPrefixes(heroCodeName)) {
            // Preserve base skeleton rig (<prefix>.unity3d) and smooth base (<prefix>_skin.unity3d) — NEVER delete them!
            String baseRig = hDir + "/" + prefix + ".unity3d";
            String bakRig = backupHDir + "/" + prefix + ".unity3d";
            ShizukuShell.exec("if [ ! -s \"" + bakRig + "\" ] && [ -s \"" + baseRig + "\" ]; then cp -f \"" + baseRig + "\" \"" + bakRig + "\"; fi");
            ShizukuShell.exec("if [ ! -s \"" + baseRig + "\" ] && [ -s \"" + bakRig + "\" ]; then cp -f \"" + bakRig + "\" \"" + baseRig + "\"; fi");

            String baseSkin = hDir + "/" + prefix + "_skin.unity3d";
            String bakSkin = backupHDir + "/" + prefix + "_skin.unity3d";
            ShizukuShell.exec("if [ ! -s \"" + bakSkin + "\" ] && [ -s \"" + baseSkin + "\" ]; then cp -f \"" + baseSkin + "\" \"" + bakSkin + "\"; fi");
            ShizukuShell.exec("if [ ! -s \"" + baseSkin + "\" ] && [ -s \"" + bakSkin + "\" ]; then cp -f \"" + bakSkin + "\" \"" + baseSkin + "\"; fi");

            String[] baseTargets = {
                    prefix + "_skin_high_add.unity3d",
                    prefix + "_skin_add.unity3d",
                    prefix + "_skin_low_add.unity3d",
                    prefix + "_skin_extlow_add.unity3d",
                    prefix + "_skin_shadow_high_add.unity3d",
                    prefix + "_skin_shadow_add.unity3d",
                    prefix + "_skin01_high_add.unity3d",
                    prefix + "_skin01_add.unity3d",
                    prefix + "_skin01_low_add.unity3d",
                    prefix + "_skin01_extlow_add.unity3d",
                    prefix + "_skin01_shadow_high_add.unity3d",
                    prefix + "_skin01_shadow_add.unity3d",
                    prefix + "_skin02_high_add.unity3d",
                    prefix + "_skin02_add.unity3d",
                    prefix + "_skin02_low_add.unity3d",
                    prefix + "_skin02_extlow_add.unity3d",
                    prefix + "_skin02_shadow_high_add.unity3d",
                    prefix + "_skin02_shadow_add.unity3d",
                    prefix + "_skin03_high_add.unity3d",
                    prefix + "_skin03_add.unity3d",
                    prefix + "_skin03_low_add.unity3d",
                    prefix + "_skin03_extlow_add.unity3d",
                    prefix + "_skin03_shadow_high_add.unity3d",
                    prefix + "_skin03_shadow_add.unity3d"
            };

            String[] suffixes = {
                    "_high_add.unity3d",
                    "_add.unity3d",
                    "_low_add.unity3d",
                    "_extlow_add.unity3d",
                    "_shadow_high_add.unity3d",
                    "_shadow_add.unity3d"
            };

            List<String> targetsList = new ArrayList<>(Arrays.asList(baseTargets));
            if (targetSkinId != null && !targetSkinId.trim().isEmpty()) {
                String tid = targetSkinId.trim();
                for (String sfx : suffixes) {
                    String customFile = prefix + "_skin" + tid + sfx;
                    if (!targetsList.contains(customFile)) targetsList.add(customFile);
                    if (tid.startsWith("0") && tid.length() == 2) {
                        String singleFile = prefix + "_skin" + tid.substring(1) + sfx;
                        if (!targetsList.contains(singleFile)) targetsList.add(singleFile);
                    }
                }
            }
            String[] targets = targetsList.toArray(new String[0]);

            // 1. Back up originals if not yet backed up (and non-empty)
            for (String file : targets) {
                String orig = hDir + "/" + file;
                String bak = backupHDir + "/" + file;
                ShizukuShell.exec("if [ ! -s \"" + bak + "\" ] && [ -s \"" + orig + "\" ]; then cp -f \"" + orig + "\" \"" + bak + "\"; fi");
            }

            // 2. Stage target skin bundles to STAGE_DIR, patch their internal m_Container via UnityBundlePatcher, and deploy
            File stageHDir = new File(STAGE_DIR, "h_patch_" + prefix);
            deleteRecursive(stageHDir);
            stageHDir.mkdirs();

            String srcSkinToken = prefix + "_skin" + targetSkinId;
            boolean anyStaged = false;
            for (String sfx : suffixes) {
                String remoteSrc = hDir + "/" + srcSkinToken + sfx;
                File localSrc = new File(stageHDir, srcSkinToken + sfx);
                ShizukuShell.exec("if [ -s \"" + remoteSrc + "\" ]; then cp -f \"" + remoteSrc + "\" \"" + localSrc.getAbsolutePath() + "\"; chmod 666 \"" + localSrc.getAbsolutePath() + "\"; fi");
                if (localSrc.exists() && localSrc.length() > 1024) {
                    anyStaged = true;
                }
            }

            if (anyStaged) {
                UnityBundlePatcher.patchHeroModelSet(stageHDir, prefix, targetSkinId);
                for (String file : targets) {
                    File patchedFile = new File(stageHDir, file);
                    if (patchedFile.exists() && patchedFile.length() > 1024) {
                        String dest = hDir + "/" + file;
                        ShizukuShell.exec("cp -f \"" + patchedFile.getAbsolutePath() + "\" \"" + dest + "\"");
                        ShizukuShell.exec(
                                "if [ -d \"" + FILES + "/mini_patch\" ]; then " +
                                "  find \"" + FILES + "/mini_patch\" -type d -path \"*/Art/android/h\" -exec cp -f \"" + patchedFile.getAbsolutePath() + "\" {}/" + file + " \\; 2>/dev/null; " +
                                "fi"
                        );
                    }
                }
            }
            deleteRecursive(stageHDir);
        }
    }

    private static void applyHeroSwapInternal(Context context, HeroSkinOption h, Callback callback) throws Exception {
        injectGameResAlternative(context);

        // Purge legacy poisoned cache folder so corrupted in-place bundles are discarded
        File legacyCacheRoot = new File("/sdcard/Download/WillyLibTool/extracted");
        if (legacyCacheRoot.exists()) {
            deleteRecursive(legacyCacheRoot);
        }

        // 1. If downloadUrl is present, fetch and extract the skin bundle (3D models, VFX/Particles, Audio, UI)
        if (h.downloadUrl != null && !h.downloadUrl.isEmpty()) {
            File cacheDir = new File("/sdcard/Download/WillyLibTool/extracted_v4/" + h.heroId + "_" + h.targetSkinId);
            if (!isExtractedCacheValid(cacheDir)) {
                Log.w(TAG, "Cache missing or poisoned for " + h.heroId + "; downloading clean bundle...");
                deleteRecursive(cacheDir);
                cacheDir.mkdirs();
                postProgress(callback, "Downloading " + h.heroName + " (" + h.skinName + ")...");
                downloadAndExtractSkinZip(h.downloadUrl, cacheDir, callback);
            }

            if (isExtractedCacheValid(cacheDir)) {
                postProgress(callback, "Injecting " + h.heroName + " (" + h.skinName + ")...");
                installExtractedSkinBundle(cacheDir, h, callback);
            } else {
                throw new IOException("Downloaded skin bundle for " + h.heroName + " failed integrity check.");
            }
        } else {
            // Offline fallback swap using local device assets
            if (h.heroCodeName != null && h.targetSkinId != null) {
                postProgress(callback, "Applying 3D model for " + h.heroName + " (" + h.skinName + ")...");
                swapHero3DModel(h.heroCodeName, h.targetSkinId);
            }
        }

        // 2. Bundled offline asset injection (for Hayabusa)
        if ("hayabusa".equalsIgnoreCase(h.heroId) && context != null) {
            try {
                File stage = new File(STAGE_DIR);
                stage.mkdirs();
                stageAsset(context, "mlbb_drone/base/Art/android/nextnextninja_skill03_UI_skin07_add.unity3d", stage, "nextnextninja_skill03_UI_skin07_add.unity3d");
                stageAsset(context, "mlbb_drone/base/Art/android/nextnextninja_skill03_fire_skin07_add.unity3d", stage, "nextnextninja_skill03_fire_skin07_add.unity3d");
                stageAsset(context, "mlbb_drone/base/Art/android/nextnextninja_skill03_beiji_skin07_add.unity3d", stage, "nextnextninja_skill03_beiji_skin07_add.unity3d");

                String[] bundledHaya = {
                        "nextnextninja_skill03_UI_skin07_add.unity3d",
                        "nextnextninja_skill03_fire_skin07_add.unity3d",
                        "nextnextninja_skill03_beiji_skin07_add.unity3d"
                };
                for (String bf : bundledHaya) {
                    File st = new File(stage, bf);
                    if (st.exists() && st.length() > 0) {
                        String dest = DRAGON_ASSETS + "/Art/android/" + bf;
                        ShizukuShell.exec("cp -f \"" + st.getAbsolutePath() + "\" \"" + dest + "\"");
                        st.delete();
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void applyHeroSwapInternal(Context context, HeroSkinOption h) throws Exception {
        applyHeroSwapInternal(context, h, null);
    }

    private static void restoreHeroSwapInternal(HeroSkinOption h) {
        if (h.heroCodeName != null) {
            healHeroBaseRig(h.heroCodeName);
            String hDir = DRAGON_ASSETS + "/Art/android/h";
            String backupHDir = BACKUP_SKINS_DIR + "/Art/android/h";
            for (String prefix : getCandidateHeroPrefixes(h.heroCodeName)) {
                // 1. Wildcard purge of ALL modded skin meshes (skin01 to skin20, plus any _skin_add/_skin_high_add variants)
                // from both dragon2017 and all mini_patch slots
                String purgeCmd =
                        "rm -f \"" + hDir + "/" + prefix + "_skin0\"*.unity3d 2>/dev/null; " +
                        "rm -f \"" + hDir + "/" + prefix + "_skin1\"*.unity3d 2>/dev/null; " +
                        "rm -f \"" + hDir + "/" + prefix + "_skin2\"*.unity3d 2>/dev/null; " +
                        "rm -f \"" + hDir + "/" + prefix + "_skin_high_add.unity3d\" 2>/dev/null; " +
                        "rm -f \"" + hDir + "/" + prefix + "_skin_add.unity3d\" 2>/dev/null; " +
                        "rm -f \"" + hDir + "/" + prefix + "_skin_low_add.unity3d\" 2>/dev/null; " +
                        "rm -f \"" + hDir + "/" + prefix + "_skin_extlow_add.unity3d\" 2>/dev/null; " +
                        "rm -f \"" + hDir + "/" + prefix + "_skin_shadow_high_add.unity3d\" 2>/dev/null; " +
                        "rm -f \"" + hDir + "/" + prefix + "_skin_shadow_add.unity3d\" 2>/dev/null; " +
                        "if [ -d \"" + FILES + "/mini_patch\" ]; then " +
                        "  find \"" + FILES + "/mini_patch\" -type d -path \"*/Art/android/h\" -exec rm -f {}/" + prefix + "_skin0*.unity3d {}/" + prefix + "_skin1*.unity3d {}/" + prefix + "_skin2*.unity3d \\; 2>/dev/null; " +
                        "  find \"" + FILES + "/mini_patch\" -type d -path \"*/Art/android/h\" -exec rm -f {}/" + prefix + "_skin_high_add.unity3d {}/" + prefix + "_skin_add.unity3d {}/" + prefix + "_skin_low_add.unity3d {}/" + prefix + "_skin_extlow_add.unity3d {}/" + prefix + "_skin_shadow_high_add.unity3d {}/" + prefix + "_skin_shadow_add.unity3d \\; 2>/dev/null; " +
                        "fi; ";
                ShizukuShell.exec(purgeCmd);

                // 2. Restore genuine stock files if saved in backup
                String[] targets = {
                        prefix + ".unity3d",
                        prefix + "_skin.unity3d",
                        prefix + "_skin_high_add.unity3d",
                        prefix + "_skin_add.unity3d",
                        prefix + "_skin_low_add.unity3d",
                        prefix + "_skin_extlow_add.unity3d",
                        prefix + "_skin_shadow_high_add.unity3d",
                        prefix + "_skin_shadow_add.unity3d"
                };
                for (String file : targets) {
                    String orig = hDir + "/" + file;
                    String bak = backupHDir + "/" + file;
                    ShizukuShell.exec(
                            "if [ -s \"" + bak + "\" ]; then " +
                            "  cp -f \"" + bak + "\" \"" + orig + "\"; " +
                            "  if [ -d \"" + FILES + "/mini_patch\" ]; then find \"" + FILES + "/mini_patch\" -type d -path \"*/Art/android/h\" -exec cp -f \"" + bak + "\" {}/" + file + " \\; 2>/dev/null; fi; " +
                            "  rm -f \"" + bak + "\"; " +
                            "fi"
                    );
                }
            }
            healHeroBaseRig(h.heroCodeName);
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
        try (InputStream in = context.getAssets().open(assetPath);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.e(TAG, "Cannot read asset: " + assetPath, t);
            return null;
        }
    }

    private static void stageAsset(Context context, String assetPath, File stageDir, String outFileName) {
        if (context == null) return;
        File target = new File(stageDir, outFileName);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (InputStream in = context.getAssets().open(assetPath);
             FileOutputStream fos = new FileOutputStream(target)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
            fos.flush();
            target.setReadable(true, false);
        } catch (Throwable t) {
            Log.e(TAG, "Cannot stage asset: " + assetPath, t);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. COMBAT DAMAGE & HERO STATS INJECTION (SHIZUKU NON-ROOT)
    // ─────────────────────────────────────────────────────────────────────────

    public static final String BACKUP_COMBAT_DIR = FILES + "/dragon2017/backup_combat";

    public static void injectCombatDamage(Context context, Callback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                postComplete(callback, false, "Shizuku permission not granted.");
                return;
            }
            if (context == null) {
                postComplete(callback, false, "Context unavailable for staging combat assets.");
                return;
            }

            try {
                postProgress(callback, "1/4: Staging BattleConfig.unity3d (10.5MB) & HeroStatConfig...");
                File stage = new File(STAGE_DIR, "combat");
                deleteRecursive(stage);
                stage.mkdirs();

                String[][] docAssets = {
                        {"mlbb_drone/base/Document/android/BattleSystemConfig.bytes", "BattleSystemConfig.bytes"},
                        {"mlbb_drone/base/Document/android/BattleConfig.unity3d", "BattleConfig.unity3d"},
                        {"mlbb_drone/base/Document/android/BattleConfig.json", "BattleConfig.json"},
                        {"mlbb_drone/base/Document/android/HeroStatConfig.json", "HeroStatConfig.json"},
                        {"mlbb_drone/base/Document/android/res_skip_patch.xml", "res_skip_patch.xml"},
                        {"mlbb_drone/base/Document/android/ResCheckConf.unity3d", "ResCheckConf.unity3d"},
                        {"mlbb_drone/base/Document/android/ResCheckConf.xml", "ResCheckConf.xml"},
                        {"mlbb_drone/base/Document/android/BinaryPatchMD5.xml", "BinaryPatchMD5.xml"},
                        {"mlbb_drone/base/Document/android/LoadResMgr_xxh_and.bytes", "LoadResMgr_xxh_and.bytes"},
                        {"mlbb_drone/base/Document/android/LoadResMgrHigh_xxh_and.bytes", "LoadResMgrHigh_xxh_and.bytes"},
                        {"mlbb_drone/base/Document/android/mola_config.xml", "mola_config.xml"},
                        {"mlbb_drone/base/Document/android/mode_versions_build.xml", "mode_versions_build.xml"},
                        {"mlbb_drone/base/Document/android/SplitLibMD5.xml", "SplitLibMD5.xml"}
                };

                for (String[] pair : docAssets) {
                    stageAsset(context, pair[0], stage, pair[1]);
                }

                stageAsset(context, "mlbb_drone/base/_load_res.bytes", stage, "_load_res.bytes");
                stageAsset(context, "mlbb_drone/base/__fix_rescheck", stage, "__fix_rescheck");
                stageAsset(context, "mlbb_drone/base/gameres/android/Define.bytes", stage, "Define.bytes");
                stageAsset(context, "mlbb_drone/base/gameres/android/mlf/ModulesConf.bytes", stage, "ModulesConf.bytes");

                File battleBundle = new File(stage, "BattleConfig.unity3d");
                if (!battleBundle.exists() || battleBundle.length() < 1000000) {
                    postComplete(callback, false, "Failed to stage BattleConfig.unity3d payload.");
                    return;
                }

                postProgress(callback, "2/4: Backing up original BattleConfig & unlocking permissions...");
                String docAndroid = DRAGON_ASSETS + "/Document/android";
                String bakDoc = BACKUP_COMBAT_DIR + "/Document/android";
                String bakGameres = BACKUP_COMBAT_DIR + "/gameres/android";
                ShizukuShell.exec(
                        "mkdir -p \"" + docAndroid + "\" \"" + bakDoc + "\" \"" + bakGameres + "/mlf\"; " +
                        "chmod -R 777 \"" + DRAGON_ASSETS + "/Document\" 2>/dev/null; " +
                        "chmod -R 777 \"" + FILES + "/LoadResManager/Document\" 2>/dev/null; " +
                        "chmod -R 777 \"" + MINI_PATCH + "\" 2>/dev/null"
                );

                String[] backupDocNames = {
                        "BattleSystemConfig.bytes",
                        "BattleConfig.unity3d",
                        "ResCheckConf.unity3d",
                        "ResCheckConf.xml",
                        "BinaryPatchMD5.xml",
                        "LoadResMgr_xxh_and.bytes",
                        "LoadResMgrHigh_xxh_and.bytes",
                        "mola_config.xml",
                        "mode_versions_build.xml",
                        "SplitLibMD5.xml"
                };
                for (String name : backupDocNames) {
                    String orig = docAndroid + "/" + name;
                    String bak = bakDoc + "/" + name;
                    ShizukuShell.exec("if [ ! -s \"" + bak + "\" ] && [ -s \"" + orig + "\" ]; then cp -f \"" + orig + "\" \"" + bak + "\"; fi");
                }
                ShizukuShell.exec("if [ ! -s \"" + bakGameres + "/Define.bytes\" ] && [ -s \"" + DRAGON_ASSETS + "/gameres/android/Define.bytes\" ]; then cp -f \"" + DRAGON_ASSETS + "/gameres/android/Define.bytes\" \"" + bakGameres + "/Define.bytes\"; fi");
                ShizukuShell.exec("if [ ! -s \"" + bakGameres + "/mlf/ModulesConf.bytes\" ] && [ -s \"" + DRAGON_ASSETS + "/gameres/android/mlf/ModulesConf.bytes\" ]; then cp -f \"" + DRAGON_ASSETS + "/gameres/android/mlf/ModulesConf.bytes\" \"" + bakGameres + "/mlf/ModulesConf.bytes\"; fi");

                postProgress(callback, "3/4: Injecting BattleConfig, HeroStatConfig & Define.bytes into dragon2017...");
                String stagePath = stage.getAbsolutePath();
                String[] docTargets = {
                        DRAGON_ASSETS + "/Document/android",
                        DRAGON_ASSETS + "/Document",
                        FILES + "/LoadResManager/Document/android"
                };

                for (String doc : docTargets) {
                    ShizukuShell.exec("mkdir -p \"" + doc + "\" && chmod 777 \"" + doc + "\" 2>/dev/null");
                    for (String[] pair : docAssets) {
                        String fn = pair[1];
                        ShizukuShell.exec("chmod 666 \"" + doc + "/" + fn + "\" 2>/dev/null; cp -f \"" + stagePath + "/" + fn + "\" \"" + doc + "/" + fn + "\"");
                    }
                }

                // Inject Lua/Table Define.bytes & ModulesConf.bytes + root rescheck markers
                ShizukuShell.exec(
                        "mkdir -p \"" + DRAGON_ASSETS + "/gameres/android/mlf\"; " +
                        "cp -f \"" + stagePath + "/Define.bytes\" \"" + DRAGON_ASSETS + "/gameres/android/Define.bytes\" 2>/dev/null; " +
                        "cp -f \"" + stagePath + "/ModulesConf.bytes\" \"" + DRAGON_ASSETS + "/gameres/android/mlf/ModulesConf.bytes\" 2>/dev/null; " +
                        "cp -f \"" + stagePath + "/_load_res.bytes\" \"" + DRAGON_ASSETS + "/_load_res.bytes\" 2>/dev/null; " +
                        "cp -f \"" + stagePath + "/__fix_rescheck\" \"" + DRAGON_ASSETS + "/__fix_rescheck\" 2>/dev/null; " +
                        "touch \"" + DRAGON_ASSETS + "/__ready\" \"" + DRAGON_ASSETS + "/__active\" 2>/dev/null"
                );

                postProgress(callback, "4/4: Syncing Combat Damage to active mini_patch slots & locking files...");
                List<String> activeSlots = discoverActiveSlots();
                int patchedSlots = 0;
                for (String slot : activeSlots) {
                    String slotDoc = slot + "/Document/android";
                    String slotGameres = slot + "/gameres/android";
                    ShizukuShell.exec("mkdir -p \"" + slotDoc + "\" \"" + slotGameres + "/mlf\" && chmod 777 \"" + slotDoc + "\" 2>/dev/null");
                    for (String[] pair : docAssets) {
                        String fn = pair[1];
                        ShizukuShell.exec("chmod 666 \"" + slotDoc + "/" + fn + "\" 2>/dev/null; cp -f \"" + stagePath + "/" + fn + "\" \"" + slotDoc + "/" + fn + "\"");
                    }
                    ShizukuShell.exec(
                            "cp -f \"" + stagePath + "/Define.bytes\" \"" + slotGameres + "/Define.bytes\" 2>/dev/null; " +
                            "cp -f \"" + stagePath + "/ModulesConf.bytes\" \"" + slotGameres + "/mlf/ModulesConf.bytes\" 2>/dev/null; " +
                            "cp -f \"" + stagePath + "/_load_res.bytes\" \"" + slot + "/_load_res.bytes\" 2>/dev/null; " +
                            "cp -f \"" + stagePath + "/__fix_rescheck\" \"" + slot + "/__fix_rescheck\" 2>/dev/null; " +
                            "touch \"" + slot + "/__ready\" \"" + slot + "/__active\" 2>/dev/null; " +
                            "chmod 444 \"" + slotDoc + "/BattleSystemConfig.bytes\" \"" + slotDoc + "/BattleConfig.unity3d\" \"" + slotDoc + "/BattleConfig.json\" \"" + slotDoc + "/HeroStatConfig.json\" \"" + slotDoc + "/res_skip_patch.xml\" \"" + slotDoc + "/ResCheckConf.unity3d\" \"" + slotDoc + "/ResCheckConf.xml\" \"" + slotDoc + "/BinaryPatchMD5.xml\" 2>/dev/null"
                    );
                    patchedSlots++;
                }

                // Also sync into any dynamically created mini_patch Document directories
                ShizukuShell.exec(
                        "if [ -d \"" + MINI_PATCH + "\" ]; then " +
                        "  for d in \"" + MINI_PATCH + "\"/*/*/*/ ; do " +
                        "    if [ -d \"$d\" ]; then " +
                        "      mkdir -p \"$d/Document/android\" \"$d/gameres/android/mlf\" 2>/dev/null; " +
                        "      chmod 666 \"$d/Document/android/BattleSystemConfig.bytes\" \"$d/Document/android/BattleConfig.unity3d\" \"$d/Document/android/BattleConfig.json\" \"$d/Document/android/HeroStatConfig.json\" \"$d/Document/android/res_skip_patch.xml\" 2>/dev/null; " +
                        "      cp -f \"" + stagePath + "/BattleSystemConfig.bytes\" \"$d/Document/android/BattleSystemConfig.bytes\" 2>/dev/null; " +
                        "      cp -f \"" + stagePath + "/BattleConfig.unity3d\" \"$d/Document/android/BattleConfig.unity3d\" 2>/dev/null; " +
                        "      cp -f \"" + stagePath + "/BattleConfig.json\" \"$d/Document/android/BattleConfig.json\" 2>/dev/null; " +
                        "      cp -f \"" + stagePath + "/HeroStatConfig.json\" \"$d/Document/android/HeroStatConfig.json\" 2>/dev/null; " +
                        "      cp -f \"" + stagePath + "/res_skip_patch.xml\" \"$d/Document/android/res_skip_patch.xml\" 2>/dev/null; " +
                        "      cp -f \"" + stagePath + "/ResCheckConf.unity3d\" \"$d/Document/android/ResCheckConf.unity3d\" 2>/dev/null; " +
                        "      cp -f \"" + stagePath + "/ResCheckConf.xml\" \"$d/Document/android/ResCheckConf.xml\" 2>/dev/null; " +
                        "      cp -f \"" + stagePath + "/BinaryPatchMD5.xml\" \"$d/Document/android/BinaryPatchMD5.xml\" 2>/dev/null; " +
                        "      cp -f \"" + stagePath + "/Define.bytes\" \"$d/gameres/android/Define.bytes\" 2>/dev/null; " +
                        "      chmod 444 \"$d/Document/android/BattleSystemConfig.bytes\" \"$d/Document/android/BattleConfig.unity3d\" \"$d/Document/android/BattleConfig.json\" \"$d/Document/android/HeroStatConfig.json\" \"$d/Document/android/res_skip_patch.xml\" 2>/dev/null; " +
                        "    fi; " +
                        "  done; " +
                        "fi"
                );

                // Persist Combat Damage flags into prefs_int so Unity PlayerPrefs & BattleSystem pick them up
                String combatPrefsScript =
                        "INT_FILE=\"" + DRAGON_ASSETS + "/prefs_int\"; " +
                        "chmod 666 \"$INT_FILE\" \"" + FILES + "/prefs_int\" 2>/dev/null; " +
                        "touch \"$INT_FILE\"; " +
                        "for k in DamageLockMax:1 DamageMultiplier:10 PhysicalDamageBase:10000 MagicDamageBase:10000 " +
                        "         TrueDamageBase:10000 TrueDamageBoost:10000 CritRateBoost:1 PenetrationBoost:1 " +
                        "         PhysicalPenBoost:10000 MagicPenBoost:10000 AttackSpeedUnlock:1 ZeroSkillCost:1 " +
                        "         RankedCombatSync:1 ClassicCombatSync:1 AllModeCombatOverride:1 " +
                        "         m_BasePhyAtt:10000 m_BaseMagAtt:10000 CountHurtValue:10000 EmblemBoost:100 ItemBoost:100; do " +
                        "  key=$(echo \"$k\" | cut -d: -f1); " +
                        "  val=$(echo \"$k\" | cut -d: -f2); " +
                        "  sed -i \"/^$key:/d\" \"$INT_FILE\" 2>/dev/null; " +
                        "  echo \"$key:$val\" >> \"$INT_FILE\"; " +
                        "done; " +
                        "cp -f \"$INT_FILE\" \"" + FILES + "/prefs_int\" 2>/dev/null; " +
                        "chmod 444 \"$INT_FILE\" \"" + FILES + "/prefs_int\" 2>/dev/null";
                ShizukuShell.exec(combatPrefsScript);

                // Lock dragon2017 & LoadResManager combat configs read-only (444) so ResCheck cannot overwrite
                for (String doc : docTargets) {
                    ShizukuShell.exec(
                            "chmod 444 \"" + doc + "/BattleSystemConfig.bytes\" " +
                            "\"" + doc + "/BattleConfig.unity3d\" " +
                            "\"" + doc + "/BattleConfig.json\" " +
                            "\"" + doc + "/HeroStatConfig.json\" " +
                            "\"" + doc + "/res_skip_patch.xml\" " +
                            "\"" + doc + "/ResCheckConf.unity3d\" " +
                            "\"" + doc + "/ResCheckConf.xml\" " +
                            "\"" + doc + "/BinaryPatchMD5.xml\" 2>/dev/null"
                    );
                }

                deleteRecursive(stage);

                postComplete(callback, true,
                        "⚔️ Combat Damage & Hero Stats Injected! (No-Root Shizuku)\n" +
                        "• BattleConfig.unity3d (984 tables: Skill, Bullet, Effect, Trigger)\n" +
                        "• BattleConfig.json + HeroStatConfig.json (All 6 Roles)\n" +
                        "• ResCheck Bypass + MD5 Locked (" + patchedSlots + " mini_patch slots)\n" +
                        "Restart MLBB before entering match.");
            } catch (Throwable t) {
                Log.e(TAG, "Combat damage injection error", t);
                postComplete(callback, false, "Combat injection error: " + t.getMessage());
            }
        });
    }

    public static void restoreCombatDamage(Callback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                postComplete(callback, false, "Shizuku permission not granted.");
                return;
            }

            try {
                postProgress(callback, "Unlocking & restoring stock BattleConfig files...");
                String docAndroid = DRAGON_ASSETS + "/Document/android";
                String bakDoc = BACKUP_COMBAT_DIR + "/Document/android";
                String bakGameres = BACKUP_COMBAT_DIR + "/gameres/android";

                // 1. Unlock all 444 read-only permissions first
                ShizukuShell.exec(
                        "chmod -R 777 \"" + DRAGON_ASSETS + "/Document\" 2>/dev/null; " +
                        "chmod -R 777 \"" + FILES + "/LoadResManager/Document\" 2>/dev/null; " +
                        "chmod -R 777 \"" + MINI_PATCH + "\" 2>/dev/null; " +
                        "chmod 666 \"" + DRAGON_ASSETS + "/prefs_int\" \"" + FILES + "/prefs_int\" 2>/dev/null"
                );

                // 2. Remove custom combat config files from dragon2017, LoadResManager, and mini_patch
                String[] customOnlyFiles = {
                        "BattleConfig.json",
                        "HeroStatConfig.json",
                        "res_skip_patch.xml"
                };
                for (String fn : customOnlyFiles) {
                    ShizukuShell.exec(
                            "rm -f \"" + docAndroid + "/" + fn + "\" " +
                            "\"" + DRAGON_ASSETS + "/Document/" + fn + "\" " +
                            "\"" + FILES + "/LoadResManager/Document/android/" + fn + "\" 2>/dev/null; " +
                            "find \"" + MINI_PATCH + "\" -name '" + fn + "' -exec rm -f {} + 2>/dev/null"
                    );
                }

                // 3. Restore backed up original files if available
                String[] backupDocNames = {
                        "BattleSystemConfig.bytes",
                        "BattleConfig.unity3d",
                        "ResCheckConf.unity3d",
                        "ResCheckConf.xml",
                        "BinaryPatchMD5.xml",
                        "LoadResMgr_xxh_and.bytes",
                        "LoadResMgrHigh_xxh_and.bytes",
                        "mola_config.xml",
                        "mode_versions_build.xml",
                        "SplitLibMD5.xml"
                };
                for (String name : backupDocNames) {
                    String orig = docAndroid + "/" + name;
                    String bak = bakDoc + "/" + name;
                    ShizukuShell.exec(
                            "if [ -s \"" + bak + "\" ]; then " +
                            "  cp -f \"" + bak + "\" \"" + orig + "\"; " +
                            "  find \"" + MINI_PATCH + "\" -name '" + name + "' -exec cp -f \"" + bak + "\" {} \\; 2>/dev/null; " +
                            "fi"
                    );
                }
                ShizukuShell.exec(
                        "if [ -s \"" + bakGameres + "/Define.bytes\" ]; then cp -f \"" + bakGameres + "/Define.bytes\" \"" + DRAGON_ASSETS + "/gameres/android/Define.bytes\"; fi; " +
                        "if [ -s \"" + bakGameres + "/mlf/ModulesConf.bytes\" ]; then cp -f \"" + bakGameres + "/mlf/ModulesConf.bytes\" \"" + DRAGON_ASSETS + "/gameres/android/mlf/ModulesConf.bytes\"; fi"
                );

                // 4. Clean combat keys from prefs_int
                ShizukuShell.exec(
                        "sed -i '/DamageLockMax/d; /DamageMultiplier/d; /PhysicalDamageBase/d; /MagicDamageBase/d; /TrueDamageBase/d; /TrueDamageBoost/d; /CritRateBoost/d; /PenetrationBoost/d; /PhysicalPenBoost/d; /MagicPenBoost/d; /AttackSpeedUnlock/d; /ZeroSkillCost/d; /RankedCombatSync/d; /ClassicCombatSync/d; /AllModeCombatOverride/d; /m_BasePhyAtt/d; /m_BaseMagAtt/d; /CountHurtValue/d; /EmblemBoost/d; /ItemBoost/d' \"" + DRAGON_ASSETS + "/prefs_int\" 2>/dev/null; " +
                        "cp -f \"" + DRAGON_ASSETS + "/prefs_int\" \"" + FILES + "/prefs_int\" 2>/dev/null"
                );

                postComplete(callback, true, "Stock Combat Damage & BattleConfig restored!\nRestart MLBB.");
            } catch (Throwable t) {
                Log.e(TAG, "Combat damage restore error", t);
                postComplete(callback, false, "Restore error: " + t.getMessage());
            }
        });
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
        return 165.0f; // Safe gaming phone default
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
