package com.autoopenapp;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;

final class ScheduleConfig {
    static final String EXTRA_ALARM_TIME = "alarm_time";
    static final String DEFAULT_PACKAGE_NAME = "com.ss.android.lark";
    static final String DEFAULT_ACTIVITY_NAME = ".main.app.MainActivity";
    private static final int MINIMUM_FIXED_GAP_MINUTES = 8 * 60 + 1;
    private static final int MORNING_START_MINUTES = 8 * 60 + 30;
    private static final int MORNING_END_MINUTES = 8 * 60 + 50;
    private static final int EVENING_START_MINUTES = 18 * 60 + 10;
    private static final int EVENING_END_MINUTES = 21 * 60 + 30;

    final boolean enabled;
    final String packageName;
    final String activityName;
    final String deepLink;
    final boolean workdaysOnly;
    final ArrayList<String> fixedTimes;
    final ArrayList<String> times;
    final ArrayList<String> datedTimes;

    ScheduleConfig(boolean enabled, String packageName, String activityName, String deepLink, boolean workdaysOnly, List<String> times) {
        this(enabled, packageName, activityName, deepLink, workdaysOnly, new ArrayList<String>(), times, new ArrayList<String>());
    }

    ScheduleConfig(boolean enabled, String packageName, String activityName, String deepLink, boolean workdaysOnly, List<String> fixedTimes, List<String> times) {
        this(enabled, packageName, activityName, deepLink, workdaysOnly, fixedTimes, times, new ArrayList<String>());
    }

    ScheduleConfig(boolean enabled, String packageName, String activityName, String deepLink, boolean workdaysOnly, List<String> fixedTimes, List<String> times, List<String> datedTimes) {
        this.enabled = enabled;
        String cleanPackage = clean(packageName);
        String cleanActivity = clean(activityName);
        this.packageName = TextUtils.isEmpty(cleanPackage) ? DEFAULT_PACKAGE_NAME : cleanPackage;
        this.activityName = TextUtils.isEmpty(cleanActivity) ? DEFAULT_ACTIVITY_NAME : cleanActivity;
        this.deepLink = clean(deepLink);
        this.workdaysOnly = workdaysOnly;
        this.fixedTimes = cleanTimes(fixedTimes);
        this.times = cleanTimes(times);
        this.datedTimes = cleanDateTimes(datedTimes);
    }

    static ScheduleConfig empty() {
        return new ScheduleConfig(true, DEFAULT_PACKAGE_NAME, DEFAULT_ACTIVITY_NAME, "", true, new ArrayList<String>());
    }

    boolean isRunnable() {
        return enabled && !TextUtils.isEmpty(packageName) && (!allTimes().isEmpty() || !datedTimes.isEmpty());
    }

    ArrayList<String> allTimes() {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        merged.addAll(fixedTimes);
        merged.addAll(times);
        ArrayList<String> values = new ArrayList<>(merged);
        Collections.sort(values);
        return values;
    }

    ScheduleConfig withGeneratedFixedTimesIfNeeded() {
        Random random = new Random();
        ArrayList<String> generated = new ArrayList<>();
        String morning = firstFixedTimeInRange(fixedTimes, MORNING_START_MINUTES, MORNING_END_MINUTES);
        if (TextUtils.isEmpty(morning)) {
            morning = randomMorningTime(random, generated);
        }
        generated.add(morning);

        String evening = firstFixedTimeInRange(fixedTimes, EVENING_START_MINUTES, EVENING_END_MINUTES);
        if (TextUtils.isEmpty(evening) || !hasRequiredFixedGap(morning, evening)) {
            evening = randomEveningTime(random, generated);
        }
        generated.add(evening);
        if (generated.equals(fixedTimes)) {
            return this;
        }
        return new ScheduleConfig(enabled, packageName, activityName, deepLink, workdaysOnly, generated, times, datedTimes);
    }

    ScheduleConfig withRegeneratedFixedTime(String completedTime) {
        if (TextUtils.isEmpty(completedTime) || !fixedTimes.contains(completedTime)) {
            return this;
        }
        Random random = new Random();
        ArrayList<String> nextFixedTimes = new ArrayList<>(fixedTimes);
        int index = nextFixedTimes.indexOf(completedTime);
        if (isMorningFixedTime(completedTime)) {
            nextFixedTimes.set(index, randomMorningTime(random, nextFixedTimes));
        } else if (isEveningFixedTime(completedTime)) {
            nextFixedTimes.set(index, randomEveningTime(random, nextFixedTimes));
        }
        return new ScheduleConfig(enabled, packageName, activityName, deepLink, workdaysOnly, nextFixedTimes, times, datedTimes);
    }

    ScheduleConfig withoutDatedTime(String completedTime) {
        if (!isValidDateTime(completedTime) || !datedTimes.contains(completedTime)) {
            return this;
        }
        ArrayList<String> nextDatedTimes = new ArrayList<>(datedTimes);
        nextDatedTimes.remove(completedTime);
        return new ScheduleConfig(enabled, packageName, activityName, deepLink, workdaysOnly, fixedTimes, times, nextDatedTimes);
    }

