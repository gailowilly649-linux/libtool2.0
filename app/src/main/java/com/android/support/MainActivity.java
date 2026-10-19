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
    private Button btnGrantOverlay;
    private Button btnToggleService;
    private Button btnLaunchMLBB;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtOverlayStatus = findViewById(R.id.txtOverlayStatus);
        txtStorageStatus = findViewById(R.id.txtStorageStatus);
        txtServiceStatus = findViewById(R.id.txtServiceStatus);
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
            updateUiState();
        });

        btnLaunchMLBB.setOnClickListener(v -> launchGame(MLBB_PACKAGE));

        checkAndRequestStoragePermission();
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
        }
    }

    private void startOverlayService() {
        Intent intent = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "Willy LibTool Started!", Toast.LENGTH_SHORT).show();
    }

    private void stopOverlayService() {
        Intent intent = new Intent(this, OverlayService.class);
        intent.setAction("STOP");
        startService(intent);
        Toast.makeText(this, "Willy LibTool Stopped", Toast.LENGTH_SHORT).show();
    }

    private void launchGame(String packageName) {
        PackageManager pm = getPackageManager();
        Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
        if (launchIntent != null) {
            startActivity(launchIntent);
        } else {
            Toast.makeText(this, "Game (" + packageName + ") not installed!", Toast.LENGTH_LONG).show();
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
