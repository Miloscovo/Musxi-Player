package tech.musxi.qqloginpoc;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.webkit.*;
import android.widget.*;
import java.util.ArrayList;

/** Debug-only official OAuth experiment. No DOM, password, JS bridge, console or cookie import. */
public final class QqWebLoginWindow {
    public interface Listener {
        void authorization(QqWebLoginApi.WebAuthorization authorization);
        void cancelled();
        /** Fixed stages and a validated host only; never URLs or user data. */
        default void stage(String stage, String host) {}
    }
    private final Listener listener;
    private final QqWebLoginApi.WebAttempt attempt = QqWebLoginApi.createWebLogin();
    private final Dialog dialog;
    private final LinearLayout container;
    private final TextView status;
    private final ArrayList<WebView> views = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean closed, submitted;
    private final Runnable expiry = this::expire;
    private void expire() { if (!closed) { listener.stage("EXPIRED", ""); close(); } }

    public QqWebLoginWindow(Activity activity, Listener listener) {
        this.listener = listener;
        dialog = new Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar);
        dialog.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Color.WHITE);
        status = new TextView(activity);
        status.setTextColor(Color.BLACK);
        status.setPadding(24, 20, 24, 12);
        status.setText("QQ 官方网页登录 · 完成后自动验证并返回");
        container.addView(status);
        Button cancel = new Button(activity);
        cancel.setText("取消网页登录");
        cancel.setOnClickListener(v -> close());
        container.addView(cancel);
        dialog.setContentView(container);
        dialog.setOnDismissListener(ignored -> dispose());
        WebView.setWebContentsDebuggingEnabled(false);
        CookieManager.getInstance().setAcceptCookie(true);
        WebView web = createView(activity);
        dialog.show();
        main.postDelayed(expiry, 180_000);
        listener.stage("OPENED", "");
        web.loadUrl(attempt.passwordLoginUrl());
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private WebView createView(Activity activity) {
        WebView web = new WebView(activity);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(true);
        // This fixed-width official page has no mobile viewport. A 980px overview makes its
        // footer microscopic; use the actual window width and retain pinch zoom instead.
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSaveFormData(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame() && "https".equalsIgnoreCase(request.getUrl().getScheme())
                        && !callbackTarget(request.getUrl().toString())) return false;
                return navigate(view, request.getUrl().toString());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return navigate(view, url); }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                navigate(view, url);
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (request.isForMainFrame() && (callbackTarget(url) || !QqWebLoginPolicy.allowsNavigation(url))) {
                    main.post(() -> navigate(view, url));
                    return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
                            java.util.Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
                }
                return null;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (closed || !QqWebLoginPolicy.allowsNavigation(url) || QqWebLoginPolicy.isCallback(url)) return;
                String host = android.net.Uri.parse(url).getHost();
                listener.stage("PAGE_READY", host);
                status.setText("QQ 官方网页登录 · " + host + "\n请选择网页方式，完成登录和授权后自动返回。");
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel(); listener.stage("TLS_FAILED", "");
                status.setText("网页安全连接未通过，请检查网络和系统时间后重试。");
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && !closed) {
                    listener.stage("PAGE_ERROR", "");
                    status.setText("官方网页登录未加载完成，可取消后重试或使用 QQ App 登录。");
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) { return true; }
            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean gesture, Message result) {
                if (closed || !gesture || views.size() >= 3) return false;
                WebView child = createView(activity);
                ((WebView.WebViewTransport) result.obj).setWebView(child);
                result.sendToTarget();
                return true;
            }
            @Override public void onCloseWindow(WebView window) {
                if (views.size() <= 1 || window == views.get(0)) return;
                container.removeView(window); views.remove(window); window.destroy();
                views.get(views.size() - 1).setVisibility(android.view.View.VISIBLE);
            }
        });
        if (!views.isEmpty()) views.get(views.size() - 1).setVisibility(android.view.View.GONE);
        views.add(web);
        container.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        return web;
    }

    private boolean navigate(WebView view, String url) {
        if (closed || submitted) return true;
        if (callbackTarget(url)) {
            view.stopLoading();
            try {
                QqWebLoginApi.WebAuthorization authorization = QqWebLoginApi.captureWebAuthorization(attempt, url);
                submitted = true;
                listener.stage("AUTHORIZED", "");
                listener.authorization(authorization);
                dialog.dismiss();
            } catch (java.io.IOException invalid) {
                listener.stage("CALLBACK_REJECTED", "");
                status.setText("本次网页授权未完成或已失效，请取消后重试。");
            }
            return true;
        }
        if (QqWebLoginPolicy.allowsNavigation(url)) return false;
        view.stopLoading();
        android.net.Uri blocked = android.net.Uri.parse(url);
        String host = blocked.getHost();
        listener.stage("NAVIGATION_BLOCKED", host != null && host.matches("[A-Za-z0-9.-]{1,253}") ? host : "");
        status.setText("此窗口使用网页登录，请在官方页面选择其他登录方式；也可取消后使用 QQ App 登录。");
        return true;
    }

    public void close() { dialog.dismiss(); }
    private boolean callbackTarget(String url) {
        android.net.Uri uri = android.net.Uri.parse(url);
        return "y.qq.com".equalsIgnoreCase(uri.getHost()) && "/portal/wx_redirect.html".equals(uri.getPath());
    }
    private void dispose() {
        if (closed) return;
        closed = true; main.removeCallbacks(expiry);
        for (WebView view : views) { view.stopLoading(); container.removeView(view); view.destroy(); }
        views.clear();
        // Cookie jar belongs to this app. Do not clear NetEase or other browser sessions.
        if (!submitted) listener.cancelled();
    }
}
