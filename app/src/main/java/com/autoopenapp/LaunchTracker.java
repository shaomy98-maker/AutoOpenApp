package com.autoopenapp;

import android.content.Context;
import android.content.SharedPreferences;

final class LaunchTracker {
    private static final String PREFS = "auto_open_launch";
    private static final String KEY_SUCCESS_VALUE = "success_value";
    private static final String KEY_SUCCESS_AT = "success_at";
    private static final String KEY_DISPATCH_VALUE = "dispatch_value";
    private static final String KEY_DISPATCH_PACKAGE = "dispatch_package";
    private static final String KEY_DISPATCH_AT = "dispatch_at";

    static final int MAX_ATTEMPTS = 6;
    static final long RETRY_DELAY_MILLIS = 20_000L;
    private static final long SUCCESS_WINDOW_MILLIS = 5 * 60_000L;

    private LaunchTracker() {
    }

    static void markDispatched(Context context, String alarmValue, String packageName) {
        prefs(context).edit()
                .putString(KEY_DISPATCH_VALUE, alarmValue == null ? "" : alarmValue)
                .putString(KEY_DISPATCH_PACKAGE, packageName == null ? "" : packageName)
                .putLong(KEY_DISPATCH_AT, System.currentTimeMillis())
                .apply();
    }

    static void markSuccess(Context context, String alarmValue) {
        prefs(context).edit()
                .putString(KEY_SUCCESS_VALUE, alarmValue == null ? "" : alarmValue)
                .putLong(KEY_SUCCESS_AT, System.currentTimeMillis())
                .apply();
    }

    static boolean recentlySucceeded(Context context, String alarmValue) {
        SharedPreferences prefs = prefs(context);
        String stored = prefs.getString(KEY_SUCCESS_VALUE, "");
        long at = prefs.getLong(KEY_SUCCESS_AT, 0L);
        String target = alarmValue == null ? "" : alarmValue;
        return target.equals(stored) && (System.currentTimeMillis() - at) <= SUCCESS_WINDOW_MILLIS;
    }

    static boolean targetObservedAfterDispatch(Context context, String alarmValue, String packageName) {
        SharedPreferences prefs = prefs(context);
        String expectedValue = alarmValue == null ? "" : alarmValue;
        String expectedPackage = packageName == null ? "" : packageName;
        if (!expectedValue.equals(prefs.getString(KEY_DISPATCH_VALUE, ""))
                || !expectedPackage.equals(prefs.getString(KEY_DISPATCH_PACKAGE, ""))) {
            return false;
        }
        long dispatchedAt = prefs.getLong(KEY_DISPATCH_AT, 0L);
        return dispatchedAt > 0L
                && ForegroundAppVerifier.isTargetForegroundSince(context, expectedPackage, dispatchedAt);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
