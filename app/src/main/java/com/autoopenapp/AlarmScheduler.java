package com.autoopenapp;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;
import java.util.Locale;

final class AlarmScheduler {
    static final String EXTRA_RETRY = "retry";
    static final String EXTRA_ATTEMPT = "attempt";
    private static final int RETRY_REQUEST_CODE = 9000;

    private AlarmScheduler() {
    }

    static void reschedule(Context context) {
        cancelAll(context);
        ScheduleConfig config = ScheduleStore.load(context);
        if (!config.isRunnable()) {
            RunLog.i(context, "未安排闹钟：配置未启用或缺少包名/时间");
            return;
        }
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            // setAlarmClock 不需要 SCHEDULE_EXACT_ALARM 权限，仍可准时触发，这里只提示不再中止排程
            RunLog.i(context, "缺少精确闹钟权限，使用 setAlarmClock 继续排程；建议在权限设置中授予以提升可靠性");
        }
        int scheduledCount = 0;
        for (String dateTime : config.datedTimes) {
            long triggerAt = ScheduleConfig.parseDateTimeMillis(dateTime);
            if (triggerAt <= System.currentTimeMillis()) {
                RunLog.i(context, "跳过已过期指定日期时间 " + dateTime);
                continue;
            }
            scheduleOne(context, alarmManager, dateTime, triggerAt, "已安排指定日期闹钟 ");
            scheduledCount++;
        }
        for (String time : config.allTimes()) {
            if (!ScheduleConfig.isAllowedTriggerTime(time)) {
                RunLog.i(context, "跳过非法时间 " + time + "，" + ScheduleConfig.allowedTimeDescription());
                continue;
            }
            long triggerAt = nextTriggerMillis(time, config.workdaysOnly);
            scheduleOne(context, alarmManager, time, triggerAt, "已安排闹钟 ");
            scheduledCount++;
        }
        if (scheduledCount == 0) {
            RunLog.i(context, "未安排闹钟：没有未来指定日期时间，且每日时间都不在允许时间段，" + ScheduleConfig.allowedTimeDescription());
        }
    }

    static void cancelAll(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        for (int hour = 0; hour < 24; hour++) {
            for (int minute = 0; minute < 60; minute++) {
                cancelOne(context, alarmManager, String.format(java.util.Locale.US, "%02d:%02d", hour, minute));
            }
        }
        ScheduleConfig config = ScheduleStore.load(context);
        for (String dateTime : config.datedTimes) {
            cancelOne(context, alarmManager, dateTime);
        }
    }

    private static void cancelOne(Context context, AlarmManager alarmManager, String time) {
            Intent intent = new Intent(context, AlarmReceiver.class);
            PendingIntent operation = PendingIntent.getBroadcast(
                    context,
                    requestCodeFor(time),
                    intent,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
            );
            if (operation != null) {
                alarmManager.cancel(operation);
                operation.cancel();
            }
    }

    static void cancelValue(Context context, String value) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        cancelOne(context, alarmManager, value);
    }

    static void scheduleRetry(Context context, String alarmValue, int nextAttempt) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.putExtra(ScheduleConfig.EXTRA_ALARM_TIME, alarmValue);
        intent.putExtra(EXTRA_RETRY, true);
        intent.putExtra(EXTRA_ATTEMPT, nextAttempt);
        PendingIntent operation = PendingIntent.getBroadcast(
                context,
                RETRY_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent showIntent = PendingIntent.getActivity(
                context,
                8900,
                TargetLauncher.buildAlarmAlertIntent(context, alarmValue),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        long triggerAt = System.currentTimeMillis() + LaunchTracker.RETRY_DELAY_MILLIS;
        alarmManager.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAt, showIntent), operation);
        RunLog.i(context, "已安排补偿重试 attempt=" + nextAttempt + " value=" + alarmValue + " triggerAt=" + triggerAt);
    }

    static void cancelRetry(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, AlarmReceiver.class);
        PendingIntent operation = PendingIntent.getBroadcast(
                context,
                RETRY_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        if (operation != null) {
            alarmManager.cancel(operation);
            operation.cancel();
        }
    }

    static long nextTriggerMillis(Context context) {
        ScheduleConfig config = ScheduleStore.load(context);
        if (!config.isRunnable()) {
            return -1L;
        }
        long now = System.currentTimeMillis();
        long best = Long.MAX_VALUE;
        for (String dateTime : config.datedTimes) {
            long triggerAt = ScheduleConfig.parseDateTimeMillis(dateTime);
            if (triggerAt > now && triggerAt < best) {
                best = triggerAt;
            }
        }
        for (String time : config.allTimes()) {
            if (!ScheduleConfig.isAllowedTriggerTime(time)) {
                continue;
            }
            long triggerAt = nextTriggerMillis(time, config.workdaysOnly);
            if (triggerAt < best) {
                best = triggerAt;
            }
        }
        return best == Long.MAX_VALUE ? -1L : best;
    }

    private static long nextTriggerMillis(String hhmm, boolean workdaysOnly) {
        int hour = Integer.parseInt(hhmm.substring(0, 2));
        int minute = Integer.parseInt(hhmm.substring(3, 5));
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        while (calendar.getTimeInMillis() <= System.currentTimeMillis() || (workdaysOnly && !isWorkday(calendar))) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }
        return calendar.getTimeInMillis();
    }

    static boolean shouldRunToday(ScheduleConfig config) {
        return !config.workdaysOnly || isWorkday(Calendar.getInstance());
    }

    static boolean shouldRunNow() {
        Calendar calendar = Calendar.getInstance();
        String time = String.format(Locale.US, "%02d:%02d",
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE));
        return ScheduleConfig.isAllowedTriggerTime(time);
    }

    static boolean shouldBypassTimeLimits(String alarmTime) {
        return ScheduleConfig.isValidDateTime(alarmTime);
    }

    private static void scheduleOne(Context context, AlarmManager alarmManager, String alarmValue, long triggerAt, String prefix) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.putExtra(ScheduleConfig.EXTRA_ALARM_TIME, alarmValue);
        PendingIntent operation = PendingIntent.getBroadcast(
                context,
                requestCodeFor(alarmValue),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent showIntent = PendingIntent.getActivity(
                context,
                8000 + requestCodeFor(alarmValue),
                TargetLauncher.buildAlarmAlertIntent(context, alarmValue),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        AlarmManager.AlarmClockInfo info = new AlarmManager.AlarmClockInfo(triggerAt, showIntent);
        alarmManager.setAlarmClock(info, operation);
        RunLog.i(context, prefix + alarmValue + " triggerAt=" + triggerAt);
    }

    private static boolean isWorkday(Calendar calendar) {
        int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
        return dayOfWeek != Calendar.SATURDAY && dayOfWeek != Calendar.SUNDAY;
    }

    private static int requestCodeFor(String time) {
        if (ScheduleConfig.isValidTime(time)) {
            return 1000 + Integer.parseInt(time.substring(0, 2)) * 60 + Integer.parseInt(time.substring(3, 5));
        }
        return 4000 + Math.abs(time.hashCode() % 50000);
    }
}
