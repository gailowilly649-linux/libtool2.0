package com.android.support.shizuku;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.widget.Button;
import android.widget.TextView;

import com.android.support.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * UI + logic for the "Shizuku Tools" card in MainActivity:
 * backup/restore of MLBB settings files, cache cleaning, and display refresh-rate control.
 * Everything here only touches /sdcard/Android/data/com.mobile.legends and system display settings.
 */
public final class ShizukuPanel {

    private static final String PKG = "com.mobile.legends";
    private static final String DATA = "/sdcard/Android/data/" + PKG;
    private static final String FILES = DATA + "/files";
    private static final String ASSETS_REL = "dragon2017/assets";
    private static final String BACKUP_DIR = "/sdcard/WillyLibTool/backups";

    private static final String PREFS = "shizuku_tools";
    private static final String KEY_RR_FORCED = "rr_forced";
    private static final String KEY_OLD_PEAK = "old_peak_refresh_rate";
    private static final String KEY_OLD_MIN = "old_min_refresh_rate";

    private static final String[][] CACHE_TARGETS = {
            {"Unity Cache", FILES + "/UnityCache"},
            {"Crash Logs", FILES + "/CrashAsset"},
            {"Recorded Videos (replays)", FILES + "/RecordVideo"},
            {"App Cache", DATA + "/cache"},
    };

    private final Activity activity;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private final TextView txtStatus;
    private final TextView txtRefresh;
    private final TextView txtLog;
    private final Button btnGrant;
    private final Button btnBackup;
    private final Button btnRestore;
    private final Button btnClean;
    private final Button btnRefreshRate;

    private final Shizuku.OnBinderReceivedListener binderReceived = this::postUpdate;
    private final Shizuku.OnBinderDeadListener binderDead = this::postUpdate;
    private final Shizuku.OnRequestPermissionResultListener permResult = (code, result) -> {
        if (code == ShizukuShell.REQUEST_CODE) {
            log(ShizukuShell.hasPermission() ? "Shizuku permission granted." : "Shizuku permission denied.");
            postUpdate();
        }
    };

