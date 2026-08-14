package com.autoopenapp;

import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class AlarmScheduler {
    static final String EXTRA_RETRY = "retry";
    static final String EXTRA_ATTEMPT = "attempt";

    private static final String ACTION_MAIN_ALARM = "com.autoopenapp.action.MAIN_ALARM";
    private static final String ACTION_RETRY_ALARM = "com.autoopenapp.action.RETRY_ALARM";
    private static final int MAIN_REQUEST_CODE = 7100;
    private static final int MAIN_SHOW_REQUEST_CODE = 7101;
    // Kept at the old value so migration can explicitly distinguish old/no-data and new/data PIs.
    private static final int RETRY_REQUEST_CODE = 9000;
    private static final int RETRY_SHOW_REQUEST_CODE = 8900;

    private static final String PREFS = "auto_open_alarm_scheduler";
    private static final String KEY_ACTIVE_MAIN_VALUES = "active_main_values_v2";
    private static final String KEY_ACTIVE_RETRY_VALUES = "active_retry_values_v2";
    private static final String KEY_LEGACY_CLEANED = "legacy_pending_intents_cleaned_v2";

    private AlarmScheduler() {
    }

    static synchronized void reschedule(Context context) {
        ScheduleConfig config = ScheduleStore.load(context);
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            RunLog.i(context, "未安排闹钟：AlarmManager 不可用");
            return;
        }

        // Check before cancelling the currently installed alarms. If exact access changes while
        // scheduling, setWakeupAlarm still catches the race and falls back safely.
        boolean exactAllowed = canScheduleExactAlarms(context, alarmManager);
        if (!config.isRunnable()) {
            cancelAllInternal(context, alarmManager, config);
            cancelAllRetries(context);
            RunLog.i(context, "未安排闹钟：配置未启用或缺少包名/时间");
            return;
        }
        if (!exactAllowed) {
            // Preserve any alarms still owned by the OS. Permission/recovery components will
            // invoke reschedule again after exact-alarm access becomes available.
            RunLog.i(context, "缺少精确闹钟权限，保留现有排程并停止本次重排");
            return;
        }

        cancelAllInternal(context, alarmManager, config);
        int scheduledCount = 0;
        long now = System.currentTimeMillis();
        for (String dateTime : config.datedTimes) {
            long triggerAt = ScheduleConfig.parseDateTimeMillis(dateTime);
            if (triggerAt <= now) {
                RunLog.i(context, "跳过已过期指定日期时间 " + dateTime);
                continue;
            }
            if (scheduleOne(context, alarmManager, dateTime, triggerAt,
                    "已安排指定日期闹钟 ", exactAllowed)) {
                scheduledCount++;
            }
        }
        for (String time : config.allTimes()) {
            if (!ScheduleConfig.isAllowedTriggerTime(time)) {
                RunLog.i(context, "跳过非法时间 " + time + "，" + ScheduleConfig.allowedTimeDescription());
                continue;
            }
            long triggerAt = nextTriggerMillis(time, config.workdaysOnly);
            if (scheduleOne(context, alarmManager, time, triggerAt, "已安排闹钟 ", exactAllowed)) {
                scheduledCount++;
            }
        }
        if (scheduledCount == 0) {
            RunLog.i(context, "未安排闹钟：没有未来指定日期时间，且每日时间都不在允许时间段，"
                    + ScheduleConfig.allowedTimeDescription());
        }
    }

    static synchronized void cancelAll(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        cancelAllInternal(context, alarmManager, ScheduleStore.load(context));
    }

    private static void cancelAllInternal(
            Context context,
            AlarmManager alarmManager,
            ScheduleConfig config
    ) {
        for (String alarmValue : activeValues(context, KEY_ACTIVE_MAIN_VALUES)) {
            cancelMainPendingIntents(context, alarmManager, alarmValue);
        }
        clearActiveValues(context, KEY_ACTIVE_MAIN_VALUES);
        cleanupLegacyPendingIntentsOnce(context, alarmManager, config);
    }

    static synchronized void cancelValue(Context context, String value) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        cancelMainPendingIntents(context, alarmManager, value);
        // This also handles a value scheduled by an older app version before migration ran.
        cancelLegacyMainPendingIntents(context, alarmManager, value);
        removeActiveValue(context, KEY_ACTIVE_MAIN_VALUES, value);
    }

    static synchronized void scheduleRetry(Context context, String alarmValue, int nextAttempt) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            RunLog.i(context, "无法安排补偿重试：AlarmManager 不可用");
            return;
        }
        if (!canScheduleExactAlarms(context, alarmManager)) {
            RunLog.i(context, "缺少精确闹钟权限，不安排补偿重试 value=" + alarmValue);
            return;
        }

        PendingIntent operation = null;
        PendingIntent showIntent = null;
        try {
            operation = retryOperation(context, alarmValue, nextAttempt,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            showIntent = retryShowIntent(context, alarmValue,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long triggerAt = System.currentTimeMillis() + LaunchTracker.RETRY_DELAY_MILLIS;
            if (!setWakeupAlarm(context, alarmManager, triggerAt, showIntent, operation,
                    "补偿重试", true)) {
                cancelCreatedPendingIntents(alarmManager, operation, showIntent);
                return;
            }
            if (!addActiveValue(context, KEY_ACTIVE_RETRY_VALUES, alarmValue)) {
                cancelCreatedPendingIntents(alarmManager, operation, showIntent);
                RunLog.i(context, "补偿重试注册状态保存失败，已撤销该重试 value=" + alarmValue);
                return;
            }
            RunLog.i(context, "已安排补偿重试 attempt=" + nextAttempt
                    + " value=" + alarmValue + " triggerAt=" + triggerAt);
        } catch (SecurityException e) {
            cancelCreatedPendingIntents(alarmManager, operation, showIntent);
            RunLog.e(context, "补偿重试安排被系统拒绝 value=" + alarmValue, e);
        } catch (RuntimeException e) {
            cancelCreatedPendingIntents(alarmManager, operation, showIntent);
            RunLog.e(context, "补偿重试安排失败 value=" + alarmValue, e);
        }
    }

    static synchronized void cancelRetry(Context context, String alarmValue) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            RunLog.i(context, "无法取消补偿重试：AlarmManager 不可用 value=" + alarmValue);
            return;
        }
        cancelRetryPendingIntents(context, alarmManager, alarmValue);
        removeActiveValue(context, KEY_ACTIVE_RETRY_VALUES, alarmValue);
    }

    /** Compatibility overload for callers that intentionally want every retry removed. */
    static void cancelRetry(Context context) {
        cancelAllRetries(context);
    }

    static synchronized void cancelAllRetries(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            RunLog.i(context, "无法取消全部补偿重试：AlarmManager 不可用");
            return;
        }
        for (String alarmValue : activeValues(context, KEY_ACTIVE_RETRY_VALUES)) {
            cancelRetryPendingIntents(context, alarmManager, alarmValue);
        }
        // An upgrade may leave the old global retry alive; it has requestCode=9000 and no data.
        cancelLegacyRetryPendingIntent(context, alarmManager);
        clearActiveValues(context, KEY_ACTIVE_RETRY_VALUES);
    }

    /** Removes a consumed alarm from the persistent registry before receiver work begins. */
    static synchronized void onAlarmTriggered(Context context, String alarmValue, boolean retry) {
        removeActiveValue(context,
                retry ? KEY_ACTIVE_RETRY_VALUES : KEY_ACTIVE_MAIN_VALUES,
                alarmValue);
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
        while (calendar.getTimeInMillis() <= System.currentTimeMillis()
                || (workdaysOnly && !isWorkday(calendar))) {
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

    private static boolean scheduleOne(
            Context context,
            AlarmManager alarmManager,
            String alarmValue,
            long triggerAt,
            String prefix,
            boolean exactAllowed
    ) {
        PendingIntent operation = null;
        PendingIntent showIntent = null;
        try {
            operation = mainOperation(context, alarmValue,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            showIntent = mainShowIntent(context, alarmValue,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (!setWakeupAlarm(context, alarmManager, triggerAt, showIntent, operation,
                    alarmValue, exactAllowed)) {
                cancelCreatedPendingIntents(alarmManager, operation, showIntent);
                return false;
            }
            if (!addActiveValue(context, KEY_ACTIVE_MAIN_VALUES, alarmValue)) {
                cancelCreatedPendingIntents(alarmManager, operation, showIntent);
                RunLog.i(context, "闹钟注册状态保存失败，已撤销 value=" + alarmValue);
                return false;
            }
            RunLog.i(context, prefix + alarmValue + " triggerAt=" + triggerAt);
            return true;
        } catch (SecurityException e) {
            cancelCreatedPendingIntents(alarmManager, operation, showIntent);
            RunLog.e(context, "闹钟安排被系统拒绝 value=" + alarmValue, e);
            return false;
        } catch (RuntimeException e) {
            cancelCreatedPendingIntents(alarmManager, operation, showIntent);
            RunLog.e(context, "闹钟安排失败 value=" + alarmValue, e);
            return false;
        }
    }

    private static boolean setWakeupAlarm(
            Context context,
            AlarmManager alarmManager,
            long triggerAt,
            PendingIntent showIntent,
            PendingIntent operation,
            String label,
            boolean exactAllowed
    ) {
        if (!exactAllowed) {
            return false;
        }
        try {
            AlarmManager.AlarmClockInfo info = new AlarmManager.AlarmClockInfo(triggerAt, showIntent);
            alarmManager.setAlarmClock(info, operation);
            return true;
        } catch (SecurityException exactDenied) {
            RunLog.e(context, "精确闹钟权限在排程时发生变化，改用系统容错调度 label=" + label,
                    exactDenied);
        } catch (RuntimeException exactFailure) {
            RunLog.e(context, "精确闹钟安排失败，尝试系统容错调度 label=" + label,
                    exactFailure);
        }
        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation);
            RunLog.i(context, "已使用系统容错闹钟，可能被系统小幅延后 label=" + label);
            return true;
        } catch (RuntimeException fallbackFailure) {
            RunLog.e(context, "闹钟安排彻底失败 label=" + label, fallbackFailure);
            return false;
        }
    }

    private static boolean canScheduleExactAlarms(Context context, AlarmManager alarmManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        try {
            return alarmManager.canScheduleExactAlarms();
        } catch (RuntimeException e) {
            RunLog.e(context, "精确闹钟权限状态读取失败，停止本次排程", e);
            return false;
        }
    }

    private static PendingIntent mainOperation(Context context, String alarmValue, int flags) {
        Intent intent = new Intent(context, AlarmReceiver.class)
                .setAction(ACTION_MAIN_ALARM)
                .setData(Uri.parse(AlarmIdentity.mainOperation(alarmValue)))
                .putExtra(ScheduleConfig.EXTRA_ALARM_TIME, alarmValue);
        return PendingIntent.getBroadcast(context, MAIN_REQUEST_CODE, intent, flags);
    }

    private static PendingIntent mainShowIntent(Context context, String alarmValue, int flags) {
        Intent intent = TargetLauncher.buildAlarmAlertIntent(context, alarmValue)
                .setData(Uri.parse(AlarmIdentity.mainShow(alarmValue)));
        return PendingIntent.getActivity(
                context,
                MAIN_SHOW_REQUEST_CODE,
                intent,
                flags,
                pendingIntentCreatorOptions()
        );
    }

    private static PendingIntent retryOperation(
            Context context,
            String alarmValue,
            int attempt,
            int flags
    ) {
        Intent intent = new Intent(context, AlarmReceiver.class)
                .setAction(ACTION_RETRY_ALARM)
                .setData(Uri.parse(AlarmIdentity.retryOperation(alarmValue)))
                .putExtra(ScheduleConfig.EXTRA_ALARM_TIME, alarmValue)
                .putExtra(EXTRA_RETRY, true)
                .putExtra(EXTRA_ATTEMPT, attempt);
        return PendingIntent.getBroadcast(context, RETRY_REQUEST_CODE, intent, flags);
    }

    private static PendingIntent retryShowIntent(Context context, String alarmValue, int flags) {
        Intent intent = TargetLauncher.buildAlarmAlertIntent(context, alarmValue)
                .setData(Uri.parse(AlarmIdentity.retryShow(alarmValue)));
        return PendingIntent.getActivity(
                context,
                RETRY_SHOW_REQUEST_CODE,
                intent,
                flags,
                pendingIntentCreatorOptions()
        );
    }

    private static void cancelMainPendingIntents(
            Context context,
            AlarmManager alarmManager,
            String alarmValue
    ) {
        PendingIntent operation = mainOperation(context, alarmValue,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent showIntent = mainShowIntent(context, alarmValue,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        cancelCreatedPendingIntents(alarmManager, operation, showIntent);
    }

    private static void cancelRetryPendingIntents(
            Context context,
            AlarmManager alarmManager,
            String alarmValue
    ) {
        PendingIntent operation = retryOperation(context, alarmValue, 0,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent showIntent = retryShowIntent(context, alarmValue,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        cancelCreatedPendingIntents(alarmManager, operation, showIntent);
    }

    private static void cancelCreatedPendingIntents(
            AlarmManager alarmManager,
            PendingIntent operation,
            PendingIntent showIntent
    ) {
        if (operation != null) {
            alarmManager.cancel(operation);
            operation.cancel();
        }
        if (showIntent != null) {
            showIntent.cancel();
        }
    }

    private static void cleanupLegacyPendingIntentsOnce(
            Context context,
            AlarmManager alarmManager,
            ScheduleConfig config
    ) {
        SharedPreferences preferences = schedulerPreferences(context);
        if (preferences.getBoolean(KEY_LEGACY_CLEANED, false)) {
            return;
        }
        for (int hour = 0; hour < 24; hour++) {
            for (int minute = 0; minute < 60; minute++) {
                cancelLegacyMainPendingIntents(context, alarmManager,
                        String.format(Locale.US, "%02d:%02d", hour, minute));
            }
        }
        for (String dateTime : config.datedTimes) {
            cancelLegacyMainPendingIntents(context, alarmManager, dateTime);
        }
        cancelLegacyRetryPendingIntent(context, alarmManager);
        if (!preferences.edit().putBoolean(KEY_LEGACY_CLEANED, true).commit()) {
            RunLog.i(context, "旧版 PendingIntent 清理标记保存失败，下次将再次清理");
        }
    }

    private static void cancelLegacyMainPendingIntents(
            Context context,
            AlarmManager alarmManager,
            String alarmValue
    ) {
        Intent operationIntent = new Intent(context, AlarmReceiver.class);
        PendingIntent operation = PendingIntent.getBroadcast(
                context,
                legacyRequestCodeFor(alarmValue),
                operationIntent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        Intent showActivityIntent = TargetLauncher.buildAlarmAlertIntent(context, alarmValue)
                .setData(null);
        PendingIntent showIntent = PendingIntent.getActivity(
                context,
                8000 + legacyRequestCodeFor(alarmValue),
                showActivityIntent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        cancelCreatedPendingIntents(alarmManager, operation, showIntent);
    }

    private static void cancelLegacyRetryPendingIntent(Context context, AlarmManager alarmManager) {
        Intent operationIntent = new Intent(context, AlarmReceiver.class);
        PendingIntent operation = PendingIntent.getBroadcast(
                context,
                RETRY_REQUEST_CODE,
                operationIntent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent showIntent = PendingIntent.getActivity(
                context,
                RETRY_SHOW_REQUEST_CODE,
                TargetLauncher.buildAlarmAlertIntent(context, "").setData(null),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        cancelCreatedPendingIntents(alarmManager, operation, showIntent);
    }

    private static Set<String> activeValues(Context context, String key) {
        try {
            Set<String> stored = schedulerPreferences(context).getStringSet(key, null);
            return stored == null ? new HashSet<>() : new HashSet<>(stored);
        } catch (RuntimeException e) {
            RunLog.e(context, "闹钟注册状态读取失败 key=" + key, e);
            schedulerPreferences(context).edit().remove(key).commit();
            return new HashSet<>();
        }
    }

    private static boolean addActiveValue(Context context, String key, String alarmValue) {
        Set<String> values = activeValues(context, key);
        if (!values.add(normalizeValue(alarmValue))) {
            return true;
        }
        return schedulerPreferences(context).edit().putStringSet(key, values).commit();
    }

    private static void removeActiveValue(Context context, String key, String alarmValue) {
        Set<String> values = activeValues(context, key);
        if (!values.remove(normalizeValue(alarmValue))) {
            return;
        }
        SharedPreferences.Editor editor = schedulerPreferences(context).edit();
        if (values.isEmpty()) {
            editor.remove(key);
        } else {
            editor.putStringSet(key, values);
        }
        if (!editor.commit()) {
            RunLog.i(context, "闹钟注册状态移除失败 key=" + key + " value=" + alarmValue);
        }
    }

    private static void clearActiveValues(Context context, String key) {
        if (!schedulerPreferences(context).edit().remove(key).commit()) {
            RunLog.i(context, "闹钟注册状态清空失败 key=" + key);
        }
    }

    private static String normalizeValue(String alarmValue) {
        return alarmValue == null ? "" : alarmValue;
    }

    private static SharedPreferences schedulerPreferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

    private static boolean isWorkday(Calendar calendar) {
        int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
        return dayOfWeek != Calendar.SATURDAY && dayOfWeek != Calendar.SUNDAY;
    }

    private static int legacyRequestCodeFor(String time) {
        if (ScheduleConfig.isValidTime(time)) {
            return 1000 + Integer.parseInt(time.substring(0, 2)) * 60
                    + Integer.parseInt(time.substring(3, 5));
        }
        return 4000 + Math.abs(normalizeValue(time).hashCode() % 50000);
    }
}
