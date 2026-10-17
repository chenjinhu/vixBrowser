package com.vixmobi.browser;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** The exported activity accepts web addresses only, never local files or executable schemes. */
public final class UrlPolicy {
    private UrlPolicy() {}

    /** Returns a normalized HTTP(S) URL, or null for invalid/unsupported input. */
    public static String normalize(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String candidate = raw.trim();
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '\\') {
                return null;
            }
        }
        if (candidate.startsWith("//")) {
            candidate = "https:" + candidate;
        }
        try {
            URI uri = new URI(candidate);
            if (uri.getScheme() == null) {
                candidate = "https://" + candidate;
                uri = new URI(candidate);
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getRawUserInfo() != null
                    || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535
                    || uri.getRawAuthority().endsWith(":")) {
                return null;
            }
            return scheme + candidate.substring(candidate.indexOf(':'));
        } catch (URISyntaxException exception) {
            return null;
        }
    }
}
