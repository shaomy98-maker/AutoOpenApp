package com.autoopenapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;

import java.util.Locale;

final class PermissionUtil {
    private PermissionUtil() {
    }

    static boolean isExactAlarmReady(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return alarmManager != null && alarmManager.canScheduleExactAlarms();
    }

    static Intent exactAlarmIntent(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null;
        }
        return new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context));
    }

    static boolean hasOverlay(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        if (Settings.canDrawOverlays(context)) {
            return true;
        }
        try {
            AppOpsManager appOpsManager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            return appOpsManager != null && appOpsManager.checkOpNoThrow(
                    AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    Process.myUid(),
                    context.getPackageName()
            ) == AppOpsManager.MODE_ALLOWED;
        } catch (Exception ignored) {
            return false;
        }
    }

    static Intent overlayIntent(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null;
        }
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(context));
    }

    static boolean isBatteryUnrestricted(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager == null || powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    @SuppressLint("BatteryLife")
    static Intent batteryIntent(Context context) {
        return new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context));
    }

    static boolean hasNotifications(Context context) {
        if (Build.VERSION.SDK_INT < 33) {
            return true;
        }
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasUsageAccess(Context context) {
        try {
            AppOpsManager appOpsManager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            return appOpsManager != null && appOpsManager.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.getPackageName()
            ) == AppOpsManager.MODE_ALLOWED;
        } catch (Exception ignored) {
            return false;
        }
    }

    static Intent usageAccessIntent(Context context) {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageUri(context));
        if (intent.resolveActivity(context.getPackageManager()) != null) {
            return intent;
        }
        return new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
    }

    static Intent oemAutoStartIntent(Context context) {
        String manufacturer = Build.MANUFACTURER == null
                ? ""
                : Build.MANUFACTURER.toLowerCase(Locale.US);
        Intent intent = new Intent();
        if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
            intent.setComponent(new ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ));
        } else if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
            intent.setComponent(new ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ));
        } else if (manufacturer.contains("oppo") || manufacturer.contains("realme")) {
            intent.setComponent(new ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ));
        } else if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
            intent.setComponent(new ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ));
        } else if (manufacturer.contains("samsung")) {
            intent.setComponent(new ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
            ));
        }
        if (intent.getComponent() != null && intent.resolveActivity(context.getPackageManager()) != null) {
            return intent;
        }
        return appDetailsIntent(context);
    }

    static boolean canFullScreen(Context context) {
        if (Build.VERSION.SDK_INT < 34) {
            return true;
        }
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return notificationManager == null || notificationManager.canUseFullScreenIntent();
    }

    static Intent fullScreenIntent(Context context) {
        if (Build.VERSION.SDK_INT < 34) {
            return null;
        }
        return new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(context));
    }

    static Intent appDetailsIntent(Context context) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context));
    }

    static boolean allCriticalReady(Context context) {
        return isExactAlarmReady(context)
                && hasOverlay(context)
                && isBatteryUnrestricted(context)
                && hasNotifications(context)
                && canFullScreen(context)
                && hasUsageAccess(context);
    }

    private static Uri packageUri(Context context) {
        return Uri.parse("package:" + context.getPackageName());
    }
}
