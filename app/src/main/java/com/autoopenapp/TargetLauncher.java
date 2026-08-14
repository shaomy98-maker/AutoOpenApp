package com.autoopenapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

final class TargetLauncher {
    static final String CHANNEL_ID = "launch_alerts";
    private static final int NOTIFICATION_ID = 2001;

    private TargetLauncher() {
    }

    static Intent buildTargetIntent(Context context, ScheduleConfig config) {
        List<Intent> intents = buildTargetIntents(context, config);
        return intents.isEmpty() ? new Intent() : intents.get(0);
    }

    static List<Intent> buildTargetIntents(Context context, ScheduleConfig config) {
        ArrayList<Intent> intents = new ArrayList<>();
        Intent intent;
        if (!TextUtils.isEmpty(config.deepLink)) {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(config.deepLink));
            intent.setPackage(config.packageName);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intents.add(intent);
        } else if (!TextUtils.isEmpty(config.activityName)) {
            intent = new Intent();
            String className = config.activityName.startsWith(".")
                    ? config.packageName + config.activityName
                    : config.activityName;
            intent.setAction(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(new ComponentName(config.packageName, className));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intents.add(intent);
        }
        PackageManager packageManager = context.getPackageManager();
        Intent launcherIntent = packageManager.getLaunchIntentForPackage(config.packageName);
        if (launcherIntent != null) {
            launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intents.add(launcherIntent);
        }
        Intent packageIntent = new Intent(Intent.ACTION_MAIN);
        packageIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        packageIntent.setPackage(config.packageName);
        packageIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intents.add(packageIntent);
        return intents;
    }

    static Intent buildAlarmAlertIntent(Context context, String alarmTime) {
        Intent intent = new Intent(context, AlarmAlertActivity.class);
        intent.putExtra(ScheduleConfig.EXTRA_ALARM_TIME, alarmTime);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }

    static boolean launch(Context context, ScheduleConfig config) {
        return launch(context, config, "");
    }

    static boolean launch(Context context, ScheduleConfig config, String alarmTime) {
        List<Intent> intents = buildTargetIntents(context, config);
        for (int i = 0; i < intents.size(); i++) {
            try {
                context.startActivity(intents.get(i));
                RunLog.i(context, "目标启动请求已被系统接受，第 " + (i + 1) + " 种方式，package=" + config.packageName);
                LaunchTracker.markDispatched(context, alarmTime, config.packageName);
                if (ForegroundAppVerifier.canVerify(context)) {
                    RunLog.i(context, "等待使用情况权限验证目标确实进入前台，保留补偿重试");
                } else {
                    RunLog.i(context, "未开启使用情况访问，按启动请求已接受处理；建议在权限设置中开启前台验证");
                    LaunchTracker.markSuccess(context, alarmTime);
                    ScheduleStore.completeAfterSuccessfulLaunch(context, alarmTime);
                    AlarmScheduler.cancelRetry(context);
                }
                return true;
            } catch (Exception e) {
                RunLog.e(context, "目标启动失败，第 " + (i + 1) + " 种方式，package=" + config.packageName, e);
            }
        }
        showLaunchNotification(context, config);
        return false;
    }

    static void fireLaunch(Context context, String alarmTime) {
        ensureChannel(context);
        ScheduleConfig config = ScheduleStore.load(context);
        if (!config.isRunnable()) {
            RunLog.i(context, "fireLaunch 配置无效，已跳过");
            return;
        }
        // 锁屏/熄屏兜底：全屏通知，系统在锁屏时会自动全屏拉起中转页
        showAlarmAlertNotification(context, alarmTime);
        // 亮屏已解锁兜底：悬浮窗作为可见窗口豁免后台启动限制，直接打开目标
        OverlayLaunchService.start(context, alarmTime);
        // 闹钟刚触发的临时豁免窗口最新鲜，尽力直接拉起中转页
        try {
            context.startActivity(buildAlarmAlertIntent(context, alarmTime), backgroundStartOptions());
            RunLog.i(context, "已尝试直接启动中转页");
        } catch (Exception e) {
            RunLog.e(context, "直接启动中转页失败（后台限制），依赖通知/悬浮窗兜底", e);
        }
    }

    static void testLaunch(Context context, ScheduleConfig config) {
        boolean launched = launch(context, config);
        Toast.makeText(context, launched ? "已尝试打开目标应用" : "直接打开失败，已显示通知兜底", Toast.LENGTH_LONG).show();
    }

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "定时打开提醒",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("到达设定时间时打开目标应用或页面");
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    static void showLaunchNotification(Context context, ScheduleConfig config) {
        ensureChannel(context);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                4100,
                buildTargetIntent(context, config),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        Notification notification = builder
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle("到时间了")
                .setContentText("点击打开 " + config.packageName)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_MAX)
                .setCategory(Notification.CATEGORY_ALARM)
                .build();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
    }

    static void showAlarmAlertNotification(Context context, String alarmTime) {
        ensureChannel(context);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                4200,
                buildAlarmAlertIntent(context, alarmTime),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        Notification notification = builder
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle("定时打开应用")
                .setContentText("到时间了，正在准备打开目标应用")
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)
                .setAutoCancel(true)
                .setOngoing(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setPriority(Notification.PRIORITY_MAX)
                .setCategory(Notification.CATEGORY_ALARM)
                .build();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
        RunLog.i(context, "已发送全屏提醒通知");
    }

    private static android.os.Bundle backgroundStartOptions() {
        ActivityOptions options = ActivityOptions.makeBasic();
        if (Build.VERSION.SDK_INT >= 34) {
            options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            );
        }
        return options.toBundle();
    }
}
