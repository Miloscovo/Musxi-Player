package tech.musxi.neteaseloginpoc;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Message;
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
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/** Official pages in this app's own cookie jar; no password/DOM/JS bridge access. */
public final class WebLoginWindow {
    public interface Listener {
        void ready(boolean firstParty, boolean thirdParty);
        void cookies(String header);
        void cancelled();
    }
    private final Activity activity;
    private final Listener listener;
    private final Dialog dialog;
    private final LinearLayout container;
    private final TextView status;
    private final ArrayList<WebView> views = new ArrayList<>();
    private final CookieManager jar;
    private boolean closed, submitted;

    public WebLoginWindow(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        dialog = new Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar);
        dialog.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Color.WHITE);
        status = new TextView(activity);
        status.setText("正在准备网页登录…");
        status.setTextColor(Color.BLACK);
        status.setPadding(20, 20, 20, 12);
        container.addView(status);
        Button done = new Button(activity);
        done.setText("我已完成网页登录，验证并返回");
        container.addView(done);
        done.setOnClickListener(v -> submit());
        Button reload = new Button(activity);
        reload.setText("重新加载官方登录页");
        container.addView(reload);
        reload.setOnClickListener(v -> {
            if (closed || submitted) return;
            while (views.size() > 1) {
                WebView popup = views.remove(views.size() - 1);
                popup.stopLoading();
                container.removeView(popup);
                popup.destroy();
            }
            views.get(0).setVisibility(android.view.View.VISIBLE);
            views.get(0).loadUrl(WebLoginPolicy.LOGIN_URL);
        });
        Button cancel = new Button(activity);
        cancel.setText("取消网页登录");
        container.addView(cancel);
        cancel.setOnClickListener(v -> close());
        dialog.setContentView(container);
        dialog.setOnDismissListener(ignored -> dispose());
        WebView.setWebContentsDebuggingEnabled(false);
        jar = CookieManager.getInstance();
        jar.setAcceptCookie(true);
        WebView web = createView();
        dialog.show();
        // Start with a clean, app-owned login jar. Never touches Edge or the official app.
        jar.removeAllCookies(ignored -> {
            if (closed) return;
            jar.flush();
            android.webkit.WebStorage.getInstance().deleteAllData();
            // Test real write/read support using a fixed non-authentication marker.
            jar.setCookie(WebLoginPolicy.LOGIN_URL,
                    "MUSXI_COOKIE_CHECK=1; Path=/; Secure; SameSite=None", written -> {
                if (closed) return;
                String test = jar.getCookie(WebLoginPolicy.LOGIN_URL);
                boolean readable = Boolean.TRUE.equals(written) && test != null
                        && test.contains("MUSXI_COOKIE_CHECK=1");
                jar.setCookie(WebLoginPolicy.LOGIN_URL,
                        "MUSXI_COOKIE_CHECK=; Path=/; Secure; Max-Age=0", removed -> {
                    if (closed) return;
                    listener.ready(readable && jar.acceptCookie(), jar.acceptThirdPartyCookies(web));
                    status.setText(readable
                            ? "网易云官方网页登录 · Cookie 读写检查通过，正在加载。完成登录后点上方验证按钮。"
                            : "网页登录 Cookie 读写检查未通过，可重新打开窗口或使用相册扫码。");
                    web.loadUrl(WebLoginPolicy.LOGIN_URL);
                });
            });
        });
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private WebView createView() {
        WebView web = new WebView(activity);
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
                            java.util.Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
                }
                return null;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                // Interception is not guaranteed for every redirect in a navigation chain.
                if (!WebLoginPolicy.allowsNavigation(url)) {
                    view.stopLoading();
                    block(url);
                }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!request.isForMainFrame() && "https".equalsIgnoreCase(request.getUrl().getScheme())) return false;
                return block(url);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return block(url); }
            @Override public void onPageFinished(WebView view, String url) {
                if (closed || !WebLoginPolicy.allowsNavigation(url)) return;
                String host = android.net.Uri.parse(url).getHost();
                status.setText("官方网页登录 · " + host + "\nCookie 和网页存储已启用。完成登录后点上方“验证并返回”。");
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                status.setText("网页安全连接未通过，请检查网络和系统时间后重新加载。");
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) status.setText("官方网页加载未完成，请检查网络后重新加载。");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage message) { return true; }
            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean userGesture, Message result) {
                if (closed || !userGesture || views.size() >= 3) return false;
                WebView child = createView();
                ((WebView.WebViewTransport) result.obj).setWebView(child);
                result.sendToTarget();
                return true;
            }
            @Override public void onCloseWindow(WebView window) {
                if (views.size() <= 1 || window == views.get(0)) return;
                container.removeView(window);
                views.remove(window);
                window.destroy();
                views.get(views.size() - 1).setVisibility(android.view.View.VISIBLE);
            }
        });
        if (!views.isEmpty()) views.get(views.size() - 1).setVisibility(android.view.View.GONE);
        views.add(web);
        container.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        return web;
    }

    private boolean block(String url) {
        if (WebLoginPolicy.allowsNavigation(url)) return false;
        status.setText("当前跳转未在此登录窗口支持，请在官方网页选择手机号等网页方式；可取消后使用相册扫码。");
        return true;
    }

    private void submit() {
        if (closed || submitted) return;
        // Read only this app's browser cookies applicable to the exact account API URL.
        String header = jar.getCookie(NeteaseApi.STATUS);
        try { WebLoginPolicy.parseMusicCookies(header); }
        catch (java.io.IOException invalid) {
            status.setText("尚未取得网易云音乐登录会话。请先在官方页面完成登录，再点验证；若仍出现 Cookie 提示，请反馈当前步骤。");
            return;
        }
        submitted = true;
        listener.cookies(header);
        dialog.dismiss();
    }

    public void close() { dialog.dismiss(); }

    private void dispose() {
        if (closed) return;
        closed = true;
        for (WebView view : views) {
            view.stopLoading();
            container.removeView(view);
            view.destroy();
        }
        views.clear();
        jar.removeAllCookies(ignored -> jar.flush());
        android.webkit.WebStorage.getInstance().deleteAllData();
        if (!submitted) listener.cancelled();
    }
}
