package com.vixmobi.browser;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class BrowserApplication extends Application {
    public static final int API_PORT = 8765;
    private static final Set<String> POST_ROUTES = new HashSet<>(Arrays.asList(
            "/api/navigate", "/api/back", "/api/forward", "/api/refresh",
            "/api/cache/clear", "/api/js/execute", "/api/referer",
            "/api/settings", "/api/ua", "/api/history-length"));
    private static final Set<String> GET_ROUTES = new HashSet<>(Arrays.asList(
            "/api/state", "/api/loading", "/api/referer", "/api/settings", "/api/ua",
            "/api/history-length"));
    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<MainActivity> activity = new WeakReference<>(null);
    private AutomationHttpServer server;
    private String startupError;

    @Override
    public void onCreate() {
        super.onCreate();
        // Remove credentials left by versions that required API authentication.
        deleteFile("automation-token");
        try {
            server = new AutomationHttpServer(API_PORT, this::dispatch);
        } catch (IOException exception) {
            startupError = exception.getMessage();
            Log.e("BrowserApi", "Unable to start local automation API", exception);
        }
    }

    void attach(MainActivity current) {
        activity = new WeakReference<>(current);
    }

    void detach(MainActivity current) {
        if (activity.get() == current) {
            activity.clear();
        }
    }

    String getStartupError() {
        return startupError;
    }

    private AutomationHttpServer.Response dispatch(String method, String path, String body) {
        if (!GET_ROUTES.contains(path) && !POST_ROUTES.contains(path)) {
            return ApiResponse.error(404, "not_found", "Unknown API endpoint");
        }
        if (!(method.equals("GET") && GET_ROUTES.contains(path)
                || method.equals("POST") && POST_ROUTES.contains(path))) {
            return ApiResponse.error(405, "method_not_allowed",
                    "This endpoint does not support the requested HTTP method");
        }
        final JSONObject payload;
        try {
            payload = body.isEmpty() ? new JSONObject() : new JSONObject(body);
        } catch (JSONException exception) {
            return ApiResponse.error(400, "invalid_json", "Request body must be a JSON object");
        }
        CountDownLatch completion = new CountDownLatch(1);
        AtomicReference<AutomationHttpServer.Response> response = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean();
        ApiResponse.Callback respond = result -> {
            if (finished.compareAndSet(false, true)) {
                response.set(result);
                completion.countDown();
            }
        };
        main.post(() -> {
            if (finished.get()) {
                return;
            }
            MainActivity current = activity.get();
            if (current == null || current.isFinishing() || current.isDestroyed()) {
                respond.accept(ApiResponse.error(503, "browser_not_ready",
                        "Start MainActivity before sending commands"));
                return;
            }
            try {
                current.executeAutomation(method, path, payload, respond);
            } catch (RuntimeException exception) {
                respond.accept(ApiResponse.error(500, "command_failed", "WebView command failed"));
            }
        });
        try {
            if (completion.await(8, TimeUnit.SECONDS)) {
                return response.get();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        finished.set(true);
        return ApiResponse.error(504, "command_timeout", "WebView did not respond within 8 seconds");
    }
}
