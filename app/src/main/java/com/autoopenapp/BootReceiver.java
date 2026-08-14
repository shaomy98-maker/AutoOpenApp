package com.autoopenapp;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!isAllowedAction(action)) {
            return;
        }
        RunLog.i(context, "收到系统事件，重新同步任务：" + action);
        try {
            AlarmScheduler.cancelAllRetries(context);
            AlarmScheduler.reschedule(context);
        } catch (Exception e) {
            RunLog.e(context, "系统事件触发任务重排失败：" + action, e);
        }
        KeepAliveService.sync(context);
        if (KeepAliveService.isGuardEligible(context)) {
            try {
                WatchdogScheduler.schedule(context);
            } catch (Exception e) {
                RunLog.e(context, "系统事件触发后台巡检安排失败：" + action, e);
            }
            try {
                HealthJobScheduler.schedule(context);
            } catch (Exception e) {
                RunLog.e(context, "系统事件触发健康任务安排失败：" + action, e);
            }
        } else {
            try {
                WatchdogScheduler.cancel(context);
                HealthJobScheduler.cancel(context);
            } catch (Exception e) {
                RunLog.e(context, "系统事件触发恢复链清理失败：" + action, e);
            }
        }
    }

    private boolean isAllowedAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_DATE_CHANGED.equals(action)
                || Intent.ACTION_USER_UNLOCKED.equals(action)
                || AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action);
    }
}
