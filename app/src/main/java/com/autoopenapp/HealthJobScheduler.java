package com.autoopenapp;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

final class HealthJobScheduler {
    private static final int JOB_ID = 7201;

    private HealthJobScheduler() {
    }

    static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            return;
        }
        if (!KeepAliveService.isGuardEligible(context)) {
            cancel(context);
            return;
        }
        if (scheduler.getPendingJob(JOB_ID) != null) {
            return;
        }
        JobInfo.Builder builder = new JobInfo.Builder(
                JOB_ID,
                new ComponentName(context, ScheduleHealthJobService.class)
        ).setPersisted(true)
                .setPeriodic(JobInfo.getMinPeriodMillis());
        builder.setRequiresBatteryNotLow(false);
        int result = scheduler.schedule(builder.build());
        if (result != JobScheduler.RESULT_SUCCESS) {
            RunLog.i(context, "系统巡检任务安排失败 result=" + result);
        }
    }

    static void cancel(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) {
            scheduler.cancel(JOB_ID);
        }
    }
}
