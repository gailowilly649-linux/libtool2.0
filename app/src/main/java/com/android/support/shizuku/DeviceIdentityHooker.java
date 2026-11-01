package com.android.support.shizuku;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Universal In-Process Device Identity & Hardware Spoofer
 * Implements non-root ART reflection hooking, Hidden API exemption traversal,
 * and LSPosed/LSPatch-compatible hooking patterns for Android 7 through 16.
 */
public final class DeviceIdentityHooker {

    private static final String TAG = "DeviceIdentityHooker";

    private static volatile boolean sIsSpoofed = false;
    private static String sOriginalModel = Build.MODEL;
    private static String sOriginalManufacturer = Build.MANUFACTURER;
    private static String sOriginalBrand = Build.BRAND;
    private static String sOriginalDevice = Build.DEVICE;
    private static String sOriginalProduct = Build.PRODUCT;
    private static String sOriginalHardware = Build.HARDWARE;
    private static String sOriginalFingerprint = Build.FINGERPRINT;

    private DeviceIdentityHooker() {}

    public static boolean isSpoofed() {
        return sIsSpoofed;
    }

    /**
     * Executes In-Process Java ART / Reflection Hardware Masking.
     * Overrides android.os.Build fields and runtime identifiers.
     */
    public static synchronized boolean applyInProcessSpoof(
            String model,
            String manufacturer,
            String brand,
            String device,
            String product,
            String hardware,
            String board,
            String fingerprint) {

        try {
            Log.d(TAG, "Starting In-Process Reflection Spoof to: " + manufacturer + " " + model);

            // 1. Bypass Android Hidden API restrictions via reflection (Exempt all)
            exemptHiddenApis();

            // 2. Mutate android.os.Build fields
            setFinalStaticField(Build.class, "MODEL", model);
            setFinalStaticField(Build.class, "MANUFACTURER", manufacturer);
            setFinalStaticField(Build.class, "BRAND", brand);
            setFinalStaticField(Build.class, "DEVICE", device);
            setFinalStaticField(Build.class, "PRODUCT", product);
            setFinalStaticField(Build.class, "HARDWARE", hardware);
            setFinalStaticField(Build.class, "BOARD", board);
            setFinalStaticField(Build.class, "FINGERPRINT", fingerprint);

            // Android 12+ (API 31) SOC_MODEL
            try {
                setFinalStaticField(Build.class, "SOC_MODEL", "SM8650-AB");
                setFinalStaticField(Build.class, "SOC_MANUFACTURER", "Qualcomm");
            } catch (Throwable ignored) {}

            // Android 8+ (API 26) SERIAL reflection
            try {
                setFinalStaticField(Build.class, "SERIAL", "UNKNOWN");
            } catch (Throwable ignored) {}

            // 3. Override System Properties in current process
            setSystemProperty("ro.product.model", model);
            setSystemProperty("ro.product.manufacturer", manufacturer);
            setSystemProperty("ro.product.brand", brand);
            setSystemProperty("ro.product.device", device);
            setSystemProperty("ro.product.name", product);
            setSystemProperty("ro.hardware", hardware);
            setSystemProperty("ro.build.fingerprint", fingerprint);

            sIsSpoofed = true;
            Log.d(TAG, "In-Process Spoof Applied! Build.MODEL is now: " + Build.MODEL);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "In-Process Spoof failed", t);
            return false;
        }
    }

    /**
     * Reverts in-process Build fields to original system values.
     */
    public static synchronized void revertInProcessSpoof() {
        if (!sIsSpoofed) return;
        try {
            setFinalStaticField(Build.class, "MODEL", sOriginalModel);
            setFinalStaticField(Build.class, "MANUFACTURER", sOriginalManufacturer);
            setFinalStaticField(Build.class, "BRAND", sOriginalBrand);
            setFinalStaticField(Build.class, "DEVICE", sOriginalDevice);
            setFinalStaticField(Build.class, "PRODUCT", sOriginalProduct);
            setFinalStaticField(Build.class, "HARDWARE", sOriginalHardware);
            setFinalStaticField(Build.class, "FINGERPRINT", sOriginalFingerprint);
            sIsSpoofed = false;
            Log.d(TAG, "In-Process Spoof Reverted. Build.MODEL restored to: " + Build.MODEL);
        } catch (Throwable t) {
            Log.e(TAG, "Revert failed", t);
        }
    }

    /**
     * Unlocks hidden API blacklist restrictions using Double Reflection.
     */
    private static void exemptHiddenApis() {
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);

            Class<?> vmRuntimeClass = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null);
            Method setHiddenApiExemptions = (Method) getDeclaredMethod.invoke(vmRuntimeClass, "setHiddenApiExemptions", new Class[]{String[].class});

            Object vmRuntime = getRuntime.invoke(null);
            setHiddenApiExemptions.invoke(vmRuntime, (Object) new String[]{"L"});
            Log.d(TAG, "Hidden API exemptions granted via VMRuntime double-reflection.");
        } catch (Throwable t) {
            Log.w(TAG, "VMRuntime hidden API exemption error (will rely on Shizuku shell): " + t.getMessage());
        }
    }

    /**
     * Modifies a final static field using Unsafe or Field modifier stripping.
     */
    private static void setFinalStaticField(Class<?> clazz, String fieldName, Object newValue) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);

        // Attempt direct set
        try {
            field.set(null, newValue);
            return;
        } catch (IllegalAccessException ignored) {}

        // Strip final modifier if direct write fails
        try {
            Field modifiersField = Field.class.getDeclaredField("accessFlags");
            modifiersField.setAccessible(true);
            modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
            field.set(null, newValue);
        } catch (Throwable t) {
            // Unsafe fallback via dynamic reflection
            try {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                Object unsafe = unsafeField.get(null);
                Method staticFieldBase = unsafeClass.getMethod("staticFieldBase", Field.class);
                Method staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
                Method putObject = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);

                Object base = staticFieldBase.invoke(unsafe, field);
                long offset = ((Number) staticFieldOffset.invoke(unsafe, field)).longValue();
                putObject.invoke(unsafe, base, offset, newValue);
            } catch (Throwable t2) {
                Log.w(TAG, "Could not override field: " + fieldName + " (" + t2.getMessage() + ")");
            }
        }
    }

    /**
     * Injects system property into the runtime via android.os.SystemProperties reflection.
     */
    private static void setSystemProperty(String key, String val) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method setMethod = sp.getMethod("set", String.class, String.class);
            setMethod.invoke(null, key, val);
        } catch (Throwable ignored) {
            System.setProperty(key, val);
        }
    }
}
