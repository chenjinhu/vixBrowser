package com.vixmobi.browser;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Small, bounded HTTP/1.1 JSON server. Exposed to the host through adb forward. */
public final class AutomationHttpServer implements Closeable {
    private static final int MAX_HEADERS = 8192;
    private static final int MAX_BODY = 65536;
    private final ServerSocket listener;
    private final ThreadPoolExecutor workers;
    private final Set<Socket> clients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final RequestHandler handler;
    private volatile boolean closed;

    public interface RequestHandler {
        Response handle(String method, String path, String body);
    }

    public static final class Response {
        public final int status;
        public final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    public AutomationHttpServer(int port, RequestHandler handler) throws IOException {
        this.handler = handler;
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        try {
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        } catch (IOException exception) {
            listener.close();
            throw exception;
        }
        workers = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8), runnable -> {
                    Thread thread = new Thread(runnable, "browser-http-worker");
                    thread.setDaemon(true);
                    return thread;
                });
        Thread acceptor = new Thread(this::acceptConnections, "browser-http-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int getPort() {
        return listener.getLocalPort();
    }

    private void acceptConnections() {
        while (!closed) {
            try {
                Socket client = listener.accept();
                client.setSoTimeout(5000);
                clients.add(client);
                try {
                    workers.execute(() -> serve(client));
                } catch (RejectedExecutionException exception) {
                    clients.remove(client);
                    client.close();
                }
            } catch (IOException exception) {
                // close() interrupts accept(); transient failures must not spin the loop.
                close();
            }
        }
    }

    private void serve(Socket client) {
        try (Socket socket = client) {
            Response response;
            try {
                response = readAndDispatch(socket.getInputStream());
            } catch (RequestFailure failure) {
                response = error(failure.status, failure.code);
            } catch (IOException exception) {
                response = error(400, "incomplete_request");
            } catch (RuntimeException exception) {
                response = error(500, "internal_error");
            }
            writeResponse(socket.getOutputStream(), response);
        } catch (IOException ignored) {
            // The caller may close the socket before receiving a response.
        } finally {
            clients.remove(client);
        }
    }

    private Response readAndDispatch(InputStream input) throws IOException, RequestFailure {
        String requestLine = readLine(input, MAX_HEADERS);
        int remaining = MAX_HEADERS - requestLine.length() - 2;
        String[] request = requestLine.split(" ", -1);
        if (request.length != 3 || !request[2].equals("HTTP/1.1")
                || !request[1].startsWith("/") || request[1].contains("#")) {
            throw new RequestFailure(400, "invalid_request");
        }
        Map<String, String> headers = new HashMap<>();
        boolean duplicateHeader = false;
        while (true) {
            String line = readLine(input, remaining);
            remaining -= line.length() + 2;
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new RequestFailure(400, "invalid_headers");
            }
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            if (headers.put(name, line.substring(colon + 1).trim()) != null) {
                duplicateHeader = true;
            }
        }
        if (duplicateHeader) {
            return error(400, "duplicate_header");
        }
        if (headers.containsKey("origin")) {
            return error(403, "browser_origin_forbidden");
        }
        if (headers.containsKey("transfer-encoding")) {
            return error(400, "transfer_encoding_not_supported");
        }
        if (!request[0].equals("GET") && !request[0].equals("POST")) {
            return error(405, "method_not_allowed");
        }
        String lengthValue = headers.get("content-length");
        if (request[0].equals("POST") && lengthValue == null) {
            return error(411, "content_length_required");
        }
        int length = 0;
        if (lengthValue != null) {
            if (!lengthValue.matches("[0-9]{1,9}")) {
                return error(400, "invalid_content_length");
            }
            length = Integer.parseInt(lengthValue);
        }
        if (length > MAX_BODY) {
            return error(413, "body_too_large");
        }
        if (request[0].equals("GET") && length != 0) {
            return error(400, "get_body_not_supported");
        }
        if (request[0].equals("POST") && !header(headers, "content-type")
                .split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            return error(415, "json_content_type_required");
        }
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(body, offset, length - offset);
            if (count == -1) {
                throw new RequestFailure(400, "incomplete_body");
            }
            offset += count;
        }
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
            return handler.handle(request[0], request[1], json);
        } catch (CharacterCodingException exception) {
            return error(400, "invalid_utf8");
        }
    }

    private static String header(Map<String, String> headers, String name) {
        String value = headers.get(name);
        return value == null ? "" : value;
    }

    private static String readLine(InputStream input, int limit)
            throws IOException, RequestFailure {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int count = 0; count < limit; count++) {
            int value = input.read();
            if (value == -1) {
                throw new RequestFailure(400, "incomplete_headers");
            }
            if (value == '\r') {
                if (input.read() != '\n') {
                    throw new RequestFailure(400, "invalid_headers");
                }
                return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
            }
            if (value == '\n' || value == 0) {
                throw new RequestFailure(400, "invalid_headers");
            }
            line.write(value);
        }
        throw new RequestFailure(431, "headers_too_large");
    }

    private static Response error(int status, String code) {
        return new Response(status, "{\"ok\":false,\"error\":\"" + code + "\"}");
    }

    private static void writeResponse(OutputStream output, Response response) throws IOException {
        byte[] body = response.body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + response.status + " " + reason(response.status) + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 202: return "Accepted";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 411: return "Length Required";
            case 413: return "Content Too Large";
            case 415: return "Unsupported Media Type";
            case 431: return "Request Header Fields Too Large";
            case 503: return "Service Unavailable";
            case 504: return "Gateway Timeout";
            default: return "Internal Server Error";
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            listener.close();
        } catch (IOException ignored) {
        }
        for (Socket client : clients) {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
        workers.shutdownNow();
    }

    private static final class RequestFailure extends Exception {
        final int status;
        final String code;

        RequestFailure(int status, String code) {
            this.status = status;
            this.code = code;
        }
    }
}
