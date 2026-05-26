package com.autoopenapp;

import android.content.Context;
import android.content.SharedPreferences;

final class LaunchTracker {
    private static final String PREFS = "auto_open_launch";
    private static final String KEY_SUCCESS_VALUE = "success_value";
    private static final String KEY_SUCCESS_AT = "success_at";

    static final int MAX_ATTEMPTS = 6;
    static final long RETRY_DELAY_MILLIS = 20_000L;
    private static final long SUCCESS_WINDOW_MILLIS = 5 * 60_000L;

    private LaunchTracker() {
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

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
