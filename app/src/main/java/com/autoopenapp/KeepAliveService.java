package com.autoopenapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class KeepAliveService extends Service {
    private static final String ACTION_STOP_GUARD = "com.autoopenapp.action.STOP_MAIN_GUARD";
    private static final String CHANNEL_ID = "keep_alive_v3";
    private static final String[] LEGACY_CHANNEL_IDS = {"keep_alive", "keep_alive_v2"};
    private static final int NOTIFICATION_ID = 1001;
    private static final long REBIND_DELAY_MILLIS = 1_500L;
    private final IBinder localBinder = new Binder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean rescueBound;
    private boolean rescueBindingRegistered;
    private boolean destroying;
    private final ServiceConnection rescueConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            rescueBound = true;
            RunLog.i(KeepAliveService.this, "独立守护进程已连接");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            rescueBound = false;
            recoverRescueProcess("守护进程连接断开");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            rescueBound = false;
            clearRescueBinding();
            recoverRescueProcess("守护进程 Binder 死亡");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            rescueBound = false;
            clearRescueBinding();
            recoverRescueProcess("守护进程未返回 Binder");
        }
    };

    static void start(Context context) {
        Intent intent = new Intent(context, KeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            RunLog.e(context, "常驻前台服务启动失败", e);
        }
    }

    static void stop(Context context) {
        deliverStop(context, new Intent(context, KeepAliveService.class).setAction(ACTION_STOP_GUARD));
        RescueService.stop(context);
        WatchdogScheduler.cancel(context);
        HealthJobScheduler.schedule(context);
    }

    private static void deliverStop(Context context, Intent intent) {
        try {
            context.startService(intent);
        } catch (Exception e) {
            context.stopService(intent);
            RunLog.e(context, "常驻服务停止指令发送失败，已直接停止", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        RunLog.i(this, "常驻前台服务已创建");
        if (ScheduleStore.load(this).enabled) {
            RescueService.start(this);
            bindRescueProcess();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if ((intent != null && ACTION_STOP_GUARD.equals(intent.getAction()))
                || !ScheduleStore.load(this).enabled) {
            stopGuardNow();
            return START_NOT_STICKY;
        }
        AlarmScheduler.reschedule(this);
        WatchdogScheduler.schedule(this);
        HealthJobScheduler.schedule(this);
        RescueService.start(this);
        bindRescueProcess();
        startForeground(NOTIFICATION_ID, buildNotification());
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        RunLog.i(this, "最近任务被划走，已安排快速恢复常驻服务");
        WatchdogScheduler.scheduleQuickRecovery(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onTrimMemory(int level) {
        if (level >= TRIM_MEMORY_COMPLETE) {
            RunLog.i(this, "系统内存回收压力较高，已安排进程恢复巡检 level=" + level);
            WatchdogScheduler.scheduleQuickRecovery(this);
        }
        super.onTrimMemory(level);
    }

    @Override
    public void onDestroy() {
        destroying = true;
        handler.removeCallbacksAndMessages(null);
        clearRescueBinding();
        if (ScheduleStore.load(this).enabled) {
            RunLog.i(this, "常驻服务被销毁，已安排快速恢复");
            WatchdogScheduler.scheduleQuickRecovery(this);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return localBinder;
    }

    private void bindRescueProcess() {
        if (rescueBindingRegistered || destroying || !ScheduleStore.load(this).enabled) {
            return;
        }
        try {
            Intent intent = new Intent(this, RescueService.class);
            rescueBindingRegistered = bindService(intent, rescueConnection, BIND_AUTO_CREATE | BIND_IMPORTANT);
            if (!rescueBindingRegistered) {
                recoverRescueProcess("绑定独立守护进程失败");
            }
        } catch (Exception e) {
            RunLog.e(this, "绑定独立守护进程异常", e);
            recoverRescueProcess("绑定独立守护进程异常");
        }
    }

    private void recoverRescueProcess(String reason) {
        if (destroying || !ScheduleStore.load(this).enabled) {
            return;
        }
        RunLog.i(this, reason + "，将在 1.5 秒后恢复");
        handler.postDelayed(() -> {
            if (!destroying && ScheduleStore.load(this).enabled) {
                RescueService.start(this);
                bindRescueProcess();
            }
        }, REBIND_DELAY_MILLIS);
    }

    private void clearRescueBinding() {
        if (rescueBindingRegistered) {
            try {
                unbindService(rescueConnection);
            } catch (Exception ignored) {
            }
        }
        rescueBindingRegistered = false;
        rescueBound = false;
    }

    private void stopGuardNow() {
        destroying = true;
        handler.removeCallbacksAndMessages(null);
        clearRescueBinding();
        stopForeground(true);
        stopSelf();
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        ScheduleConfig config = ScheduleStore.load(this);
        long next = AlarmScheduler.nextTriggerMillis(this);
        String title;
        if (!config.isRunnable()) {
            title = "定时未启用";
        } else if (next > 0) {
            title = "下次 " + new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(next));
        } else {
            title = "暂无可执行时间";
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle(title)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            for (String legacy : LEGACY_CHANNEL_IDS) {
                manager.deleteNotificationChannel(legacy);
            }
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "定时状态",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("显示定时任务的下次触发时间");
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
    }
}
