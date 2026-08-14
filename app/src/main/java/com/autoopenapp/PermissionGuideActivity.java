package com.autoopenapp;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class PermissionGuideActivity extends Activity {
    private LinearLayout container;
    private TextView progressView;
    private TextView bannerView;
    private boolean exactAlarmWasReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TargetLauncher.ensureChannel(this);
        exactAlarmWasReady = PermissionUtil.isExactAlarmReady(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean exactAlarmReady = PermissionUtil.isExactAlarmReady(this);
        if (!exactAlarmWasReady && exactAlarmReady) {
            AlarmScheduler.cancelAllRetries(this);
            AlarmScheduler.reschedule(this);
            KeepAliveService.sync(this);
            RunLog.i(this, "精确闹钟权限已开启，已重新安排任务");
        } else if (exactAlarmWasReady && !exactAlarmReady) {
            KeepAliveService.sync(this);
            RunLog.i(this, "精确闹钟权限已关闭，已停止常驻服务");
        }
        exactAlarmWasReady = exactAlarmReady;
        render();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        render();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(0xFFF4F6FA);
        scrollView.setClipToPadding(false);
        applySystemBarInsets(scrollView);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(28));
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("权限引导");
        textDp(title, 24);
        title.setTextColor(0xFF101828);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("按顺序逐项开启，到点才能稳定自动打开应用");
        textDp(subtitle, 13);
        subtitle.setTextColor(0xFF667085);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle, matchWrap());

        bannerView = new TextView(this);
        bannerView.setText("已全部开启，到点会自动打开目标应用");
        textDp(bannerView, 14);
        bannerView.setTextColor(0xFF067647);
        bannerView.setTypeface(Typeface.DEFAULT_BOLD);
        bannerView.setPadding(dp(16), dp(14), dp(16), dp(14));
        bannerView.setBackground(rounded(0xFFE7F8EF, 0xFFA6E9C5, 18));
        bannerView.setVisibility(View.GONE);
        root.addView(bannerView, matchWrapWithTop(2));

        progressView = new TextView(this);
        textDp(progressView, 13);
        progressView.setTextColor(0xFF2563EB);
        progressView.setTypeface(Typeface.DEFAULT_BOLD);
        progressView.setPadding(0, dp(8), 0, dp(8));
        root.addView(progressView, matchWrap());

        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        root.addView(container, matchWrap());

        Button done = new Button(this);
        done.setText("完成");
        done.setAllCaps(false);
        done.setTextColor(0xFFFFFFFF);
        textDp(done, 15);
        done.setTypeface(Typeface.DEFAULT_BOLD);
        done.setBackground(rounded(0xFF2F66E8, 0xFF2F66E8, 26));
        done.setOnClickListener(v -> finish());
        root.addView(done, matchWrapWithTop(18));

        setContentView(scrollView);
    }

    private void render() {
        container.removeAllViews();
        int total = 0;
        int ready = 0;

        boolean exactApplicable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        boolean exactReady = PermissionUtil.isExactAlarmReady(this);
        total++;
        if (exactReady) {
            ready++;
        }
        addItem("精确闹钟", "保证到点准时触发，不被系统延后", exactReady, exactApplicable,
                () -> launch(PermissionUtil.exactAlarmIntent(this)));

        boolean overlayReady = PermissionUtil.hasOverlay(this);
        total++;
        if (overlayReady) {
            ready++;
        }
        addItem("悬浮窗", "亮屏使用手机时，后台自动拉起目标的必需权限", overlayReady, true,
                () -> launch(PermissionUtil.overlayIntent(this)));

        boolean batteryReady = PermissionUtil.isBatteryUnrestricted(this);
        total++;
        if (batteryReady) {
            ready++;
        }
        addItem("电池优化白名单", "避免系统休眠杀进程导致闹钟失效", batteryReady, true,
                () -> launch(PermissionUtil.batteryIntent(this)));

        boolean usageReady = PermissionUtil.hasUsageAccess(this);
        total++;
        if (usageReady) {
            ready++;
        }
        addItem("使用情况访问", "验证目标是否真的进入前台，被拦截时自动继续补拉", usageReady, true,
                () -> launch(PermissionUtil.usageAccessIntent(this)));

        boolean notificationsReady = PermissionUtil.areAppNotificationsEnabled(this);
        total++;
        if (notificationsReady) {
            ready++;
        }
        addItem("应用通知", "用于锁屏全屏提醒和失败时的兜底通知", notificationsReady, true,
                this::openNotificationPermission);

        boolean launchChannelReady = PermissionUtil.isLaunchChannelReady(this);
        total++;
        if (launchChannelReady) {
            ready++;
        }
        addItem("定时提醒渠道", "渠道被关闭后，到点提醒和全屏拉起将无法显示", launchChannelReady, true,
                () -> launch(PermissionUtil.launchChannelSettingsIntent(this)));

        if (Build.VERSION.SDK_INT >= 34) {
            boolean fullScreenReady = PermissionUtil.canFullScreen(this);
            total++;
            if (fullScreenReady) {
                ready++;
            }
            addItem("全屏通知", "锁屏/熄屏时自动全屏拉起", fullScreenReady, true,
                    () -> launch(PermissionUtil.fullScreenIntent(this)));
        }

        addItem("厂商自启动 / 后台保护", "请手动允许自启动、后台运行并在最近任务中锁定本应用", false, true,
                () -> launch(PermissionUtil.oemAutoStartIntent(this)), "去确认");

        progressView.setText("已完成 " + ready + " / " + total);
        bannerView.setText("可检测权限已全部开启，请再确认厂商后台保护");
        bannerView.setVisibility(ready == total ? View.VISIBLE : View.GONE);
    }

    private void addItem(String name, String why, boolean ready, boolean applicable, Runnable action) {
        addItem(name, why, ready, applicable, action, "去开启");
    }

    private void addItem(String name, String why, boolean ready, boolean applicable, Runnable action, String actionLabel) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackground(rounded(0xFFFFFFFF, 0xFFE1E5EC, 22));

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);

        TextView nameView = new TextView(this);
        nameView.setText(name);
        textDp(nameView, 16);
        nameView.setTextColor(0xFF101828);
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        textBox.addView(nameView, matchWrap());

        TextView whyView = new TextView(this);
        whyView.setText(why);
        textDp(whyView, 12);
        whyView.setTextColor(0xFF667085);
        whyView.setPadding(0, dp(3), 0, 0);
        textBox.addView(whyView, matchWrap());

        row.addView(textBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        if (ready) {
            TextView status = new TextView(this);
            status.setText(applicable ? "已开启" : "已满足");
            textDp(status, 13);
            status.setTypeface(Typeface.DEFAULT_BOLD);
            status.setTextColor(applicable ? 0xFF067647 : 0xFF667085);
            status.setGravity(Gravity.CENTER);
            status.setPadding(dp(14), dp(8), dp(14), dp(8));
            status.setBackground(rounded(applicable ? 0xFFE7F8EF : 0xFFF1F5F9,
                    applicable ? 0xFFA6E9C5 : 0xFFE1E5EC, 18));
            row.addView(status, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        } else {
            Button open = new Button(this);
            open.setText(actionLabel);
            open.setAllCaps(false);
            open.setTextColor(0xFFFFFFFF);
            textDp(open, 13);
            open.setTypeface(Typeface.DEFAULT_BOLD);
            open.setMinWidth(dp(84));
            open.setMinHeight(dp(40));
            open.setPadding(dp(12), 0, dp(12), 0);
            open.setBackground(rounded(0xFF2F66E8, 0xFF2F66E8, 22));
            open.setOnClickListener(v -> action.run());
            row.addView(open, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        container.addView(row, matchWrapWithTop(10));
    }

    private void launch(Intent intent) {
        if (intent != null) {
            try {
                startActivity(intent);
                return;
            } catch (Exception ignored) {
            }
        }
        try {
            startActivity(PermissionUtil.appDetailsIntent(this));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开系统设置，请手动到设置中开启", Toast.LENGTH_LONG).show();
        }
    }

    private void openNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !PermissionUtil.hasNotificationPermission(this)) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 33);
            return;
        }
        launch(PermissionUtil.appNotificationSettingsIntent(this));
    }

    private void applySystemBarInsets(View view) {
        final int initialLeft = view.getPaddingLeft();
        final int initialTop = view.getPaddingTop();
        final int initialRight = view.getPaddingRight();
        final int initialBottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets systemBars = insets.getInsets(WindowInsets.Type.systemBars());
                left = systemBars.left;
                top = systemBars.top;
                right = systemBars.right;
                bottom = systemBars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            target.setPadding(
                    initialLeft + left,
                    initialTop + top,
                    initialRight + right,
                    initialBottom + bottom
            );
            return insets;
        });
        view.requestApplyInsets();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapWithTop(int topDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(topDp);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void textDp(TextView view, float value) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, value);
        view.setIncludeFontPadding(false);
    }

    private GradientDrawable rounded(int color, int strokeColor, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }
}
