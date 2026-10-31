package tech.musxi.loginpoc;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.view.WindowManager;
import android.webkit.*;
import android.widget.*;
import java.util.ArrayList;

/** Debug-only official browser. No password, DOM, JS bridge or response-body access. */
public final class KugouWebLoginWindow {
    public interface Listener {
        void session(KugouApi.Session session);
        void cancelled();
        void stage(String stage, String host);
    }
    private final Dialog dialog;
    private final LinearLayout container;
    private final TextView status;
    private final Listener listener;
    private final ArrayList<WebView> views = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable expiry = this::expire;
    private KugouApi.Session baselineSession;
    private boolean closed, submitted;

    private void expire() { if (!closed) { listener.stage("EXPIRED", ""); close(); } }

    public KugouWebLoginWindow(Activity activity, Listener listener) {
        this.listener = listener;
        dialog = new Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar);
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Color.WHITE);
        status = new TextView(activity);
        status.setTextColor(Color.BLACK);
        status.setPadding(20, 16, 20, 12);
        status.setText("酷狗官方网页登录 · 可选择短信方式，完成后点验证。现有账号保留。");
        container.addView(status);
        LinearLayout actions = new LinearLayout(activity);
        Button verify = new Button(activity);
        verify.setText("登录后验证");
        verify.setOnClickListener(v -> submit());
        actions.addView(verify, new LinearLayout.LayoutParams(0, -2, 1));
        Button cancel = new Button(activity);
        cancel.setText("结束验证");
        cancel.setOnClickListener(v -> close());
        actions.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        container.addView(actions);
        dialog.setContentView(container);
        dialog.setOnDismissListener(ignored -> dispose());
        WebView.setWebContentsDebuggingEnabled(false);
        CookieManager.getInstance().setAcceptCookie(true);
        try {
            baselineSession = KugouWebLoginPolicy.parseCookies(
                    CookieManager.getInstance().getCookie("https://www.kugou.com/"));
        } catch (java.io.IOException noSession) { baselineSession = null; }
        WebView web = createView(activity);
        dialog.show();
        main.postDelayed(expiry, 600_000);
        listener.stage("OPENED", "");
        listener.stage(baselineSession == null ? "NO_BASELINE_SESSION" : "BASELINE_SESSION_PRESENT", "");
        web.loadUrl(KugouWebLoginPolicy.LOGIN_URL);
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
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        // The official popup is a fixed 440px desktop layout; at phone density its
        // account/SMS switch becomes too small to hit. Start enlarged, retaining pinch zoom.
        settings.setSupportZoom(true);
        web.setInitialScale(140);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSaveFormData(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request.isForMainFrame() && navigate(view, request.getUrl().toString());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return navigate(view, url); }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) { navigate(view, url); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request.isForMainFrame() && !KugouWebLoginPolicy.allowsNavigation(request.getUrl().toString())) {
                    main.post(() -> navigate(view, request.getUrl().toString()));
                    return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
                            java.util.Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
                }
                return null;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (closed || !KugouWebLoginPolicy.allowsNavigation(url)) return;
                String host = Uri.parse(url).getHost();
                listener.stage("PAGE_READY", host);
                status.setText("酷狗官方网页登录 · " + host + "\n完成官方登录后点“登录后验证”。");
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                if (!closed) {
                    listener.stage("TLS_FAILED", "");
                    status.setText("官方网页安全连接未通过，可结束后重试。");
                }
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!closed && request.isForMainFrame()) {
                    listener.stage("PAGE_ERROR", "");
                    status.setText("官方网页未加载完成，可结束后重试。");
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
                views.get(views.size() - 1).setVisibility(View.VISIBLE);
            }
        });
        if (!views.isEmpty()) views.get(views.size() - 1).setVisibility(View.GONE);
        views.add(web);
        container.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        return web;
    }

    private boolean navigate(WebView view, String url) {
        if (closed || submitted) return true;
        if (KugouWebLoginPolicy.allowsNavigation(url)) return false;
        view.stopLoading();
        String host = Uri.parse(url).getHost();
        listener.stage("NAVIGATION_BLOCKED", host != null && host.matches("[A-Za-z0-9.-]{1,253}") ? host : "");
        status.setText("此处只验证酷狗官方网页，可在页面选择短信登录；App 登录仍可从正式账号页使用。");
        return true;
    }

    private void submit() {
        if (closed || submitted) return;
        try {
            // Read only the app-local cookies applicable to the official music website.
            KugouApi.Session session = KugouWebLoginPolicy.parseCookies(
                    CookieManager.getInstance().getCookie("https://www.kugou.com/"));
            if (baselineSession != null && baselineSession.userId.equals(session.userId)
                    && baselineSession.token.equals(session.token)) {
                listener.stage("SESSION_UNCHANGED", "");
                status.setText("浏览器中已有酷狗会话，尚未观察到新登录结果。原账号保留，可结束本次验证。");
                return;
            }
            submitted = true;
            listener.stage("COOKIE_CAPTURED", "");
            listener.session(session);
            dialog.dismiss();
        } catch (java.io.IOException invalid) {
            listener.stage("SESSION_NOT_READY", "");
            status.setText("尚未取得酷狗网页登录会话。请先完成官方登录，再点“登录后验证”。");
        }
    }

    public void close() { dialog.dismiss(); }
    private void dispose() {
        if (closed) return;
        closed = true;
        main.removeCallbacks(expiry);
        for (WebView view : views) { view.stopLoading(); container.removeView(view); view.destroy(); }
        views.clear();
        baselineSession = null;
        // Never clear the shared app cookie jar or the production encrypted login.
        if (!submitted) listener.cancelled();
    }
}
