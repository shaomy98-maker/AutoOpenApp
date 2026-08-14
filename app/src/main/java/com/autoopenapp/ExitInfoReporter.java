package com.autoopenapp;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class ExitInfoReporter {
    private static final String PREFS = "auto_open_exit";
    private static final String KEY_LAST_SEEN = "last_seen_exit_ts";
    private static final int MAX_FETCH = 10;

    private ExitInfoReporter() {
    }

    static void logRecentExits(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            doLogExits(context);
        } catch (Throwable t) {
            RunLog.e(context, "读取 ApplicationExitInfo 失败",
                    t instanceof Exception ? (Exception) t : new Exception(t));
        }
    }

    @TargetApi(Build.VERSION_CODES.R)
    private static void doLogExits(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return;
        }
        List<ApplicationExitInfo> infos = am.getHistoricalProcessExitReasons(null, 0, MAX_FETCH);
        if (infos == null || infos.isEmpty()) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long lastSeen = prefs.getLong(KEY_LAST_SEEN, 0L);
        long newest = lastSeen;
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

        // 列表通常按 newest-first 排，倒序处理这样写日志顺序也是先早后晚
        for (int i = infos.size() - 1; i >= 0; i--) {
            ApplicationExitInfo info = infos.get(i);
            long ts = info.getTimestamp();
            if (ts <= lastSeen) {
                continue;
            }
            if (ts > newest) {
                newest = ts;
            }
            String when = fmt.format(new Date(ts));
            String reason = reasonName(info.getReason());
            String desc = info.getDescription();
            if (desc == null) {
                desc = "";
            }
            long pssMb = info.getPss() / 1024;
            RunLog.i(context, "上次进程退出 " + when + " reason=" + reason
                    + (pssMb > 0 ? " pss=" + pssMb + "MB" : "")
                    + " " + desc);
        }
        if (newest > lastSeen) {
            prefs.edit().putLong(KEY_LAST_SEEN, newest).apply();
        }
    }

    @TargetApi(Build.VERSION_CODES.R)
    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_UNKNOWN: return "UNKNOWN";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INIT_FAIL";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERM_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "FORCE_STOP";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            default: return "REASON_" + reason;
        }
    }
}
