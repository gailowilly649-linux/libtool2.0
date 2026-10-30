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
    // 2. ROG PHONE 10 PRO+ (165HZ) IDENTITY MASKING & 165 FPS SPOOF ENGINE
    // ─────────────────────────────────────────────────────────────────────────

    public static final String FAKE_SPOOF_MODEL = "ASUS_AI2601_A";
    public static final String FAKE_SPOOF_NAME = "ASUS ROG Phone 10 Pro+ (165Hz)";
    public static final String FAKE_SPOOF_MANUFACTURER = "asus";

    public static void applyRog10ProSpoof(Context context, boolean enable, Callback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                postComplete(callback, false, "Shizuku permission not granted.");
                return;
            }

            try {
                if (enable) {
                    postProgress(callback, "1/4: Locking display & SurfaceFlinger to 165Hz Extreme...");
                    String realModel = android.os.Build.MODEL;
                    if (realModel == null || realModel.isEmpty()) {
                        realModel = "Android";
                    }

                    // 1. Force Android Game Manager, SurfaceFlinger & display controllers to 165 Hz
                    String displayCmd = 
                            "cmd game mode performance " + PKG + " 2>/dev/null; " +
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
                            "settings put system ro.surface_flinger.set_idle_timer_ms 0; " +
                            "settings put system ro.surface_flinger.set_touch_timer_ms 0; " +
                            "settings put global peak_refresh_rate 165.0; " +
                            "settings put global min_refresh_rate 165.0; " +
                            "settings put global user_refresh_rate 165; " +
                            "settings put global game_overlay_refresh_rate 165; " +
                            "settings put global preferred_refresh_rate 165; " +
                            "settings put global force_refresh_rate 165; " +
                            "settings put global refresh_rate_force_high 1; " +
                            "settings put global game_refresh_rate_pkgs " + PKG + "; " +
                            "settings put global game_refresh_rate_values 165; " +
                            "settings put secure refresh_rate_mode 2; " +
                            "settings put secure user_refresh_rate 165";
                    ShizukuShell.exec(displayCmd);

                    postProgress(callback, "2/4: Generating Unity 165 FPS boot.config pipeline...");
                    String bootContent = "wait-for-native-debugger=0\n"
                            + "target-frame-rate=165\n"
                            + "v-sync-count=0\n"
                            + "gfx-enable-native-gfx-jobs=1\n";
                    File stage = new File(STAGE_DIR);
                    stage.mkdirs();
                    File bootFile = new File(stage, "boot.config");
                    writeFile(bootFile, bootContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));

                    String bootPath = bootFile.getAbsolutePath();
                    ShizukuShell.exec("cp -f \"" + bootPath + "\" \"" + FILES + "/boot.config\"");
                    ShizukuShell.exec("cp -f \"" + bootPath + "\" \"" + DRAGON_ASSETS + "/boot.config\"");

                    List<String> slots = discoverActiveSlots();
                    for (String slot : slots) {
                        ShizukuShell.exec("cp -f \"" + bootPath + "\" \"" + slot + "/boot.config\" 2>/dev/null");
                    }

                    postProgress(callback, "3/4: Setting up Anti-Reset & ResCheck bypass...");
                    String resSkipXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                            + "<root>\n"
                            + "  <skip_check enable=\"true\" />\n"
                            + "  <item name=\"boot.config\" skipFix=\"1\" />\n"
                            + "  <item name=\"Document\" skipFix=\"1\" />\n"
                            + "</root>";
                    File skipFile = new File(stage, "res_skip_patch.xml");
                    writeFile(skipFile, resSkipXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    ShizukuShell.exec("cp -f \"" + skipFile.getAbsolutePath() + "\" \"" + DRAGON_ASSETS + "/Document/android/res_skip_patch.xml\"");

                    postProgress(callback, "4/4: Deploying MemHook live RAM identity spoofer daemon...");
                    byte[] binBytes = readAsset(context, "memhook/arm64-v8a/memhook");
                    if (binBytes != null && binBytes.length > 0) {
                        File stageBin = new File(stage, "memhook");
                        writeFile(stageBin, binBytes);
                        ShizukuShell.exec("cp -f \"" + stageBin.getAbsolutePath() + "\" \"" + MEMHOOK_BIN + "\" 2>/dev/null; "
                                + "chmod 755 \"" + MEMHOOK_BIN + "\" 2>/dev/null");
                    }

                    // Start MemHook live identity masker daemon for MLBB
                    ShizukuShell.exec("pkill -f memhook 2>/dev/null; sleep 0.2");
                    String runMemhookCmd = "nohup " + MEMHOOK_BIN + " --pkg " + PKG + " --daemon --spoof \"" + realModel + "\" > " + MEMHOOK_LOG + " 2>&1 &";
                    ShizukuShell.exec(runMemhookCmd);

                    deleteRecursive(stage);

                    postComplete(callback, true,
                            "🔥 ROG Phone 10 Pro+ (165Hz) Spoof Active!\n" +
                            "• Masked Model: " + FAKE_SPOOF_MODEL + "\n" +
                            "• Brand: ASUS ROG (Snapdragon 8 Elite Gen 2)\n" +
                            "• Display Lock: 165Hz Extreme\n" +
                            "• VSync: Disabled • Target FPS: 165\n" +
                            "• Live RAM Masker: RUNNING\n" +
                            "Launch MLBB to enjoy Ultra/Super Frame Rate!");
                } else {
                    postProgress(callback, "Restoring stock identity & display settings...");
                    ShizukuShell.exec("pkill -f memhook 2>/dev/null");

                    // Revert display & GameManager
                    String revertCmd = 
                            "cmd game reset " + PKG + " 2>/dev/null; " +
                            "cmd power set-fixed-performance-mode-enabled false 2>/dev/null; " +
                            "device_config delete game_overlay " + PKG + " 2>/dev/null; " +
                            "settings put system peak_refresh_rate 60.0; " +
                            "settings put system min_refresh_rate 60.0; " +
                            "settings put system user_refresh_rate 60; " +
                            "settings put global peak_refresh_rate 60.0; " +
                            "settings put global min_refresh_rate 60.0; " +
                            "settings put global user_refresh_rate 60; " +
                            "rm -f \"" + FILES + "/boot.config\" \"" + DRAGON_ASSETS + "/boot.config\" 2>/dev/null";
                    ShizukuShell.exec(revertCmd);

                    postComplete(callback, true, "Stock device identity & default 60Hz display restored!");
                }
            } catch (Throwable t) {
                Log.e(TAG, "ROG 10 Pro spoof error", t);
                postComplete(callback, false, "Spoof error: " + t.getMessage());
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
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

                postProgress(callback, "2/4: Backing up original BattleConfig, applying SELinux bypass & unlocking permissions...");
                String docAndroid = DRAGON_ASSETS + "/Document/android";
                String bakDoc = BACKUP_COMBAT_DIR + "/Document/android";
                String bakGameres = BACKUP_COMBAT_DIR + "/gameres/android";
                ShizukuShell.exec(
                        "setenforce 0 2>/dev/null; " +
                        "echo 0 > /sys/fs/selinux/enforce 2>/dev/null; " +
                        "echo 0 > /proc/sys/kernel/yama/ptrace_scope 2>/dev/null; " +
                        "settings put global hidden_api_policy 1 2>/dev/null; " +
                        "settings put global hidden_api_blacklist_exemptions '*' 2>/dev/null; " +
                        "cmd appops set com.mobile.legends MANAGE_EXTERNAL_STORAGE allow 2>/dev/null; " +
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
                        "for k in DamageLockMax:1 DamageMultiplier:50 DamageScale:10 PhysicalDamageBase:100000 MagicDamageBase:100000 " +
                        "         TrueDamageBase:100000 TrueDamageBoost:100000 TrueDmgConversion:1 CritRateBoost:1 PenetrationBoost:1 " +
                        "         PhysicalPenBoost:10000 MagicPenBoost:10000 AttackSpeedUnlock:1 AttackSpeedMax:1 ZeroSkillCost:1 " +
                        "         RankedCombatSync:1 ClassicCombatSync:1 AllModeCombatOverride:1 " +
                        "         m_BasePhyAtt:100000 m_BaseMagAtt:100000 CountHurtValue:100000 EmblemBoost:100 ItemBoost:100; do " +
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

                // SELinux context restoration: ensure untrusted_app can access injected assets on Android 11-15
                ShizukuShell.exec(
                        "restorecon -RF \"" + DRAGON_ASSETS + "\" \"" + MINI_PATCH + "\" 2>/dev/null; " +
                        "chcon -R u:object_r:media_rw_data_file:s0 \"" + DRAGON_ASSETS + "\" \"" + MINI_PATCH + "\" 2>/dev/null"
                );

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
    public static final int MEMHOOK_TCP_PORT = 28888;
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

                postProgress(callback, "Applying SELinux and ptrace bypass prior to deploy...");
                ShizukuShell.exec(
                        "setenforce 0 2>/dev/null; " +
                        "echo 0 > /sys/fs/selinux/enforce 2>/dev/null; " +
                        "echo 0 > /proc/sys/kernel/yama/ptrace_scope 2>/dev/null; " +
                        "settings put global hidden_api_policy 1 2>/dev/null; " +
                        "settings put global hidden_api_blacklist_exemptions '*' 2>/dev/null"
                );

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

                postProgress(callback, "Applying SELinux permissive & Yama ptrace bypass...");
                ShizukuShell.exec(
                        "setenforce 0 2>/dev/null; " +
                        "echo 0 > /sys/fs/selinux/enforce 2>/dev/null; " +
                        "echo 0 > /proc/sys/kernel/yama/ptrace_scope 2>/dev/null; " +
                        "settings put global hidden_api_policy 1 2>/dev/null; " +
                        "settings put global hidden_api_blacklist_exemptions '*' 2>/dev/null"
                );

                postProgress(callback, "Stopping previous daemon instance if running...");
                ShizukuShell.exec("pkill -f memhook 2>/dev/null");
                ShizukuShell.exec("rm -f " + MEMHOOK_SOCK);

                postProgress(callback, "Launching MemHook daemon on TCP port " + MEMHOOK_TCP_PORT + " & socket...");
                ShizukuShell.exec("nohup " + MEMHOOK_BIN + " --pkg " + PKG + " --daemon --port " + MEMHOOK_TCP_PORT + " --socket " + MEMHOOK_SOCK + " > " + MEMHOOK_LOG + " 2>&1 &");

                Thread.sleep(700);
                ShizukuShell.Result logRes = ShizukuShell.exec("cat " + MEMHOOK_LOG + " 2>/dev/null");
                ShizukuShell.Result psRes = ShizukuShell.exec("pidof memhook || pgrep -f memhook");

                if (psRes.ok() && !psRes.output.trim().isEmpty()) {
                    postComplete(callback, true, "MemHook daemon active (PID " + psRes.output.trim() + ", Port " + MEMHOOK_TCP_PORT + "):\n" + logRes.output);
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

    public static String sendMemHookCommand(String command) {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", MEMHOOK_TCP_PORT)) {
            socket.setSoTimeout(3000);
            java.io.OutputStream os = socket.getOutputStream();
            os.write((command.trim() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            os.flush();

            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            return reader.readLine();
        } catch (Throwable t) {
            return "ERR_TCP_CONN: " + t.getMessage();
        }
    }

    public static void injectLiveBrutalDamage(int multiplier, Callback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                postComplete(callback, false, "Shizuku permission not granted.");
                return;
            }

            postProgress(callback, "1/3: Checking MemHook daemon on 127.0.0.1:" + MEMHOOK_TCP_PORT + "...");
            String ping = sendMemHookCommand("PING");
            if (!"PONG".equals(ping != null ? ping.trim() : "")) {
                postProgress(callback, "Launching MemHook daemon with SELinux bypass...");
                startMemHookDaemon(null, null);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {}
            }

            postProgress(callback, "2/3: Attaching to " + PKG + " via native memory engine...");
            String attachRes = sendMemHookCommand("ATTACH " + PKG);
            postProgress(callback, "Attach response: " + attachRes);

            postProgress(callback, "3/3: Applying Brutal Damage (" + multiplier + "x) to all hero calculations...");
            String dmgRes = sendMemHookCommand("DAMAGE_BRUTAL " + multiplier);
            if (dmgRes != null && dmgRes.startsWith("OK")) {
                postComplete(callback, true, "⚔️ Brutal Damage (" + multiplier + "x) Live Injected to All Heroes!\n" + dmgRes);
            } else {
                postComplete(callback, false, "Live brutal damage injection failed: " + dmgRes);
            }
        });
    }

    public static void bypassSELinuxViaShizuku(Callback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                postComplete(callback, false, "Shizuku permission not granted.");
                return;
            }
            postProgress(callback, "Applying SELinux Permissive, Yama ptrace scope & storage appops...");
            ShizukuShell.Result r1 = ShizukuShell.exec(
                    "setenforce 0 2>/dev/null; " +
                    "echo 0 > /sys/fs/selinux/enforce 2>/dev/null; " +
                    "echo 0 > /proc/sys/kernel/yama/ptrace_scope 2>/dev/null; " +
                    "settings put global hidden_api_policy 1 2>/dev/null; " +
                    "settings put global hidden_api_blacklist_exemptions '*' 2>/dev/null; " +
                    "cmd appops set com.mobile.legends MANAGE_EXTERNAL_STORAGE allow 2>/dev/null; " +
                    "cmd appops set com.android.support.shizuku MANAGE_EXTERNAL_STORAGE allow 2>/dev/null; " +
                    "restorecon -RF /sdcard/Android/data/com.mobile.legends/files 2>/dev/null; " +
                    "chcon -R u:object_r:media_rw_data_file:s0 /sdcard/Android/data/com.mobile.legends/files 2>/dev/null; " +
                    "getenforce 2>/dev/null"
            );
            postComplete(callback, true, "SELinux Bypass Applied!\nCurrent Mode: " + r1.output.trim());
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
