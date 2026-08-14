package com.autoopenapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class WatchdogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!KeepAliveService.isGuardEligible(context)) {
            KeepAliveService.sync(context);
            return;
        }
        RunLog.i(context, "后台巡检触发，重建闹钟并恢复常驻服务");
        AlarmScheduler.reschedule(context);
        KeepAliveService.sync(context);
        HealthJobScheduler.schedule(context);
        WatchdogScheduler.schedule(context);
    }
}
