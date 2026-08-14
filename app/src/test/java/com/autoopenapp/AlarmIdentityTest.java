package com.autoopenapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class AlarmIdentityTest {
    @Test
    public void datedValuesThatCollidedAsRequestCodes_haveDistinctIdentities() {
        String first = AlarmIdentity.mainOperation("2026-08-16 02:10");
        String second = AlarmIdentity.mainOperation("2026-08-17 00:00");

        assertNotEquals(first, second);
    }

    @Test
    public void mainAndRetryPendingIntents_useSeparateNamespaces() {
        String value = "2026-10-26 09:55";

        assertNotEquals(AlarmIdentity.mainOperation(value), AlarmIdentity.retryOperation(value));
        assertNotEquals(AlarmIdentity.mainShow(value), AlarmIdentity.retryShow(value));
        assertNotEquals(AlarmIdentity.mainOperation(value), AlarmIdentity.mainShow(value));
    }

    @Test
    public void identityIsStableAndUriSafe() {
        String identity = AlarmIdentity.retryOperation("任务 A / 08:30");

        assertEquals(identity, AlarmIdentity.retryOperation("任务 A / 08:30"));
        assertFalse(identity.contains(" "));
    }
}
