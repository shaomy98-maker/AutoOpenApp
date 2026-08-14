package com.autoopenapp;

import java.nio.charset.StandardCharsets;

/** Builds stable, collision-free PendingIntent data identities without Android dependencies. */
final class AlarmIdentity {
    private static final String PREFIX = "autoopenapp://alarm/";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private AlarmIdentity() {
    }

    static String mainOperation(String alarmValue) {
        return build("main", alarmValue);
    }

    static String mainShow(String alarmValue) {
        return build("main-show", alarmValue);
    }

    static String retryOperation(String alarmValue) {
        return build("retry", alarmValue);
    }

    static String retryShow(String alarmValue) {
        return build("retry-show", alarmValue);
    }

    private static String build(String type, String alarmValue) {
        return PREFIX + type + "/" + percentEncode(alarmValue == null ? "" : alarmValue);
    }

    private static String percentEncode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte raw : bytes) {
            int valueByte = raw & 0xff;
            if ((valueByte >= 'a' && valueByte <= 'z')
                    || (valueByte >= 'A' && valueByte <= 'Z')
                    || (valueByte >= '0' && valueByte <= '9')
                    || valueByte == '-' || valueByte == '.' || valueByte == '_'
                    || valueByte == '~') {
                encoded.append((char) valueByte);
            } else {
                encoded.append('%')
                        .append(HEX[valueByte >>> 4])
                        .append(HEX[valueByte & 0x0f]);
            }
        }
        return encoded.toString();
    }
}
