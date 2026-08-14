package com.autoopenapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "unknown" : intent.getAction();
        RunLog.i(context, "收到系统恢复广播 action=" + action);
        AlarmScheduler.reschedule(context);
        KeepAliveService.start(context);
        WatchdogScheduler.schedule(context);
        HealthJobScheduler.schedule(context);
    }
}
