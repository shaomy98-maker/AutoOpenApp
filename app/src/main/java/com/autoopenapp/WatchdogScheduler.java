package com.autoopenapp;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

final class WatchdogScheduler {
    private static final int REQUEST_CODE = 9801;
    private static final long WATCHDOG_INTERVAL_MILLIS = 15 * 60_000L;
    private static final long QUICK_RECOVERY_DELAY_MILLIS = 5_000L;

    private WatchdogScheduler() {
    }

    static void schedule(Context context) {
        scheduleAt(context, SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MILLIS, "后台巡检");
    }

    static void scheduleQuickRecovery(Context context) {
        scheduleAt(context, SystemClock.elapsedRealtime() + QUICK_RECOVERY_DELAY_MILLIS, "进程恢复");
    }

    static void cancel(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager != null) {
            PendingIntent operation = operation(context, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (operation != null) {
                manager.cancel(operation);
                operation.cancel();
            }
        }
    }

    private static void scheduleAt(Context context, long triggerAt, String reason) {
        if (!KeepAliveService.isGuardEligible(context)) {
            cancel(context);
            return;
        }
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            RunLog.i(context, "无法安排" + reason + "：AlarmManager 不可用");
            return;
        }
        PendingIntent operation = operation(context, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, operation);
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, operation);
            }
            RunLog.i(context, "已安排" + reason + " triggerElapsed=" + triggerAt);
        } catch (SecurityException e) {
            cancel(context);
            RunLog.e(context, reason + "调度权限已变化，已停止恢复链", e);
        } catch (RuntimeException e) {
            cancel(context);
            RunLog.e(context, reason + "安排失败，已取消本次恢复链", e);
        }
    }

    private static PendingIntent operation(Context context, int flags) {
        Intent intent = new Intent(context, WatchdogReceiver.class);
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }
}
