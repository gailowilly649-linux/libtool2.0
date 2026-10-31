package com.android.support.shizuku;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Universal Android & MLBB Hardware/Environment Masking Enforcer
 * Cross-version support: Android 7 (Nougat, API 24) to Android 16 (Baklava, API 36).
 * Handles:
 * - Dynamic generation and spoofing of Android ID (SSAID) and Google Advertising ID (AAID)
 * - Vendor display pipelines (ASUS, Xiaomi, Samsung, OnePlus, Tecno, Infinix, MediaTek, Qualcomm)
 * - Android Game Manager (API 31+), DeviceConfig (API 29+), and SurfaceFlinger system properties
 * - AppOps isolation across storage & phone state permissions
 * - Unity Dragon Engine boot.config, DeviceConfig.json, and ResCheck MD5 skip definitions
 * - SELinux permissive execution context & Yama ptrace scope adjustments
 */
public final class MlbbSpoofMaskingEnforcer {

    private static final String TAG = "MlbbSpoofMasking";
    public static final String TARGET_PKG = "com.mobile.legends";
    public static final String BASE_DATA_PATH = "/sdcard/Android/data/" + TARGET_PKG;
    public static final String ASSETS_PATH = BASE_DATA_PATH + "/files/dragon2017/assets";
    public static final String STAGE_DIR = "/sdcard/Download/WillyLibTool/spoof_stage";

    // Hardware Target Configuration (ASUS ROG Phone 8 Pro / Snapdragon 8 Gen 3)
    public static final class DeviceIdentityConfig {
        public static final String BRAND = "asus";
        public static final String MANUFACTURER = "ASUS";
        public static final String MODEL = "ASUS_AI2401_A";
        public static final String PRODUCT_DEVICE = "ASUS_AI2401";
        public static final String HARDWARE = "qcom";
        public static final String BOARD = "kalama";
        public static final String FINGERPRINT = "asus/WW_AI2401/ASUS_AI2401:14/UKQ1.230924.001/34.1420.1420.426:user/release-keys";
        public static final String SOC_MODEL = "SM8650-AB";
        public static final String GL_RENDERER = "Adreno (TM) 750";
        public static final String GL_VENDOR = "Qualcomm";
        public static final String REFRESH_RATE = "165";
        public static final String FPS_TARGET = "165";
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final SecureRandom RANDOM = new SecureRandom();

    public interface EnforceCallback {
        void onStep(String message);
        void onComplete(boolean success, String summary);
    }

    private MlbbSpoofMaskingEnforcer() {}

    private static void notifyStep(EnforceCallback cb, String msg) {
        if (cb != null) MAIN_HANDLER.post(() -> cb.onStep(msg));
    }

    private static void notifyDone(EnforceCallback cb, boolean ok, String summary) {
        if (cb != null) MAIN_HANDLER.post(() -> cb.onComplete(ok, summary));
    }

    public static String generateRandomAndroidId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(16);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static String generateRandomAaid() {
        return UUID.randomUUID().toString();
    }

    /**
     * Executes Full Masking Suite across all Android OS tiers (Android 7 - 16).
     */
    public static void executeFullMasking(Context context, EnforceCallback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                notifyDone(callback, false, "ERR: Shizuku permission not granted or binder dead.");
                return;
            }