    public ShizukuPanel(Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        txtStatus = activity.findViewById(R.id.txtShizukuStatus);
        txtRefresh = activity.findViewById(R.id.txtRefreshInfo);
        txtLog = activity.findViewById(R.id.txtShizukuLog);
        btnGrant = activity.findViewById(R.id.btnShizukuGrant);
        btnBackup = activity.findViewById(R.id.btnBackup);
        btnRestore = activity.findViewById(R.id.btnRestore);
        btnClean = activity.findViewById(R.id.btnCleanCache);
        btnRefreshRate = activity.findViewById(R.id.btnRefreshRate);

        btnGrant.setOnClickListener(v -> onGrant());
        btnBackup.setOnClickListener(v -> onBackup());
        btnRestore.setOnClickListener(v -> onRestore());
        btnClean.setOnClickListener(v -> onClean());
        btnRefreshRate.setOnClickListener(v -> onRefreshRate());

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceived);
            Shizuku.addBinderDeadListener(binderDead);
            Shizuku.addRequestPermissionResultListener(permResult);
        } catch (Throwable t) {
            log("Shizuku API unavailable: " + t);
        }
    }

    public void onDestroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceived);
            Shizuku.removeBinderDeadListener(binderDead);
            Shizuku.removeRequestPermissionResultListener(permResult);
        } catch (Throwable ignored) {
        }
        worker.shutdownNow();
    }

    // ---------------------------------------------------------------- status

    private void postUpdate() {
        main.post(this::updateUi);
    }

    public void updateUi() {
        boolean running = ShizukuShell.isRunning();
        boolean granted = ShizukuShell.hasPermission();
        if (!running) {
            txtStatus.setText("NOT RUNNING");
            txtStatus.setTextColor(Color.parseColor("#FF5252"));
            btnGrant.setText("HOW TO START SHIZUKU");
        } else if (!granted) {
            txtStatus.setText("NO PERMISSION");
            txtStatus.setTextColor(Color.parseColor("#FFAB00"));
            btnGrant.setText("GRANT SHIZUKU PERMISSION");
        } else {
            txtStatus.setText("READY");
            txtStatus.setTextColor(Color.parseColor("#00E676"));
            btnGrant.setText("SHIZUKU CONNECTED");
        }
        btnGrant.setEnabled(!granted);
        btnBackup.setEnabled(granted);
        btnRestore.setEnabled(granted);
        btnClean.setEnabled(granted);
        btnRefreshRate.setEnabled(granted);

        float current = currentRefreshRate();
        float max = maxRefreshRate();
        txtRefresh.setText(String.format(Locale.US, "%.0f Hz (max %.0f Hz)", current, max));
        boolean forced = prefs.getBoolean(KEY_RR_FORCED, false);
        btnRefreshRate.setText(forced ? "DISPLAY: RESTORE DEFAULT REFRESH RATE"
                : String.format(Locale.US, "DISPLAY: FORCE %.0f HZ", max));
    }

    private void onGrant() {
        if (!ShizukuShell.isRunning()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Start Shizuku first")
                    .setMessage("1. Install \"Shizuku\" from Play Store.\n"
                            + "2. Enable Developer Options > Wireless debugging.\n"
                            + "3. Open Shizuku and tap \"Start\" (via Wireless debugging pairing).\n"
                            + "4. Come back here and tap GRANT SHIZUKU PERMISSION.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        if (ShizukuShell.shouldShowRationale()) {
            log("Permission was denied permanently. Allow Willy LibTool inside the Shizuku app.");
            return;
        }
        ShizukuShell.requestPermission();
    }

    // ---------------------------------------------------------------- backup / restore

    private void onBackup() {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String out = BACKUP_DIR + "/mlbb_settings_" + ts + ".tar";
        // Only the small top-level config/state files + ModeVersion; the multi-GB asset folders are excluded.
        String cmd = "mkdir -p " + ShizukuShell.q(BACKUP_DIR)
                + " && cd " + ShizukuShell.q(FILES)
                + " && list=$(find " + ASSETS_REL + " -maxdepth 1 -type f)"
                + " && if [ -d ModeVersion ]; then list=\"$list ModeVersion\"; fi"
                + " && [ -n \"$list\" ] && tar -cf " + ShizukuShell.q(out) + " $list"
                + " && echo \"Saved: " + out + "\" && ls -la " + ShizukuShell.q(out);
        run("Backing up MLBB settings...", cmd, null);
    }

    private void onRestore() {
        runForResult("ls -1t " + ShizukuShell.q(BACKUP_DIR) + "/*.tar 2>/dev/null", res -> {
            if (!res.ok() || res.output.isEmpty()) {
                log("No backups found in " + BACKUP_DIR);
                return;
            }
            String[] files = res.output.split("\n");
            String[] labels = new String[files.length];
            for (int i = 0; i < files.length; i++) {
                labels[i] = files[i].substring(files[i].lastIndexOf('/') + 1);
            }
            new AlertDialog.Builder(activity)
                    .setTitle("Restore which backup?")
                    .setItems(labels, (d, which) -> confirmRestore(files[which].trim(), labels[which]))
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    private void confirmRestore(String path, String label) {
        new AlertDialog.Builder(activity)
                .setTitle("Restore " + label + "?")
                .setMessage("Mobile Legends will be force-stopped and its settings files overwritten with this backup.")
                .setPositiveButton("Restore", (d, w) -> run("Restoring " + label + "...",
                        "am force-stop " + PKG
                                + " && tar -xf " + ShizukuShell.q(path) + " -C " + ShizukuShell.q(FILES)
                                + " && echo 'Restore complete.'", null))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------------------------------------------------------- cache cleaner

    private void onClean() {
        StringBuilder cmd = new StringBuilder();
        for (String[] t : CACHE_TARGETS) {
            cmd.append("s=$(du -sk ").append(ShizukuShell.q(t[1])).append(" 2>/dev/null | cut -f1); echo ${s:-0};");
        }
        runForResult(cmd.toString(), res -> {
            String[] sizes = res.output.split("\n");
            String[] labels = new String[CACHE_TARGETS.length];
            boolean[] checked = new boolean[CACHE_TARGETS.length];
            for (int i = 0; i < CACHE_TARGETS.length; i++) {
                long kb = 0;
                try {
                    kb = Long.parseLong(i < sizes.length ? sizes[i].trim() : "0");
                } catch (NumberFormatException ignored) {
                }
                labels[i] = CACHE_TARGETS[i][0] + "  (" + humanSize(kb) + ")";
                // Replays are user content; leave unchecked by default.
                checked[i] = !CACHE_TARGETS[i][1].endsWith("RecordVideo") && kb > 0;
            }
            new AlertDialog.Builder(activity)
                    .setTitle("Clean MLBB cache")
                    .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                    .setPositiveButton("Clean", (d, w) -> {
                        StringBuilder rm = new StringBuilder("am force-stop " + PKG + ";");
                        List<String> names = new ArrayList<>();
                        for (int i = 0; i < CACHE_TARGETS.length; i++) {
                            if (!checked[i]) continue;
                            String p = ShizukuShell.q(CACHE_TARGETS[i][1]);
                            rm.append(" [ -d ").append(p).append(" ] && rm -rf ").append(p).append("/* ")
                                    .append(p).append("/.[!.]* 2>/dev/null;");
                            names.add(CACHE_TARGETS[i][0]);
                        }
                        if (names.isEmpty()) {
                            log("Nothing selected.");
                            return;
                        }
                        rm.append(" echo 'Cleaned: ").append(String.join(", ", names)).append("'");
                        run("Cleaning cache...", rm.toString(), null);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    private static String humanSize(long kb) {
        if (kb >= 1024 * 1024) return String.format(Locale.US, "%.2f GB", kb / 1048576f);
        if (kb >= 1024) return String.format(Locale.US, "%.1f MB", kb / 1024f);
        return kb + " KB";
    }

    // ---------------------------------------------------------------- refresh rate

    private void onRefreshRate() {
        boolean forced = prefs.getBoolean(KEY_RR_FORCED, false);
        if (!forced) {
            float max = maxRefreshRate();
            if (max <= 61f) {
                log("This display only supports " + (int) max + " Hz; nothing to force.");
                return;
            }
            String val = String.format(Locale.US, "%.1f", max);
            runForResult("settings get system peak_refresh_rate; settings get system min_refresh_rate", res -> {
                String[] old = res.output.split("\n");
                prefs.edit()
                        .putString(KEY_OLD_PEAK, old.length > 0 ? old[0].trim() : "null")
                        .putString(KEY_OLD_MIN, old.length > 1 ? old[1].trim() : "null")
                        .apply();
                run("Forcing display to " + val + " Hz...",
                        "settings put system peak_refresh_rate " + val
                                + " && settings put system min_refresh_rate " + val
                                + " && echo 'Display locked to " + val + " Hz.'"
                                + " && echo 'Note: in-game FPS is still set in MLBB > Settings > Graphics.'",
                        r -> {
                            if (r.ok()) prefs.edit().putBoolean(KEY_RR_FORCED, true).apply();
                        });
            });
        } else {
            String cmd = restoreSettingCmd("peak_refresh_rate", prefs.getString(KEY_OLD_PEAK, "null"))
                    + " ; " + restoreSettingCmd("min_refresh_rate", prefs.getString(KEY_OLD_MIN, "null"))
                    + " ; echo 'Display refresh rate restored.'";
            run("Restoring default refresh rate...", cmd, r -> prefs.edit().putBoolean(KEY_RR_FORCED, false).apply());
        }
    }

    private static String restoreSettingCmd(String key, String old) {
        if (old == null || old.isEmpty() || "null".equals(old)) {
            return "settings delete system " + key;
        }
        return "settings put system " + key + " " + ShizukuShell.q(old);
    }

    private float currentRefreshRate() {
        Display d = activity.getWindowManager().getDefaultDisplay();
        return d != null ? d.getRefreshRate() : 60f;
    }

    private float maxRefreshRate() {
        Display d = activity.getWindowManager().getDefaultDisplay();
        if (d == null) return 60f;
        float max = d.getRefreshRate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (Display.Mode m : d.getSupportedModes()) {
                max = Math.max(max, m.getRefreshRate());
            }
        }
        return max;
    }

    // ---------------------------------------------------------------- plumbing

    private interface Callback {
        void done(ShizukuShell.Result r);
    }

    private void run(String title, String cmd, Callback after) {
        log(title);
        setBusy(true);
        worker.execute(() -> {
            ShizukuShell.Result r = ShizukuShell.exec(cmd);
            main.post(() -> {
                setBusy(false);
                log((r.ok() ? "" : "[exit " + r.exitCode + "] ") + (r.output.isEmpty() ? "Done." : r.output));
                if (after != null) after.done(r);
                updateUi();
            });
        });
    }

    private void runForResult(String cmd, Callback cb) {
        setBusy(true);
        worker.execute(() -> {
            ShizukuShell.Result r = ShizukuShell.exec(cmd);
            main.post(() -> {
                setBusy(false);
                updateUi();
                cb.done(r);
            });
        });
    }

    private void setBusy(boolean busy) {
        boolean granted = ShizukuShell.hasPermission();
        btnBackup.setEnabled(!busy && granted);
        btnRestore.setEnabled(!busy && granted);
        btnClean.setEnabled(!busy && granted);
        btnRefreshRate.setEnabled(!busy && granted);
    }

    private void log(String msg) {
        main.post(() -> {
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            String prev = txtLog.getText().toString();
            if ("Ready.".equals(prev)) prev = "";
            String next = "[" + ts + "] " + msg + (prev.isEmpty() ? "" : "\n" + prev);
            if (next.length() > 4000) next = next.substring(0, 4000);
            txtLog.setText(next);
        });
    }
}
