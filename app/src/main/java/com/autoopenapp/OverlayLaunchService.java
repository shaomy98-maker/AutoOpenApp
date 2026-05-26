package com.autoopenapp;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class OverlayLaunchService extends Service {
    private static final String EXTRA_ALARM_TIME = "alarm_time";
    private static final String CHANNEL_ID = "overlay_launch";
    private static final int NOTIFICATION_ID = 1002;
    private View overlayView;
    private WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    static boolean canUseOverlay(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    static void start(Context context, String alarmTime) {
        if (!canUseOverlay(context)) {
            RunLog.i(context, "未启用悬浮窗权限，跳过悬浮层拉起");
            return;
        }
        try {
            Intent intent = new Intent(context, OverlayLaunchService.class);
            intent.putExtra(EXTRA_ALARM_TIME, alarmTime);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            RunLog.e(context, "启动悬浮层服务失败", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String alarmTime = intent == null ? "" : intent.getStringExtra(EXTRA_ALARM_TIME);
        showOverlayAndLaunch(alarmTime);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }

    private void showOverlayAndLaunch(String alarmTime) {
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            TextView view = new TextView(this);
            view.setText("正在打开应用");
            view.setTextSize(14);
            view.setTextColor(0xFFFFFFFF);
            view.setGravity(Gravity.CENTER);
            view.setBackgroundColor(0xCC111827);
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    dp(64),
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP;
            overlayView = view;
            windowManager.addView(overlayView, params);
            RunLog.i(this, "悬浮层已显示，作为可见窗口拉起目标");
            // 等窗口真正附着后再拉起，可见窗口此时已可豁免后台启动限制
            handler.postDelayed(() -> launchFromVisibleWindow(alarmTime), 80L);
            handler.postDelayed(this::stopSelf, 3_000L);
        } catch (Exception e) {
            RunLog.e(this, "显示悬浮层失败", e);
            stopSelf();
        }
    }

    private void launchFromVisibleWindow(String alarmTime) {
        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean locked = keyguardManager != null && keyguardManager.isKeyguardLocked();
        boolean interactive = powerManager == null || powerManager.isInteractive();
        try {
            if (locked || !interactive) {
                // 需要先亮屏并解除锁屏，交给中转页处理唤醒、解锁后再开目标
                RunLog.i(this, "悬浮层检测到锁屏/熄屏，拉起中转页处理唤醒解锁");
                startActivity(TargetLauncher.buildAlarmAlertIntent(this, alarmTime));
            } else {
                // 屏幕已亮且已解锁，直接打开目标，少一跳更稳
                RunLog.i(this, "悬浮层在已解锁状态直接打开目标");
                ScheduleConfig config = ScheduleStore.load(this);
                boolean ok = TargetLauncher.launch(this, config, alarmTime);
                if (!ok) {
                    startActivity(TargetLauncher.buildAlarmAlertIntent(this, alarmTime));
                }
            }
        } catch (Exception e) {
            RunLog.e(this, "悬浮层拉起失败", e);
        }
    }

    private void removeOverlay() {
        if (windowManager != null && overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {
            }
            overlayView = null;
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle("正在打开目标应用")
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "拉起目标",
                    NotificationManager.IMPORTANCE_MIN
            );
            channel.setDescription("到点拉起目标应用时的短暂前台服务");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
