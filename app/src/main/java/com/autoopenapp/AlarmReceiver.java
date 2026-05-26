package com.autoopenapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        boolean isRetry = intent != null && intent.getBooleanExtra(AlarmScheduler.EXTRA_RETRY, false);
        int attempt = intent == null ? 0 : intent.getIntExtra(AlarmScheduler.EXTRA_ATTEMPT, 0);
        String alarmTime = intent == null ? "" : intent.getStringExtra(ScheduleConfig.EXTRA_ALARM_TIME);
        RunLog.i(context, "AlarmReceiver 收到" + (isRetry ? "补偿重试" : "闹钟") + "广播 attempt=" + attempt + " value=" + alarmTime);

        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AutoOpenApp:alarm"
        );
        wakeLock.acquire(30_000L);
        try {
            if (isRetry && LaunchTracker.recentlySucceeded(context, alarmTime)) {
                RunLog.i(context, "目标已成功打开，停止补偿重试 value=" + alarmTime);
                AlarmScheduler.cancelRetry(context);
                return;
            }

            ScheduleConfig config = ScheduleStore.load(context);
            if (!config.isRunnable()) {
                RunLog.i(context, "闹钟触发但配置无效，enabled=" + config.enabled + ", package=" + config.packageName + ", times=" + config.times);
                return;
            }

            // 时间窗/工作日限制只对主触发生效；补偿重试是对已合法触发的补拉，不再二次校验
            if (!isRetry) {
                boolean bypassTimeLimits = AlarmScheduler.shouldBypassTimeLimits(alarmTime);
                if (!bypassTimeLimits && !AlarmScheduler.shouldRunToday(config)) {
                    RunLog.i(context, "今天是周末，已跳过拉起任务");
                    AlarmScheduler.reschedule(context);
                    return;
                }
                if (!bypassTimeLimits && !AlarmScheduler.shouldRunNow()) {
                    RunLog.i(context, "当前时间不在允许时间段，已跳过拉起任务：" + ScheduleConfig.allowedTimeDescription());
                    AlarmScheduler.reschedule(context);
                    return;
                }
            }

            RunLog.i(context, "闹钟配置有效，准备拉起目标，alarmTime=" + alarmTime + ", times=" + config.allTimes() + ", datedTimes=" + config.datedTimes);
            KeepAliveService.start(context);
            TargetLauncher.fireLaunch(context, alarmTime);

            if (!isRetry) {
                AlarmScheduler.reschedule(context);
            }

            int nextAttempt = attempt + 1;
            if (nextAttempt < LaunchTracker.MAX_ATTEMPTS) {
                AlarmScheduler.scheduleRetry(context, alarmTime, nextAttempt);
            } else {
                RunLog.i(context, "已达最大补偿重试次数，停止补拉 value=" + alarmTime);
            }
        } finally {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }
}
