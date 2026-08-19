package com.autoopenapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Random;

import org.junit.Test;

public class ScheduleConfigTest {
    @Test
    public void allowedDailyWindows_includeOnlyConfiguredRanges() {
        assertTrue(ScheduleConfig.isAllowedTriggerTime("08:20"));
        assertTrue(ScheduleConfig.isAllowedTriggerTime("09:00"));
        assertTrue(ScheduleConfig.isAllowedTriggerTime("18:00"));
        assertTrue(ScheduleConfig.isAllowedTriggerTime("22:00"));

        assertFalse(ScheduleConfig.isAllowedTriggerTime("08:19"));
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
    public void fixedRandomTimes_dropLegacyDailyEveningTime() {
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

        assertEquals(Collections.singletonList("08:50"), updated.fixedTimes);
    }

    @Test
    public void fixedRandomTimes_keepTodayEveningAfterMorningSuccess() {
        ScheduleConfig config = new ScheduleConfig(
                true,
                ScheduleConfig.DEFAULT_PACKAGE_NAME,
                ScheduleConfig.DEFAULT_ACTIVITY_NAME,
                "",
                true,
                Collections.singletonList("08:43"),
                Collections.<String>emptyList()
        );

        ScheduleConfig updated = config.withRegeneratedFixedTime("08:43");

        assertEquals(Collections.singletonList("08:43"), updated.fixedTimes);
    }

    @Test
    public void completedMorning_createsNormalWednesdayDatedEveningBeforeNineThirty() {
        ScheduleConfig config = new ScheduleConfig(
                true,
                ScheduleConfig.DEFAULT_PACKAGE_NAME,
                ScheduleConfig.DEFAULT_ACTIVITY_NAME,
                "",
                true,
                Collections.singletonList("08:43"),
                Collections.<String>emptyList()
        );

        ScheduleConfig updated = config.withRegeneratedFixedTime(
                "08:43",
                calendar(2026, Calendar.AUGUST, 19, 9, 0),
                new Random(0)
        );

        assertEquals(1, updated.datedTimes.size());
        assertTrue(updated.datedTimes.get(0).startsWith("2026-08-19 "));
        String time = updated.datedTimes.get(0).substring(11);
        assertTrue(minutesOfDay(time) >= minutesOfDay("18:13"));
        assertTrue(minutesOfDay(time) < minutesOfDay("21:30"));
        assertTrue(minutesOfDay(time) <= minutesOfDay("21:29"));
    }

    @Test
    public void completedMorning_neverCreatesNormalWednesdayDatedEveningAtOrAfterNineThirty() {
        ScheduleConfig config = new ScheduleConfig(
                true,
                ScheduleConfig.DEFAULT_PACKAGE_NAME,
                ScheduleConfig.DEFAULT_ACTIVITY_NAME,
                "",
                true,
                Collections.singletonList("08:43"),
                Collections.<String>emptyList()
        );

        ScheduleConfig updated = config.withRegeneratedFixedTime(
                "08:43",
                calendar(2026, Calendar.AUGUST, 19, 9, 0),
                maxRandom()
        );

        String time = updated.datedTimes.get(0).substring(11);
        assertEquals("21:29", time);
    }

    @Test
    public void completedMorning_keepsExistingValidTodayEveningStable() {
        ScheduleConfig config = new ScheduleConfig(
                true,
                ScheduleConfig.DEFAULT_PACKAGE_NAME,
                ScheduleConfig.DEFAULT_ACTIVITY_NAME,
                "",
                true,
                Collections.singletonList("08:43"),
                Collections.<String>emptyList(),
                Collections.singletonList("2026-08-19 18:30")
        );

        ScheduleConfig updated = config.withRegeneratedFixedTime(
                "08:43",
                calendar(2026, Calendar.AUGUST, 19, 9, 0),
                maxRandom()
        );

        assertEquals(Collections.singletonList("2026-08-19 18:30"), updated.datedTimes);
    }

    @Test
    public void completedMorning_replacesExistingInvalidNormalWednesdayEvening() {
        ScheduleConfig config = new ScheduleConfig(
                true,
                ScheduleConfig.DEFAULT_PACKAGE_NAME,
                ScheduleConfig.DEFAULT_ACTIVITY_NAME,
                "",
                true,
                Collections.singletonList("08:43"),
                Collections.<String>emptyList(),
                Collections.singletonList("2026-08-19 21:41")
        );

        ScheduleConfig updated = config.withRegeneratedFixedTime(
                "08:43",
                calendar(2026, Calendar.AUGUST, 19, 9, 0),
                maxRandom()
        );

        assertEquals(Collections.singletonList("2026-08-19 21:29"), updated.datedTimes);
    }

    @Test
    public void completedMorning_createsOvertimeMondayDatedEveningAfterNineThirty() {
        ScheduleConfig config = new ScheduleConfig(
                true,
                ScheduleConfig.DEFAULT_PACKAGE_NAME,
                ScheduleConfig.DEFAULT_ACTIVITY_NAME,
                "",
                true,
                Collections.singletonList("08:43"),
                Collections.<String>emptyList()
        );

        ScheduleConfig updated = config.withRegeneratedFixedTime(
                "08:43",
                calendar(2026, Calendar.AUGUST, 17, 9, 0),
                new Random(0)
        );

        assertEquals(1, updated.datedTimes.size());
        String time = updated.datedTimes.get(0).substring(11);
        assertTrue(minutesOfDay(time) >= minutesOfDay("21:30"));
        assertTrue(minutesOfDay(time) <= minutesOfDay("22:00"));
    }

    @Test
    public void completedDatedEvening_regeneratesOnlyNextMorning() {
        ScheduleConfig config = new ScheduleConfig(
                true,
                ScheduleConfig.DEFAULT_PACKAGE_NAME,
                ScheduleConfig.DEFAULT_ACTIVITY_NAME,
                "",
                true,
                Collections.singletonList("08:43"),
                Collections.<String>emptyList(),
                Collections.singletonList("2026-08-19 18:30")
        );

        ScheduleConfig updated = config.withRegeneratedFixedTime(
                "2026-08-19 18:30",
                calendar(2026, Calendar.AUGUST, 19, 19, 0),
                new Random(0)
        );

        assertTrue(updated.datedTimes.isEmpty());
        assertEquals(1, updated.fixedTimes.size());
        assertTrue(minutesOfDay(updated.fixedTimes.get(0)) >= minutesOfDay("08:30"));
        assertTrue(minutesOfDay(updated.fixedTimes.get(0)) <= minutesOfDay("08:50"));
    }

    private static int minutesOfDay(String value) {
        return Integer.parseInt(value.substring(0, 2)) * 60 + Integer.parseInt(value.substring(3, 5));
    }

    private static Calendar calendar(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(year, month, day, hour, minute, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }

    private static Random maxRandom() {
        return new Random() {
            @Override
            public int nextInt(int bound) {
                return bound - 1;
            }
        };
    }
}
