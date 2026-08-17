package com.autoopenapp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;

final class ScheduleStore {
    private static final String PREFS = "auto_open_prefs";
    private static final String KEY_CONFIG = "schedule_config";

    private ScheduleStore() {
    }

    static synchronized ScheduleConfig load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final String raw;
        try {
            raw = preferences.getString(KEY_CONFIG, "");
        } catch (RuntimeException e) {
            return recoverCorruptConfig(context, e);
        }

        // No stored value is the first-run product default and intentionally starts enabled.
        if (raw == null || raw.trim().isEmpty()) {
            ScheduleConfig initial = ScheduleConfig.empty().withGeneratedFixedTimesIfNeeded();
            save(context, initial);
            return initial;
        }

        try {
            // ScheduleConfig.fromJson historically falls back to enabled defaults on a parse
            // error. Validate first so a damaged non-empty preference fails closed instead.
            new JSONObject(raw);
            ScheduleConfig config = ScheduleConfig.fromJson(raw).withGeneratedFixedTimesIfNeeded();
            String normalized = config.toJson();
            if (!normalized.equals(raw)) {
                save(context, config);
            }
            return config;
        } catch (JSONException | RuntimeException e) {
            return recoverCorruptConfig(context, e);
        }
    }

    static synchronized void save(Context context, ScheduleConfig config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CONFIG, config.toJson())
                .apply();
    }

    static synchronized boolean regenerateFixedTimeAfterSuccess(
            Context context,
            String completedTime
    ) {
        ScheduleConfig current = load(context);
        ScheduleConfig updated = current.withRegeneratedFixedTime(completedTime);
        if (updated.fixedTimes.equals(current.fixedTimes)) {
            return false;
        }
        save(context, updated);
        return true;
    }

    static synchronized boolean removeDatedTimeAfterSuccess(
            Context context,
            String completedTime
    ) {
        ScheduleConfig current = load(context);
        ScheduleConfig updated = current.withoutDatedTime(completedTime);
        if (updated.datedTimes.equals(current.datedTimes)) {
            return false;
        }
        save(context, updated);
        return true;
    }

    /**
     * Completes all configuration side effects in one atomic, idempotent in-process update.
     * A second success for the same old value sees neither the dated entry nor the old fixed
     * evening value and therefore makes no further pair change. Morning fixed success keeps
     * today's evening fixed time in place.
     */
    static synchronized boolean completeAfterSuccess(Context context, String completedTime) {
        ScheduleConfig current = load(context);
        ScheduleConfig updated = current.withoutDatedTime(completedTime)
                .withRegeneratedFixedTime(completedTime);
        boolean changed = !updated.datedTimes.equals(current.datedTimes)
                || !updated.fixedTimes.equals(current.fixedTimes);
        if (!changed) {
            return false;
        }
        save(context, updated);
        RunLog.i(context, "任务确认成功，已完成一次性/固定随机配对收尾 value=" + completedTime);
        return true;
    }

    /** Compatibility entry point retained while older launch paths are upgraded. */
    static boolean completeAfterSuccessfulLaunch(Context context, String completedTime) {
        boolean changed = completeAfterSuccess(context, completedTime);
        if (changed) {
            AlarmScheduler.reschedule(context);
        }
        return changed;
    }

    private static ScheduleConfig recoverCorruptConfig(Context context, Throwable cause) {
        ScheduleConfig safe = new ScheduleConfig(
                false,
                ScheduleConfig.DEFAULT_PACKAGE_NAME,
                ScheduleConfig.DEFAULT_ACTIVITY_NAME,
                "",
                true,
                Collections.<String>emptyList()
        ).withGeneratedFixedTimesIfNeeded();
        save(context, safe);
        RunLog.e(context, "排程配置损坏，已关闭自动任务并写入安全配置", cause);
        return safe;
    }
}
