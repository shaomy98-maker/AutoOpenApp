package com.autoopenapp;

import android.app.job.JobParameters;
import android.app.job.JobService;

public class ScheduleHealthJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        if (!KeepAliveService.isGuardEligible(this)) {
            WatchdogScheduler.cancel(this);
            HealthJobScheduler.cancel(this);
            KeepAliveService.sync(this);
            return false;
        }
        RunLog.i(this, "系统 JobScheduler 巡检触发，校准全部排程");
        AlarmScheduler.reschedule(this);
        WatchdogScheduler.schedule(this);
        KeepAliveService.sync(this);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (KeepAliveService.isGuardEligible(this)) {
            WatchdogScheduler.schedule(this);
            return true;
        }
        WatchdogScheduler.cancel(this);
        HealthJobScheduler.cancel(this);
        KeepAliveService.sync(this);
        return false;
    }
}
