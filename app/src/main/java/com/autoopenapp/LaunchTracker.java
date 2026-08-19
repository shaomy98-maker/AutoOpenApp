package com.autoopenapp;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.Map;

final class LaunchTracker {
    private static final String PREFS = "auto_open_launch";
    private static final String KEY_SUCCESS_AT_PREFIX = "success_at_v2:";
    private static final String KEY_DISPATCH_PACKAGE_PREFIX = "dispatch_package_v2:";
    private static final String KEY_DISPATCH_AT_PREFIX = "dispatch_at_v2:";

    static final int MAX_ATTEMPTS = 6;
    static final long RETRY_DELAY_MILLIS = 20_000L;
    private static final long SUCCESS_WINDOW_MILLIS = 5 * 60_000L;

    private LaunchTracker() {
    }

    static void markDispatched(Context context, String alarmValue, String packageName) {
        String scope = scope(alarmValue);
        prefs(context).edit()
                .putString(KEY_DISPATCH_PACKAGE_PREFIX + scope,
                        packageName == null ? "" : packageName)
                .putLong(KEY_DISPATCH_AT_PREFIX + scope, System.currentTimeMillis())
                .apply();
    }

    static void markSuccess(Context context, String alarmValue) {
        String scope = scope(alarmValue);
        prefs(context).edit()
                .putLong(KEY_SUCCESS_AT_PREFIX + scope, System.currentTimeMillis())
                .remove(KEY_DISPATCH_PACKAGE_PREFIX + scope)
                .remove(KEY_DISPATCH_AT_PREFIX + scope)
                .apply();
    }

    static boolean recentlySucceeded(Context context, String alarmValue) {
        long at = prefs(context).getLong(KEY_SUCCESS_AT_PREFIX + scope(alarmValue), 0L);
        long delta = System.currentTimeMillis() - at;
        return at > 0L && delta >= 0L && delta <= SUCCESS_WINDOW_MILLIS;
    }

    static long successAt(Context context, String alarmValue) {
        return prefs(context).getLong(KEY_SUCCESS_AT_PREFIX + scope(alarmValue), 0L);
    }

    static String latestMorningSuccessToday(Context context, Calendar today) {
        String latestValue = "";
        long latestAt = 0L;
        for (Map.Entry<String, ?> entry : prefs(context).getAll().entrySet()) {
            if (!entry.getKey().startsWith(KEY_SUCCESS_AT_PREFIX)
                    || !(entry.getValue() instanceof Long)) {
                continue;
            }
            String value = alarmValueFromSuccessKey(entry.getKey());
            if (!ScheduleConfig.isValidTime(value)
                    || Integer.parseInt(value.substring(0, 2)) >= 12) {
                continue;
            }
            long at = (Long) entry.getValue();
            if (isSameDay(at, today) && at > latestAt) {
                latestAt = at;
                latestValue = value;
            }
        }
        return latestValue;
    }

    static boolean targetObservedAfterDispatch(
            Context context,
            String alarmValue,
            String packageName
    ) {
        SharedPreferences preferences = prefs(context);
        String scope = scope(alarmValue);
        String expectedPackage = packageName == null ? "" : packageName;
        if (!expectedPackage.equals(
                preferences.getString(KEY_DISPATCH_PACKAGE_PREFIX + scope, ""))) {
            return false;
        }
        long dispatchedAt = preferences.getLong(KEY_DISPATCH_AT_PREFIX + scope, 0L);
        long delta = System.currentTimeMillis() - dispatchedAt;
        return dispatchedAt > 0L && delta >= 0L
                && ForegroundAppVerifier.isTargetForegroundSince(
                        context,
                        expectedPackage,
                        dispatchedAt
                );
    }

    private static String scope(String alarmValue) {
        String value = alarmValue == null ? "" : alarmValue;
        // Length makes the boundary explicit and SharedPreferences keys safely accept the value.
        return value.length() + ":" + value;
    }

    private static String alarmValueFromSuccessKey(String key) {
        String scoped = key.substring(KEY_SUCCESS_AT_PREFIX.length());
        int colon = scoped.indexOf(':');
        return colon < 0 ? "" : scoped.substring(colon + 1);
    }

    private static boolean isSameDay(long millis, Calendar day) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        return calendar.get(Calendar.YEAR) == day.get(Calendar.YEAR)
                && calendar.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
