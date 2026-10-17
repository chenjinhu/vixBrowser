package com.vixmobi.browser;

import java.net.URI;
import java.net.URISyntaxException;

/** Validates a custom HTTP Referer before passing it to WebView. */
public final class RefererPolicy {
    private static final int MAX_LENGTH = 4096;

    private RefererPolicy() {}

    /** Null/empty means WebView defaults; invalid values throw without changing settings. */
    public static String normalize(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        if (raw.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Referer is too long");
        }
        for (int i = 0; i < raw.length(); i++) {
            if (Character.isISOControl(raw.charAt(i))) {
                throw new IllegalArgumentException("Referer contains a control character");
            }
        }
        String candidate = raw.trim();
        String normalized = UrlPolicy.normalize(candidate);
        try {
            if (normalized == null || new URI(candidate).getScheme() == null) {
                throw new IllegalArgumentException("Referer must be a complete HTTP or HTTPS URL");
            }
            // Fragments are not part of an HTTP Referer. Encode Unicode for the header.
            int fragment = normalized.indexOf('#');
            if (fragment >= 0) {
                normalized = normalized.substring(0, fragment);
            }
            String ascii = new URI(normalized).toASCIIString();
            if (ascii.length() > MAX_LENGTH) {
                throw new IllegalArgumentException("Referer is too long");
            }
            return ascii;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Referer must be a valid URL", exception);
        }
    }
}
