package com.autoopenapp;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class AlarmAlertActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean resumed;
    private boolean autoLaunchRequested;
    private boolean unlockRequestInFlight;
    private boolean openInProgress;
    private int unlockGeneration;
    private String alarmTime = "";
    private TextView messageView;
    private PowerManager.WakeLock screenWakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        alarmTime = readAlarmTime(getIntent());
        RunLog.i(this, "AlarmAlertActivity onCreate，准备亮屏 value=" + alarmTime);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );
        buildUi();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String nextAlarmTime = readAlarmTime(intent);
        if (nextAlarmTime.equals(alarmTime) && (unlockRequestInFlight || openInProgress)) {
            RunLog.i(this, "AlarmAlertActivity 忽略正在处理中的重复 Intent value=" + nextAlarmTime);
            return;
        }

        handler.removeCallbacksAndMessages(null);
        unlockGeneration++;
        unlockRequestInFlight = false;
        openInProgress = false;
        autoLaunchRequested = false;
        alarmTime = nextAlarmTime;
        refreshMessage();
        RunLog.i(this, "AlarmAlertActivity onNewIntent，已切换任务 value=" + alarmTime);
        if (resumed) {
            wakeScreenNow();
            launchSoon();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        wakeScreenNow();
        launchSoon();
    }

    @Override
    protected void onPause() {
        resumed = false;
        releaseScreenWakeLock();
        super.onPause();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFFF6F8FA);
        applySystemBarInsets(root);

        TextView title = new TextView(this);
        title.setText("到时间了");
        title.setTextSize(28);
        title.setTextColor(0xFF111827);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        messageView = new TextView(this);
        messageView.setTextSize(16);
        messageView.setTextColor(0xFF4B5563);
        messageView.setGravity(Gravity.CENTER);
        messageView.setPadding(0, dp(10), 0, dp(18));
        root.addView(messageView, matchWrap());
        refreshMessage();

        Button openNow = new Button(this);
        openNow.setText("立即打开");
        openNow.setAllCaps(false);
        openNow.setOnClickListener(v -> wakeAndDismissThenOpen());
        root.addView(openNow, matchWrap());

        setContentView(root);
        root.requestApplyInsets();
    }

    @SuppressWarnings("deprecation")
    private void applySystemBarInsets(View root) {
        int basePadding = dp(24);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets safeInsets = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(
                        basePadding + safeInsets.left,
                        basePadding + safeInsets.top,
                        basePadding + safeInsets.right,
                        basePadding + safeInsets.bottom
                );
            } else {
                view.setPadding(
                        basePadding + insets.getSystemWindowInsetLeft(),
                        basePadding + insets.getSystemWindowInsetTop(),
                        basePadding + insets.getSystemWindowInsetRight(),
                        basePadding + insets.getSystemWindowInsetBottom()
                );
            }
            return insets;
        });
    }

    private void refreshMessage() {
        if (messageView == null) {
            return;
        }
        ScheduleConfig config = ScheduleStore.load(this);
        messageView.setText("正在打开 " + config.packageName);
    }

    private void setStatus(String message) {
        if (messageView != null) {
            messageView.setText(message);
        }
    }

    private void launchSoon() {
        if (autoLaunchRequested) {
            return;
        }
        autoLaunchRequested = true;
        wakeAndDismissThenOpen();
    }

    private void wakeAndDismissThenOpen() {
        if (unlockRequestInFlight || openInProgress) {
            return;
        }
        wakeScreenNow();
        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguardManager != null && keyguardManager.isKeyguardLocked()) {
            RunLog.i(this, "当前锁屏中，等待系统确认解锁");
            setStatus("请先解锁设备，解锁后将自动打开应用");
            unlockRequestInFlight = true;
            int requestGeneration = ++unlockGeneration;
            try {
                keyguardManager.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                    @Override
                    public void onDismissSucceeded() {
                        if (!finishUnlockRequest(requestGeneration)) {
                            return;
                        }
                        RunLog.i(AlarmAlertActivity.this, "系统确认解锁成功，准备打开目标");
                        setStatus("解锁成功，正在打开应用");
                        openAfterShortDelay(requestGeneration);
                    }

                    @Override
                    public void onDismissCancelled() {
                        if (!finishUnlockRequest(requestGeneration)) {
                            return;
                        }
                        RunLog.i(AlarmAlertActivity.this, "用户取消解锁，保留提醒页面");
                        setStatus("尚未解锁，请解锁后点击“立即打开”");
                    }

                    @Override
                    public void onDismissError() {
                        if (!finishUnlockRequest(requestGeneration)) {
                            return;
                        }
                        RunLog.i(AlarmAlertActivity.this, "系统无法完成解锁，保留提醒页面");
                        setStatus("无法自动解锁，请解锁后点击“立即打开”");
                    }
                });
            } catch (RuntimeException e) {
                finishUnlockRequest(requestGeneration);
                RunLog.e(this, "请求系统解锁失败，保留提醒页面", e);
                setStatus("无法请求解锁，请解锁后点击“立即打开”");
            }
            return;
        }

        int requestGeneration = ++unlockGeneration;
        openTarget(requestGeneration);
    }

    private boolean finishUnlockRequest(int requestGeneration) {
        if (requestGeneration != unlockGeneration) {
            return false;
        }
        unlockRequestInFlight = false;
        return true;
    }

    private void openAfterShortDelay(int requestGeneration) {
        handler.postDelayed(() -> openTarget(requestGeneration), 300L);
    }

    private void openTarget(int requestGeneration) {
        if (requestGeneration != unlockGeneration || openInProgress) {
            return;
        }
        if (!resumed) {
            autoLaunchRequested = false;
            RunLog.i(this, "提醒页暂不在前台，等待恢复后再打开目标");
            return;
        }

        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguardManager != null && keyguardManager.isKeyguardLocked()) {
            RunLog.i(this, "打开前设备再次锁定，保留提醒页面");
            setStatus("设备仍处于锁定状态，请解锁后点击“立即打开”");
            return;
        }

        ScheduleConfig config = ScheduleStore.load(this);
        if (!config.isRunnable()) {
            RunLog.i(this, "AlarmAlertActivity 配置无效，保留提醒页面");
            setStatus("当前任务配置已失效，无法打开目标应用");
            return;
        }

        openInProgress = true;
        RunLog.i(this, "AlarmAlertActivity 开始打开目标 value=" + alarmTime);
        boolean launchAccepted = TargetLauncher.launch(this, config, alarmTime);
        if (launchAccepted) {
            finish();
        } else {
            openInProgress = false;
            setStatus("自动打开失败，请稍后点击“立即打开”重试");
            RunLog.i(this, "目标启动失败，保留提醒页面 value=" + alarmTime);
        }
    }

    @SuppressWarnings("deprecation")
    private void wakeScreenNow() {
        if (screenWakeLock != null && screenWakeLock.isHeld()) {
            return;
        }
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) {
                RunLog.i(this, "PowerManager 不可用，无法申请短时亮屏");
                return;
            }
            int flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP
                    | PowerManager.ON_AFTER_RELEASE;
            screenWakeLock = powerManager.newWakeLock(flags, "AutoOpenApp:screen");
            screenWakeLock.setReferenceCounted(false);
            screenWakeLock.acquire(8_000L);
            RunLog.i(this, "已申请短时亮屏 WakeLock");
        } catch (Exception e) {
            RunLog.e(this, "亮屏 WakeLock 申请失败", e);
        }
    }

    private void releaseScreenWakeLock() {
        if (screenWakeLock != null && screenWakeLock.isHeld()) {
            screenWakeLock.release();
        }
        screenWakeLock = null;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unlockGeneration++;
        releaseScreenWakeLock();
        super.onDestroy();
    }

    private String readAlarmTime(Intent intent) {
        if (intent == null) {
            return "";
        }
        String value = intent.getStringExtra(ScheduleConfig.EXTRA_ALARM_TIME);
        return value == null ? "" : value;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
