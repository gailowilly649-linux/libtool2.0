package com.android.support;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.support.shizuku.MlbbModInjector;

public class OverlayService extends Service {

    private static final String TAG = "OverlayService";
    private static final String CHANNEL_ID = "willy_libtool_channel";
    private static final int NOTIFICATION_ID = 1337;

    public static final String ACTION_TOGGLE_OVERLAY = "com.android.support.ACTION_TOGGLE_OVERLAY";
    public static final String ACTION_HIDE_OVERLAY = "com.android.support.ACTION_HIDE_OVERLAY";
    public static final String ACTION_SHOW_OVERLAY = "com.android.support.ACTION_SHOW_OVERLAY";
    public static final String ACTION_STOP_OVERLAY = "com.android.support.STOP_OVERLAY";

    public static boolean isRunning = false;
    public static boolean isStealthHidden = false;

    private WindowManager windowManager;
    private View bubbleView;
    private WindowManager.LayoutParams bubbleParams;
    private View menuView;
    private WindowManager.LayoutParams menuParams;

    private TextView txtStatus;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "OverlayService starting native floating overlay");

        createNotificationChannel();
        startForegroundSafely();

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        setupBubble();
        setupMenu();

        isRunning = true;
        isStealthHidden = false;
    }

    private void startForegroundSafely() {
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground error", t);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Willy LibTool Active Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Running Willy LibTool Floating Mod Menu");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent toggleIntent = new Intent(this, OverlayService.class);
        toggleIntent.setAction(ACTION_TOGGLE_OVERLAY);
        PendingIntent togglePendingIntent = PendingIntent.getService(
                this, 1, toggleIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent stopIntent = new Intent(this, OverlayService.class);
        stopIntent.setAction(ACTION_STOP_OVERLAY);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 2, stopIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        String title = isStealthHidden ? "Willy LibTool (Stealth Mode)" : "Willy LibTool";
        String contentText = isStealthHidden
                ? "Overlay is Hidden • Tap below or pull shade to unhide"
                : "Floating Menu Overlay is Active";
        String toggleText = isStealthHidden ? "👁️ Show Menu" : "🙈 Hide Menu";

        builder.setContentTitle(title)
                .setContentText(contentText)
                .setSmallIcon(R.drawable.app_icon)
                .setContentIntent(pendingIntent)
                .setOngoing(true);

        builder.addAction(0, toggleText, togglePendingIntent);
        builder.addAction(0, "❌ Stop", stopPendingIntent);

        return builder.build();
    }

    private void updateNotification() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildNotification());
            }
        } catch (Throwable t) {
            Log.e(TAG, "updateNotification error", t);
        }
    }

    private int dpToPx(int dp) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return Math.round(dp * (metrics.densityDpi / 160f));
    }

    @SuppressWarnings("deprecation")
    private int getOverlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. DRAGGABLE FLOATING BUBBLE
    // ─────────────────────────────────────────────────────────────────────────

    private void setupBubble() {
        int bubbleSize = dpToPx(56);
        bubbleParams = new WindowManager.LayoutParams(
                bubbleSize,
                bubbleSize,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = 24;
        bubbleParams.y = dpToPx(160);

        ImageView iconView = new ImageView(this);
        iconView.setImageResource(R.drawable.app_icon);
        int padding = dpToPx(6);
        iconView.setPadding(padding, padding, padding, padding);

        // Circular styled bubble with accent border
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#EE12151C"));
        bg.setStroke(dpToPx(2), Color.parseColor("#00E5FF"));
        iconView.setBackground(bg);

        iconView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private long touchStartTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = bubbleParams.x;
                        initialY = bubbleParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        touchStartTime = System.currentTimeMillis();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        bubbleParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                        bubbleParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                        try {
                            windowManager.updateViewLayout(bubbleView, bubbleParams);
                        } catch (Exception ignored) {
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        float diffX = Math.abs(event.getRawX() - initialTouchX);
                        float diffY = Math.abs(event.getRawY() - initialTouchY);
                        long duration = System.currentTimeMillis() - touchStartTime;

                        // Tap opens floating menu, long hold (>= 700ms) hides into stealth mode
                        if (diffX < 15 && diffY < 15) {
                            if (duration >= 700) {
                                hideAllOverlays();
                            } else {
                                showMenu();
                            }
                        }
                        return true;
                }
                return false;
            }
        });

        bubbleView = iconView;
        try {
            windowManager.addView(bubbleView, bubbleParams);
        } catch (Exception e) {
            Log.e(TAG, "Failed to add bubbleView", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. COMPACT FLOATING MOD MENU
    // ─────────────────────────────────────────────────────────────────────────

    private void setupMenu() {
        LayoutInflater inflater = LayoutInflater.from(this);
        menuView = inflater.inflate(R.layout.floating_menu, null);

        menuParams = new WindowManager.LayoutParams(
                dpToPx(320),
                WindowManager.LayoutParams.WRAP_CONTENT,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        menuParams.gravity = Gravity.TOP | Gravity.START;
        menuParams.x = dpToPx(30);
        menuParams.y = dpToPx(120);

        txtStatus = menuView.findViewById(R.id.txtFloatingStatus);

        // Make floating menu draggable via header
        View header = menuView.findViewById(R.id.floatingHeader);
        header.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = menuParams.x;
                        initialY = menuParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        menuParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                        menuParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                        try {
                            windowManager.updateViewLayout(menuView, menuParams);
                        } catch (Exception ignored) {
                        }
                        return true;
                }
                return false;
            }
        });

        // Window controls
        Button btnMin = menuView.findViewById(R.id.btnFloatingMinimize);
        btnMin.setOnClickListener(v -> hideMenu());

        Button btnStealth = menuView.findViewById(R.id.btnFloatingStealth);
        if (btnStealth != null) {
            btnStealth.setOnClickListener(v -> hideAllOverlays());
        }

        Button btnClose = menuView.findViewById(R.id.btnFloatingClose);
        btnClose.setOnClickListener(v -> stopSelf());

        // Mod injection listener callbacks
        MlbbModInjector.Callback modCallback = new MlbbModInjector.Callback() {
            @Override
            public void onProgress(String message) {
                if (txtStatus != null) txtStatus.setText(message);
            }

            @Override
            public void onComplete(boolean success, String message) {
                if (txtStatus != null) {
                    txtStatus.setText((success ? "✅ " : "❌ ") + message);
                }
                Toast.makeText(OverlayService.this, message, Toast.LENGTH_SHORT).show();
            }
        };

        // Section 1: Drone View
        menuView.findViewById(R.id.btnDrone2x).setOnClickListener(v -> {
            txtStatus.setText("Injecting 2X Drone View...");
            MlbbModInjector.applyDroneView(this, 2, modCallback);
        });

        menuView.findViewById(R.id.btnDrone3x).setOnClickListener(v -> {
            txtStatus.setText("Injecting 3X Drone View...");
            MlbbModInjector.applyDroneView(this, 3, modCallback);
        });

        menuView.findViewById(R.id.btnDrone4x).setOnClickListener(v -> {
            txtStatus.setText("Injecting 4X Drone View...");
            MlbbModInjector.applyDroneView(this, 4, modCallback);
        });

        menuView.findViewById(R.id.btnDrone5x).setOnClickListener(v -> {
            txtStatus.setText("Injecting 5X Drone View...");
            MlbbModInjector.applyDroneView(this, 5, modCallback);
        });

        menuView.findViewById(R.id.btnDroneReset).setOnClickListener(v -> {
            txtStatus.setText("Restoring stock camera...");
            MlbbModInjector.restoreDefaultDrone(modCallback);
        });

        // Section 2: Real 165 FPS & ASUS ROG Phone 8 Pro Fake Spoof
        View btnUnlock165 = menuView.findViewById(R.id.btnUnlock165Fps);
        if (btnUnlock165 != null) {
            btnUnlock165.setOnClickListener(v -> {
                txtStatus.setText("Unlocking Real 165 FPS & Spoofing ROG 8 Pro...");
                MlbbModInjector.unlockReal165Fps(this, true, modCallback);
            });
        }

        View btnResetFps = menuView.findViewById(R.id.btnResetFps);
        if (btnResetFps != null) {
            btnResetFps.setOnClickListener(v -> {
                txtStatus.setText("Restoring stock 60 Hz display & settings...");
                MlbbModInjector.unlockReal165Fps(this, false, modCallback);
            });
        }

        // Section 3: 7 Heroes Skins Unlocker
        View btnUnlockAllSkins = menuView.findViewById(R.id.btnUnlockAllSkins);
        if (btnUnlockAllSkins != null) {
            btnUnlockAllSkins.setOnClickListener(v -> {
                txtStatus.setText("Unlocking All 12 Hero Skins...");
                MlbbModInjector.unlockAll7Heroes(OverlayService.this, modCallback);
            });
        }

        View btnRestoreAllSkins = menuView.findViewById(R.id.btnRestoreAllSkins);
        if (btnRestoreAllSkins != null) {
            btnRestoreAllSkins.setOnClickListener(v -> {
                txtStatus.setText("Restoring all hero skins...");
                MlbbModInjector.restoreAllHeroSkins(modCallback);
            });
        }

        View btnSkinChou = menuView.findViewById(R.id.btnSkinChou);
        if (btnSkinChou != null) {
            btnSkinChou.setOnClickListener(v -> {
                txtStatus.setText("Unlocking Chou Anniversary...");
                MlbbModInjector.unlockHeroSkin(OverlayService.this, "chou", modCallback);
            });
        }

        View btnSkinFanny = menuView.findViewById(R.id.btnSkinFanny);
        if (btnSkinFanny != null) {
            btnSkinFanny.setOnClickListener(v -> {
                txtStatus.setText("Unlocking Fanny Aspirant...");
                MlbbModInjector.unlockHeroSkin(OverlayService.this, "fanny", modCallback);
            });
        }

        View btnSkinLing = menuView.findViewById(R.id.btnSkinLing);
        if (btnSkinLing != null) {
            btnSkinLing.setOnClickListener(v -> {
                txtStatus.setText("Unlocking Ling Collector...");
                MlbbModInjector.unlockHeroSkin(OverlayService.this, "ling", modCallback);
            });
        }

        View btnSkinGusion = menuView.findViewById(R.id.btnSkinGusion);
        if (btnSkinGusion != null) {
            btnSkinGusion.setOnClickListener(v -> {
                txtStatus.setText("Unlocking Gusion Dragon...");
                MlbbModInjector.unlockHeroSkin(OverlayService.this, "gusion", modCallback);
            });
        }

        View btnSkinHayabusa = menuView.findViewById(R.id.btnSkinHayabusa);
        if (btnSkinHayabusa != null) {
            btnSkinHayabusa.setOnClickListener(v -> {
                txtStatus.setText("Unlocking Hayabusa Kitsune...");
                MlbbModInjector.unlockHeroSkin(OverlayService.this, "hayabusa", modCallback);
            });
        }

        View btnSkinLancelot = menuView.findViewById(R.id.btnSkinLancelot);
        if (btnSkinLancelot != null) {
            btnSkinLancelot.setOnClickListener(v -> {
                txtStatus.setText("Unlocking Lancelot Hero...");
                MlbbModInjector.unlockHeroSkin(OverlayService.this, "lancelot", modCallback);
            });
        }

        View btnSkinGranger = menuView.findViewById(R.id.btnSkinGranger);
        if (btnSkinGranger != null) {
            btnSkinGranger.setOnClickListener(v -> {
                txtStatus.setText("Unlocking Granger Legend...");
                MlbbModInjector.unlockHeroSkin(OverlayService.this, "granger", modCallback);
            });
        }

        View btnSkinLayla = menuView.findViewById(R.id.btnSkinLayla);
        if (btnSkinLayla != null) {
            btnSkinLayla.setOnClickListener(v -> {
                txtStatus.setText("Unlocking Layla Collector...");
                MlbbModInjector.unlockHeroSkin(OverlayService.this, "layla", modCallback);
            });
        }

        View btnSkinMoskov = menuView.findViewById(R.id.btnSkinMoskov);
        if (btnSkinMoskov != null) {
            btnSkinMoskov.setOnClickListener(v -> {
                txtStatus.setText("Unlocking Moskov Abyss...");
                MlbbModInjector.unlockHeroSkin(OverlayService.this, "moskov", modCallback);
            });
        }

        View btnSkinClaude = menuView.findViewById(R.id.btnSkinClaude);
        if (btnSkinClaude != null) {
            btnSkinClaude.setOnClickListener(v -> {
                txtStatus.setText("Unlocking Claude Sanrio...");
                MlbbModInjector.unlockHeroSkin(OverlayService.this, "claude", modCallback);
            });
        }

        View btnSkinMiya = menuView.findViewById(R.id.btnSkinMiya);
        if (btnSkinMiya != null) {
            btnSkinMiya.setOnClickListener(v -> {
                txtStatus.setText("Unlocking Miya Collector...");
                MlbbModInjector.unlockHeroSkin(OverlayService.this, "miya", modCallback);
            });
        }

        View btnSkinKarrie = menuView.findViewById(R.id.btnSkinKarrie);
        if (btnSkinKarrie != null) {
            btnSkinKarrie.setOnClickListener(v -> {
                txtStatus.setText("Unlocking Karrie M5...");
                MlbbModInjector.unlockHeroSkin(OverlayService.this, "karrie", modCallback);
            });
        }

        // Section 4: Map & Battlefield Assets
        View btnInjectMapSkins = menuView.findViewById(R.id.btnInjectMapSkins);
        if (btnInjectMapSkins != null) {
            btnInjectMapSkins.setOnClickListener(v -> {
                txtStatus.setText("Injecting Map & Skins...");
                MlbbModInjector.injectMapAndSkins(this, modCallback);
            });
        }

        View btnRestoreMapSkins = menuView.findViewById(R.id.btnRestoreMapSkins);
        if (btnRestoreMapSkins != null) {
            btnRestoreMapSkins.setOnClickListener(v -> {
                txtStatus.setText("Restoring stock map & skins...");
                MlbbModInjector.restoreDefaultMapAndSkins(modCallback);
            });
        }

        menuView.setVisibility(View.GONE);
        try {
            windowManager.addView(menuView, menuParams);
        } catch (Exception e) {
            Log.e(TAG, "Failed to add menuView", e);
        }
    }

    public void showMenu() {
        isStealthHidden = false;
        if (bubbleView != null) {
            bubbleView.setVisibility(View.VISIBLE);
            bubbleView.setAlpha(0.4f);
        }
        if (menuView != null) {
            menuView.setVisibility(View.VISIBLE);
        }
        updateNotification();
    }

    public void hideMenu() {
        if (menuView != null) {
            menuView.setVisibility(View.GONE);
        }
        if (bubbleView != null) {
            bubbleView.setVisibility(View.VISIBLE);
            bubbleView.setAlpha(1.0f);
        }
    }

    public void hideAllOverlays() {
        isStealthHidden = true;
        if (menuView != null) {
            menuView.setVisibility(View.GONE);
        }
        if (bubbleView != null) {
            bubbleView.setVisibility(View.GONE);
        }
        updateNotification();
        Toast.makeText(this, "Floating menu hidden (Stealth). Tap notification to unhide.", Toast.LENGTH_SHORT).show();
    }

    public void showAllOverlays() {
        isStealthHidden = false;
        if (bubbleView != null) {
            bubbleView.setVisibility(View.VISIBLE);
            bubbleView.setAlpha(1.0f);
        }
        if (menuView != null) {
            menuView.setVisibility(View.VISIBLE);
        }
        updateNotification();
        Toast.makeText(this, "Floating menu restored!", Toast.LENGTH_SHORT).show();
    }

    public void toggleStealthMode() {
        if (isStealthHidden) {
            showAllOverlays();
        } else {
            hideAllOverlays();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("STOP".equals(action) || ACTION_STOP_OVERLAY.equals(action) || "com.android.support.STOP_OVERLAY".equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            } else if (ACTION_TOGGLE_OVERLAY.equals(action)) {
                toggleStealthMode();
                return START_STICKY;
            } else if (ACTION_HIDE_OVERLAY.equals(action)) {
                hideAllOverlays();
                return START_STICKY;
            } else if (ACTION_SHOW_OVERLAY.equals(action)) {
                showAllOverlays();
                return START_STICKY;
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        isStealthHidden = false;

        if (menuView != null && windowManager != null) {
            try {
                windowManager.removeView(menuView);
            } catch (Exception ignored) {
            }
        }

        if (bubbleView != null && windowManager != null) {
            try {
                windowManager.removeView(bubbleView);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
