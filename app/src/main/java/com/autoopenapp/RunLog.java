package com.autoopenapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class RunLog {
    private static final String TAG = "AutoOpenApp";
    private static final String PREFS = "auto_open_logs";
    private static final String KEY_LAST = "last_log";
    private static final String KEY_HISTORY = "log_history";
    private static final int MAX_HISTORY_CHARS = 5000;

    private RunLog() {
    }

    static void i(Context context, String message) {
        write(context, "INFO", message, null);
    }

    static void e(Context context, String message, Throwable throwable) {
        write(context, "ERROR", message, throwable);
    }

    static String last(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String history = preferences.getString(KEY_HISTORY, "");
        if (!history.isEmpty()) {
            return history;
        }
        return preferences.getString(KEY_LAST, "暂无运行日志");
    }

    private static void write(Context context, String level, String message, Throwable throwable) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        String text = time + " " + level + " " + message
                + (throwable == null ? "" : " : " + throwable.getClass().getSimpleName() + " " + throwable.getMessage());
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String history = text + "\n" + preferences.getString(KEY_HISTORY, "");
        if (history.length() > MAX_HISTORY_CHARS) {
            history = history.substring(0, MAX_HISTORY_CHARS);
        }
        preferences
                .edit()
                .putString(KEY_LAST, text)
                .putString(KEY_HISTORY, history)
                .apply();
        if (throwable == null) {
            Log.i(TAG, text);
        } else {
            Log.e(TAG, text, throwable);
        }
    }
}