    String toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("enabled", enabled);
            object.put("packageName", packageName);
            object.put("activityName", activityName);
            object.put("deepLink", deepLink);
            object.put("workdaysOnly", workdaysOnly);
            JSONArray fixedArray = new JSONArray();
            for (String time : fixedTimes) {
                fixedArray.put(time);
            }
            object.put("fixedTimes", fixedArray);
            JSONArray array = new JSONArray();
            for (String time : times) {
                array.put(time);
            }
            object.put("times", array);
            JSONArray datedArray = new JSONArray();
            for (String dateTime : datedTimes) {
                datedArray.put(dateTime);
            }
            object.put("datedTimes", datedArray);
        } catch (JSONException ignored) {
        }
        return object.toString();
    }

    static ScheduleConfig fromJson(String json) {
        if (TextUtils.isEmpty(json)) {
            return empty();
        }
        try {
            JSONObject object = new JSONObject(json);
            JSONArray array = object.optJSONArray("times");
            JSONArray fixedArray = object.optJSONArray("fixedTimes");
            JSONArray datedArray = object.optJSONArray("datedTimes");
            ArrayList<String> times = new ArrayList<>();
            ArrayList<String> fixedTimes = new ArrayList<>();
            ArrayList<String> datedTimes = new ArrayList<>();
            if (fixedArray != null) {
                for (int i = 0; i < fixedArray.length(); i++) {
                    String value = fixedArray.optString(i);
                    if (isValidTime(value)) {
                        fixedTimes.add(value);
                    }
                }
            }
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    String value = array.optString(i);
                    if (isValidTime(value)) {
                        times.add(value);
                    }
                }
            }
            if (datedArray != null) {
                for (int i = 0; i < datedArray.length(); i++) {
                    String value = datedArray.optString(i);
                    if (isValidDateTime(value)) {
                        datedTimes.add(value);
                    }
                }
            }
            return new ScheduleConfig(
                    object.optBoolean("enabled", true),
                    object.optString("packageName", ""),
                    object.optString("activityName", ""),
                    object.optString("deepLink", ""),
                    object.optBoolean("workdaysOnly", true),
                    fixedTimes,
                    times,
                    datedTimes
            );
        } catch (JSONException e) {
            return empty();
        }
    }

    static boolean isValidTime(String value) {
        if (value == null || !value.matches("\\d{2}:\\d{2}")) {
            return false;
        }
        int hour = Integer.parseInt(value.substring(0, 2));
        int minute = Integer.parseInt(value.substring(3, 5));
        return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59;
    }

    static boolean isValidDateTime(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")) {
            return false;
        }
        return parseDateTimeMillis(value) > 0;
    }

    static long parseDateTimeMillis(String value) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            format.setLenient(false);
            Date date = format.parse(value);
            return date == null ? -1L : date.getTime();
        } catch (ParseException e) {
            return -1L;
        }
    }

    static boolean isAllowedTriggerTime(String value) {
        if (!isValidTime(value)) {
            return false;
        }
        int minutes = minutesOfDay(value);
        return (minutes >= minutesOfDay("08:00") && minutes <= minutesOfDay("09:00"))
                || (minutes >= minutesOfDay("18:00") && minutes <= minutesOfDay("22:00"));
    }

    static String allowedTimeDescription() {
        return "仅允许 08:00-09:00、18:00-22:00";
    }

    static String fixedTimeDescription() {
        return "固定随机：08:30-08:50、18:10-21:30，间隔超过 8 小时";
    }

    private static int minutesOfDay(String value) {
        return Integer.parseInt(value.substring(0, 2)) * 60 + Integer.parseInt(value.substring(3, 5));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static ArrayList<String> cleanTimes(List<String> values) {
        ArrayList<String> cleanValues = new ArrayList<>();
        if (values == null) {
            return cleanValues;
        }
        for (String value : values) {
            if (isValidTime(value) && !cleanValues.contains(value)) {
                cleanValues.add(value);
            }
        }
        Collections.sort(cleanValues);
        return cleanValues;
    }

    private static ArrayList<String> cleanDateTimes(List<String> values) {
        ArrayList<String> cleanValues = new ArrayList<>();
        if (values == null) {
            return cleanValues;
        }
        for (String value : values) {
            if (isValidDateTime(value) && !cleanValues.contains(value)) {
                cleanValues.add(value);
            }
        }
        Collections.sort(cleanValues);
        return cleanValues;
    }

    private static String randomMorningTime(Random random, List<String> existing) {
        return randomTime(random, MORNING_START_MINUTES, MORNING_END_MINUTES, existing);
    }

    private static String randomEveningTime(Random random, List<String> existing) {
        return randomTime(random, EVENING_START_MINUTES, EVENING_END_MINUTES, existing);
    }

    private static String randomTime(Random random, int startMinutes, int endMinutes, List<String> existing) {
        int range = endMinutes - startMinutes + 1;
        for (int i = 0; i < range * 2; i++) {
            String value = formatMinutes(startMinutes + random.nextInt(range));
            if (!existing.contains(value)) {
                return value;
            }
        }
        return formatMinutes(startMinutes + random.nextInt(range));
    }

    private static String firstFixedTimeInRange(List<String> values, int startMinutes, int endMinutes) {
        for (String value : values) {
            if (!isValidTime(value)) {
                continue;
            }
            int minutes = minutesOfDay(value);
            if (minutes >= startMinutes && minutes <= endMinutes) {
                return value;
            }
        }
        return "";
    }

    private static boolean isMorningFixedTime(String value) {
        if (!isValidTime(value)) {
            return false;
        }
        int minutes = minutesOfDay(value);
        return minutes >= MORNING_START_MINUTES && minutes <= MORNING_END_MINUTES;
    }

    private static boolean isEveningFixedTime(String value) {
        if (!isValidTime(value)) {
            return false;
        }
        int minutes = minutesOfDay(value);
        return minutes >= EVENING_START_MINUTES && minutes <= EVENING_END_MINUTES;
    }

    private static boolean hasRequiredFixedGap(String morning, String evening) {
        return isValidTime(morning)
                && isValidTime(evening)
                && minutesOfDay(evening) - minutesOfDay(morning) >= MINIMUM_FIXED_GAP_MINUTES;
    }

    private static String formatMinutes(int minutes) {
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }
}
