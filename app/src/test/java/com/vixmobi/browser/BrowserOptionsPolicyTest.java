package com.vixmobi.browser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class BrowserOptionsPolicyTest {
    @Test
    public void normalizesUaAndRestoresDefaultsForEmptyInput() {
        assertNull(BrowserOptionsPolicy.normalizeUserAgent(null));
        assertNull(BrowserOptionsPolicy.normalizeUserAgent("   "));
        assertEquals("Mozilla/5.0 (Linux; Android 16) vixBrowser/1.2",
                BrowserOptionsPolicy.normalizeUserAgent(" Mozilla/5.0 (Linux; Android 16) vixBrowser/1.2 "));
    }

    @Test
    public void rejectsHeaderInjectionAndInvalidUaCharacters() {
        for (String value : new String[]{"test\r\nInjected: true", "test\n", "测试/1.0", "test\u007f"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> BrowserOptionsPolicy.normalizeUserAgent(value));
        }
    }

    @Test
    public void rejectsOversizedUa() {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 4097; i++) {
            value.append('a');
        }
        assertThrows(IllegalArgumentException.class,
                () -> BrowserOptionsPolicy.normalizeUserAgent(value.toString()));
    }

    @Test
    public void acceptsHistoryLengthBoundsAndNull() {
        assertNull(BrowserOptionsPolicy.normalizeHistoryLength(null));
        assertEquals(Integer.valueOf(0), BrowserOptionsPolicy.normalizeHistoryLength(0));
        assertEquals(Integer.valueOf(7), BrowserOptionsPolicy.normalizeHistoryLength(7L));
        assertEquals(Integer.valueOf(7), BrowserOptionsPolicy.normalizeHistoryLength(7.0));
        assertEquals(Integer.valueOf(Integer.MAX_VALUE),
                BrowserOptionsPolicy.normalizeHistoryLength(Integer.MAX_VALUE));
    }

    @Test
    public void rejectsFractionalNegativeAndNonNumericHistoryLengths() {
        for (Object value : new Object[]{-1, 2.5, 2147483648L, "7", true, Double.NaN,
                Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> BrowserOptionsPolicy.normalizeHistoryLength(value));
        }
    }

    @Test
    public void parsesHistoryLengthFromUiAndIntent() {
        assertNull(BrowserOptionsPolicy.parseHistoryLength(""));
        assertNull(BrowserOptionsPolicy.parseHistoryLength(null));
        assertEquals(Integer.valueOf(12), BrowserOptionsPolicy.parseHistoryLength(" 12 "));
        for (String value : new String[]{"-1", "1.5", "2147483648", "invalid"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> BrowserOptionsPolicy.parseHistoryLength(value));
        }
    }
}
