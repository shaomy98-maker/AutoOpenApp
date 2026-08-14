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
import android.text.TextUtils;
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
    private String activeAlarmTime = "";
    private boolean launchAttempted;
    private Runnable launchRunnable;
    private Runnable stopRunnable;

    static boolean canUseOverlay(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    static void start(Context context, String alarmTime) {
        if (!canUseOverlay(context)) {
            RunLog.i(context, "未启用悬浮窗权限，跳过悬浮层拉起");
            return;
        }
        if (!TextUtils.isEmpty(alarmTime) && LaunchTracker.recentlySucceeded(context, alarmTime)) {
            RunLog.i(context, "悬浮层跳过最近已成功的任务 value=" + alarmTime);
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
        handler.removeCallbacksAndMessages(null);
        launchRunnable = null;
        stopRunnable = null;
        removeOverlay();
        activeAlarmTime = "";
        launchAttempted = false;
        super.onDestroy();
    }

    private void showOverlayAndLaunch(String alarmTime) {
        String alarmValue = alarmTime == null ? "" : alarmTime;
        if (overlayView != null) {
            if (TextUtils.equals(activeAlarmTime, alarmValue)) {
                RunLog.i(this, "悬浮层已在处理同一任务，忽略重复启动 value=" + alarmValue);
            } else {
                RunLog.i(this, "悬浮层正处理其他任务，新任务依赖其独立通知/重试 value=" + alarmValue);
            }
            return;
        }
        if (!isUnlockedAndInteractive()) {
            RunLog.i(this, "设备状态已变为锁屏或熄屏，悬浮层不再启动，依赖全屏提醒 value=" + alarmValue);
            stopSelf();
            return;
        }

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
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP;
            overlayView = view;
            activeAlarmTime = alarmValue;
            launchAttempted = false;
            windowManager.addView(overlayView, params);
            RunLog.i(this, "悬浮层已显示，作为可见窗口拉起目标");
            // 等窗口真正附着后再拉起，可见窗口此时已可豁免后台启动限制
            launchRunnable = () -> launchFromVisibleWindow(alarmValue);
            stopRunnable = this::stopSelf;
            handler.postDelayed(launchRunnable, 80L);
            handler.postDelayed(stopRunnable, 3_000L);
        } catch (Exception e) {
            RunLog.e(this, "显示悬浮层失败", e);
            stopSelf();
        }
    }

    private void launchFromVisibleWindow(String alarmTime) {
        if (!TextUtils.equals(activeAlarmTime, alarmTime) || launchAttempted) {
            RunLog.i(this, "悬浮层忽略已过期或重复的拉起回调 value=" + alarmTime);
            return;
        }
        launchAttempted = true;
        if (!isUnlockedAndInteractive()) {
            RunLog.i(this, "拉起前设备状态变为锁屏或熄屏，依赖全屏提醒 value=" + alarmTime);
            stopSelf();
            return;
        }

        try {
            RunLog.i(this, "悬浮层在已解锁状态直接打开目标");
            ScheduleConfig config = ScheduleStore.load(this);
            boolean ok = TargetLauncher.launch(this, config, alarmTime);
            if (!ok) {
                RunLog.i(this, "悬浮层直接打开失败，已保留任务通知供用户处理 value=" + alarmTime);
            }
        } catch (Exception e) {
            RunLog.e(this, "悬浮层拉起失败", e);
        } finally {
            stopSelf();
        }
    }

    private boolean isUnlockedAndInteractive() {
        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean locked = keyguardManager != null && keyguardManager.isKeyguardLocked();
        boolean interactive = powerManager == null || powerManager.isInteractive();
        return !locked && interactive;
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
