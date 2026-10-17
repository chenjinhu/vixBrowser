package com.vixmobi.browser;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AutomationHttpServerTest {
    private AutomationHttpServer server;
    private final AtomicInteger calls = new AtomicInteger();

    @Before
    public void startServer() throws Exception {
        server = new AutomationHttpServer(0, (method, path, body) -> {
            calls.incrementAndGet();
            return new AutomationHttpServer.Response(200, "{\"ok\":true,\"text\":\"中文\"}");
        });
    }

    @After
    public void stopServer() {
        server.close();
    }

    @Test
    public void acceptsGetWithoutAuthenticationAndUsesUtf8ByteLength() throws Exception {
        String response = exchange("GET /api/state HTTP/1.1\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertTrue(response.contains("Content-Length: 27\r\n"));
        assertTrue(response.endsWith("{\"ok\":true,\"text\":\"中文\"}"));
        assertEquals(1, calls.get());
    }

    @Test
    public void acceptsPostWithoutAuthentication() throws Exception {
        assertStatus(200, "POST /api/back HTTP/1.1\r\n"
                + "Content-Type: application/json\r\nContent-Length: 2\r\n\r\n{}");
        assertEquals(1, calls.get());
    }

    @Test
    public void rejectsBrowserOrigin() throws Exception {
        assertStatus(403, "GET /api/state HTTP/1.1\r\n"
                + "Origin: https://example.com\r\n\r\n");
        assertEquals(0, calls.get());
    }

    @Test
    public void dispatchesUtf8JsonBodyWithoutChangingSource() throws Exception {
        String json = "{\"script\":\"document.title='中文'\"}";
        server.close();
        server = new AutomationHttpServer(0, (method, path, body) -> {
            assertEquals("POST", method);
            assertEquals("/api/js/execute", path);
            assertEquals(json, body);
            return new AutomationHttpServer.Response(200, "{\"ok\":true}");
        });
        assertStatus(200, "POST /api/js/execute HTTP/1.1\r\n"
                + "Content-Type: application/json; charset=utf-8\r\nContent-Length: "
                + json.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + json);
    }

    @Test
    public void rejectsOversizedAndAmbiguousRequests() throws Exception {
        String prefix = "POST /api/navigate HTTP/1.1\r\n"
                + "Content-Type: application/json\r\n";
        assertStatus(413, prefix + "Content-Length: 65537\r\n\r\n");
        assertStatus(400, prefix + "Content-Length: -1\r\n\r\n");
        assertStatus(400, prefix + "Content-Length: 0\r\nContent-Length: 1\r\n\r\n");
        assertStatus(400, prefix + "Transfer-Encoding: chunked\r\n\r\n");
        assertStatus(411, prefix + "\r\n");
        assertEquals(0, calls.get());
    }

    @Test
    public void requiresJsonForPostAndRejectsUnsupportedMethods() throws Exception {
        assertStatus(415, "POST /api/back HTTP/1.1\r\n"
                + "Content-Type: text/plain\r\nContent-Length: 0\r\n\r\n");
        assertStatus(405, "DELETE /api/back HTTP/1.1\r\n\r\n");
        assertEquals(0, calls.get());
    }

    private void assertStatus(int expected, String request) throws Exception {
        assertTrue(exchange(request).startsWith("HTTP/1.1 " + expected + " "));
    }

    private String exchange(String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            InputStream input = socket.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
