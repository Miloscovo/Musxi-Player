package tech.musxi.neteaseloginpoc;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.function.BooleanSupplier;

/**
 * Activity-scoped NetEase web login WebView controller. Enforces official HTTPS navigation
 * allowlists, rejects SSL errors, disables JS bridges, and keeps cookie values out of UI state.
 */
public final class WebLoginHost {
    public enum Stage {
        PREPARING,
        LOADING,
        READY,
        COOKIE_UNAVAILABLE,
        CLEANUP_FAILED,
        NAVIGATION_BLOCKED,
        SSL_ERROR,
        LOAD_ERROR,
        COOKIES_MISSING
    }

    public interface Listener {
        void stateChanged(Stage stage, String host, int progress, boolean canSubmit, boolean canReload, boolean canGoBack);
        void ready(boolean firstParty, boolean thirdParty);
        void cookies(String header);
        void cancelled();
    }

    private final Activity activity;
    private final ViewGroup webSlot;
    private final Listener listener;
    private final CookieManager jar;
    private final BooleanSupplier current;
    private final ArrayList<WebView> views = new ArrayList<>();
    private final Runnable closeHost = this::close;

    private Stage stage = Stage.PREPARING;
    private String currentHost = Uri.parse(WebLoginPolicy.LOGIN_URL).getHost();
    private int progress = 0;
    private boolean cookieUnavailable;
    private boolean closed;
    private boolean submitted;
    private boolean prepared;

    public WebLoginHost(Activity activity, ViewGroup webSlot, Listener listener, BooleanSupplier current) {
        this.activity = activity;
        this.webSlot = webSlot;
        this.listener = listener;
        this.current = current;
        WebView.setWebContentsDebuggingEnabled(false);
        jar = CookieManager.getInstance();
        jar.setAcceptCookie(true);
        WebView web = createView();
        emit();
        WebLoginSession.prepare(activity, available -> {
            if (closed) return;
            if (!current.getAsBoolean()) { close(); return; }
            if (!available) {
                stage = Stage.CLEANUP_FAILED;
                progress = 0;
                emit();
                return;
            }
            prepared = true;
            WebLoginSession.opened(closeHost);
            // Test real write/read support using a fixed non-authentication marker.
            jar.setCookie(WebLoginPolicy.LOGIN_URL,
                    "MUSXI_COOKIE_CHECK=1; Path=/; Secure; SameSite=None", written -> {
                if (closed) return;
                if (!current.getAsBoolean()) { close(); return; }
                String test = jar.getCookie(WebLoginPolicy.LOGIN_URL);
                boolean readable = Boolean.TRUE.equals(written) && test != null
                        && test.contains("MUSXI_COOKIE_CHECK=1");
                jar.setCookie(WebLoginPolicy.LOGIN_URL,
                        "MUSXI_COOKIE_CHECK=; Path=/; Secure; Max-Age=0", removed -> {
                    if (closed) return;
                    if (!current.getAsBoolean()) { close(); return; }
                    listener.ready(readable && jar.acceptCookie(), jar.acceptThirdPartyCookies(web));
                    cookieUnavailable = !readable;
                    stage = readable ? Stage.LOADING : Stage.COOKIE_UNAVAILABLE;
                    progress = 10;
                    emit();
                    web.loadUrl(WebLoginPolicy.LOGIN_URL);
                });
            });
        });
    }

    public void reload() {
        if (closed || submitted || !prepared || !current.getAsBoolean()) return;
        while (views.size() > 1) {
            WebView popup = views.remove(views.size() - 1);
            popup.stopLoading();
            webSlot.removeView(popup);
            popup.destroy();
        }
        if (views.isEmpty()) return;
        views.get(0).setVisibility(View.VISIBLE);
        currentHost = Uri.parse(WebLoginPolicy.LOGIN_URL).getHost();
        stage = cookieUnavailable ? Stage.COOKIE_UNAVAILABLE : Stage.LOADING;
        progress = 10;
        emit();
        views.get(0).loadUrl(WebLoginPolicy.LOGIN_URL);
    }

    public void submit() {
        if (closed || submitted || !prepared || !current.getAsBoolean()) return;
        // Read only this app's browser cookies applicable to the exact account API URL.
        String header = jar.getCookie(NeteaseApi.STATUS);
        try {
            WebLoginPolicy.parseMusicCookies(header);
        } catch (IOException invalid) {
            stage = Stage.COOKIES_MISSING;
            emit();
            return;
        }
        submitted = true;
        listener.cookies(header);
        close();
    }

