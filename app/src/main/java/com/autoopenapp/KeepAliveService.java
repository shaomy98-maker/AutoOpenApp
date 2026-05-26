package com.autoopenapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class KeepAliveService extends Service {
    private static final String CHANNEL_ID = "keep_alive_v3";
    private static final String[] LEGACY_CHANNEL_IDS = {"keep_alive", "keep_alive_v2"};
    private static final int NOTIFICATION_ID = 1001;

    static void start(Context context) {
        Intent intent = new Intent(context, KeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception ignored) {
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
        AlarmScheduler.reschedule(this);
        startForeground(NOTIFICATION_ID, buildNotification());
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
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
