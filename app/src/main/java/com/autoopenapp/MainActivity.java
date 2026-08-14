package com.autoopenapp;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends android.app.Activity {
    private EditText packageInput;
    private EditText activityInput;
    private EditText deepLinkInput;
    private EditText dateTimeInput;
    private EditText timeInput;
    private Switch enableSwitch;
    private Switch workdaysOnlySwitch;
    private LinearLayout timesContainer;
    private TextView nextTimeView;
    private TextView nextMetaView;
    private TextView targetSummaryView;
    private TextView taskCountView;
    private TextView lastLogView;
    private boolean loadingConfig;
    private boolean criticalPromptShown;
    private boolean exactAlarmWasReady;
    private final ArrayList<String> fixedTimes = new ArrayList<>();
    private final ArrayList<String> times = new ArrayList<>();
    private final ArrayList<String> datedTimes = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TargetLauncher.ensureChannel(this);
        buildUi();
        loadConfig();
        exactAlarmWasReady = PermissionUtil.isExactAlarmReady(this);
        KeepAliveService.sync(this);
        maybePromptCriticalPermissions();
        ExitInfoReporter.logRecentExits(this);
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
        updatePermissionHint();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(0xFFF4F6FA);
        scrollView.setClipToPadding(false);
        applySystemBarInsets(scrollView);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        scrollView.addView(root);

        packageInput = hiddenInput(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        activityInput = hiddenInput(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        deepLinkInput = hiddenInput(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        dateTimeInput = hiddenInput(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_NORMAL);
        timeInput = hiddenInput(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(2), 0, dp(12));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("定时任务");
        textDp(title, 22);
        title.setTextColor(0xFF101828);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleBox.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("像闹钟一样打开飞书");
        textDp(subtitle, 12);
        subtitle.setTextColor(0xFF667085);
        subtitle.setPadding(0, dp(3), 0, 0);
        titleBox.addView(subtitle, matchWrap());
        header.addView(titleBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        enableSwitch = new Switch(this);
        enableSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (loadingConfig) {
                    return;
                }
                saveAndSchedule(false);
            }
        });
        header.addView(enableSwitch, new LinearLayout.LayoutParams(dp(62), dp(44)));
        root.addView(header, matchWrap());

        addDashboardCard(root);
        addActionGrid(root);

        workdaysOnlySwitch = new Switch(this);
        workdaysOnlySwitch.setChecked(true);
        workdaysOnlySwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (loadingConfig) {
                    return;
                }
                saveAndSchedule(false);
            }
        });

        TextView timesTitle = sectionTitle("任务卡片");
        textDp(timesTitle, 16);
        timesTitle.setPadding(0, dp(18), 0, dp(8));
        root.addView(timesTitle, matchWrap());

        timesContainer = new LinearLayout(this);
        timesContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(timesContainer, matchWrap());

        setContentView(scrollView);
    }

    private void addDashboardCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF2563EB, 0xFF111827}
        );
        background.setCornerRadius(dp(22));
        card.setBackground(background);

        TextView label = new TextView(this);
        label.setText("下一次任务");
        textDp(label, 12);
        label.setTextColor(0xFFDBEAFE);
        card.addView(label, matchWrap());

        nextTimeView = new TextView(this);
        nextTimeView.setText("--:--");
        textDp(nextTimeView, 34);
        nextTimeView.setTextColor(0xFFFFFFFF);
        nextTimeView.setTypeface(Typeface.DEFAULT_BOLD);
        nextTimeView.setPadding(0, dp(2), 0, 0);
        card.addView(nextTimeView, matchWrap());

        nextMetaView = new TextView(this);
        nextMetaView.setText("暂无可执行时间");
        textDp(nextMetaView, 12);
        nextMetaView.setTextColor(0xFFE0F2FE);
        nextMetaView.setPadding(0, dp(2), 0, dp(14));
        card.addView(nextMetaView, matchWrap());

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);

        targetSummaryView = dashboardMetric("目标应用", ScheduleConfig.DEFAULT_PACKAGE_NAME);
        taskCountView = dashboardMetric("任务数量", "0");
        metrics.addView(targetSummaryView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        countParams.leftMargin = dp(10);
        metrics.addView(taskCountView, countParams);
        card.addView(metrics, matchWrap());

        root.addView(card, matchWrapWithTop(4));
    }

    private TextView dashboardMetric(String title, String value) {
        TextView view = new TextView(this);
        view.setText(title + "\n" + value);
        textDp(view, 12);
        view.setTextColor(0xFFFFFFFF);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(rounded(0x22FFFFFF, 0x44FFFFFF, 18));
        return view;
    }

    private EditText hiddenInput(int inputType) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(inputType);
        return input;
    }

    private void addActionGrid(LinearLayout root) {
        LinearLayout row1 = actionRow();
        row1.addView(actionCircle("+", "指定日期", true, v -> showDateTimeDialog()), circleParams(false));
        row1.addView(actionCircle("◷", "每日时间", false, v -> showDailyTimeDialog()), circleParams(false));
        row1.addView(actionCircle("◎", "目标应用", false, v -> showTargetDialog()), circleParams(false));
        row1.addView(actionCircle("⚙", "权限设置", false, v -> showSettingsDialog()), circleParams(false));
        root.addView(row1, matchWrapWithTop(18));

        LinearLayout row2 = actionRow();
        row2.setGravity(Gravity.CENTER);
        row2.addView(actionCircle("▶", "立即测试", false, v -> testLaunch()), smallCircleParams());
        row2.addView(actionCircle("≡", "运行日志", false, v -> showLogDialog()), smallCircleParams());
        root.addView(row2, matchWrapWithTop(14));
    }

    private LinearLayout actionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams circleParams(boolean hasLeftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(86), 1);
        if (hasLeftMargin) {
            params.leftMargin = dp(10);
        }
        return params;
    }

    private LinearLayout.LayoutParams smallCircleParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(92), dp(86));
        params.leftMargin = dp(8);
        params.rightMargin = dp(8);
        return params;
    }

    private LinearLayout actionCircle(String icon, String title, boolean primary, View.OnClickListener listener) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(2), 0, dp(2), 0);
        item.setOnClickListener(listener);

        TextView iconView = new TextView(this);
        iconView.setText(icon);
        textDp(iconView, 22);
        iconView.setTextColor(primary ? 0xFFFFFFFF : 0xFF111827);
        iconView.setTypeface(Typeface.DEFAULT_BOLD);
        iconView.setGravity(Gravity.CENTER);
        iconView.setBackground(circleDrawable(primary ? 0xFF2563EB : 0xFFFFFFFF, primary ? 0xFF2563EB : 0xFFE1E7EF));
        iconView.setElevation(dp(primary ? 7 : 3));
        item.addView(iconView, new LinearLayout.LayoutParams(dp(56), dp(56)));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        textDp(titleView, 11);
        titleView.setTextColor(0xFF667085);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, dp(7), 0, 0);
        item.addView(titleView, matchWrap());
        return item;
    }

    private EditText addInput(LinearLayout root, String label, String hint) {
        TextView textView = sectionTitle(label);
        textView.setPadding(0, dp(12), 0, dp(4));
        root.addView(textView, matchWrap());
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        textDp(input, 15);
        styleInput(input);
        root.addView(input, matchWrap());
        return input;
    }

    private TextView sectionTitle(String value) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textDp(textView, 15);
        textView.setTextColor(0xFF111827);
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        return textView;
    }

    private Button button(String text) {
        return button(text, 0xFF111827, 0xFFFFFFFF, 0xFFE5E7EB);
    }

    private Button button(String text, int textColor, int backgroundColor, int strokeColor) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(textColor);
        textDp(button, 13);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinHeight(dp(42));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(rounded(backgroundColor, strokeColor, 24));
        return button;
    }

    private void loadConfig() {
        ScheduleConfig config = ScheduleStore.load(this);
        loadingConfig = true;
        try {
            packageInput.setText(config.packageName);
            activityInput.setText(config.activityName);
            deepLinkInput.setText(config.deepLink);
            fixedTimes.clear();
            fixedTimes.addAll(config.fixedTimes);
            times.clear();
            times.addAll(config.times);
            datedTimes.clear();
            datedTimes.addAll(config.datedTimes);
            enableSwitch.setChecked(config.enabled);
            workdaysOnlySwitch.setChecked(config.workdaysOnly);
            renderTimes();
        } finally {
            loadingConfig = false;
        }
    }

    private ScheduleConfig currentConfig() {
        return new ScheduleConfig(
                enableSwitch.isChecked(),
                packageInput.getText().toString(),
                activityInput.getText().toString(),
                deepLinkInput.getText().toString(),
                workdaysOnlySwitch.isChecked(),
                fixedTimes,
                times,
                datedTimes
        );
    }

    private void saveAndSchedule(boolean showToast) {
        if (loadingConfig) {
            return;
        }
        ScheduleConfig config = currentConfig();
        ScheduleStore.save(this, config);
        AlarmScheduler.cancelAllRetries(this);
        AlarmScheduler.reschedule(this);
        KeepAliveService.sync(this);
        if (showToast) {
            if (!config.isRunnable()) {
                toast("已保存。启用时需要包名和至少一个时间。");
            } else {
                toast("已保存，" + (config.workdaysOnly ? "仅周一到周五执行，" : "每天执行，") + ScheduleConfig.allowedTimeDescription());
            }
        }
        updatePermissionHint();
        refreshLastLog();
        if (config.enabled) {
            maybePromptCriticalPermissions();
        }
    }

    private void maybePromptCriticalPermissions() {
        if (criticalPromptShown || PermissionUtil.allCriticalReady(this)) {
            return;
        }
        ArrayList<String> missing = new ArrayList<>();
        if (!PermissionUtil.isExactAlarmReady(this)) {
            missing.add("精确闹钟（准时触发）");
        }
        if (!PermissionUtil.hasOverlay(this)) {
            missing.add("悬浮窗（亮屏时后台拉起必需）");
        }
        if (!PermissionUtil.isBatteryUnrestricted(this)) {
            missing.add("电池优化白名单（避免进程被压制）");
        }
        if (!PermissionUtil.hasNotifications(this)) {
            missing.add("通知与定时提醒渠道（锁屏提醒必需）");
        }
        if (!PermissionUtil.canFullScreen(this)) {
            missing.add("全屏通知（锁屏自动拉起）");
        }
        if (!PermissionUtil.hasUsageAccess(this)) {
            missing.add("使用情况访问（验证目标真正打开并自动补拉）");
        }
        if (missing.isEmpty()) {
            return;
        }
        criticalPromptShown = true;
        new AlertDialog.Builder(this)
                .setTitle("为保证到点必开，建议开启")
                .setMessage("• " + TextUtils.join("\n• ", missing)
                        + "\n\n未开启时，后台或亮屏场景可能只显示通知而不会自动打开应用。")
                .setPositiveButton("去开启", (dialog, which) -> openPermissionGuide())
                .setNegativeButton("稍后", null)
                .show();
    }

    private void openPermissionGuide() {
        startActivity(new Intent(this, PermissionGuideActivity.class));
    }

    private void showDateTimeDialog() {
        LinearLayout panel = dialogPanel();
        panel.addView(dialogIcon("+", 0xFFEFF6FF, 0xFF2563EB), new LinearLayout.LayoutParams(dp(62), dp(62)));
        TextView title = dialogTitle("添加指定日期时间");
        panel.addView(title, matchWrapWithTop(14));
        TextView subtitle = dialogSubtitle("最高优先级，不受每日允许时间段限制");
        panel.addView(subtitle, matchWrap());

        EditText input = dialogInput("2026-05-20 15:30", InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_NORMAL);
        input.setText(dateTimeInput.getText().toString());
        panel.addView(input, matchWrapWithTop(16));

        Button choose = button("选择日期和时间", 0xFF101828, 0xFFF1F5F9, 0xFFF1F5F9);
        choose.setOnClickListener(v -> showDateTimePicker(input));
        panel.addView(choose, matchWrapWithTop(10));

        AlertDialog dialog = createContentDialog(panel);
        LinearLayout actions = dialogActions();
        Button cancel = button("取消", 0xFF667085, 0xFFF7F8FA, 0xFFF7F8FA);
        cancel.setOnClickListener(v -> dialog.dismiss());
        Button save = button("保存", 0xFFFFFFFF, 0xFF2F66E8, 0xFF2F66E8);
        save.setOnClickListener(v -> {
            String value = normalizeDateTime(input.getText().toString());
            if (!ScheduleConfig.isValidDateTime(value)) {
                toast("请输入日期时间，例如 2026-05-20 15:30");
                return;
            }
            addDateTimeValue(value);
            dateTimeInput.setText("");
            dialog.dismiss();
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(52), 1);
        saveParams.leftMargin = dp(10);
        actions.addView(save, saveParams);
        panel.addView(actions, matchWrapWithTop(16));
        dialog.show();
    }

    private void showDailyTimeDialog() {
        LinearLayout panel = dialogPanel();
        panel.addView(dialogIcon("◷", 0xFFEFF6FF, 0xFF2563EB), new LinearLayout.LayoutParams(dp(62), dp(62)));
        panel.addView(dialogTitle("添加每日时间"), matchWrapWithTop(14));
        panel.addView(dialogSubtitle(ScheduleConfig.allowedTimeDescription()), matchWrap());

        EditText input = dialogInput("18:30", InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        input.setText(timeInput.getText().toString());
        panel.addView(input, matchWrapWithTop(16));

        AlertDialog dialog = createContentDialog(panel);
        Button choose = button("打开 24 小时时间选择器", 0xFF101828, 0xFFF1F5F9, 0xFFF1F5F9);
        choose.setOnClickListener(v -> showTimePicker(input));
        panel.addView(choose, matchWrapWithTop(10));

        LinearLayout actions = dialogActions();
        Button cancel = button("取消", 0xFF667085, 0xFFF7F8FA, 0xFFF7F8FA);
        cancel.setOnClickListener(v -> dialog.dismiss());
        Button save = button("保存", 0xFFFFFFFF, 0xFF2F66E8, 0xFF2F66E8);
        save.setOnClickListener(v -> {
            String value = normalizeTime(input.getText().toString());
            if (!ScheduleConfig.isValidTime(value)) {
                toast("请输入 24 小时时间，例如 18:30");
                return;
            }
            timeInput.setText(value);
            addManualTime();
            dialog.dismiss();
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(52), 1);
        saveParams.leftMargin = dp(10);
        actions.addView(save, saveParams);
        panel.addView(actions, matchWrapWithTop(16));
        dialog.show();
    }

    private void showTargetDialog() {
        LinearLayout panel = dialogPanel();
        panel.addView(dialogIcon("◎", 0xFFEFF6FF, 0xFF2563EB), new LinearLayout.LayoutParams(dp(62), dp(62)));
        panel.addView(dialogTitle("目标应用"), matchWrapWithTop(14));
        panel.addView(dialogSubtitle("默认飞书 Lark，可指定包名、页面或 Deep link"), matchWrap());

        EditText packageEdit = dialogInput("com.ss.android.lark", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        packageEdit.setText(packageInput.getText().toString());
        panel.addView(packageEdit, matchWrapWithTop(16));

        EditText activityEdit = dialogInput(".main.app.MainActivity", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        activityEdit.setText(activityInput.getText().toString());
        panel.addView(activityEdit, matchWrapWithTop(10));

        EditText deepLinkEdit = dialogInput("Deep link，可留空", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        deepLinkEdit.setText(deepLinkInput.getText().toString());
        panel.addView(deepLinkEdit, matchWrapWithTop(10));

        AlertDialog dialog = createContentDialog(panel);
        LinearLayout actions = dialogActions();
        Button cancel = button("取消", 0xFF667085, 0xFFF7F8FA, 0xFFF7F8FA);
        cancel.setOnClickListener(v -> dialog.dismiss());
        Button save = button("保存", 0xFFFFFFFF, 0xFF2F66E8, 0xFF2F66E8);
        save.setOnClickListener(v -> {
            packageInput.setText(packageEdit.getText().toString().trim());
            activityInput.setText(activityEdit.getText().toString().trim());
            deepLinkInput.setText(deepLinkEdit.getText().toString().trim());
            saveAndSchedule(true);
            updateDashboard();
            dialog.dismiss();
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(52), 1);
        saveParams.leftMargin = dp(10);
        actions.addView(save, saveParams);
        panel.addView(actions, matchWrapWithTop(16));
        dialog.show();
    }

    private void showSettingsDialog() {
        LinearLayout panel = dialogPanel();
        panel.addView(dialogIcon("⚙", 0xFFEFF6FF, 0xFF2563EB), new LinearLayout.LayoutParams(dp(62), dp(62)));
        panel.addView(dialogTitle("权限设置"), matchWrapWithTop(14));
        panel.addView(dialogSubtitle("检查精确闹钟、通知渠道、全屏通知、悬浮窗和电池优化"), matchWrap());

        Switch workdays = new Switch(this);
        workdays.setText("仅周一到周五执行");
        textDp(workdays, 14);
        workdays.setTextColor(0xFF101828);
        workdays.setTypeface(Typeface.DEFAULT_BOLD);
        workdays.setPadding(dp(16), dp(12), dp(16), dp(12));
        workdays.setBackground(rounded(0xFFF7F8FA, 0xFFF7F8FA, 18));
        workdays.setChecked(workdaysOnlySwitch.isChecked());
        panel.addView(workdays, matchWrapWithTop(16));

        Button permission = button("逐项权限引导", 0xFFFFFFFF, 0xFF2F66E8, 0xFF2F66E8);
        panel.addView(permission, matchWrapWithTop(12));

        AlertDialog dialog = createContentDialog(panel);
        permission.setOnClickListener(v -> {
            workdaysOnlySwitch.setChecked(workdays.isChecked());
            saveAndSchedule(false);
            dialog.dismiss();
            openPermissionGuide();
        });

        LinearLayout actions = dialogActions();
        Button cancel = button("取消", 0xFF667085, 0xFFF7F8FA, 0xFFF7F8FA);
        cancel.setOnClickListener(v -> dialog.dismiss());
        Button save = button("保存", 0xFFFFFFFF, 0xFF2F66E8, 0xFF2F66E8);
        save.setOnClickListener(v -> {
            workdaysOnlySwitch.setChecked(workdays.isChecked());
            saveAndSchedule(true);
            updateDashboard();
            dialog.dismiss();
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(52), 1);
        saveParams.leftMargin = dp(10);
        actions.addView(save, saveParams);
        panel.addView(actions, matchWrapWithTop(16));
        dialog.show();
    }

    private void showLogDialog() {
        TextView logView = new TextView(this);
        logView.setText(RunLog.last(this));
        textDp(logView, 12);
        logView.setTextColor(0xFF344054);
        logView.setPadding(dp(18), dp(16), dp(18), dp(16));
        logView.setLineSpacing(dp(2), 1.0f);
        logView.setBackground(rounded(0xFFF7F8FA, 0xFFF7F8FA, 18));

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(logView);

        LinearLayout panel = dialogPanel();
        panel.addView(dialogIcon("≡", 0xFFEFF6FF, 0xFF2563EB), new LinearLayout.LayoutParams(dp(62), dp(62)));
        panel.addView(dialogTitle("运行日志"), matchWrapWithTop(14));
        panel.addView(dialogSubtitle("最近的触发、唤醒和拉起记录"), matchWrap());
        panel.addView(scrollView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360)));

        AlertDialog dialog = createContentDialog(panel);
        Button close = button("关闭", 0xFFFFFFFF, 0xFF2F66E8, 0xFF2F66E8);
        close.setOnClickListener(v -> dialog.dismiss());
        panel.addView(close, matchWrapWithTop(14));
        dialog.show();
    }

    private void testLaunch() {
        ScheduleConfig config = currentConfig();
        if (config.packageName.isEmpty()) {
            toast("请先填写目标包名");
            return;
        }
        TargetLauncher.testLaunch(MainActivity.this, config);
    }

    private LinearLayout dialogPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(24), dp(24), dp(22));
        panel.setBackground(rounded(0xFFFFFFFF, 0xFFFFFFFF, 34));
        return panel;
    }

    private TextView dialogIcon(String value, int backgroundColor, int textColor) {
        TextView icon = new TextView(this);
        icon.setText(value);
        textDp(icon, 28);
        icon.setTextColor(textColor);
        icon.setTypeface(Typeface.DEFAULT_BOLD);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(circleDrawable(backgroundColor, backgroundColor));
        return icon;
    }

    private TextView dialogTitle(String value) {
        TextView title = new TextView(this);
        title.setText(value);
        textDp(title, 18);
        title.setTextColor(0xFF101828);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        return title;
    }

    private TextView dialogSubtitle(String value) {
        TextView subtitle = new TextView(this);
        subtitle.setText(value);
        textDp(subtitle, 12);
        subtitle.setTextColor(0xFF667085);
        subtitle.setPadding(0, dp(5), 0, 0);
        return subtitle;
    }

    private EditText dialogInput(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setInputType(inputType);
        textDp(input, 15);
        input.setTypeface(Typeface.DEFAULT_BOLD);
        input.setTextColor(0xFF101828);
        input.setHintTextColor(0xFF98A2B3);
        input.setPadding(dp(16), 0, dp(16), 0);
        input.setMinHeight(dp(50));
        input.setBackground(rounded(0xFFF1F5F9, 0xFFF1F5F9, 28));
        return input;
    }

    private LinearLayout dialogActions() {
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        return actions;
    }

    private AlertDialog createContentDialog(LinearLayout panel) {
        return new AlertDialog.Builder(this)
                .setView(panel)
                .create();
    }

    private void showTimePicker() {
        showTimePicker(null);
    }

    private void showTimePicker(EditText output) {
        TimePickerDialog dialog = new TimePickerDialog(
                this,
                (view, hourOfDay, minute) -> {
                    String value = String.format(Locale.US, "%02d:%02d", hourOfDay, minute);
                    if (output == null) {
                        addTimeValue(value);
                    } else {
                        output.setText(value);
                    }
                },
                8,
                0,
                true
        );
        dialog.setTitle("选择 24 小时时间");
        dialog.show();
    }

    private void showDateTimePicker() {
        showDateTimePicker(null);
    }

    private void showDateTimePicker(EditText output) {
        Calendar calendar = Calendar.getInstance();
        DatePickerDialog dateDialog = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    TimePickerDialog timeDialog = new TimePickerDialog(
                            this,
                            (timeView, hourOfDay, minute) -> {
                                String value = String.format(Locale.US, "%04d-%02d-%02d %02d:%02d",
                                        year,
                                        month + 1,
                                        dayOfMonth,
                                        hourOfDay,
                                        minute);
                                if (output == null) {
                                    addDateTimeValue(value);
                                } else {
                                    output.setText(value);
                                }
                            },
                            calendar.get(Calendar.HOUR_OF_DAY),
                            calendar.get(Calendar.MINUTE),
                            true
                    );
                    timeDialog.setTitle("选择 24 小时时间");
                    timeDialog.show();
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
        );
        dateDialog.setTitle("选择日期");
        dateDialog.show();
    }

    private void addManualDateTime() {
        String value = normalizeDateTime(dateTimeInput.getText().toString());
        if (!ScheduleConfig.isValidDateTime(value)) {
            toast("请输入日期时间，例如 2026-05-20 15:30");
            return;
        }
        if (ScheduleConfig.parseDateTimeMillis(value) <= System.currentTimeMillis()) {
            toast("指定日期时间必须晚于当前时间");
            return;
        }
        addDateTimeValue(value);
        dateTimeInput.setText("");
    }

    private void addDateTimeValue(String value) {
        if (!ScheduleConfig.isValidDateTime(value)) {
            toast("日期时间格式不正确");
            return;
        }
        if (ScheduleConfig.parseDateTimeMillis(value) <= System.currentTimeMillis()) {
            toast("指定日期时间必须晚于当前时间");
            return;
        }
        if (datedTimes.contains(value)) {
            toast("该指定日期时间已存在");
            return;
        }
        datedTimes.add(value);
        java.util.Collections.sort(datedTimes);
        renderTimes();
        saveAndSchedule(false);
    }

    private void addManualTime() {
        String value = normalizeTime(timeInput.getText().toString());
        if (!ScheduleConfig.isValidTime(value)) {
            toast("请输入 24 小时时间，例如 18:30");
            return;
        }
        if (!ScheduleConfig.isAllowedTriggerTime(value)) {
            toast("该时间不可用，" + ScheduleConfig.allowedTimeDescription());
            return;
        }
        addTimeValue(value);
        timeInput.setText("");
    }

    private void addTimeValue(String value) {
        if (!ScheduleConfig.isAllowedTriggerTime(value)) {
            toast("该时间不可用，" + ScheduleConfig.allowedTimeDescription());
            return;
        }
        if (fixedTimes.contains(value) || times.contains(value)) {
            toast("该时间已存在");
            return;
        }
        if (!times.contains(value)) {
            times.add(value);
            java.util.Collections.sort(times);
            renderTimes();
            saveAndSchedule(false);
        }
    }

    private String normalizeTime(String value) {
        String normalized = value == null ? "" : value.trim()
                .replace('：', ':')
                .replace('.', ':')
                .replace(' ', ':');
        if (normalized.matches("\\d{1}:\\d{2}")) {
            normalized = "0" + normalized;
        }
        return normalized;
    }

    private String normalizeDateTime(String value) {
        String normalized = value == null ? "" : value.trim()
                .replace('：', ':')
                .replace('/', '-')
                .replace('T', ' ');
        while (normalized.contains("  ")) {
            normalized = normalized.replace("  ", " ");
        }
        return normalized;
    }

    private TextView taskDot(String text, int backgroundColor, int textColor) {
        TextView dot = new TextView(this);
        dot.setText(text);
        textDp(dot, 15);
        dot.setTextColor(textColor);
        dot.setTypeface(Typeface.DEFAULT_BOLD);
        dot.setGravity(Gravity.CENTER);
        dot.setBackground(circleDrawable(backgroundColor, backgroundColor));
        return dot;
    }

    private boolean isMorning(String hhmm) {
        if (!ScheduleConfig.isValidTime(hhmm)) {
            return false;
        }
        return Integer.parseInt(hhmm.substring(0, 2)) < 12;
    }

    private void renderTimes() {
        timesContainer.removeAllViews();
        updateDashboard();
        if (datedTimes.isEmpty() && fixedTimes.isEmpty() && times.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有添加时间");
            empty.setTextColor(0xFF6B7280);
            textDp(empty, 13);
            empty.setPadding(dp(16), dp(16), dp(16), dp(16));
            empty.setBackground(rounded(0xFFFFFFFF, 0xFFE1E5EC, 18));
            timesContainer.addView(empty, matchWrap());
            return;
        }
        for (String dateTime : new ArrayList<>(datedTimes)) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(14), dp(14), dp(14));
            row.setBackground(rounded(0xFFFFF7ED, 0xFFFDBA74, 28));
            TextView dot = taskDot("定", 0xFFFFE8C2, 0xFF9A3412);
            row.addView(dot, new LinearLayout.LayoutParams(dp(48), dp(48)));
            TextView value = new TextView(this);
            value.setText("指定日期 · 最高优先级\n" + dateTime.substring(5));
            textDp(value, 16);
            value.setTypeface(Typeface.DEFAULT_BOLD);
            value.setTextColor(0xFF101828);
            LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            valueParams.leftMargin = dp(12);
            row.addView(value, valueParams);
            Button remove = button("删", 0xFF9A3412, 0xFFFFE8C2, 0xFFFFE8C2);
            remove.setOnClickListener(v -> {
                AlarmScheduler.cancelValue(this, dateTime);
                datedTimes.remove(dateTime);
                renderTimes();
                saveAndSchedule(false);
            });
            row.addView(remove, new LinearLayout.LayoutParams(dp(48), dp(48)));
            timesContainer.addView(row, matchWrapWithTop(10));
        }
        for (String time : new ArrayList<>(fixedTimes)) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(14), dp(14), dp(14));
            row.setBackground(rounded(0xFFEFF6FF, 0xFFBFDBFE, 28));
            TextView dot = taskDot(isMorning(time) ? "早" : "晚", 0xFFDBEAFE, 0xFF1D4ED8);
            row.addView(dot, new LinearLayout.LayoutParams(dp(48), dp(48)));
            TextView value = new TextView(this);
            value.setText((isMorning(time) ? "早间随机 08:30-08:50" : "晚间随机 18:10-21:30")
                    + " · 成功后自动换\n" + time);
            textDp(value, 17);
            value.setTypeface(Typeface.DEFAULT_BOLD);
            value.setTextColor(0xFF2542BD);
            LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            valueParams.leftMargin = dp(12);
            row.addView(value, valueParams);
            TextView randomStatus = new TextView(this);
            randomStatus.setText("自动换");
            textDp(randomStatus, 12);
            randomStatus.setTypeface(Typeface.DEFAULT_BOLD);
            randomStatus.setTextColor(0xFF2563EB);
            randomStatus.setGravity(Gravity.CENTER);
            randomStatus.setPadding(dp(10), dp(7), dp(10), dp(7));
            randomStatus.setBackground(rounded(0xFFDBEAFE, 0xFFDBEAFE, 18));
            row.addView(randomStatus, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            timesContainer.addView(row, matchWrapWithTop(10));
        }
        for (String time : new ArrayList<>(times)) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(14), dp(14), dp(14));
            row.setBackground(rounded(0xFFFFFFFF, 0xFFE1E5EC, 28));
            TextView dot = taskDot("自", 0xFFF1F5F9, 0xFF334155);
            row.addView(dot, new LinearLayout.LayoutParams(dp(48), dp(48)));
            TextView value = new TextView(this);
            value.setText("每日自定义\n" + time);
            textDp(value, 17);
            value.setTypeface(Typeface.DEFAULT_BOLD);
            value.setTextColor(0xFF101828);
            LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            valueParams.leftMargin = dp(12);
            row.addView(value, valueParams);
            Button remove = button("删", 0xFFBE123C, 0xFFFFF1F2, 0xFFFFF1F2);
            remove.setOnClickListener(v -> {
                times.remove(time);
                renderTimes();
                saveAndSchedule(false);
            });
            row.addView(remove, new LinearLayout.LayoutParams(dp(48), dp(48)));
            timesContainer.addView(row, matchWrapWithTop(10));
        }
    }

    private void updateDashboard() {
        if (nextTimeView == null) {
            return;
        }
        boolean enabled = enableSwitch != null && enableSwitch.isChecked();
        String nextValue = nextDisplayValue();
        String nextMeta = nextMetaText(nextValue);
        nextTimeView.setText(nextValue.isEmpty() ? "--:--" : nextValue);
        nextMetaView.setText(nextMeta);
        nextTimeView.setAlpha(enabled ? 1.0f : 0.55f);
        nextMetaView.setAlpha(enabled ? 1.0f : 0.7f);
        String packageName = packageInput == null ? ScheduleConfig.DEFAULT_PACKAGE_NAME : packageInput.getText().toString().trim();
        if (packageName.isEmpty()) {
            packageName = ScheduleConfig.DEFAULT_PACKAGE_NAME;
        }
        targetSummaryView.setText("目标应用\n" + targetDisplayName(packageName));
        int total = datedTimes.size() + fixedTimes.size() + times.size();
        taskCountView.setText("任务数量\n" + total);
    }

    private String targetDisplayName(String packageName) {
        if (ScheduleConfig.DEFAULT_PACKAGE_NAME.equals(packageName)) {
            return "飞书 Lark";
        }
        return packageName;
    }

    private String nextDisplayValue() {
        if (enableSwitch == null || !enableSwitch.isChecked()) {
            return "";
        }
        long now = System.currentTimeMillis();
        String nextDateTime = "";
        long nextDateTimeMillis = Long.MAX_VALUE;
        for (String value : datedTimes) {
            long millis = ScheduleConfig.parseDateTimeMillis(value);
            if (millis > now && millis < nextDateTimeMillis) {
                nextDateTimeMillis = millis;
                nextDateTime = value;
            }
        }
        String nextDailyTime = nextDailyTime();
        if (!nextDateTime.isEmpty() && (nextDailyTime.isEmpty() || nextDateTimeMillis <= nextDailyMillis(nextDailyTime))) {
            return nextDateTime.substring(5);
        }
        return nextDailyTime;
    }

    private String nextMetaText(String nextValue) {
        if (enableSwitch == null || !enableSwitch.isChecked()) {
            return "任务已停用";
        }
        if (nextValue.isEmpty()) {
            return "暂无可执行时间";
        }
        if (nextValue.length() > 5) {
            return "指定日期时间 · 不受每日时间限制";
        }
        long triggerAt = nextDailyMillis(nextValue);
        if (triggerAt == Long.MAX_VALUE) {
            return "暂无可执行时间";
        }
        String date = new SimpleDateFormat("MM-dd EEE", Locale.CHINA).format(new Date(triggerAt));
        return (workdaysOnlySwitch != null && workdaysOnlySwitch.isChecked())
                ? "每日任务 · " + date + "（工作日）"
                : "每日任务 · " + date;
    }

    private String nextDailyTime() {
        ArrayList<String> values = new ScheduleConfig(
                true,
                ScheduleConfig.DEFAULT_PACKAGE_NAME,
                ScheduleConfig.DEFAULT_ACTIVITY_NAME,
                "",
                true,
                fixedTimes,
                times,
                new ArrayList<String>()
        ).allTimes();
        if (values.isEmpty()) {
            return "";
        }
        String bestValue = "";
        long bestMillis = Long.MAX_VALUE;
        for (String value : values) {
            if (!ScheduleConfig.isAllowedTriggerTime(value)) {
                continue;
            }
            long triggerAt = nextDailyMillis(value);
            if (triggerAt < bestMillis) {
                bestMillis = triggerAt;
                bestValue = value;
            }
        }
        return bestValue;
    }

    private long nextDailyMillis(String hhmm) {
        if (!ScheduleConfig.isValidTime(hhmm)) {
            return Long.MAX_VALUE;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hhmm.substring(0, 2)));
        calendar.set(Calendar.MINUTE, Integer.parseInt(hhmm.substring(3, 5)));
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        boolean workdaysOnly = workdaysOnlySwitch != null && workdaysOnlySwitch.isChecked();
        while (calendar.getTimeInMillis() <= System.currentTimeMillis()
                || (workdaysOnly && isWeekend(calendar))) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }
        return calendar.getTimeInMillis();
    }

    private boolean isWeekend(Calendar calendar) {
        int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
        return dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY;
    }

    private void updatePermissionHint() {
        refreshLastLog();
        updateDashboard();
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

    private void stylePanelSwitch(Switch view) {
        view.setPadding(dp(14), dp(10), dp(14), dp(10));
        view.setBackground(rounded(0xFFFFFFFF, 0xFFE5E7EB, 16));
    }

    private void styleInput(EditText input) {
        input.setTextColor(0xFF111827);
        input.setHintTextColor(0xFF9CA3AF);
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setMinHeight(dp(48));
        input.setBackground(rounded(0xFFFFFFFF, 0xFFD1D5DB, 14));
    }

    private GradientDrawable rounded(int color, int strokeColor, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private GradientDrawable circleDrawable(int color, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }

    private void refreshLastLog() {
        if (lastLogView != null) {
            lastLogView.setText(RunLog.last(this));
        }
    }
}