    /** Steps back through popup windows or WebView history before closing the login sheet. */
    public boolean goBack() {
        if (closed || views.isEmpty()) return false;
        if (views.size() > 1) {
            WebView popup = views.remove(views.size() - 1);
            popup.stopLoading();
            webSlot.removeView(popup);
            popup.destroy();
            views.get(views.size() - 1).setVisibility(View.VISIBLE);
            syncVisibleMeta();
            return true;
        }
        WebView root = views.get(0);
        if (root.canGoBack()) {
            root.goBack();
            return true;
        }
        return false;
    }

    public void close() {
        if (closed) return;
        closed = true;
        for (WebView view : views) {
            view.stopLoading();
            webSlot.removeView(view);
            view.destroy();
        }
        views.clear();
        WebLoginSession.closed(activity, closeHost);
        if (!submitted) listener.cancelled();
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private WebView createView() {
        WebView web = new WebView(activity);
        web.setBackgroundColor(Color.WHITE);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(true);
        settings.setSaveFormData(false);
        // Keep the engine's real UA; do not impersonate the official app or another browser.
        jar.setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // POST navigations do not invoke shouldOverrideUrlLoading.
                if (request.isForMainFrame()
                        && !WebLoginPolicy.allowsNavigation(request.getUrl().toString())) {
                    return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
                            Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                // Interception is not guaranteed for every redirect in a navigation chain.
                if (!WebLoginPolicy.allowsNavigation(url)) {
                    view.stopLoading();
                    block(url);
                    return;
                }
                if (closed || view != activeView()) return;
                String parsedHost = Uri.parse(url).getHost();
                if (parsedHost != null) currentHost = parsedHost;
                if (prepared && !cookieUnavailable) {
                    stage = Stage.LOADING;
                }
                progress = 15;
                emit();
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!request.isForMainFrame() && "https".equalsIgnoreCase(request.getUrl().getScheme())) return false;
                return block(url);
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return block(url);
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (closed || !WebLoginPolicy.allowsNavigation(url) || view != activeView()) return;
                String parsedHost = Uri.parse(url).getHost();
                if (parsedHost != null) currentHost = parsedHost;
                progress = 100;
                if (!isStickyErrorStage()) {
                    stage = Stage.READY;
                }
                emit();
            }

            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                if (closed) return;
                stage = Stage.SSL_ERROR;
                progress = 100;
                emit();
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (closed || !request.isForMainFrame()) return;
                stage = Stage.LOAD_ERROR;
                progress = 100;
                emit();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage message) { return true; }

            @Override public void onProgressChanged(WebView view, int newProgress) {
                if (closed || !prepared || view != activeView()) return;
                progress = Math.max(0, Math.min(100, newProgress));
                emit();
            }

            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean userGesture, Message result) {
                if (closed || !prepared || !current.getAsBoolean() || !userGesture || views.size() >= 3) return false;
                WebView child = createView();
                ((WebView.WebViewTransport) result.obj).setWebView(child);
                result.sendToTarget();
                return true;
            }

            @Override public void onCloseWindow(WebView window) {
                if (views.size() <= 1 || window == views.get(0)) return;
                webSlot.removeView(window);
                views.remove(window);
                window.destroy();
                views.get(views.size() - 1).setVisibility(View.VISIBLE);
                syncVisibleMeta();
            }
        });
        if (!views.isEmpty()) views.get(views.size() - 1).setVisibility(View.GONE);
        views.add(web);
        ViewGroup.LayoutParams params = (webSlot instanceof LinearLayout)
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                : new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        webSlot.addView(web, params);
        return web;
    }

    private WebView activeView() {
        return views.isEmpty() ? null : views.get(views.size() - 1);
    }

    private void syncVisibleMeta() {
        WebView active = activeView();
        String url = active != null ? active.getUrl() : null;
        if (url != null && WebLoginPolicy.allowsNavigation(url)) {
            String host = Uri.parse(url).getHost();
            if (host != null) currentHost = host;
            if (!isStickyErrorStage()) stage = Stage.READY;
        }
        emit();
    }

    private boolean isStickyErrorStage() {
        return cookieUnavailable
                || stage == Stage.LOAD_ERROR
                || stage == Stage.SSL_ERROR
                || stage == Stage.NAVIGATION_BLOCKED;
    }

    private boolean block(String url) {
        if (WebLoginPolicy.allowsNavigation(url)) return false;
        if (!closed) {
            stage = Stage.NAVIGATION_BLOCKED;
            progress = 100;
            emit();
        }
        return true;
    }

    private void emit() {
        boolean canReload = !closed && !submitted && prepared;
        boolean canSubmit = canReload;
        boolean canGoBack = !closed && (views.size() > 1 || (!views.isEmpty() && views.get(0).canGoBack()));
        listener.stateChanged(stage, currentHost, progress, canSubmit, canReload, canGoBack);
    }
}
