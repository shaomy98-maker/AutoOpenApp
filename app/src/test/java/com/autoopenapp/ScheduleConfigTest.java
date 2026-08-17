package com.autoopenapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class ScheduleConfigTest {
    @Test
    public void allowedDailyWindows_includeOnlyConfiguredRanges() {
        assertTrue(ScheduleConfig.isAllowedTriggerTime("08:00"));
        assertTrue(ScheduleConfig.isAllowedTriggerTime("09:00"));
        assertTrue(ScheduleConfig.isAllowedTriggerTime("18:00"));
        assertTrue(ScheduleConfig.isAllowedTriggerTime("22:00"));

        assertFalse(ScheduleConfig.isAllowedTriggerTime("07:59"));
        assertFalse(ScheduleConfig.isAllowedTriggerTime("09:01"));
        assertFalse(ScheduleConfig.isAllowedTriggerTime("17:59"));
        assertFalse(ScheduleConfig.isAllowedTriggerTime("22:01"));
        assertFalse(ScheduleConfig.isAllowedTriggerTime("24:00"));
    }

    @Test
    public void datedTimeParsing_isStrict() {
        assertTrue(ScheduleConfig.isValidDateTime("2028-02-29 08:30"));

        assertFalse(ScheduleConfig.isValidDateTime("2026-02-29 08:30"));
        assertFalse(ScheduleConfig.isValidDateTime("2026-13-01 08:30"));
        assertFalse(ScheduleConfig.isValidDateTime("2026-08-14 24:00"));
        assertFalse(ScheduleConfig.isValidDateTime("2026/08/14 08:30"));
    }

    @Test
    public void fixedRandomTimes_repairEveningToSafeClockOutWindow() {
        ScheduleConfig config = new ScheduleConfig(
                true,
                ScheduleConfig.DEFAULT_PACKAGE_NAME,
                ScheduleConfig.DEFAULT_ACTIVITY_NAME,
                "",
                true,
                Arrays.asList("08:50", "18:10"),
                Collections.<String>emptyList()
        );

        ScheduleConfig updated = config.withGeneratedFixedTimesIfNeeded();

        assertEquals("08:50", updated.fixedTimes.get(0));
        assertTrue(minutesOfDay(updated.fixedTimes.get(1)) >= minutesOfDay("21:30"));
        assertTrue(minutesOfDay(updated.fixedTimes.get(1)) <= minutesOfDay("22:00"));
        assertTrue(minutesOfDay(updated.fixedTimes.get(1)) - minutesOfDay(updated.fixedTimes.get(0)) >= 9 * 60 + 30);
    }

    @Test
    public void fixedRandomTimes_keepTodayEveningAfterMorningSuccess() {
        ScheduleConfig config = new ScheduleConfig(
                true,
                ScheduleConfig.DEFAULT_PACKAGE_NAME,
                ScheduleConfig.DEFAULT_ACTIVITY_NAME,
                "",
                true,
                Arrays.asList("08:43", "21:45"),
                Collections.<String>emptyList()
        );

        ScheduleConfig updated = config.withRegeneratedFixedTime("08:43");

        assertEquals(Arrays.asList("08:43", "21:45"), updated.fixedTimes);
    }

    @Test
    public void fixedRandomTimes_regeneratePairAfterEveningSuccess() {
        ScheduleConfig config = new ScheduleConfig(
                true,
                ScheduleConfig.DEFAULT_PACKAGE_NAME,
                ScheduleConfig.DEFAULT_ACTIVITY_NAME,
                "",
                true,
                Arrays.asList("08:43", "21:45"),
                Collections.<String>emptyList()
        );

        ScheduleConfig updated = config.withRegeneratedFixedTime("21:45");

        assertEquals(2, updated.fixedTimes.size());
        assertTrue(minutesOfDay(updated.fixedTimes.get(0)) >= minutesOfDay("08:30"));
        assertTrue(minutesOfDay(updated.fixedTimes.get(0)) <= minutesOfDay("08:50"));
        assertTrue(minutesOfDay(updated.fixedTimes.get(1)) >= minutesOfDay("21:30"));
        assertTrue(minutesOfDay(updated.fixedTimes.get(1)) <= minutesOfDay("22:00"));
        assertTrue(minutesOfDay(updated.fixedTimes.get(1)) - minutesOfDay(updated.fixedTimes.get(0)) >= 9 * 60 + 30);
    }

    private static int minutesOfDay(String value) {
        return Integer.parseInt(value.substring(0, 2)) * 60 + Integer.parseInt(value.substring(3, 5));
    }
}
