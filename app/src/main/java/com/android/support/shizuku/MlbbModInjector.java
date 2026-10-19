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
    // 2. 120 FPS & ULTRA GRAPHICS UNLOCK
    // ─────────────────────────────────────────────────────────────────────────

    public static void unlock120Fps(Context context, boolean unlock, Callback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                postComplete(callback, false, "Shizuku permission not granted.");
                return;
            }

            try {
                if (unlock) {
                    postProgress(callback, "Unlocking 120 FPS display refresh rate...");
                    // Force system refresh rate to 120Hz/144Hz
                    ShizukuShell.exec("settings put system peak_refresh_rate 120.0");
                    ShizukuShell.exec("settings put system min_refresh_rate 120.0");
                    ShizukuShell.exec("settings put system user_refresh_rate 120 2>/dev/null");

                    postProgress(callback, "Writing 120 FPS config to MLBB...");
                    // Build PlayerPrefs XML snippet to enable high FPS mode (3 = 120FPS)
                    String prefsXml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                            + "<map>\n"
                            + "    <int name=\"high_fps_mode\" value=\"3\" />\n"
                            + "    <int name=\"fps_level\" value=\"3\" />\n"
                            + "    <int name=\"resolution_high\" value=\"1\" />\n"
                            + "    <int name=\"outline\" value=\"1\" />\n"
                            + "    <int name=\"shadow\" value=\"1\" />\n"
                            + "    <int name=\"hd_mode\" value=\"1\" />\n"
                            + "    <int name=\"render_scale\" value=\"120\" />\n"
                            + "</map>";

                    File stage = new File(STAGE_DIR);
                    stage.mkdirs();
                    File prefsFile = new File(stage, "120fps_prefs.xml");
                    writeFile(prefsFile, prefsXml.getBytes("UTF-8"));

                    // Copy to shared_prefs if directory exists or accessible
                    String targetPrefs = "/data/data/" + PKG + "/shared_prefs/" + PKG + "_preferences.xml";
                    ShizukuShell.exec("cp -f \"" + prefsFile.getAbsolutePath() + "\" \"" + targetPrefs + "\" 2>/dev/null");
                    ShizukuShell.exec("rm -rf \"" + STAGE_DIR + "\"");

                    postComplete(callback, true, "120 FPS & Ultra Graphics unlocked!\nDisplay locked to 120 Hz.");
                } else {
                    postProgress(callback, "Restoring default FPS...");
                    ShizukuShell.exec("settings put system peak_refresh_rate 60.0");
                    ShizukuShell.exec("settings put system min_refresh_rate 60.0");
                    postComplete(callback, true, "Display refresh rate restored to default.");
                }
            } catch (Throwable t) {
                postComplete(callback, false, "120 FPS error: " + t.getMessage());
            }
        });
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

    private static void writeFile(File file, byte[] data) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
            fos.flush();
        }
        file.setReadable(true, false);
    }
}
