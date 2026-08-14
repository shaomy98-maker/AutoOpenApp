package com.autoopenapp;

import android.content.Context;
import android.content.SharedPreferences;

final class ScheduleStore {
    private static final String PREFS = "auto_open_prefs";
    private static final String KEY_CONFIG = "schedule_config";

    private ScheduleStore() {
    }

    static ScheduleConfig load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ScheduleConfig config = ScheduleConfig.fromJson(preferences.getString(KEY_CONFIG, ""))
                .withGeneratedFixedTimesIfNeeded();
        save(context, config);
        return config;
    }

    static void save(Context context, ScheduleConfig config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CONFIG, config.toJson())
                .apply();
    }

    static boolean regenerateFixedTimeAfterSuccess(Context context, String completedTime) {
        ScheduleConfig current = load(context);
        ScheduleConfig updated = current.withRegeneratedFixedTime(completedTime);
        if (updated.fixedTimes.equals(current.fixedTimes)) {
            return false;
        }
        save(context, updated);
        return true;
    }

    static boolean removeDatedTimeAfterSuccess(Context context, String completedTime) {
        ScheduleConfig current = load(context);
        ScheduleConfig updated = current.withoutDatedTime(completedTime);
        if (updated.datedTimes.equals(current.datedTimes)) {
            return false;
        }
        save(context, updated);
        return true;
    }

    static boolean completeAfterSuccessfulLaunch(Context context, String completedTime) {
        ScheduleConfig current = load(context);
        ScheduleConfig updated = current.withoutDatedTime(completedTime)
                .withRegeneratedFixedTime(completedTime);
        if (updated.toJson().equals(current.toJson())) {
            return false;
        }
        save(context, updated);
        RunLog.i(context, "任务确认成功，已更新一次性/固定随机时间 value=" + completedTime);
        AlarmScheduler.reschedule(context);
        return true;
    }
}
