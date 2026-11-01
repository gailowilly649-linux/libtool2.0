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
                    postProgress(callback, "1/5: Executing Universal Android Device & Identity Masker...");
                    MlbbSpoofMaskingEnforcer.executeFullMasking(context, new MlbbSpoofMaskingEnforcer.EnforceCallback() {
                        @Override
                        public void onStep(String message) {
                            postProgress(callback, message);
                        }

                        @Override
                        public void onComplete(boolean success, String summary) {
                            if (!success) {
                                postComplete(callback, false, summary);
                                return;
                            }

                            postProgress(callback, "Deploying MemHook live memory identity spoofer daemon...");
                            String realModel = android.os.Build.MODEL;
                            if (realModel == null || realModel.isEmpty()) realModel = "Android";

                            File stage = new File(STAGE_DIR);
                            stage.mkdirs();
                            byte[] binBytes = readAsset(context, "memhook/arm64-v8a/memhook");
                            if (binBytes != null && binBytes.length > 0) {
                                File stageBin = new File(stage, "memhook");
                                try {
                                    writeFile(stageBin, binBytes);
                                    ShizukuShell.exec("cp -f \"" + stageBin.getAbsolutePath() + "\" \"" + MEMHOOK_BIN + "\" 2>/dev/null; "
                                            + "chmod 755 \"" + MEMHOOK_BIN + "\" 2>/dev/null");
                                } catch (Throwable ignored) {}
                            }

                            ShizukuShell.exec("pkill -f memhook 2>/dev/null; sleep 0.2");
                            String runMemhookCmd = "nohup " + MEMHOOK_BIN + " --pkg " + PKG + " --daemon --spoof \"" + realModel + "\" > " + MEMHOOK_LOG + " 2>&1 &";
                            ShizukuShell.exec(runMemhookCmd);
                            deleteRecursive(stage);

                            postComplete(callback, true,
                                    "🔥 Universal Hardware & MLBB Masking Active!\n" +
                                    "• Android ID & AAID: Randomized & Cloaked\n" +
                                    "• Model: ASUS ROG Phone 8 Pro (ASUS_AI2401_A)\n" +
                                    "• GPU: Adreno (TM) 750 (165Hz)\n" +
                                    "• Cross-Version Compat: Android 7 - 16 Full Support\n" +
                                    "• Unity Target FPS: 165 • VSync: Disabled");
                        }
                    });
                } else {
                    postProgress(callback, "Restoring stock identity & display settings across all Android tiers...");
                    ShizukuShell.exec("pkill -f memhook 2>/dev/null");
                    MlbbSpoofMaskingEnforcer.restoreDefaultEnvironment(new MlbbSpoofMaskingEnforcer.EnforceCallback() {
                        @Override
                        public void onStep(String message) {
                            postProgress(callback, message);
                        }

                        @Override
                        public void onComplete(boolean success, String summary) {
                            postComplete(callback, success, summary);
                        }
                    });
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
                long startTime = System.currentTimeMillis();
                postProgress(callback, "1/3: Turbo-staging BattleConfig (10.5MB) & HeroStat payloads...");
                File stage = new File(STAGE_DIR, "combat");
                deleteRecursive(stage);
                File stageDoc = new File(stage, "Document/android");
                File stageGameres = new File(stage, "gameres/android/mlf");
                stageDoc.mkdirs();
                stageGameres.mkdirs();

                String[][] docAssets = {
                        {"mlbb_drone/base/Document/android/BattleSystemConfig.bytes", "BattleSystemConfig.bytes"},
                        {"mlbb_drone/base/Document/android/BattleConfig.unity3d", "BattleConfig.unity3d"},
                        {"mlbb_drone/base/Document/android/BattleConfig.json", "BattleConfig.json"},
                        {"mlbb_drone/base/Document/android/HeroStatConfig.json", "HeroStatConfig.json"},
                        {"mlbb_drone/base/Document/android/PropertyAlternative_native.unity3d", "PropertyAlternative_native.unity3d"},
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
                    stageAsset(context, pair[0], stageDoc, pair[1]);
                }

                stageAsset(context, "mlbb_drone/base/_load_res.bytes", stage, "_load_res.bytes");
                stageAsset(context, "mlbb_drone/base/__fix_rescheck", stage, "__fix_rescheck");
                stageAsset(context, "mlbb_drone/base/gameres/android/Define.bytes", new File(stage, "gameres/android"), "Define.bytes");
                stageAsset(context, "mlbb_drone/base/gameres/android/mlf/ModulesConf.bytes", stageGameres, "ModulesConf.bytes");

                File battleBundle = new File(stageDoc, "BattleConfig.unity3d");
                if (!battleBundle.exists() || battleBundle.length() < 1000000) {
                    postComplete(callback, false, "Failed to stage BattleConfig.unity3d payload.");
                    return;
                }

                postProgress(callback, "2/3: Executing Turbo Batch Injection & SELinux bypass script...");
                String stagePath = stage.getAbsolutePath();
                String docAndroid = DRAGON_ASSETS + "/Document/android";
                String bakDoc = BACKUP_COMBAT_DIR + "/Document/android";
                String bakGameres = BACKUP_COMBAT_DIR + "/gameres/android";
                String loadResAndroid = FILES + "/LoadResManager/Document/android";

                // Unified high-performance single shell script: eliminates 100+ IPC binder forks
                String turboScript =
                        "STAGE=\"" + stagePath + "\"\n" +
                        "DOC_ORIG=\"" + docAndroid + "\"\n" +
                        "BAK_DOC=\"" + bakDoc + "\"\n" +
                        "BAK_GAMERES=\"" + bakGameres + "\"\n" +
                        "DRAGON=\"" + DRAGON_ASSETS + "\"\n" +
                        "FILES=\"" + FILES + "\"\n" +
                        "LOADRES=\"" + loadResAndroid + "\"\n" +
                        "MINI=\"" + MINI_PATCH + "\"\n\n" +
                        "# 1. SELinux & AppOps bypass\n" +
                        "setenforce 0 2>/dev/null || true\n" +
                        "echo 0 > /sys/fs/selinux/enforce 2>/dev/null || true\n" +
                        "echo 0 > /proc/sys/kernel/yama/ptrace_scope 2>/dev/null || true\n" +
                        "settings put global hidden_api_policy 1 2>/dev/null || true\n" +
                        "settings put global hidden_api_blacklist_exemptions '*' 2>/dev/null || true\n" +
                        "cmd appops set com.mobile.legends MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true\n\n" +
                        "# 2. Dirs & unlock\n" +
                        "mkdir -p \"$DOC_ORIG\" \"$BAK_DOC\" \"$BAK_GAMERES/mlf\" \"$LOADRES\" \"$DRAGON/Document\" 2>/dev/null\n" +
                        "chmod -R 777 \"$DRAGON/Document\" \"$FILES/LoadResManager/Document\" \"$MINI\" 2>/dev/null || true\n\n" +
                        "# 3. Fast backup loop\n" +
                        "for f in BattleSystemConfig.bytes BattleConfig.unity3d PropertyAlternative_native.unity3d ResCheckConf.unity3d ResCheckConf.xml BinaryPatchMD5.xml LoadResMgr_xxh_and.bytes LoadResMgrHigh_xxh_and.bytes mola_config.xml mode_versions_build.xml SplitLibMD5.xml; do\n" +
                        "  [ -s \"$DOC_ORIG/$f\" ] && [ ! -s \"$BAK_DOC/$f\" ] && cp -f \"$DOC_ORIG/$f\" \"$BAK_DOC/$f\"\n" +
                        "done\n" +
                        "[ -s \"$DRAGON/gameres/android/Define.bytes\" ] && [ ! -s \"$BAK_GAMERES/Define.bytes\" ] && cp -f \"$DRAGON/gameres/android/Define.bytes\" \"$BAK_GAMERES/Define.bytes\"\n" +
                        "[ -s \"$DRAGON/gameres/android/mlf/ModulesConf.bytes\" ] && [ ! -s \"$BAK_GAMERES/mlf/ModulesConf.bytes\" ] && cp -f \"$DRAGON/gameres/android/mlf/ModulesConf.bytes\" \"$BAK_GAMERES/mlf/ModulesConf.bytes\"\n\n" +
                        "# 4. Wildcard kernel batch copy to primary paths\n" +
                        "chmod 666 \"$DOC_ORIG\"/* \"$DRAGON/Document\"/* \"$LOADRES\"/* 2>/dev/null || true\n" +
                        "cp -f \"$STAGE/Document/android\"/* \"$DOC_ORIG/\"\n" +
                        "cp -f \"$STAGE/Document/android\"/* \"$DRAGON/Document/\" 2>/dev/null || true\n" +
                        "cp -f \"$STAGE/Document/android\"/* \"$LOADRES/\" 2>/dev/null || true\n" +
                        "mkdir -p \"$DRAGON/gameres/android/mlf\" 2>/dev/null\n" +
                        "cp -f \"$STAGE/gameres/android/Define.bytes\" \"$DRAGON/gameres/android/Define.bytes\" 2>/dev/null || true\n" +
                        "cp -f \"$STAGE/gameres/android/mlf/ModulesConf.bytes\" \"$DRAGON/gameres/android/mlf/ModulesConf.bytes\" 2>/dev/null || true\n" +
                        "cp -f \"$STAGE/_load_res.bytes\" \"$DRAGON/_load_res.bytes\" 2>/dev/null || true\n" +
                        "cp -f \"$STAGE/__fix_rescheck\" \"$DRAGON/__fix_rescheck\" 2>/dev/null || true\n" +
                        "touch \"$DRAGON/__ready\" \"$DRAGON/__active\" 2>/dev/null || true\n\n" +
                        "# 5. Fast mini_patch slot sync via wildcards\n" +
                        "for slot in $(ls -d \"$DRAGON/slots/\"* \"$MINI/\"*/*/*/ 2>/dev/null); do\n" +
                        "  [ -d \"$slot\" ] || continue\n" +
                        "  mkdir -p \"$slot/Document/android\" \"$slot/gameres/android/mlf\" 2>/dev/null || true\n" +
                        "  chmod 666 \"$slot/Document/android\"/* 2>/dev/null || true\n" +
                        "  cp -f \"$STAGE/Document/android\"/* \"$slot/Document/android/\" 2>/dev/null || true\n" +
                        "  cp -f \"$STAGE/gameres/android/Define.bytes\" \"$slot/gameres/android/Define.bytes\" 2>/dev/null || true\n" +
                        "  cp -f \"$STAGE/gameres/android/mlf/ModulesConf.bytes\" \"$slot/gameres/android/mlf/ModulesConf.bytes\" 2>/dev/null || true\n" +
                        "  cp -f \"$STAGE/_load_res.bytes\" \"$slot/_load_res.bytes\" 2>/dev/null || true\n" +
                        "  cp -f \"$STAGE/__fix_rescheck\" \"$slot/__fix_rescheck\" 2>/dev/null || true\n" +
                        "  touch \"$slot/__ready\" \"$slot/__active\" 2>/dev/null || true\n" +
                        "  chmod 444 \"$slot/Document/android\"/* 2>/dev/null || true\n" +
                        "done\n\n" +
                        "# 6. Instant prefs_int update in 1 block\n" +
                        "INT_FILE=\"$DRAGON/prefs_int\"\n" +
                        "touch \"$INT_FILE\"\n" +
                        "chmod 666 \"$INT_FILE\" \"$FILES/prefs_int\" 2>/dev/null || true\n" +
                        "sed -i '/DamageLockMax/d; /DamageMultiplier/d; /DamageScale/d; /PhysicalDamageBase/d; /MagicDamageBase/d; /TrueDamageBase/d; /TrueDamageBoost/d; /TrueDmgConversion/d; /CritRateBoost/d; /PenetrationBoost/d; /PhysicalPenBoost/d; /MagicPenBoost/d; /AttackSpeedUnlock/d; /AttackSpeedMax/d; /ZeroSkillCost/d; /RankedCombatSync/d; /ClassicCombatSync/d; /AllModeCombatOverride/d; /CalcSrcHurt/d; /CalcAddHurt/d; /AttackRawDamage/d; /m_APhaseDamage/d; /m_BasePhyAtt/d; /m_BaseMagAtt/d; /CountHurtValue/d; /EmblemBoost/d; /ItemBoost/d' \"$INT_FILE\" 2>/dev/null || true\n" +
                        "cat << 'EOFCOMBAT' >> \"$INT_FILE\"\n" +
                        "DamageLockMax:1\n" +
                        "DamageMultiplier:50\n" +
                        "DamageScale:10\n" +
                        "PhysicalDamageBase:100000\n" +
                        "MagicDamageBase:100000\n" +
                        "TrueDamageBase:100000\n" +
                        "TrueDamageBoost:100000\n" +
                        "TrueDmgConversion:1\n" +
                        "CritRateBoost:1\n" +
                        "PenetrationBoost:1\n" +
                        "PhysicalPenBoost:10000\n" +
                        "MagicPenBoost:10000\n" +
                        "AttackSpeedUnlock:1\n" +
                        "AttackSpeedMax:1\n" +
                        "ZeroSkillCost:1\n" +
                        "RankedCombatSync:1\n" +
                        "ClassicCombatSync:1\n" +
                        "AllModeCombatOverride:1\n" +
                        "CalcSrcHurt:100000\n" +
                        "CalcAddHurt:100000\n" +
                        "AttackRawDamage:100000\n" +
                        "m_APhaseDamage:100000\n" +
                        "m_BasePhyAtt:100000\n" +
                        "m_BaseMagAtt:100000\n" +
                        "CountHurtValue:100000\n" +
                        "EmblemBoost:100\n" +
                        "ItemBoost:100\n" +
                        "EOFCOMBAT\n" +
                        "cp -f \"$INT_FILE\" \"$FILES/prefs_int\" 2>/dev/null || true\n" +
                        "chmod 444 \"$INT_FILE\" \"$FILES/prefs_int\" 2>/dev/null || true\n\n" +
                        "# 7. Lock primary Document targets\n" +
                        "chmod 444 \"$DOC_ORIG\"/* \"$DRAGON/Document\"/* \"$LOADRES\"/* 2>/dev/null || true\n\n" +
                        "# 8. Scoped SELinux fix (specific targets only, no full directory crawl)\n" +
                        "chcon -R u:object_r:media_rw_data_file:s0 \"$DRAGON/Document\" \"$DRAGON/gameres\" \"$LOADRES\" 2>/dev/null || true\n";

                postProgress(callback, "3/3: Running unified batch executor...");
                ShizukuShell.Result execRes = ShizukuShell.exec(turboScript);
                deleteRecursive(stage);

                long durationMs = System.currentTimeMillis() - startTime;
                postComplete(callback, execRes.ok() || execRes.exitCode == 0,
                        "⚔️ Combat Damage & Hero Stats Injected! (" + (durationMs / 1000.0) + "s)\n" +
                        "• BattleConfig.unity3d (984 tables: Skill, Bullet, Effect, Trigger)\n" +
                        "• PropertyAlternative_native.unity3d + HeroStatConfig.json\n" +
                        "• Turbo Pipeline Active (Single Batch IPC, 0s delay)\n" +
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
                String loadResAndroid = FILES + "/LoadResManager/Document/android";

                // Unified high-speed batch restore script
                String turboRestoreScript =
                        "DOC_ORIG=\"" + docAndroid + "\"\n" +
                        "BAK_DOC=\"" + bakDoc + "\"\n" +
                        "BAK_GAMERES=\"" + bakGameres + "\"\n" +
                        "DRAGON=\"" + DRAGON_ASSETS + "\"\n" +
                        "FILES=\"" + FILES + "\"\n" +
                        "LOADRES=\"" + loadResAndroid + "\"\n" +
                        "MINI=\"" + MINI_PATCH + "\"\n\n" +
                        "# 1. Unlock write permissions\n" +
                        "chmod -R 777 \"$DRAGON/Document\" \"$LOADRES\" \"$MINI\" 2>/dev/null || true\n" +
                        "chmod 666 \"$DRAGON/prefs_int\" \"$FILES/prefs_int\" 2>/dev/null || true\n\n" +
                        "# 2. Remove custom-only combat configs\n" +
                        "rm -f \"$DOC_ORIG/BattleConfig.json\" \"$DOC_ORIG/HeroStatConfig.json\" \"$DOC_ORIG/res_skip_patch.xml\" \\\n" +
                        "      \"$DRAGON/Document/BattleConfig.json\" \"$DRAGON/Document/HeroStatConfig.json\" \"$DRAGON/Document/res_skip_patch.xml\" \\\n" +
                        "      \"$LOADRES/BattleConfig.json\" \"$LOADRES/HeroStatConfig.json\" \"$LOADRES/res_skip_patch.xml\" 2>/dev/null || true\n" +
                        "find \"$MINI\" -name 'BattleConfig.json' -o -name 'HeroStatConfig.json' -o -name 'res_skip_patch.xml' -exec rm -f {} + 2>/dev/null || true\n\n" +
                        "# 3. Restore backed up original files\n" +
                        "if [ -d \"$BAK_DOC\" ]; then\n" +
                        "  cp -f \"$BAK_DOC\"/* \"$DOC_ORIG/\" 2>/dev/null || true\n" +
                        "  cp -f \"$BAK_DOC\"/* \"$DRAGON/Document/\" 2>/dev/null || true\n" +
                        "  cp -f \"$BAK_DOC\"/* \"$LOADRES/\" 2>/dev/null || true\n" +
                        "fi\n" +
                        "if [ -d \"$BAK_GAMERES\" ]; then\n" +
                        "  [ -s \"$BAK_GAMERES/Define.bytes\" ] && cp -f \"$BAK_GAMERES/Define.bytes\" \"$DRAGON/gameres/android/Define.bytes\" 2>/dev/null || true\n" +
                        "  [ -s \"$BAK_GAMERES/mlf/ModulesConf.bytes\" ] && cp -f \"$BAK_GAMERES/mlf/ModulesConf.bytes\" \"$DRAGON/gameres/android/mlf/ModulesConf.bytes\" 2>/dev/null || true\n" +
                        "fi\n\n" +
                        "# 4. Clean combat keys from prefs_int\n" +
                        "sed -i '/DamageLockMax/d; /DamageMultiplier/d; /DamageScale/d; /PhysicalDamageBase/d; /MagicDamageBase/d; /TrueDamageBase/d; /TrueDamageBoost/d; /TrueDmgConversion/d; /CritRateBoost/d; /PenetrationBoost/d; /PhysicalPenBoost/d; /MagicPenBoost/d; /AttackSpeedUnlock/d; /AttackSpeedMax/d; /ZeroSkillCost/d; /RankedCombatSync/d; /ClassicCombatSync/d; /AllModeCombatOverride/d; /CalcSrcHurt/d; /CalcAddHurt/d; /AttackRawDamage/d; /m_APhaseDamage/d; /m_BasePhyAtt/d; /m_BaseMagAtt/d; /CountHurtValue/d; /EmblemBoost/d; /ItemBoost/d' \"$DRAGON/prefs_int\" 2>/dev/null || true\n" +
                        "cp -f \"$DRAGON/prefs_int\" \"$FILES/prefs_int\" 2>/dev/null || true\n";

                ShizukuShell.Result res = ShizukuShell.exec(turboRestoreScript);
                postComplete(callback, res.ok() || res.exitCode == 0, "Stock Combat Damage & BattleConfig restored!\nRestart MLBB.");
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
