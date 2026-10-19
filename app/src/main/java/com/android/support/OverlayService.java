package com.android.support;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import imgui.il2cpp.tool.NativeMethods;

public class OverlayService extends Service implements SurfaceHolder.Callback, View.OnTouchListener, Choreographer.FrameCallback {

    private static final String CHANNEL_ID = "willy_libtool_channel";
    private static final int NOTIFICATION_ID = 1337;

    public static boolean isRunning = false;

    private WindowManager windowManager;
    private SurfaceView surfaceView;
    private WindowManager.LayoutParams windowParams;
    private boolean isRendering = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        setupOverlay();
        isRunning = true;
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

        return builder
                .setContentTitle("Willy LibTool")
                .setContentText("Floating Menu Overlay is Active")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void setupOverlay() {
        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        windowParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );
        windowParams.gravity = Gravity.TOP | Gravity.START;
        windowParams.x = 0;
        windowParams.y = 0;

        surfaceView = new SurfaceView(this);
        surfaceView.setZOrderOnTop(true);
        surfaceView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        surfaceView.getHolder().addCallback(this);
        surfaceView.setOnTouchListener(this);

        try {
            windowManager.addView(surfaceView, windowParams);
        } catch (Exception e) {
            e.printStackTrace();
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

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            NativeMethods.onSurfaceCreated(holder.getSurface());
            NativeMethods.onSurfaceCreate();
            isRendering = true;
            Choreographer.getInstance().postFrameCallback(this);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        try {
            NativeMethods.onSurfaceChanged(width, height);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        isRendering = false;
        Choreographer.getInstance().removeFrameCallback(this);
        try {
            NativeMethods.onSurfaceCreated(null);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (isRendering) {
            try {
                NativeMethods.onDrawFrame();
            } catch (Throwable t) {
                t.printStackTrace();
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

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
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRendering = false;
        isRunning = false;
        Choreographer.getInstance().removeFrameCallback(this);
        if (surfaceView != null && windowManager != null) {
            try {
                windowManager.removeView(surfaceView);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
