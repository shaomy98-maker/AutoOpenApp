package com.autoopenapp;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class AlarmAlertActivity extends Activity {
    private boolean launched;
    private String alarmTime;
    private PowerManager.WakeLock screenWakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        alarmTime = getIntent() == null ? "" : getIntent().getStringExtra(ScheduleConfig.EXTRA_ALARM_TIME);
        RunLog.i(this, "AlarmAlertActivity onCreate，准备亮屏");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );
        wakeScreenNow();
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        launchSoon();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(0xFFF6F8FA);

        TextView title = new TextView(this);
        title.setText("到时间了");
        title.setTextSize(28);
        title.setTextColor(0xFF111827);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView message = new TextView(this);
        ScheduleConfig config = ScheduleStore.load(this);
        message.setText("正在打开 " + config.packageName);
        message.setTextSize(16);
        message.setTextColor(0xFF4B5563);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(10), 0, dp(18));
        root.addView(message, matchWrap());

        Button openNow = new Button(this);
        openNow.setText("立即打开");
        openNow.setAllCaps(false);
        openNow.setOnClickListener(v -> openTarget());
        root.addView(openNow, matchWrap());

        setContentView(root);
    }

    private void launchSoon() {
        if (launched) {
            return;
        }
        launched = true;
        wakeAndDismissThenOpen();
    }

    private void wakeAndDismissThenOpen() {
        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && keyguardManager != null && keyguardManager.isKeyguardLocked()) {
            RunLog.i(this, "当前锁屏中，尝试解除无安全锁屏");
            keyguardManager.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    openAfterShortDelay();
                }

                @Override
                public void onDismissCancelled() {
                    openAfterShortDelay();
                }

                @Override
                public void onDismissError() {
                    openAfterShortDelay();
                }
            });
        } else {
            openTarget();
        }
    }

    private void wakeScreenNow() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            int flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP
                    | PowerManager.ON_AFTER_RELEASE;
            screenWakeLock = powerManager.newWakeLock(flags, "AutoOpenApp:screen");
            screenWakeLock.acquire(8_000L);
            RunLog.i(this, "已申请短时亮屏 WakeLock");
        } catch (Exception e) {
            RunLog.e(this, "亮屏 WakeLock 申请失败", e);
        }
    }

    private void openAfterShortDelay() {
        new Handler(Looper.getMainLooper()).postDelayed(this::openTarget, 300L);
    }

    private void openTarget() {
        ScheduleConfig config = ScheduleStore.load(this);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.cancel(2001);
        if (config.isRunnable()) {
            RunLog.i(this, "AlarmAlertActivity 开始打开目标");
            boolean launchedTarget = TargetLauncher.launch(this, config, alarmTime);
            if (launchedTarget && LaunchTracker.recentlySucceeded(this, alarmTime)) {
                ScheduleStore.completeAfterSuccessfulLaunch(this, alarmTime);
            }
        } else {
            RunLog.i(this, "AlarmAlertActivity 配置无效，取消打开目标");
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        if (screenWakeLock != null && screenWakeLock.isHeld()) {
            screenWakeLock.release();
        }
        super.onDestroy();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