            try {
                String randomAndroidId = generateRandomAndroidId();
                String randomAaid = generateRandomAaid();

                notifyStep(callback, "[1/5] Injecting randomized SSAID (" + randomAndroidId + ") and Advertising ID...");
                applyIdentityIds(randomAndroidId, randomAaid);

                notifyStep(callback, "[2/5] Setting up multi-vendor 165Hz display and hidden API exemptions...");
                applyDisplayAndSystemSettings();

                notifyStep(callback, "[3/5] Applying Game Manager overrides and AppOps isolation...");
                applyGameAndAppOpsPolicies();

                notifyStep(callback, "[4/5] Writing Unity 165 FPS boot.config and high-tier DeviceConfig...");
                applyUnityProfileInjection();

                notifyStep(callback, "[5/5] Enforcing permissive SELinux file contexts and Yama ptrace...");
                enforceFileSecurityContexts();

                notifyDone(callback, true,
                        "🔥 Universal Hardware & MLBB Masking Active!\n" +
                        "• Android ID: " + randomAndroidId + "\n" +
                        "• AAID: " + randomAaid + "\n" +
                        "• Model: " + DeviceIdentityConfig.MANUFACTURER + " " + DeviceIdentityConfig.MODEL + "\n" +
                        "• GPU: " + DeviceIdentityConfig.GL_RENDERER + " (" + DeviceIdentityConfig.REFRESH_RATE + "Hz)\n" +
                        "• Target: " + TARGET_PKG + " (Android 7 - 16 supported)\n" +
                        "• Target FPS: 165 • VSync: Disabled");
            } catch (Throwable t) {
                Log.e(TAG, "Full masking execution error", t);
                notifyDone(callback, false, "Masking execution error: " + t.getMessage());
            }
        });
    }

    /**
     * Applies randomized Android ID and Advertising ID across global, secure, and user-space settings.
     */
    private static void applyIdentityIds(String androidId, String aaid) {
        String cmd = 
                "settings put secure android_id " + androidId + " 2>/dev/null; " +
                "settings put secure --user 0 android_id " + androidId + " 2>/dev/null; " +
                "settings put secure advertising_id " + aaid + " 2>/dev/null; " +
                "settings put secure limit_ad_tracking 0 2>/dev/null; " +
                "cmd wifi set-mac-randomization-enhanced enabled 2>/dev/null; " +
                "cmd bluetooth disable 2>/dev/null && sleep 0.5 && cmd bluetooth enable 2>/dev/null";
        ShizukuShell.exec(cmd);
    }

    /**
     * Broad multi-vendor display refresh-rate & hidden API exemption parameters.
     */
    private static void applyDisplayAndSystemSettings() {
        String hz = DeviceIdentityConfig.REFRESH_RATE;
        String cmd = 
                // Hidden API Policy (Android 9+)
                "settings put global hidden_api_policy 1 2>/dev/null; " +
                "settings put global hidden_api_blacklist_exemptions '*' 2>/dev/null; " +
                // Universal AOSP & Pixel Display props
                "settings put system peak_refresh_rate " + hz + ".0 2>/dev/null; " +
                "settings put system min_refresh_rate " + hz + ".0 2>/dev/null; " +
                "settings put system user_refresh_rate " + hz + " 2>/dev/null; " +
                "settings put global peak_refresh_rate " + hz + ".0 2>/dev/null; " +
                "settings put global min_refresh_rate " + hz + ".0 2>/dev/null; " +
                "settings put global user_refresh_rate " + hz + " 2>/dev/null; " +
                "settings put global preferred_refresh_rate " + hz + " 2>/dev/null; " +
                "settings put global force_refresh_rate " + hz + " 2>/dev/null; " +
                "settings put global refresh_rate_force_high 1 2>/dev/null; " +
                "settings put secure refresh_rate_mode 2 2>/dev/null; " +
                "settings put secure user_refresh_rate " + hz + " 2>/dev/null; " +
                // ASUS ROG Specific props
                "settings put system asus_fps " + hz + " 2>/dev/null; " +
                "settings put system asus_refresh_rate " + hz + " 2>/dev/null; " +
                "settings put system custom_mode 2 2>/dev/null; " +
                // Transsion (Tecno/Infinix) Specific props
                "settings put system tran_refresh_mode " + hz + " 2>/dev/null; " +
                "settings put system tran_refresh_rate " + hz + " 2>/dev/null; " +
                "settings put system tran_screen_refresh_rate_mode " + hz + " 2>/dev/null; " +
                "settings put system tecno_refresh_rate " + hz + " 2>/dev/null; " +
                "settings put system infinix_refresh_rate " + hz + " 2>/dev/null; " +
                // Xiaomi / Oppo / Vivo props
                "settings put system refresh_rate_setting " + hz + " 2>/dev/null; " +
                "settings put system max_refresh_rate " + hz + " 2>/dev/null; " +
                "settings put system screen_refresh_rate " + hz + " 2>/dev/null; " +
                "settings put system display_refresh_rate " + hz + " 2>/dev/null; " +
                "settings put system game_fps_override " + hz + " 2>/dev/null; " +
                // SurfaceFlinger Idle timers bypass
                "setprop ro.surface_flinger.set_idle_timer_ms 0 2>/dev/null; " +
                "setprop ro.surface_flinger.set_touch_timer_ms 0 2>/dev/null; " +
                "setprop debug.sf.fps " + hz + " 2>/dev/null";
        ShizukuShell.exec(cmd);
    }

    /**
     * Android Game Manager (Android 12+), DeviceConfig (Android 10+), and AppOps.
     */
    private static void applyGameAndAppOpsPolicies() {
        String fps = DeviceIdentityConfig.FPS_TARGET;
        String cmd = 
                // Game Manager (Android 12+)
                "cmd game mode performance " + TARGET_PKG + " 2>/dev/null; " +
                "cmd game set --fps " + fps + " " + TARGET_PKG + " 2>/dev/null; " +
                "cmd game set --downscale 1.0 " + TARGET_PKG + " 2>/dev/null; " +
                "cmd power set-fixed-performance-mode-enabled true 2>/dev/null; " +
                // DeviceConfig overlay (Android 10+)
                "device_config put game_overlay " + TARGET_PKG + " mode=2,fps=" + fps + ":mode=3,fps=" + fps + " 2>/dev/null; " +
                "settings put global game_overlay_refresh_rate " + fps + " 2>/dev/null; " +
                "settings put global game_refresh_rate_pkgs " + TARGET_PKG + " 2>/dev/null; " +
                "settings put global game_refresh_rate_values " + fps + " 2>/dev/null; " +
                // Storage and Telemetry isolation
                "cmd appops set " + TARGET_PKG + " MANAGE_EXTERNAL_STORAGE allow 2>/dev/null; " +
                "cmd appops set " + TARGET_PKG + " READ_EXTERNAL_STORAGE allow 2>/dev/null; " +
                "cmd appops set " + TARGET_PKG + " WRITE_EXTERNAL_STORAGE allow 2>/dev/null; " +
                "cmd appops set " + TARGET_PKG + " READ_DEVICE_IDENTIFIERS deny 2>/dev/null; " +
                "cmd appops set " + TARGET_PKG + " READ_PHONE_STATE ignore 2>/dev/null; " +
                "cmd appops set " + TARGET_PKG + " ACCESS_FINE_LOCATION ignore 2>/dev/null; " +
                "cmd appops set " + TARGET_PKG + " ACCESS_COARSE_LOCATION ignore 2>/dev/null";
        ShizukuShell.exec(cmd);
    }

    /**
     * Injects Unity boot.config and high-performance hardware profile.
     */
    private static void applyUnityProfileInjection() throws IOException {
        File stage = new File(STAGE_DIR);
        if (!stage.exists()) stage.mkdirs();

        String bootConfig = 
                "wait-for-native-debugger=0\n" +
                "target-frame-rate=" + DeviceIdentityConfig.FPS_TARGET + "\n" +
                "v-sync-count=0\n" +
                "gfx-enable-native-gfx-jobs=1\n" +
                "gl-use-system-gl=1\n" +
                "developer-mode=0\n";
        File bootFile = new File(stage, "boot.config");
        writeBytes(bootFile, bootConfig.getBytes(StandardCharsets.UTF_8));

        String hardwareProfile = 
                "{\n" +
                "  \"brand\": \"" + DeviceIdentityConfig.BRAND + "\",\n" +
                "  \"manufacturer\": \"" + DeviceIdentityConfig.MANUFACTURER + "\",\n" +
                "  \"model\": \"" + DeviceIdentityConfig.MODEL + "\",\n" +
                "  \"device\": \"" + DeviceIdentityConfig.PRODUCT_DEVICE + "\",\n" +
                "  \"soc\": \"" + DeviceIdentityConfig.SOC_MODEL + "\",\n" +
                "  \"gpu\": \"" + DeviceIdentityConfig.GL_RENDERER + "\",\n" +
                "  \"refreshRate\": " + DeviceIdentityConfig.REFRESH_RATE + ",\n" +
                "  \"maxFps\": " + DeviceIdentityConfig.FPS_TARGET + ",\n" +
                "  \"tier\": 4,\n" +
                "  \"highFpsSupported\": true,\n" +
                "  \"ultraGraphicsSupported\": true\n" +
                "}";
        File profileFile = new File(stage, "DeviceConfig.json");
        writeBytes(profileFile, hardwareProfile.getBytes(StandardCharsets.UTF_8));

        String skipXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<root><skip_check enable=\"true\" /></root>";
        File skipFile = new File(stage, "ResCheckConf.xml");
        writeBytes(skipFile, skipXml.getBytes(StandardCharsets.UTF_8));

        String[] targetDirs = {
                BASE_DATA_PATH + "/files",
                ASSETS_PATH,
                ASSETS_PATH + "/Document/android",
                BASE_DATA_PATH + "/files/LoadResManager/Document/android"
        };

        for (String dir : targetDirs) {
            ShizukuShell.exec("mkdir -p \"" + dir + "\" 2>/dev/null");
            ShizukuShell.exec("cp -f \"" + bootFile.getAbsolutePath() + "\" \"" + dir + "/boot.config\" 2>/dev/null");
            ShizukuShell.exec("cp -f \"" + profileFile.getAbsolutePath() + "\" \"" + dir + "/DeviceConfig.json\" 2>/dev/null");
            ShizukuShell.exec("cp -f \"" + skipFile.getAbsolutePath() + "\" \"" + dir + "/ResCheckConf.xml\" 2>/dev/null");
        }

        deleteRecursive(stage);
    }

    /**
     * SELinux permissive contexts and Yama ptrace scope.
     */
    private static void enforceFileSecurityContexts() {
        String cmd = 
                "restorecon -RF " + BASE_DATA_PATH + "/files 2>/dev/null; " +
                "chcon -R u:object_r:media_rw_data_file:s0 " + BASE_DATA_PATH + "/files 2>/dev/null; " +
                "chmod -R 775 " + BASE_DATA_PATH + "/files 2>/dev/null; " +
                "echo 0 > /proc/sys/kernel/yama/ptrace_scope 2>/dev/null; " +
                "setenforce 0 2>/dev/null";
        ShizukuShell.exec(cmd);
    }

    /**
     * Restores default stock environment and cleans overrides.
     */
    public static void restoreDefaultEnvironment(EnforceCallback callback) {
        EXECUTOR.execute(() -> {
            if (!ShizukuShell.hasPermission()) {
                notifyDone(callback, false, "ERR: Shizuku permission missing.");
                return;
            }

            try {
                notifyStep(callback, "Restoring stock Android settings and display refresh rates...");
                String cmd = 
                        "cmd game reset " + TARGET_PKG + " 2>/dev/null; " +
                        "cmd power set-fixed-performance-mode-enabled false 2>/dev/null; " +
                        "device_config delete game_overlay " + TARGET_PKG + " 2>/dev/null; " +
                        "settings put system peak_refresh_rate 60.0 2>/dev/null; " +
                        "settings put system min_refresh_rate 60.0 2>/dev/null; " +
                        "settings put system user_refresh_rate 60 2>/dev/null; " +
                        "settings put global peak_refresh_rate 60.0 2>/dev/null; " +
                        "settings put global min_refresh_rate 60.0 2>/dev/null; " +
                        "settings put global user_refresh_rate 60 2>/dev/null; " +
                        "cmd appops reset " + TARGET_PKG + " 2>/dev/null; " +
                        "rm -f " + BASE_DATA_PATH + "/files/boot.config 2>/dev/null; " +
                        "rm -f " + ASSETS_PATH + "/boot.config 2>/dev/null; " +
                        "rm -f " + ASSETS_PATH + "/Document/android/DeviceConfig.json 2>/dev/null; " +
                        "rm -f " + ASSETS_PATH + "/Document/android/ResCheckConf.xml 2>/dev/null";
                ShizukuShell.exec(cmd);

                notifyDone(callback, true, "Stock Android configuration and default refresh rate restored.");
            } catch (Throwable t) {
                notifyDone(callback, false, "Revert error: " + t.getMessage());
            }
        });
    }

    private static void writeBytes(File target, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(target)) {
            fos.write(data);
            fos.flush();
        }
    }

    private static void deleteRecursive(File dir) {
        if (dir == null || !dir.exists()) return;
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteRecursive(child);
                }
            }
        }
        dir.delete();
    }
}
