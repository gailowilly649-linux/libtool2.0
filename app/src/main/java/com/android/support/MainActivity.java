package com.android.support;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_OVERLAY_PERMISSION = 1001;
    private static final int REQ_STORAGE_PERMISSION = 1002;
    private static final String MLBB_PACKAGE = "com.mobile.legends";

    private TextView txtOverlayStatus;
    private TextView txtStorageStatus;
    private TextView txtServiceStatus;
    private TextView txtDeviceMode;
    private Button btnGrantOverlay;
    private Button btnToggleService;
    private Button btnLaunchMLBB;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Ensure internal storage directory is always created for non-rooted compatibility
        java.io.File filesDir = getFilesDir();
        if (filesDir != null && !filesDir.exists()) {
            filesDir.mkdirs();
        }

        txtOverlayStatus = findViewById(R.id.txtOverlayStatus);
        txtStorageStatus = findViewById(R.id.txtStorageStatus);
        txtServiceStatus = findViewById(R.id.txtServiceStatus);
        txtDeviceMode = findViewById(R.id.txtDeviceMode);
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay);
        btnToggleService = findViewById(R.id.btnToggleService);
        btnLaunchMLBB = findViewById(R.id.btnLaunchMLBB);

        btnGrantOverlay.setOnClickListener(v -> requestOverlayPermission());

        btnToggleService.setOnClickListener(v -> {
            if (!hasOverlayPermission()) {
                requestOverlayPermission();
                return;
            }

            if (OverlayService.isRunning) {
                stopOverlayService();
            } else {
                startOverlayService();
            }
            btnToggleService.postDelayed(this::updateUiState, 350);
            updateUiState();
        });

        btnLaunchMLBB.setOnClickListener(v -> launchGame());

        txtStorageStatus.setOnClickListener(v -> checkAndRequestStoragePermission());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUiState();
    }

    private boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            try {
                startActivityForResult(intent, REQ_OVERLAY_PERMISSION);
            } catch (Exception e) {
                Toast.makeText(this, "Please enable overlay permission manually in Settings", Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void checkAndRequestStoragePermission() {
        if (!hasStoragePermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, REQ_STORAGE_PERMISSION);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, REQ_STORAGE_PERMISSION);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQ_STORAGE_PERMISSION);
            }
        } else {
            Toast.makeText(this, "Storage permission already granted", Toast.LENGTH_SHORT).show();
        }
    }

    private static final String[] MLBB_PACKAGES = {
            "com.mobile.legends",
            "com.vng.mlbbvn"
    };

    private void startOverlayService() {
        try {
            Intent intent = new Intent(this, OverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "Willy LibTool Started!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to start service: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    private void stopOverlayService() {
        try {
            Intent intent = new Intent(this, OverlayService.class);
            intent.setAction("com.android.support.STOP_OVERLAY");
            startService(intent);
            Toast.makeText(this, "Willy LibTool Stopped", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error stopping service: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    private void launchGame() {
        // Auto-start Willy LibTool overlay if permitted and not yet running
        if (!OverlayService.isRunning && hasOverlayPermission()) {
            startOverlayService();
        }

        PackageManager pm = getPackageManager();
        Intent launchIntent = null;
        String foundPackage = null;

        for (String pkg : MLBB_PACKAGES) {
            try {
                launchIntent = pm.getLaunchIntentForPackage(pkg);
                if (launchIntent != null) {
                    foundPackage = pkg;
                    break;
                }
            } catch (Exception ignored) {
            }
        }

        if (launchIntent == null) {
            // Direct component fallback for Android 11-16
            for (String pkg : MLBB_PACKAGES) {
                try {
                    Intent fallback = new Intent(Intent.ACTION_MAIN);
                    fallback.addCategory(Intent.CATEGORY_LAUNCHER);
                    fallback.setComponent(new android.content.ComponentName(pkg, pkg + ".SplashActivity"));
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    startActivity(fallback);
                    Toast.makeText(this, "Launching Mobile Legends (" + pkg + ")...", Toast.LENGTH_SHORT).show();
                    return;
                } catch (Exception ignored) {
                }
            }
        }

        if (launchIntent != null) {
            try {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(launchIntent);
                Toast.makeText(this, "Launching Mobile Legends (" + foundPackage + ")...", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Error launching Mobile Legends: " + e.getMessage(), Toast.LENGTH_LONG).show();
                e.printStackTrace();
            }
        } else {
            Toast.makeText(this, "Mobile Legends not found! Please check installation.", Toast.LENGTH_LONG).show();
        }
    }

    private void updateUiState() {
        boolean overlayGranted = hasOverlayPermission();
        if (overlayGranted) {
            txtOverlayStatus.setText("GRANTED");
            txtOverlayStatus.setTextColor(Color.parseColor("#00E676"));
            btnGrantOverlay.setVisibility(View.GONE);
        } else {
            txtOverlayStatus.setText("MISSING");
            txtOverlayStatus.setTextColor(Color.parseColor("#FF5252"));
            btnGrantOverlay.setVisibility(View.VISIBLE);
        }

        boolean storageGranted = hasStoragePermission();
        if (storageGranted) {
            txtStorageStatus.setText("GRANTED");
            txtStorageStatus.setTextColor(Color.parseColor("#00E676"));
        } else {
            txtStorageStatus.setText("DENIED");
            txtStorageStatus.setTextColor(Color.parseColor("#FFAB00"));
        }

        if (txtDeviceMode != null) {
            txtDeviceMode.setText("NON-ROOTED (FULL)");
            txtDeviceMode.setTextColor(Color.parseColor("#00E676"));
        }

        if (OverlayService.isRunning) {
            txtServiceStatus.setText("ACTIVE");
            txtServiceStatus.setTextColor(Color.parseColor("#00E676"));
            btnToggleService.setText("STOP WILLY LIBTOOL");
            btnToggleService.setBackgroundColor(Color.parseColor("#37474F"));
        } else {
            txtServiceStatus.setText("STOPPED");
            txtServiceStatus.setTextColor(Color.parseColor("#FF5252"));
            btnToggleService.setText("START WILLY LIBTOOL");
            btnToggleService.setBackgroundColor(Color.parseColor("#D5011D"));
        }
    }
}
