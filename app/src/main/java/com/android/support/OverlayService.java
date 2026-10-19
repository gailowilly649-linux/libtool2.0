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
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import imgui.il2cpp.tool.NativeMethods;

public class OverlayService extends Service implements View.OnTouchListener, GLSurfaceView.Renderer {

    private static final String TAG = "OverlayService";
    private static final String CHANNEL_ID = "willy_libtool_channel";
    private static final int NOTIFICATION_ID = 1337;

    public static boolean isRunning = false;

    private WindowManager windowManager;
    private GLSurfaceView glSurfaceView;
    private WindowManager.LayoutParams glParams;
    private View bubbleView;
    private WindowManager.LayoutParams bubbleParams;

    private boolean isMenuOpen = true;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "OverlayService onCreate");

        createNotificationChannel();
        startForegroundSafely();

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        setupViews();
        isRunning = true;
    }

    private void startForegroundSafely() {
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+ (API 34)
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

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
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

        // Use standard raster PNG drawable (R.drawable.app_icon), NEVER an adaptive-icon XML
        return builder
                .setContentTitle("Willy LibTool")
                .setContentText("Floating Menu Overlay is Active")
                .setSmallIcon(R.drawable.app_icon)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private int dpToPx(int dp) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return Math.round(dp * (metrics.densityDpi / 160f));
    }

    private void setupViews() {
        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        // 1. Setup GLSurfaceView for ImGui Menu
        glParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );
        glParams.gravity = Gravity.TOP | Gravity.START;
        glParams.x = 0;
        glParams.y = 0;

        glSurfaceView = new GLSurfaceView(this);
        try {
            glSurfaceView.setEGLContextClientVersion(3);
        } catch (Exception e) {
            glSurfaceView.setEGLContextClientVersion(2);
        }
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        glSurfaceView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        glSurfaceView.setZOrderOnTop(true);
        glSurfaceView.setRenderer(this);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        glSurfaceView.setOnTouchListener(this);

        try {
            windowManager.addView(glSurfaceView, glParams);
        } catch (Exception e) {
            Log.e(TAG, "Failed to add glSurfaceView", e);
        }

        // 2. Setup Floating Bubble (Icon button for easy toggling / dragging)
        int bubbleSize = dpToPx(56);
        bubbleParams = new WindowManager.LayoutParams(
                bubbleSize,
                bubbleSize,
                layoutType,
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

        // Circular background with red border
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#E61E293B"));
        bg.setStroke(dpToPx(2), Color.parseColor("#D5011D"));
        iconView.setBackground(bg);

        setupBubbleTouch(iconView);
        bubbleView = iconView;

        try {
            windowManager.addView(bubbleView, bubbleParams);
        } catch (Exception e) {
            Log.e(TAG, "Failed to add bubbleView", e);
        }
    }

    private void setupBubbleTouch(View bubble) {
        bubble.setOnTouchListener(new View.OnTouchListener() {
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

                        // Click detected if moved less than 15px and tapped quickly
                        if (diffX < 15 && diffY < 15 && duration < 300) {
                            toggleMenu();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    public void toggleMenu() {
        setMenuVisible(!isMenuOpen);
    }

    public void setMenuVisible(boolean visible) {
        isMenuOpen = visible;
        NativeMethods.setMenuMinimized(!visible);

        if (glSurfaceView != null && windowManager != null) {
            if (visible) {
                glSurfaceView.setVisibility(View.VISIBLE);
                glParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                bubbleView.setAlpha(0.6f);
            } else {
                glSurfaceView.setVisibility(View.GONE);
                glParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                bubbleView.setAlpha(1.0f);
            }
            try {
                windowManager.updateViewLayout(glSurfaceView, glParams);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    // GLSurfaceView.Renderer callbacks
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        Log.i(TAG, "GLSurfaceView onSurfaceCreated");
        try {
            NativeMethods.onSurfaceCreated(glSurfaceView.getHolder().getSurface());
            NativeMethods.onSurfaceCreate();
        } catch (Throwable t) {
            Log.e(TAG, "Renderer onSurfaceCreated error", t);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        Log.i(TAG, "GLSurfaceView onSurfaceChanged: " + width + "x" + height);
        try {
            NativeMethods.onSurfaceChanged(width, height);
        } catch (Throwable t) {
            Log.e(TAG, "Renderer onSurfaceChanged error", t);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        try {
            NativeMethods.onDrawFrame();
        } catch (Throwable t) {
            Log.e(TAG, "Renderer onDrawFrame error", t);
        }
    }

    // Touch events for ImGui overlay
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        try {
            NativeMethods.onTouchEvent(
                    event.getActionMasked(),
                    event.getX(),
                    0,
                    event.getY(),
                    0
            );

            // If user clicked "Minimize [-]" inside the ImGui menu, collapse automatically
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                mainHandler.post(() -> {
                    if (NativeMethods.isMenuMinimized() && isMenuOpen) {
                        setMenuVisible(false);
                    }
                });
            }
        } catch (Throwable t) {
            Log.e(TAG, "onTouch error", t);
        }
        return true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        isMenuOpen = false;

        if (glSurfaceView != null) {
            try {
                glSurfaceView.onPause();
                if (windowManager != null) {
                    windowManager.removeView(glSurfaceView);
                }
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
