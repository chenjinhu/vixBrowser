package com.vixmobi.browser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class UrlPolicyTest {
    @Test
    public void preservesEncodedPathQueryAndFragment() {
        String url = "https://example.com/a%20b?q=%E4%B8%AD%E6%96%87&x=1#section";
        assertEquals(url, UrlPolicy.normalize(url));
    }

    @Test
    public void trimsInputAndNormalizesScheme() {
        assertEquals("https://example.com/path", UrlPolicy.normalize("  HTTPS://example.com/path  "));
    }

    @Test
    public void completesAddressWithoutScheme() {
        assertEquals("https://example.com/path", UrlPolicy.normalize("example.com/path"));
        assertEquals("https://example.com/path", UrlPolicy.normalize("//example.com/path"));
    }

    @Test
    public void acceptsLocalDevelopmentAddressesAndIpv6() {
        assertEquals("http://10.0.2.2:8080/", UrlPolicy.normalize("http://10.0.2.2:8080/"));
        assertEquals("http://localhost:3000/", UrlPolicy.normalize("http://localhost:3000/"));
        assertEquals("http://[::1]:8080/", UrlPolicy.normalize("http://[::1]:8080/"));
    }

    @Test
    public void rejectsNonWebSchemes() {
        for (String url : new String[]{"javascript:alert(1)", "file:///sdcard/private.txt",
                "content://settings/system", "data:text/html,test", "intent://example.com",
                "ftp://example.com", "about:blank"}) {
            assertNull(url, UrlPolicy.normalize(url));
        }
    }

    @Test
    public void rejectsEmptyMalformedOrAmbiguousAddresses() {
        for (String url : new String[]{null, "", "   ", "https://", "http:/example.com",
                "https://exa mple.com", "https://example.com/\npath", "https://example.com/%ZZ",
                "https://user:password@example.com", "https://example.com:0",
                "https://example.com:65536", "https://example.com:",
                "https://example.com:-2", "https://example.com\\@evil.test"}) {
            assertNull(url, UrlPolicy.normalize(url));
        }
    }
}
