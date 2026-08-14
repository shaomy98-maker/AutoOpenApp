package com.autoopenapp;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

final class ForegroundAppVerifier {
    private ForegroundAppVerifier() {
    }

    static boolean canVerify(Context context) {
        return PermissionUtil.hasUsageAccess(context);
    }

    static boolean isTargetForegroundSince(Context context, String packageName, long sinceMillis) {
        if (!canVerify(context) || TextUtils.isEmpty(packageName)) {
            return false;
        }
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        long begin = Math.max(now - 2 * 60_000L, sinceMillis - 2_000L);
        UsageEvents events = manager.queryEvents(begin, now);
        UsageEvents.Event event = new UsageEvents.Event();
        String latestForegroundPackage = "";
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event);
            int type = event.getEventType();
            boolean foreground = type == UsageEvents.Event.MOVE_TO_FOREGROUND;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                foreground = foreground || type == UsageEvents.Event.ACTIVITY_RESUMED;
            }
            if (foreground && event.getTimeStamp() >= begin) {
                latestForegroundPackage = event.getPackageName();
            }
        }
        boolean matched = packageName.equals(latestForegroundPackage);
        RunLog.i(context, "前台验证 target=" + packageName + " observed=" + latestForegroundPackage + " matched=" + matched);
        return matched;
    }
}
