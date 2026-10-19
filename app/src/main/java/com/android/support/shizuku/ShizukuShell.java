package com.android.support.shizuku;

import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Thin wrapper around Shizuku for running shell (UID 2000 / ADB-level) commands.
 * Shell privileges are enough to read/write /sdcard/Android/data/<pkg> on Android 11+,
 * but NOT /data/data/<pkg> or another app's memory.
 */
public final class ShizukuShell {

    public static final int REQUEST_CODE = 4242;

    private ShizukuShell() {
    }

    public static final class Result {
        public final int exitCode;
        public final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public boolean ok() {
            return exitCode == 0;
        }
    }

    public static boolean isRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPermission() {
        if (!isRunning()) return false;
        try {
            if (Shizuku.isPreV11()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean shouldShowRationale() {
        try {
            return Shizuku.shouldShowRequestPermissionRationale();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestPermission() {
        Shizuku.requestPermission(REQUEST_CODE);
    }

    /** Runs a command through `sh -c` as the Shizuku (shell) user. Blocking; call off the UI thread. */
    public static Result exec(String command) {
        if (!hasPermission()) {
            return new Result(-1, "Shizuku not running or permission not granted.");
        }
        try {
            // Shizuku.newProcess is hidden in API 13; access it via reflection (common, supported pattern).
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null, new String[]{"sh", "-c", command}, null, null);

            StringBuilder err = new StringBuilder();
            Thread errThread = new Thread(() -> readAll(p.getErrorStream(), err));
            errThread.start();
            StringBuilder out = new StringBuilder();
            readAll(p.getInputStream(), out);
            int code = p.waitFor();
            errThread.join(2000);
            if (err.length() > 0) out.append(err);
            return new Result(code, out.toString().trim());
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return new Result(-1, "exec error: " + cause);
        }
    }

    private static void readAll(InputStream in, StringBuilder sb) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (sb) {
                    sb.append(line).append('\n');
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Single-quote a string for safe use inside `sh -c`. */
    public static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
