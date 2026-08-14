package com.autoopenapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class TargetLauncher {
    static final String CHANNEL_ID = "launch_alerts";
    private static final int NOTIFICATION_ID = 2001;
    private static final String MANUAL_NOTIFICATION_TAG = "manual_launch";
    private static final Object LAUNCH_LOCK = new Object();
    private static final long DISPATCH_DEDUP_WINDOW_MILLIS = 5_000L;
    private static final Map<String, Long> RECENT_DISPATCHES = new HashMap<>();

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
        String alarmValue = alarmTime == null ? "" : alarmTime;
        Intent intent = new Intent(context, AlarmAlertActivity.class);
        intent.putExtra(ScheduleConfig.EXTRA_ALARM_TIME, alarmValue);
        // data 参与 PendingIntent identity；即使共用 requestCode，不同任务也不会互相覆盖。
        intent.setData(new Uri.Builder()
                .scheme("autoopenapp")
                .authority("alarm")
                .appendPath(TextUtils.isEmpty(alarmValue) ? MANUAL_NOTIFICATION_TAG : alarmValue)
                .build());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    static boolean launch(Context context, ScheduleConfig config) {
        return launch(context, config, "");
    }

    static boolean launch(Context context, ScheduleConfig config, String alarmTime) {
        String alarmValue = alarmTime == null ? "" : alarmTime;
        synchronized (LAUNCH_LOCK) {
            if (!TextUtils.isEmpty(alarmValue) && LaunchTracker.recentlySucceeded(context, alarmValue)) {
                RunLog.i(context, "同一任务最近已成功，跳过重复拉起 value=" + alarmValue);
                finishSuccessfulAlarm(context, alarmValue, false);
                return true;
            }

            if (!TextUtils.isEmpty(alarmValue)
                    && ForegroundAppVerifier.canVerify(context)
                    && LaunchTracker.targetObservedAfterDispatch(
                    context, alarmValue, config.packageName)) {
                RunLog.i(context, "已确认上一轮拉起后目标进入前台，跳过重复拉起 value=" + alarmValue);
                confirmObservedSuccess(context, alarmValue);
                return true;
            }

            if (!TextUtils.isEmpty(alarmValue) && wasRecentlyDispatched(alarmValue)) {
                RunLog.i(context, "同一任务的启动请求仍在处理中，跳过并行重复拉起 value=" + alarmValue);
                return true;
            }

            List<Intent> intents = buildTargetIntents(context, config);
            for (int i = 0; i < intents.size(); i++) {
                try {
                    context.startActivity(intents.get(i));
                    RunLog.i(context, "目标启动请求已被系统接受，第 " + (i + 1) + " 种方式，package=" + config.packageName);
                    if (!TextUtils.isEmpty(alarmValue)) {
                        RECENT_DISPATCHES.put(alarmValue, SystemClock.elapsedRealtime());
                        LaunchTracker.markDispatched(context, alarmValue, config.packageName);
                        if (ForegroundAppVerifier.canVerify(context)) {
                            RunLog.i(context, "等待使用情况权限验证目标确实进入前台，保留本任务补偿重试");
                        } else {
                            RunLog.i(context, "未开启使用情况访问，按启动请求已接受处理；建议开启前台验证");
                            finishSuccessfulAlarm(context, alarmValue, true);
                        }
                    } else {
                        // 手动测试使用独立通知 ID，不得影响任何真实任务的重试或配置。
                        cancelReminder(context, alarmValue);
                    }
                    return true;
                } catch (Exception e) {
                    RunLog.e(context, "目标启动失败，第 " + (i + 1) + " 种方式，package=" + config.packageName, e);
                }
            }
            showLaunchNotification(context, config, alarmValue);
            return false;
        }
    }

    static void fireLaunch(Context context, String alarmTime) {
        ensureChannel(context);
        ScheduleConfig config = ScheduleStore.load(context);
        if (!config.isRunnable()) {
            RunLog.i(context, "fireLaunch 配置无效，已跳过");
            return;
        }
        // 通知既是锁屏时的全屏入口，也是任何自动拉起失败后的用户兜底。
        showAlarmAlertNotification(context, alarmTime);

        KeyguardManager keyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean locked = keyguardManager != null && keyguardManager.isKeyguardLocked();
        boolean interactive = powerManager == null || powerManager.isInteractive();
        if (!locked && interactive) {
            // 已解锁时只走可见悬浮窗，不再并行直启中转页，避免同一任务重复打开。
            OverlayLaunchService.start(context, alarmTime);
        } else {
            RunLog.i(context, "设备锁屏或熄屏，等待全屏提醒处理 value=" + alarmTime);
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
        showLaunchNotification(context, config, "");
    }

    static void showLaunchNotification(Context context, ScheduleConfig config, String alarmTime) {
        ensureChannel(context);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                4100,
                buildAlarmAlertIntent(context, alarmTime),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                pendingIntentCreatorOptions()
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
        manager.notify(notificationTagFor(alarmTime), NOTIFICATION_ID, notification);
    }

    static void showAlarmAlertNotification(Context context, String alarmTime) {
        ensureChannel(context);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                4200,
                buildAlarmAlertIntent(context, alarmTime),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                pendingIntentCreatorOptions()
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
        manager.notify(notificationTagFor(alarmTime), NOTIFICATION_ID, notification);
        RunLog.i(context, "已发送全屏提醒通知");
    }

    static void confirmObservedSuccess(Context context, String alarmTime) {
        String alarmValue = alarmTime == null ? "" : alarmTime;
        if (TextUtils.isEmpty(alarmValue)) {
            RunLog.i(context, "忽略空任务值的成功确认，避免影响真实重试");
            return;
        }
        synchronized (LAUNCH_LOCK) {
            finishSuccessfulAlarm(context, alarmValue, true);
        }
    }

    private static void finishSuccessfulAlarm(Context context, String alarmValue, boolean markSuccess) {
        RECENT_DISPATCHES.remove(alarmValue);
        if (markSuccess) {
            LaunchTracker.markSuccess(context, alarmValue);
        }
        AlarmScheduler.cancelRetry(context, alarmValue);
        boolean changed = ScheduleStore.completeAfterSuccess(context, alarmValue);
        cancelReminder(context, alarmValue);
        if (changed) {
            RunLog.i(context, "成功后任务配置已更新，重新排程 value=" + alarmValue);
            AlarmScheduler.reschedule(context);
        }
    }

    private static boolean wasRecentlyDispatched(String alarmValue) {
        long now = SystemClock.elapsedRealtime();
        Iterator<Map.Entry<String, Long>> iterator = RECENT_DISPATCHES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (now - entry.getValue() > DISPATCH_DEDUP_WINDOW_MILLIS) {
                iterator.remove();
            }
        }
        Long dispatchedAt = RECENT_DISPATCHES.get(alarmValue);
        return dispatchedAt != null && now - dispatchedAt <= DISPATCH_DEDUP_WINDOW_MILLIS;
    }

    private static void cancelReminder(Context context, String alarmTime) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.cancel(notificationTagFor(alarmTime), NOTIFICATION_ID);
    }

    private static String notificationTagFor(String alarmTime) {
        return TextUtils.isEmpty(alarmTime)
                ? MANUAL_NOTIFICATION_TAG
                : "scheduled_launch:" + alarmTime;
    }

    @SuppressWarnings("deprecation")
    private static Bundle pendingIntentCreatorOptions() {
        ActivityOptions options = ActivityOptions.makeBasic();
        if (Build.VERSION.SDK_INT >= 36) {
            options.setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS);
        } else if (Build.VERSION.SDK_INT >= 34) {
            options.setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        }
        return options.toBundle();
    }
}
