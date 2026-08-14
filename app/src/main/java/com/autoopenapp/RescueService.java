package com.autoopenapp;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public class RescueService extends Service {
    private static final String ACTION_STOP_GUARD = "com.autoopenapp.action.STOP_RESCUE_GUARD";
    private static final String CHANNEL_ID = "process_guard_v1";
    private static final int NOTIFICATION_ID = 1003;
    private static final long RESTART_DELAY_MILLIS = 1_500L;
    private final IBinder localBinder = new Binder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean mainBound;
    private boolean mainBindingRegistered;
    private boolean destroying;
    private final ServiceConnection mainConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mainBound = true;
            RunLog.i(RescueService.this, "守护进程已连接主常驻服务");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mainBound = false;
            recoverMainProcess("主进程连接断开");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            mainBound = false;
            clearMainBinding();
            recoverMainProcess("主进程 Binder 死亡");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            mainBound = false;
            clearMainBinding();
            recoverMainProcess("主服务未返回 Binder");
        }
    };

    static void start(Context context) {
        Context appContext = context.getApplicationContext();
        if (!KeepAliveService.isGuardEligible(appContext)) {
            stop(appContext);
            return;
        }
        try {
            appContext.startForegroundService(new Intent(appContext, RescueService.class));
        } catch (Exception e) {
            RunLog.e(appContext, "独立守护进程启动失败", e);
        }
    }

    static void stop(Context context) {
        Context appContext = context.getApplicationContext();
        Intent intent = new Intent(appContext, RescueService.class).setAction(ACTION_STOP_GUARD);
        try {
            appContext.startService(intent);
        } catch (Exception e) {
            try {
                appContext.stopService(intent);
            } catch (Exception stopError) {
                RunLog.e(appContext, "直接停止独立守护进程失败", stopError);
            }
            RunLog.e(appContext, "独立守护停止指令发送失败，已直接停止", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!KeepAliveService.isGuardEligible(this)) {
            destroying = true;
            stopSelf();
            return;
        }
        try {
            createChannel();
            startForeground(NOTIFICATION_ID, buildNotification());
            RunLog.i(this, "独立守护进程已创建 process=" + android.os.Process.myPid());
            KeepAliveService.sync(this);
            bindMainProcess();
        } catch (Exception e) {
            RunLog.e(this, "创建独立守护进程失败", e);
            stopGuardNow();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_GUARD.equals(intent.getAction())) {
            stopGuardNow();
            return START_NOT_STICKY;
        }
        if (destroying || !KeepAliveService.isGuardEligible(this)) {
            stopGuardNow();
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
            bindMainProcess();
        } catch (Exception e) {
            RunLog.e(this, "更新独立守护通知失败", e);
            stopGuardNow();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (KeepAliveService.isGuardEligible(this)) {
            RunLog.i(this, "守护进程检测到最近任务被划走，安排快速恢复检查");
            WatchdogScheduler.scheduleQuickRecovery(this);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        destroying = true;
        handler.removeCallbacksAndMessages(null);
        clearMainBinding();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return localBinder;
    }

    private void bindMainProcess() {
        if (mainBindingRegistered || destroying || !KeepAliveService.isGuardEligible(this)) {
            return;
        }
        try {
            Intent intent = new Intent(this, KeepAliveService.class);
            mainBindingRegistered = bindService(intent, mainConnection, BIND_AUTO_CREATE | BIND_IMPORTANT);
            if (!mainBindingRegistered) {
                recoverMainProcess("绑定主常驻服务失败");
            }
        } catch (Exception e) {
            RunLog.e(this, "绑定主常驻服务异常", e);
            recoverMainProcess("绑定主常驻服务异常");
        }
    }

    private void recoverMainProcess(String reason) {
        if (destroying || !KeepAliveService.isGuardEligible(this)) {
            return;
        }
        RunLog.i(this, reason + "，将在 1.5 秒后恢复主进程");
        handler.postDelayed(() -> {
            if (!destroying && KeepAliveService.isGuardEligible(this)) {
                KeepAliveService.sync(this);
                bindMainProcess();
            }
        }, RESTART_DELAY_MILLIS);
    }

    private void clearMainBinding() {
        if (mainBindingRegistered) {
            try {
                unbindService(mainConnection);
            } catch (Exception ignored) {
            }
        }
        mainBindingRegistered = false;
        mainBound = false;
    }

    private void stopGuardNow() {
        destroying = true;
        handler.removeCallbacksAndMessages(null);
        clearMainBinding();
        stopForeground(true);
        stopSelf();
    }

    private Notification buildNotification() {
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                3,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                pendingIntentCreatorOptions()
        );
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle("定时守护运行中")
                .setContentText("独立进程正在保护定时任务")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            throw new IllegalStateException("NotificationManager unavailable");
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "后台守护",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("独立进程保护定时任务不因普通内存回收而丢失");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private android.os.Bundle pendingIntentCreatorOptions() {
        if (Build.VERSION.SDK_INT < 34) {
            return null;
        }
        ActivityOptions options = ActivityOptions.makeBasic();
        if (Build.VERSION.SDK_INT >= 36) {
            options.setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            );
        } else {
            options.setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            );
        }
        return options.toBundle();
    }
}
