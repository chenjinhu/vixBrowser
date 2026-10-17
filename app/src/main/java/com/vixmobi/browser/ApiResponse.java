package com.vixmobi.browser;

import org.json.JSONException;
import org.json.JSONObject;

final class ApiResponse {
    interface Callback {
        void accept(AutomationHttpServer.Response response);
    }

    private ApiResponse() {}

    static JSONObject object(Object... pairs) {
        JSONObject object = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2) {
                object.put((String) pairs[i], pairs[i + 1] == null ? JSONObject.NULL : pairs[i + 1]);
            }
            return object;
        } catch (JSONException exception) {
            throw new IllegalArgumentException("Cannot encode API response", exception);
        }
    }

    static AutomationHttpServer.Response success(int status, String key, Object value) {
        return new AutomationHttpServer.Response(status,
                object("ok", true, key, value).toString());
    }

    static AutomationHttpServer.Response error(int status, String code, String message) {
        return new AutomationHttpServer.Response(status,
                object("ok", false, "error", code, "message", message).toString());
    }
}
