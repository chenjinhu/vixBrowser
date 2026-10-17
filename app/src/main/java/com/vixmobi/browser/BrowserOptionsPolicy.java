package com.vixmobi.browser;

/** Validation shared by the UI, Intent entry point and HTTP settings. */
public final class BrowserOptionsPolicy {
    private BrowserOptionsPolicy() {}

    public static String normalizeUserAgent(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        if (raw.length() > 4096) {
            throw new IllegalArgumentException("UA must not exceed 4096 characters");
        }
        for (int i = 0; i < raw.length(); i++) {
            char value = raw.charAt(i);
            if (value < 32 || value > 126) {
                throw new IllegalArgumentException("UA must contain printable ASCII characters only");
            }
        }
        return raw.trim();
    }

    public static Integer normalizeHistoryLength(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number)) {
            throw new IllegalArgumentException("history_length must be an integer or null");
        }
        double value = ((Number) raw).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0
                || value > Integer.MAX_VALUE || value != Math.rint(value)) {
            throw new IllegalArgumentException("history_length must be between 0 and 2147483647");
        }
        return (int) value;
    }

    public static Integer parseHistoryLength(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return normalizeHistoryLength(Integer.valueOf(raw.trim()));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("history_length must be between 0 and 2147483647");
        }
    }
}
