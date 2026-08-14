package com.autoopenapp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
