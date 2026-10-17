package com.vixmobi.browser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class RefererPolicyTest {
    @Test
    public void emptyValuesRestoreDefaults() {
        assertNull(RefererPolicy.normalize(null));
        assertNull(RefererPolicy.normalize(""));
        assertNull(RefererPolicy.normalize("   "));
    }

    @Test
    public void preservesPathAndQueryAndRemovesFragment() {
        assertEquals("https://source.example/path?q=%E4%B8%AD&x=1",
                RefererPolicy.normalize(" HTTPS://source.example/path?q=%E4%B8%AD&x=1#section "));
    }

    @Test
    public void encodesUnicodeForAnHttpHeader() {
        assertEquals("https://source.example/%E4%B8%AD%E6%96%87?q=%E4%B8%AD",
                RefererPolicy.normalize("https://source.example/中文?q=中"));
    }

    @Test
    public void rejectsHeaderInjectionAndInvalidUrls() {
        for (String value : new String[]{"https://source.example/\r\nX-Injected: true",
                "https://source.example/\n", "source.example", "//source.example",
                "file:///sdcard/test", "javascript:alert(1)", "https://",
                "https://user:secret@source.example", "https://source.example/a b"}) {
            assertThrows(value, IllegalArgumentException.class, () -> RefererPolicy.normalize(value));
        }
    }

    @Test
    public void rejectsOversizedHeaderValues() {
        StringBuilder value = new StringBuilder("https://source.example/");
        for (int i = 0; i < 4096; i++) {
            value.append('a');
        }
        assertThrows(IllegalArgumentException.class, () -> RefererPolicy.normalize(value.toString()));
    }
}
