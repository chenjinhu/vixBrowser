package com.vixmobi.browser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.HashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public class MainActivity extends Activity {
    /** Explicit Intent extra; takes precedence over Intent.getData(). */
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_REFERER = "referer";
    public static final String EXTRA_UA = "ua";
    public static final String EXTRA_HISTORY_LENGTH = "history_length";
    private static final String SETTINGS = "browser_settings";
    private static final String STATE_WEBVIEW = "webview_state";
    private static final String STATE_URL = "current_url";

    private WebView webView;
    private EditText address;
    private ProgressBar progress;
    private View statusPanel;
    private TextView statusTitle;
    private TextView statusMessage;
    private Button backButton;
    private Button forwardButton;
    private Button reloadButton;
    private Button retryButton;
    private String currentUrl;
    private String customReferer;
    private String customUserAgent;
    private Integer customHistoryLength;
    private boolean historyLengthSupported;
    private ScriptHandler historyScript;
    private boolean refererUpdatePending;
    private boolean loading;
    private int loadProgress;
    private String pageError;
    private String mainFrameErrorUrl;
    private OnBackInvokedCallback backCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        customReferer = getSharedPreferences(SETTINGS, MODE_PRIVATE).getString(EXTRA_REFERER, null);
        customUserAgent = getSharedPreferences(SETTINGS, MODE_PRIVATE).getString(EXTRA_UA, null);
        if (getSharedPreferences(SETTINGS, MODE_PRIVATE).contains(EXTRA_HISTORY_LENGTH)) {
            customHistoryLength = getSharedPreferences(SETTINGS, MODE_PRIVATE)
                    .getInt(EXTRA_HISTORY_LENGTH, 0);
        }
        setContentView(R.layout.activity_main);
        bindViews();
        configureInsets();
        configureWebView();
        configureControls();
        ((BrowserApplication) getApplication()).attach(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = this::navigateBackOrFinish;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }

        if (savedInstanceState != null) {
            currentUrl = savedInstanceState.getString(STATE_URL);
            Bundle webState = savedInstanceState.getBundle(STATE_WEBVIEW);
            if (webState != null && webView.restoreState(webState) != null) {
                loadProgress = currentUrl == null ? 0 : 100;
                address.setText(currentUrl);
                statusPanel.setVisibility(View.GONE);
                updateNavigation();
                return;
            }
            if (currentUrl != null) {
                openUrl(currentUrl);
                return;
            }
        }
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void bindViews() {
        webView = findViewById(R.id.webview);
        address = findViewById(R.id.address);
        progress = findViewById(R.id.progress);
        statusPanel = findViewById(R.id.status_panel);
        statusTitle = findViewById(R.id.status_title);
        statusMessage = findViewById(R.id.status_message);
        backButton = findViewById(R.id.back);
        forwardButton = findViewById(R.id.forward);
        reloadButton = findViewById(R.id.reload);
        retryButton = findViewById(R.id.retry);
    }

    private void configureInsets() {
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return WindowInsets.CONSUMED;
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom());
            }
            return insets.consumeSystemWindowInsets();
        });
        root.requestApplyInsets();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        historyLengthSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        updateHistoryScript();
        settings.setUserAgentString(customUserAgent);
        CookieManager.getInstance().setAcceptCookie(true);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return blockUnsupportedUrl(request.getUrl().toString(), request.isForMainFrame());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return blockUnsupportedUrl(url, true);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                currentUrl = url;
                // Some WebView versions report an HTTP failure before onPageStarted.
                boolean alreadyFailed = url.equals(mainFrameErrorUrl);
                loading = !alreadyFailed;
                if (!alreadyFailed) {
                    loadProgress = 0;
                    pageError = null;
                    statusPanel.setVisibility(View.GONE);
                }
                address.setText(url);
                progress.setProgress(0);
                progress.setVisibility(alreadyFailed ? View.INVISIBLE : View.VISIBLE);
                updateNavigation();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                currentUrl = url;
                address.setText(url);
                updateNavigation();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url.equals(currentUrl)) {
                    loading = false;
                    if (pageError == null) {
                        loadProgress = 100;
                    }
                    progress.setVisibility(View.INVISIBLE);
                }
                updateNavigation();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    showLoadError(request.getUrl().toString(), getString(R.string.network_error));
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse response) {
                if (request.isForMainFrame()) {
                    showLoadError(request.getUrl().toString(),
                            getString(R.string.http_error, response.getStatusCode()));
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                // A subresource's bad certificate must not replace the whole page.
                if (error.getUrl().equals(currentUrl)) {
                    showLoadError(error.getUrl(), getString(R.string.ssl_error));
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                loadProgress = newProgress;
                progress.setProgress(newProgress);
                progress.setVisibility(loading && newProgress < 100 ? View.VISIBLE : View.INVISIBLE);
            }
        });
    }

    private void configureControls() {
        findViewById(R.id.go).setOnClickListener(view -> openAddress());
        address.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                openAddress();
                return true;
            }
            return false;
        });
        backButton.setOnClickListener(view -> {
            if (webView.canGoBack()) {
                prepareNavigation();
                webView.goBack();
            }
        });
        forwardButton.setOnClickListener(view -> {
            if (webView.canGoForward()) {
                prepareNavigation();
                webView.goForward();
            }
        });
        reloadButton.setOnClickListener(view -> reload());
        retryButton.setOnClickListener(view -> reload());
        findViewById(R.id.api_info).setOnClickListener(view -> showApiInfo());
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        // An explicitly supplied but invalid extra is an error, not a fallback to data.
        String rawUrl = intent.hasExtra(EXTRA_URL)
                ? intent.getStringExtra(EXTRA_URL) : intent.getDataString();
        boolean hasUrl = rawUrl != null || intent.hasExtra(EXTRA_URL);
        if (hasUrl && UrlPolicy.normalize(rawUrl) == null) {
            openUrl(rawUrl);
            return;
        }
        JSONObject options = new JSONObject();
        try {
            Bundle extras = intent.getExtras();
            for (String key : new String[]{EXTRA_REFERER, EXTRA_UA, EXTRA_HISTORY_LENGTH}) {
                if (extras != null && extras.containsKey(key)) {
                    Object value = extras.get(key);
                    if (key.equals(EXTRA_HISTORY_LENGTH) && value instanceof String) {
                        value = BrowserOptionsPolicy.parseHistoryLength((String) value);
                    }
                    options.put(key, value == null ? JSONObject.NULL : value);
                }
            }
            if (options.length() > 0 && !applyOptionsPayload(options, response ->
                    Toast.makeText(this, R.string.invalid_browser_settings, Toast.LENGTH_LONG).show())) {
                return;
            }
        } catch (JSONException | IllegalArgumentException exception) {
            Toast.makeText(this, R.string.invalid_browser_settings, Toast.LENGTH_LONG).show();
            return;
        }
        if (hasUrl) {
            openUrl(rawUrl);
        }
    }

    private void openAddress() {
        if (openUrl(address.getText().toString())) {
            address.clearFocus();
            InputMethodManager keyboard = getSystemService(InputMethodManager.class);
            keyboard.hideSoftInputFromWindow(address.getWindowToken(), 0);
            webView.requestFocus();
        }
    }

    private boolean openUrl(String rawUrl) {
        String url = UrlPolicy.normalize(rawUrl);
        if (url == null) {
            address.setError(getString(R.string.invalid_url));
            Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show();
            return false;
        }
        address.setError(null);
        address.setText(url);
        currentUrl = url;
        prepareNavigation();
        loadWithReferer(url);
        updateNavigation();
        return true;
    }

    private boolean blockUnsupportedUrl(String url, boolean mainFrame) {
        if (UrlPolicy.normalize(url) != null) {
            if (mainFrame) {
                prepareNavigation();
            }
            return false;
        }
        if (mainFrame) {
            Toast.makeText(this, R.string.unsupported_link, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void reload() {
        if (currentUrl != null) {
            if (pageError != null) {
                openUrl(currentUrl);
                return;
            }
            prepareNavigation();
            if (customReferer != null || refererUpdatePending) {
                // A fresh GET applies a new override or clears the previous one.
                loadWithReferer(currentUrl);
            } else {
                webView.reload();
            }
        }
    }

    private void loadWithReferer(String url) {
        Map<String, String> headers = new HashMap<>();
        if (customReferer != null) {
            headers.put("Referer", customReferer);
        }
        // Use a mutable map for compatibility with older WebView implementations.
        webView.loadUrl(url, headers);
        refererUpdatePending = false;
    }

    private void setCustomReferer(String raw) {
        applyBrowserOptions(RefererPolicy.normalize(raw), customUserAgent, customHistoryLength);
    }

    private void applyBrowserOptions(String referer, String userAgent, Integer historyLength) {
        boolean historyChanged = !Objects.equals(customHistoryLength, historyLength);
        boolean uaChanged = !Objects.equals(customUserAgent, userAgent);
        refererUpdatePending |= !Objects.equals(customReferer, referer);
        customReferer = referer;
        customUserAgent = userAgent;
        customHistoryLength = historyLength;
        SharedPreferences.Editor editor = getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                .putString(EXTRA_REFERER, referer).putString(EXTRA_UA, userAgent);
        if (historyLength == null) {
            editor.remove(EXTRA_HISTORY_LENGTH);
        } else {
            editor.putInt(EXTRA_HISTORY_LENGTH, historyLength);
        }
        editor.apply();
        // Install the new script before changing UA, which may restart a page load.
        if (historyChanged) {
            updateHistoryScript();
            webView.evaluateJavascript(HistoryLengthScript.create(historyLength), null);
        }
        if (uaChanged) {
            webView.getSettings().setUserAgentString(userAgent);
        }
    }

    private void updateHistoryScript() {
        if (historyScript != null) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                historyScript.remove();
            }
            historyScript = null;
        }
        if (customHistoryLength != null
                && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            historyScript = WebViewCompat.addDocumentStartJavaScript(webView,
                    HistoryLengthScript.create(customHistoryLength), Collections.singleton("*"));
        }
    }

    private void prepareNavigation() {
        loading = true;
        loadProgress = 0;
        pageError = null;
        mainFrameErrorUrl = null;
        statusPanel.setVisibility(View.GONE);
    }

    private void showLoadError(String url, String message) {
        loading = false;
        pageError = message;
        mainFrameErrorUrl = url;
        progress.setVisibility(View.INVISIBLE);
        statusTitle.setText(R.string.load_failed);
        statusMessage.setText(message);
        retryButton.setVisibility(View.VISIBLE);
        statusPanel.setVisibility(View.VISIBLE);
        updateNavigation();
    }

    private void updateNavigation() {
        backButton.setEnabled(webView.canGoBack());
        forwardButton.setEnabled(webView.canGoForward());
        reloadButton.setEnabled(currentUrl != null);
    }

    private void showApiInfo() {
        BrowserApplication application = (BrowserApplication) getApplication();
        if (application.getStartupError() != null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.api_unavailable)
                    .setMessage(application.getStartupError())
                    .setNeutralButton(R.string.browser_settings,
                            (dialog, which) -> showBrowserSettings())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.api_info)
                .setMessage(getString(R.string.api_details, BrowserApplication.API_PORT))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.browser_settings,
                        (dialog, which) -> showBrowserSettings())
                .show();
    }

    private void showBrowserSettings() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, 0, padding, 0);
        EditText referer = addSettingsInput(container, R.string.referer_settings, R.string.referer_hint,
                customReferer, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                R.id.settings_referer);
        EditText ua = addSettingsInput(container, R.string.ua_settings, R.string.ua_hint,
                customUserAgent, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                R.id.settings_ua);
        EditText history = addSettingsInput(container, R.string.history_length_settings,
                R.string.history_length_hint,
                customHistoryLength == null ? null : customHistoryLength.toString(),
                InputType.TYPE_CLASS_NUMBER, R.id.settings_history_length);
        TextView error = new TextView(this);
        error.setId(R.id.settings_error);
        error.setTextColor(0xFFB3261E);
        error.setVisibility(View.GONE);
        container.addView(error);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(container);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.browser_settings)
                .setMessage(R.string.browser_settings_description)
                .setView(scroll)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.reset_all_defaults, (ignored, which) -> {
                    applyBrowserOptions(null, null, null);
                    Toast.makeText(this, R.string.browser_settings_saved, Toast.LENGTH_SHORT).show();
                })
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        String nextReferer = RefererPolicy.normalize(referer.getText().toString());
                        String nextUa = BrowserOptionsPolicy.normalizeUserAgent(ua.getText().toString());
                        Integer nextHistory = BrowserOptionsPolicy.parseHistoryLength(history.getText().toString());
                        if (nextHistory != null && !historyLengthSupported) {
                            throw new IllegalArgumentException(getString(R.string.history_length_unsupported));
                        }
                        applyBrowserOptions(nextReferer, nextUa, nextHistory);
                        Toast.makeText(this, R.string.browser_settings_saved, Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    } catch (IllegalArgumentException exception) {
                        error.setText(exception.getMessage());
                        error.setVisibility(View.VISIBLE);
                    }
                }));
        dialog.show();
    }

    private EditText addSettingsInput(LinearLayout container, int label, int hint,
                                      String value, int inputType, int id) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setPadding(0, 16, 0, 0);
        container.addView(title);
        EditText input = new EditText(this);
        input.setId(id);
        input.setInputType((inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
                ? inputType | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS : inputType);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setHint(hint);
        input.setText(value);
        container.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private JSONObject browserOptions() {
        return ApiResponse.object("referer", customReferer, "ua", customUserAgent,
                "effective_ua", webView.getSettings().getUserAgentString(),
                "history_length", customHistoryLength,
                "history_length_supported", historyLengthSupported);
    }

    private String loadStatus() {
        if (pageError != null) {
            return "error";
        }
        if (loading) {
            return "loading";
        }
        return currentUrl == null ? "idle" : "complete";
    }

    private JSONObject loadingState() {
        return ApiResponse.object("ok", true, "status", loadStatus(), "loading", loading,
                "progress", loadProgress, "url", currentUrl, "title", webView.getTitle(),
                "page_error", pageError);
    }

    JSONObject automationState() {
        return ApiResponse.object("url", currentUrl, "title", webView.getTitle(),
                "loading", loading, "load_status", loadStatus(), "progress", loadProgress,
                "can_go_back", webView.canGoBack(),
                "can_go_forward", webView.canGoForward(), "page_error", pageError,
                "referer", customReferer, "ua", customUserAgent,
                "effective_ua", webView.getSettings().getUserAgentString(),
                "history_length", customHistoryLength,
                "history_length_supported", historyLengthSupported);
    }

    /** Called only on the UI thread; JS completes asynchronously without blocking WebView. */
    void executeAutomation(String method, String path, JSONObject payload, ApiResponse.Callback callback) {
        switch (path) {
            case "/api/state":
                callback.accept(ApiResponse.success(200, "state", automationState()));
                break;
            case "/api/loading":
                callback.accept(new AutomationHttpServer.Response(200, loadingState().toString()));
                break;
            case "/api/navigate":
                Object suppliedUrl = payload.opt("url");
                if (!(suppliedUrl instanceof String)
                        || UrlPolicy.normalize((String) suppliedUrl) == null) {
                    callback.accept(ApiResponse.error(400, "invalid_url",
                            "url must be a valid HTTP or HTTPS address"));
                    return;
                }
                if (hasOptions(payload) && !applyOptionsPayload(payload, callback)) {
                    return;
                }
                openUrl((String) suppliedUrl);
                callback.accept(ApiResponse.success(202, "state", automationState()));
                break;
            case "/api/referer":
                if (method.equals("POST") && !applyRefererPayload(payload, callback)) {
                    return;
                }
                callback.accept(ApiResponse.success(200, "referer", customReferer));
                break;
            case "/api/settings":
                if (method.equals("POST") && !applyOptionsPayload(payload, callback)) {
                    return;
                }
                callback.accept(ApiResponse.success(200, "settings", browserOptions()));
                break;
            case "/api/ua":
            case "/api/history-length":
                String key = path.equals("/api/ua") ? EXTRA_UA : EXTRA_HISTORY_LENGTH;
                if (method.equals("POST")) {
                    if (!payload.has(key)) {
                        callback.accept(ApiResponse.error(400, "invalid_" + key, key + " is required"));
                        return;
                    }
                    if (!applyOptionsPayload(ApiResponse.object(key, payload.opt(key)), callback)) {
                        return;
                    }
                }
                callback.accept(new AutomationHttpServer.Response(200,
                        key.equals(EXTRA_UA)
                                ? ApiResponse.object("ok", true, "ua", customUserAgent,
                                        "effective_ua", webView.getSettings().getUserAgentString()).toString()
                                : ApiResponse.object("ok", true, "history_length", customHistoryLength,
                                        "history_length_supported", historyLengthSupported).toString()));
                break;
            case "/api/back":
            case "/api/forward":
                boolean backwards = path.equals("/api/back");
                if (!(backwards ? webView.canGoBack() : webView.canGoForward())) {
                    callback.accept(ApiResponse.error(409, "no_history",
                            "No page in the requested direction"));
                    return;
                }
                prepareNavigation();
                if (backwards) {
                    webView.goBack();
                } else {
                    webView.goForward();
                }
                callback.accept(ApiResponse.success(202, "state", automationState()));
                break;
            case "/api/refresh":
                if (currentUrl == null) {
                    callback.accept(ApiResponse.error(409, "no_page", "Navigate to a page first"));
                    return;
                }
                reload();
                callback.accept(ApiResponse.success(202, "state", automationState()));
                break;
            case "/api/cache/clear":
                webView.clearCache(true);
                callback.accept(ApiResponse.success(200, "cache_cleared", true));
                break;
            case "/api/js/execute":
                executeJavaScript(payload, callback);
                break;
            default:
                callback.accept(ApiResponse.error(404, "not_found", "Unknown API endpoint"));
        }
    }

    private static boolean hasOptions(JSONObject payload) {
        return payload.has(EXTRA_REFERER) || payload.has(EXTRA_UA)
                || payload.has(EXTRA_HISTORY_LENGTH);
    }

    private boolean applyOptionsPayload(JSONObject payload, ApiResponse.Callback callback) {
        if (!hasOptions(payload)) {
            callback.accept(ApiResponse.error(400, "invalid_settings",
                    "Provide referer, ua, or history_length"));
            return false;
        }
        String field = EXTRA_REFERER;
        try {
            String referer = customReferer;
            String ua = customUserAgent;
            Integer history = customHistoryLength;
            if (payload.has(EXTRA_REFERER)) {
                referer = RefererPolicy.normalize(optionalString(payload, EXTRA_REFERER));
            }
            field = EXTRA_UA;
            if (payload.has(EXTRA_UA)) {
                ua = BrowserOptionsPolicy.normalizeUserAgent(optionalString(payload, EXTRA_UA));
            }
            field = EXTRA_HISTORY_LENGTH;
            if (payload.has(EXTRA_HISTORY_LENGTH)) {
                Object raw = payload.opt(EXTRA_HISTORY_LENGTH);
                history = BrowserOptionsPolicy.normalizeHistoryLength(raw == JSONObject.NULL ? null : raw);
                if (history != null && !historyLengthSupported) {
                    callback.accept(ApiResponse.error(409, "history_override_unavailable",
                            "Update Android System WebView to enable document-start history overrides"));
                    return false;
                }
            }
            applyBrowserOptions(referer, ua, history);
            return true;
        } catch (IllegalArgumentException exception) {
            callback.accept(ApiResponse.error(400, "invalid_" + field, exception.getMessage()));
            return false;
        }
    }

    private static String optionalString(JSONObject payload, String key) {
        Object value = payload.opt(key);
        if (value == JSONObject.NULL) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(key + " must be a string or null");
        }
        return (String) value;
    }

    private boolean applyRefererPayload(JSONObject payload, ApiResponse.Callback callback) {
        Object value = payload.opt(EXTRA_REFERER);
        if (!payload.has(EXTRA_REFERER)
                || !(value == JSONObject.NULL || value instanceof String)) {
            callback.accept(ApiResponse.error(400, "invalid_referer",
                    "referer must be an HTTP(S) URL string, or null to restore defaults"));
            return false;
        }
        try {
            setCustomReferer(value == JSONObject.NULL ? null : (String) value);
            return true;
        } catch (IllegalArgumentException exception) {
            callback.accept(ApiResponse.error(400, "invalid_referer", exception.getMessage()));
            return false;
        }
    }

    private void executeJavaScript(JSONObject payload, ApiResponse.Callback callback) {
        Object script = payload.opt("script");
        if (!(script instanceof String) || ((String) script).trim().isEmpty()) {
            callback.accept(ApiResponse.error(400, "invalid_script", "script must be a nonempty string"));
            return;
        }
        if (currentUrl == null) {
            callback.accept(ApiResponse.error(409, "no_page", "Navigate to a page first"));
            return;
        }
        // JSON-quote the supplied source so quotes, newlines and Unicode remain intact.
        // Serialize inside try/catch to report cyclic values and BigInt as JS errors as well.
        String wrapped = "(function(){try{var value=(0,eval)(" + JSONObject.quote((String) script)
                + ");if(value&&typeof value.then==='function')"
                + "{throw new Error('Promise results are not supported; return a synchronous value');}"
                + "return JSON.stringify({ok:true,value:typeof value==='undefined'?null:value});"
                + "}catch(error){return JSON.stringify({ok:false,error:String(error)});}})()";
        webView.evaluateJavascript(wrapped, raw -> {
            try {
                if (raw == null) {
                    callback.accept(ApiResponse.error(500, "js_result_unavailable",
                            "WebView did not return a result"));
                    return;
                }
                Object decoded = new JSONTokener(raw).nextValue();
                if (!(decoded instanceof String)) {
                    callback.accept(ApiResponse.error(500, "js_result_unavailable",
                            "Page navigation or script execution interrupted the result"));
                    return;
                }
                JSONObject result = new JSONObject((String) decoded);
                if (result.optBoolean("ok")) {
                    callback.accept(ApiResponse.success(200, "result", result.opt("value")));
                } else {
                    callback.accept(ApiResponse.error(400, "js_error", result.optString("error")));
                }
            } catch (JSONException exception) {
                callback.accept(ApiResponse.error(500, "invalid_js_result", "Unable to decode JS result"));
            }
        });
    }

    private void navigateBackOrFinish() {
        if (webView.canGoBack()) {
            prepareNavigation();
            webView.goBack();
        } else {
            finish();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        navigateBackOrFinish();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        Bundle webState = new Bundle();
        webView.saveState(webState);
        outState.putBundle(STATE_WEBVIEW, webState);
        outState.putString(STATE_URL, currentUrl);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        webView.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        ((BrowserApplication) getApplication()).detach(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        webView.stopLoading();
        if (historyScript != null
                && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            historyScript.remove();
        }
        webView.setWebChromeClient(null);
        webView.setWebViewClient(null);
        ((ViewGroup) webView.getParent()).removeView(webView);
        webView.destroy();
        super.onDestroy();
    }
}
